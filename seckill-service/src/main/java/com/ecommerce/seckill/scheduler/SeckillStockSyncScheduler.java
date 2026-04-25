package com.ecommerce.seckill.scheduler;

import com.ecommerce.seckill.entity.SeckillProduct;
import com.ecommerce.seckill.mapper.SeckillProductMapper;
import com.ecommerce.seckill.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀库存同步定时任务
 *
 * 职责：
 * 1. 将 Redis 中的实时库存定期同步回 MySQL（最终一致性）
 * 2. 服务重启后，从 MySQL 加载库存到 Redis（初始化恢复）
 *
 * 注意：这里采用的是「异步对账」而非「强一致同步」
 * Redis 是主库存（高性能），MySQL 是备份（持久化）
 * 秒杀的实时扣减发生在 Redis，MySQL 通过定时任务追平
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeckillStockSyncScheduler {

    private static final String SECKILL_STOCK_KEY_PREFIX = "seckill:stock:";
    private static final int BATCH_SIZE = 100; // 每批处理商品数

    private final StringRedisTemplate stringRedisTemplate;
    private final SeckillProductMapper seckillProductMapper;
    private final SeckillService seckillService;

    /**
     * 每 30 秒将 Redis 库存增量同步到 MySQL
     * 使用固定延迟（fixedDelay）而非固定频率（fixedRate），
     * 确保每次任务完成后再等待 30 秒，避免任务堆积
     */
    @Scheduled(fixedDelayString = "${seckill.sync.interval-ms:30000}")
    public void syncStockToDatabase() {
        try {
            // 1. 获取所有进行中的秒杀商品ID
            List<SeckillProduct> activeProducts = seckillProductMapper.selectActiveProductsForSync();
            if (activeProducts == null || activeProducts.isEmpty()) {
                log.debug("无进行中的秒杀活动，跳过同步");
                return;
            }

            // 2. 批量从 Redis 读取库存
            Map<Long, Integer> redisStockMap = new HashMap<>();
            for (SeckillProduct product : activeProducts) {
                String stockKey = SECKILL_STOCK_KEY_PREFIX + product.getId();
                String stockStr = stringRedisTemplate.opsForValue().get(stockKey);
                if (stockStr != null) {
                    try {
                        int stock = Integer.parseInt(stockStr);
                        redisStockMap.put(product.getId(), stock);
                    } catch (NumberFormatException e) {
                        log.warn("Redis库存格式异常: key={}, value={}", stockKey, stockStr);
                    }
                }
            }

            if (redisStockMap.isEmpty()) {
                log.debug("Redis中无进行中秒杀商品库存，跳过同步");
                return;
            }

            // 3. 批量更新 MySQL
            int updatedCount = 0;
            for (Map.Entry<Long, Integer> entry : redisStockMap.entrySet()) {
                Long productId = entry.getKey();
                Integer redisStock = entry.getValue();

                int rows = seckillProductMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.ecommerce.seckill.entity.SeckillProduct>()
                                .eq(com.ecommerce.seckill.entity.SeckillProduct::getId, productId)
                                .set(com.ecommerce.seckill.entity.SeckillProduct::getAvailableStock, redisStock)
                );
                updatedCount += rows;
            }

            log.info("Redis->MySQL库存同步完成: 处理商品 {} 个, 实际更新 {} 条",
                    redisStockMap.size(), updatedCount);

        } catch (Exception e) {
            log.error("Redis->MySQL库存同步失败", e);
        }
    }

    /**
     * 服务启动时，从 MySQL 恢复 Redis 库存
     * 扫描所有进行中的秒杀商品，将 MySQL 的 available_stock 写入 Redis
     * 使用 @PostConstruct 确保在 Spring 容器完全初始化后执行
     */
    @Scheduled(initialDelay = 5000, fixedDelay = Long.MAX_VALUE)
    public void restoreStockFromDatabaseOnStartup() {
        try {
            List<SeckillProduct> activeProducts = seckillProductMapper.selectActiveProductsForSync();
            if (activeProducts == null || activeProducts.isEmpty()) {
                log.info("无进行中的秒杀商品，跳过Redis库存恢复");
                return;
            }

            int restoredCount = 0;
            for (SeckillProduct product : activeProducts) {
                String stockKey = SECKILL_STOCK_KEY_PREFIX + product.getId();

                // 只在 Redis 中没有库存时才初始化（幂等保护）
                Boolean exists = stringRedisTemplate.hasKey(stockKey);
                if (Boolean.FALSE.equals(exists)) {
                    Integer stock = product.getAvailableStock();
                    if (stock != null && stock >= 0) {
                        stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(stock), 1L, TimeUnit.DAYS);
                        restoredCount++;
                    }
                }
            }

            log.info("MySQL->Redis库存恢复完成: 恢复商品 {} 个", restoredCount);

        } catch (Exception e) {
            log.error("MySQL->Redis库存恢复失败", e);
        }
    }
}

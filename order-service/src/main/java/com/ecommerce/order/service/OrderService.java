package com.ecommerce.order.service;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.order.dto.OrderDetailResponse;
import com.ecommerce.order.dto.OrderCreateRequest;
import com.ecommerce.order.dto.SeckillOrderCreateRequest;
import com.ecommerce.order.entity.Cart;
import com.ecommerce.order.entity.Order;
import com.ecommerce.order.entity.OrderItem;
import com.ecommerce.order.mapper.CartMapper;
import com.ecommerce.order.mapper.OrderItemMapper;
import com.ecommerce.order.mapper.OrderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderMapper orderMapper;
    private final OrderItemMapper orderItemMapper;
    private final CartMapper cartMapper;
    private final RestTemplate restTemplate;
    private final StringRedisTemplate redisTemplate;

    private static final String CART_COUNT_KEY = "cart:count:";

    @Value("${services.product.url:http://localhost:8002}")
    private String productServiceUrl;

    @Transactional(rollbackFor = Exception.class)
    public Order createOrder(OrderCreateRequest request) {
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new RuntimeException("购物项不能为空");
        }

        Order order = new Order();
        order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
        order.setUserId(request.getUserId());
        order.setReceiverName(isBlank(request.getReceiverName()) ? "默认收货人" : request.getReceiverName());
        order.setReceiverPhone(isBlank(request.getReceiverPhone()) ? "13800000000" : request.getReceiverPhone());
        order.setReceiverAddress(isBlank(request.getReceiverAddress()) ? "默认收货地址" : request.getReceiverAddress());
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderItem> orderItems = new ArrayList<>();
        List<Map<String, Object>> deductedStocks = new ArrayList<>();
        List<Long> cartIdsToDelete = new ArrayList<>();

        for (OrderCreateRequest.CartItem item : request.getItems()) {
            Integer quantity = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
            Cart cart = cartMapper.selectOne(
                new LambdaQueryWrapper<Cart>()
                    .eq(Cart::getUserId, request.getUserId())
                    .eq(Cart::getProductId, item.getProductId())
            );

            if (cart == null) {
                compensateStock(deductedStocks);
                throw new RuntimeException("购物车商品不存在: " + item.getProductId());
            }

            boolean reduced = reduceProductStock(item.getProductId(), quantity);
            if (!reduced) {
                compensateStock(deductedStocks);
                throw new RuntimeException("库存不足，商品ID: " + item.getProductId());
            }

            Map<String, Object> deducted = new HashMap<>();
            deducted.put("productId", item.getProductId());
            deducted.put("quantity", quantity);
            deductedStocks.add(deducted);

            Map<String, Object> product = getProductDetail(item.getProductId());
            BigDecimal itemPrice = parsePrice(product.get("price"));
            BigDecimal itemTotal = BigDecimal.valueOf(quantity).multiply(itemPrice);
            totalAmount = totalAmount.add(itemTotal);

            String productName = cart.getProductName();
            if (isBlank(productName)) {
                productName = String.valueOf(product.getOrDefault("name", "商品"));
            }

            String productImage = cart.getProductImage();
            if (isBlank(productImage)) {
                productImage = String.valueOf(product.getOrDefault("imageUrl", ""));
            }

            OrderItem orderItem = new OrderItem();
            orderItem.setOrderId(order.getId());
            orderItem.setProductId(cart.getProductId());
            orderItem.setProductName(productName);
            orderItem.setProductImage(productImage);
            orderItem.setQuantity(quantity);
            orderItem.setPrice(itemPrice);
            orderItem.setTotalPrice(itemTotal);
            orderItems.add(orderItem);

            cartIdsToDelete.add(cart.getId());
        }

        try {
            order.setTotalAmount(totalAmount);
            orderMapper.insert(order);

            for (OrderItem item : orderItems) {
                item.setOrderId(order.getId());
                orderItemMapper.insert(item);
            }

            for (Long cartId : cartIdsToDelete) {
                cartMapper.deleteById(cartId);
            }
        } catch (Exception exception) {
            compensateStock(deductedStocks);
            throw exception;
        }

        return order;
    }

    @Transactional(rollbackFor = Exception.class)
    public Order createSeckillOrder(SeckillOrderCreateRequest request) {
        Order existing = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                .eq(Order::getMessageId, request.getMessageId())
                .last("LIMIT 1"));
        if (existing != null) {
            return existing;
        }

        int quantity = request.getQuantity() == null || request.getQuantity() <= 0 ? 1 : request.getQuantity();

        Map<String, Object> product = getProductDetail(request.getProductId());
        if (product == null || product.get("id") == null) {
            throw new RuntimeException("秒杀下单失败：商品不存在 " + request.getProductId());
        }

        String productName = String.valueOf(product.getOrDefault("name", "秒杀商品"));
        String productImage = String.valueOf(product.getOrDefault("imageUrl", ""));
        BigDecimal price = parsePrice(product.get("price"));
        BigDecimal totalAmount = price.multiply(BigDecimal.valueOf(quantity));

        Order order = new Order();
        order.setOrderNo(IdUtil.getSnowflakeNextIdStr());
        order.setUserId(request.getUserId());
        order.setReceiverName("秒杀用户");
        order.setReceiverPhone("00000000000");
        order.setReceiverAddress("秒杀订单待补全地址");
        order.setStatus(0);
        order.setCreateTime(LocalDateTime.now());
        order.setTotalAmount(totalAmount);
        order.setMessageId(request.getMessageId());

        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException duplicateKeyException) {
            Order duplicated = orderMapper.selectOne(new LambdaQueryWrapper<Order>()
                    .eq(Order::getMessageId, request.getMessageId())
                    .last("LIMIT 1"));
            if (duplicated != null) {
                return duplicated;
            }
            throw duplicateKeyException;
        }

        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(order.getId());
        orderItem.setProductId(request.getProductId());
        orderItem.setProductName(productName);
        orderItem.setProductImage(productImage);
        orderItem.setQuantity(quantity);
        orderItem.setPrice(price);
        orderItem.setTotalPrice(totalAmount);
        orderItemMapper.insert(orderItem);

        log.info("秒杀订单创建成功: messageId={}, userId={}, productId={}, orderNo={}",
                request.getMessageId(), request.getUserId(), request.getProductId(), order.getOrderNo());
        return order;
    }

    private boolean reduceProductStock(Long productId, Integer quantity) {
        String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                .path("/api/product/stock/reduce")
                .queryParam("productId", productId)
                .queryParam("quantity", quantity)
                .toUriString();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.postForObject(url, null, Map.class);
        return response != null && Boolean.TRUE.equals(response.get("success"));
    }

    private void increaseProductStock(Long productId, Integer quantity) {
        try {
            String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                    .path("/api/product/stock/increase")
                    .queryParam("productId", productId)
                    .queryParam("quantity", quantity)
                    .toUriString();
            restTemplate.postForObject(url, null, Map.class);
        } catch (Exception exception) {
            log.error("库存补偿失败: productId={}, quantity={}", productId, quantity, exception);
        }
    }

    private void compensateStock(List<Map<String, Object>> deductedStocks) {
        for (Map<String, Object> item : deductedStocks) {
            Long productId = (Long) item.get("productId");
            Integer quantity = (Integer) item.get("quantity");
            increaseProductStock(productId, quantity);
        }
    }

    private Map<String, Object> getProductDetail(Long productId) {
        String url = UriComponentsBuilder.fromHttpUrl(productServiceUrl)
                .path("/api/product/{id}")
                .buildAndExpand(productId)
                .toUriString();

        @SuppressWarnings("unchecked")
        Map<String, Object> response = restTemplate.getForObject(url, Map.class);
        return response != null ? response : Collections.emptyMap();
    }

    private BigDecimal parsePrice(Object rawPrice) {
        if (rawPrice == null) {
            return BigDecimal.valueOf(100);
        }

        try {
            return new BigDecimal(String.valueOf(rawPrice));
        } catch (Exception exception) {
            return BigDecimal.valueOf(100);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    public List<OrderDetailResponse> getOrderList(Long userId) {
        LambdaQueryWrapper<Order> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Order::getUserId, userId);
        wrapper.orderByDesc(Order::getCreateTime);
        List<Order> orders = orderMapper.selectList(wrapper);
        if (orders.isEmpty()) {
            return Collections.emptyList();
        }

        List<OrderDetailResponse> responses = new ArrayList<>(orders.size());
        for (Order order : orders) {
            List<OrderItem> items = orderItemMapper.selectList(
                    new LambdaQueryWrapper<OrderItem>()
                            .eq(OrderItem::getOrderId, order.getId())
            );
            responses.add(OrderDetailResponse.from(order, items));
        }
        return responses;
    }

    public Order getOrderById(Long orderId) {
        return orderMapper.selectById(orderId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Order payOrder(Long orderId, Long userId) {
        Order order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new RuntimeException("订单不存在");
        }
        if (!Objects.equals(order.getUserId(), userId)) {
            throw new RuntimeException("无权操作该订单");
        }
        if (order.getStatus() != null && order.getStatus() != 0) {
            return order;
        }

        order.setStatus(1);
        order.setUpdateTime(LocalDateTime.now());
        orderMapper.updateById(order);
        return order;
    }

    public void addToCart(Long userId, Long productId, String productName, String productImage, Integer quantity) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId).eq(Cart::getProductId, productId);
        Cart existCart = cartMapper.selectOne(wrapper);

        if (existCart != null) {
            existCart.setQuantity(existCart.getQuantity() + quantity);
            existCart.setUpdateTime(LocalDateTime.now());
            cartMapper.updateById(existCart);
        } else {
            Cart cart = new Cart();
            cart.setUserId(userId);
            cart.setProductId(productId);
            cart.setProductName(productName);
            cart.setProductImage(productImage);
            cart.setQuantity(quantity);
            cart.setCreateTime(LocalDateTime.now());
            cartMapper.insert(cart);
        }
        // 同步更新 Redis 缓存的购物车数量
        updateCartCountCache(userId);
    }

    private void updateCartCountCache(Long userId) {
        try {
            LambdaQueryWrapper<Cart> countWrapper = new LambdaQueryWrapper<>();
            countWrapper.eq(Cart::getUserId, userId);
            Long count = cartMapper.selectCount(countWrapper);
            redisTemplate.opsForValue().set(CART_COUNT_KEY + userId, String.valueOf(count));
        } catch (Exception e) {
            log.warn("更新购物车数量缓存失败: {}", e.getMessage());
        }
    }

    public Integer getCartCount(Long userId) {
        try {
            String count = redisTemplate.opsForValue().get(CART_COUNT_KEY + userId);
            if (count != null) {
                return Integer.parseInt(count);
            }
        } catch (Exception e) {
            log.warn("获取购物车数量缓存失败: {}", e.getMessage());
        }
        // 缓存未命中，从数据库查询
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId);
        Long count = cartMapper.selectCount(wrapper);
        return count.intValue();
    }

    public List<Cart> getCartList(Long userId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId);
        return cartMapper.selectList(wrapper);
    }

    public void removeFromCart(Long userId, Long productId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId).eq(Cart::getProductId, productId);
        cartMapper.delete(wrapper);
        updateCartCountCache(userId);
    }

    public void clearCart(Long userId) {
        LambdaQueryWrapper<Cart> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(Cart::getUserId, userId);
        cartMapper.delete(wrapper);
        // 清空缓存
        try {
            redisTemplate.delete(CART_COUNT_KEY + userId);
        } catch (Exception e) {
            log.warn("清空购物车数量缓存失败: {}", e.getMessage());
        }
    }
}
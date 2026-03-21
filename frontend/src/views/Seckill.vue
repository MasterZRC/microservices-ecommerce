<template>
  <div class="seckill-page">
    <div class="container page-container">
      <h2 class="page-title">限时秒杀</h2>
      <el-alert v-if="errorText" :title="errorText" type="error" show-icon style="margin-bottom: 12px" />
      <div class="countdown">
        <span v-if="hasActiveActivity">距结束: </span>
        <span v-else-if="endTime">距开始: </span>
        <span v-else class="countdown-idle">暂无秒杀活动</span>
        <el-countdown v-if="endTime" :value="endTime" format="HH:mm:ss" />
      </div>
      <el-skeleton v-if="loading" :rows="4" animated />
      <el-row :gutter="20">
        <el-col :span="8" v-for="product in seckillProducts" :key="product.id">
          <el-card shadow="hover" class="seckill-card">
            <el-image :src="product.productImage" fit="cover" class="product-image" />
            <div class="product-info">
              <h3>{{ product.productName }}</h3>
              <div class="price-row">
                <span class="seckill-price">¥{{ product.seckillPrice }}</span>
                <span v-if="product.originalPrice" class="original-price">¥{{ product.originalPrice }}</span>
              </div>
              <div class="stock-info">
                <span>剩余: {{ product.availableStock }}</span>
                <el-progress :percentage="getSoldPercent(product)" :stroke-width="10" />
              </div>
              <button
                class="neo-btn-seckill"
                :class="{ 'sold-out': product.availableStock <= 0, 'ended': isEnded }"
                :disabled="product.availableStock <= 0 || isEnded"
                @click="handleSeckill(product)">
                {{ product.availableStock <= 0 ? '已抢光' : isEnded ? '秒杀结束' : '立即抢购' }}
              </button>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const seckillProducts = ref([])
const endTime = ref(null)
const loading = ref(false)
const errorText = ref('')
const hasActiveActivity = ref(false)

const isEnded = computed(() => {
  if (!endTime.value) return false
  return Date.now() > endTime.value
})

onMounted(async () => {
  loading.value = true
  errorText.value = ''
  try {
    // 获取秒杀活动信息和商品列表
    const [activityRes, productsRes] = await Promise.all([
      api.getSeckillActivity(),
      api.getSeckillProducts()
    ])

    // 设置活动状态
    hasActiveActivity.value = activityRes.data?.hasActiveActivity || false

    // 设置结束时间
    if (activityRes.data?.endTime) {
      endTime.value = new Date(activityRes.data.endTime).getTime()
    } else {
      endTime.value = null
    }

    // 获取秒杀商品列表
    let products = productsRes.data?.products || []

    // 如果没有进行中的秒杀，获取即将开始的
    if (products.length === 0) {
      const upcomingRes = await api.getUpcomingSeckillProducts(6)
      products = upcomingRes.data?.products || []

      // 如果有即将开始的商品，设置开始时间
      if (products.length > 0 && products[0].startTime) {
        endTime.value = new Date(products[0].startTime).getTime()
      }
    }

    // 处理商品数据
    if (products.length > 0) {
      seckillProducts.value = products.map(item => {
        const stock = Number(item.availableStock ?? 0)
        const total = Number(item.totalStock ?? 0)
        // originalPrice 必须来自后端，前端不自行计算
        // 如果后端未返回 originalPrice，不显示原价行（避免误导）
        const hasOriginalPrice = item.originalPrice != null && Number(item.originalPrice) > 0

        return {
          id: item.id,
          productName: item.productName,
          productImage: item.productImage,
          seckillPrice: Number(item.seckillPrice || 0),
          originalPrice: hasOriginalPrice ? Number(item.originalPrice) : null,
          availableStock: Math.max(stock, 0),
          totalStock: Math.max(total, 1)
        }
      })
    } else {
      // 没有秒杀数据时清空
      seckillProducts.value = []
      hasActiveActivity.value = false
    }
  } catch (error) {
    console.error('加载秒杀数据失败:', error)
    // 出错时清空数据
    seckillProducts.value = []
    errorText.value = '秒杀商品加载失败'
  } finally {
    loading.value = false
  }
})

// 回退方案：从商品列表获取数据
async function loadFallbackProducts() {
  try {
    const res = await api.getProducts({ page: 1, pageSize: 6 })
    const source = res.data?.products || []
    const candidates = source.slice(0, 3)
    const stockResults = await Promise.allSettled(candidates.map(item => api.getSeckillStock(item.id)))

    seckillProducts.value = candidates.map((item, index) => {
      const stockResult = stockResults[index]
      const stock = stockResult?.status === 'fulfilled'
        ? Number(stockResult.value?.data?.stock ?? item.stock ?? 0)
        : Number(item.stock ?? 0)
      // 回退方案：原价必须来自后端，不允许前端自行计算
      const hasOriginalPrice = item.originalPrice != null && Number(item.originalPrice) > 0

      return {
        id: item.id,
        productName: item.name,
        productImage: item.imageUrl,
        seckillPrice: Number(item.price || 0),
        originalPrice: hasOriginalPrice ? Number(item.originalPrice) : null,
        availableStock: Math.max(stock, 0),
        totalStock: Math.max(Number(item.stock || stock || 0), 1)
      }
    })
    // 回退方案：不设置假倒计时；无数据时前端已显示"暂无秒杀活动"
  } catch (error) {
    errorText.value = '秒杀商品加载失败'
  }
}

function getSoldPercent(product) {
  return Math.round(((product.totalStock - product.availableStock) / product.totalStock) * 100)
}

async function handleSeckill(product) {
  if (!userStore.token) {
    ElMessage.warning('请先登录')
    return
  }
  try {
    const res = await api.startSeckill({ userId: userStore.userInfo.id, seckillProductId: product.id })
    if (res.data.success) {
      ElMessage.success('秒杀成功!')
      const stockRes = await api.getSeckillStock(product.id)
      product.availableStock = Number(stockRes.data?.stock ?? Math.max(product.availableStock - 1, 0))
    } else {
      ElMessage.warning(res.data.message || '秒杀失败')
    }
  } catch (error) {
    ElMessage.error(api.getErrorMessage(error, '秒杀失败'))
  }
}
</script>

<style scoped>
.seckill-page {
  padding: 20px 0 28px;
}
.container {
  max-width: 1240px;
}
.countdown {
  margin-bottom: 30px;
  font-size: var(--neo-fs-lg);
  font-weight: 800;
}
.seckill-card {
  margin-bottom: 20px;
}
.product-image {
  width: 100%;
  height: 200px;
  border-radius: 10px;
  border: 3px solid #101010;
}
.product-info h3 {
  margin: 12px 0;
  font-size: var(--neo-fs-lg);
  font-weight: 900;
}
.price-row {
  margin-bottom: 12px;
}
.seckill-price {
  font-size: var(--neo-fs-xl);
  color: #1d4ed8;
  font-weight: 900;
  margin-right: 12px;
}
.original-price {
  font-size: var(--neo-fs-sm);
  color: #4b5563;
  text-decoration: line-through;
}
.stock-info {
  margin-bottom: 12px;
}
.stock-info span {
  display: block;
  margin-bottom: 8px;
  color: #374151;
  font-weight: 700;
  font-size: var(--neo-fs-sm);
}

/* Neobrutalism 秒杀按钮 */
.neo-btn-seckill {
  width: 100%;
  padding: 14px 20px;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  background: #ef4444;
  border: 2px solid #101010;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-seckill:hover:not(:disabled) {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #dc2626;
}

.neo-btn-seckill:active:not(:disabled) {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}

.neo-btn-seckill:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.neo-btn-seckill.sold-out,
.neo-btn-seckill.ended {
  background: #9ca3af;
  border-color: #101010;
  box-shadow: 3px 3px 0 #101010;
}
</style>
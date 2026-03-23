<template>
  <div class="product-detail">
    <div class="container" v-loading="loading" element-loading-text="加载商品中..." v-if="product">
      <el-row :gutter="40">
        <el-col :span="10">
          <el-image :src="getProductImage(product)" fit="cover" class="main-image" />
        </el-col>
        <el-col :span="14">
          <div class="product-info">
            <h1>{{ product.name }}</h1>
            <p class="description">{{ product.description }}</p>
            <div class="price-info">
              <span class="price">¥{{ product.price }}</span>
              <span class="original-price" v-if="product.originalPrice">¥{{ product.originalPrice }}</span>
            </div>
            <div class="meta">
              <p>分类: {{ product.categoryName }}</p>
              <p>品牌: {{ product.brand }}</p>
              <p>库存: {{ product.stock }}</p>
              <p>销量: {{ product.sales }}</p>
            </div>
            <div class="actions">
              <div class="quantity-selector">
                <span class="qty-label">数量:</span>
                <el-input-number v-model="quantity" :min="1" :max="product.stock" />
              </div>
              <button class="neo-btn-cart" @click="handleAddToCart">加入购物车</button>
              <button class="neo-btn-buy" @click="handleBuyNow">立即购买</button>
            </div>
          </div>
        </el-col>
      </el-row>
    </div>
    <div class="container empty" v-else-if="!loading">
      <el-empty :description="loadError || '商品不存在或已下架'" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

const product = ref(null)
const quantity = ref(1)
const loading = ref(false)
const loadError = ref('')
const defaultImage = 'https://picsum.photos/600/600?random=888'

onMounted(async () => {
  loading.value = true
  loadError.value = ''
  try {
    const res = await api.getProductById(route.params.id)
    product.value = res.data || null
    if (!product.value) {
      loadError.value = '商品不存在'
    } else {
      await recordBehaviorSafe(product.value.id, 'view')
    }
  } catch (error) {
    loadError.value = error?.response?.data?.message || '商品加载失败'
    console.error(error)
  } finally {
    loading.value = false
  }
})

function getProductImage(item) {
  return item?.imageUrl || `${defaultImage}&id=${item?.id || 0}`
}

async function handleAddToCart() {
  if (!userStore.token) {
    ElMessage.warning('请先登录')
    return
  }
  try {
    await api.addToCart({
      userId: userStore.userInfo.id,
      productId: product.value.id,
      productName: product.value.name,
      productImage: product.value.imageUrl,
      quantity: quantity.value
    })
    await recordBehaviorSafe(product.value.id, 'cart')
    ElMessage.success('添加成功')
    // 刷新购物车数量（从 Redis 获取最新值）
    cartStore.fetchCartCount(userStore.userInfo.id)
  } catch (error) {
    const msg = api.getErrorMessage(error, '添加失败')
    ElMessage.error(msg)
  }
}

async function handleBuyNow() {
  await handleAddToCart()
  router.push('/cart')
}

async function recordBehaviorSafe(productId, behaviorType) {
  if (!userStore.token || !userStore.userInfo?.id || !productId) {
    return
  }
  try {
    await api.recordBehavior({
      userId: userStore.userInfo.id,
      productId,
      behaviorType
    })
  } catch (error) {
    console.warn('行为上报失败:', behaviorType, productId, error)
  }
}
</script>

<style scoped>
.product-detail {
  padding: 20px 0 28px;
}
.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 24px;
  border: 3px solid #101010;
  border-radius: 16px;
  background: #fffef9;
  box-shadow: 6px 6px 0 #101010;
}
.empty {
  padding: 60px 20px;
}
.main-image {
  width: 100%;
  height: 400px;
  border-radius: 12px;
  border: 3px solid #101010;
}
.product-info h1 {
  font-size: var(--neo-fs-xxl);
  font-weight: 900;
  margin-bottom: 16px;
  line-height: 1.2;
}
.product-info .description {
  color: var(--neo-text-soft);
  font-size: var(--neo-fs-md);
  margin-bottom: 20px;
  line-height: 1.6;
}
.price-info {
  margin-bottom: 20px;
}
.price-info .price {
  font-size: var(--neo-fs-xxl);
  color: #1d4ed8;
  font-weight: bold;
  margin-right: 16px;
}
.price-info .original-price {
  font-size: var(--neo-fs-md);
  color: #999;
  text-decoration: line-through;
}
.meta {
  margin-bottom: 24px;
  color: #1f2937;
  font-size: var(--neo-fs-sm);
}
.meta p {
  margin: 8px 0;
}
.actions {
  display: flex;
  gap: 12px;
  align-items: center;
}

.quantity-selector {
  display: flex;
  align-items: center;
  gap: 8px;
}

.qty-label {
  font-weight: 600;
  color: #101010;
}

.quantity-selector :deep(.el-input-number) {
  width: 120px;
}

.quantity-selector :deep(.el-input__wrapper) {
  border: 2px solid #101010;
  border-radius: 10px;
  box-shadow: 2px 2px 0 #101010;
}

.quantity-selector :deep(.el-input-number__decrease),
.quantity-selector :deep(.el-input-number__increase) {
  border: none;
  background: #fffef9;
}

.quantity-selector :deep(.el-input-number__decrease:hover),
.quantity-selector :deep(.el-input-number__increase:hover) {
  color: #3b82f6;
}

/* Neobrutalism 加入购物车按钮 */
.neo-btn-cart {
  padding: 14px 28px;
  font-size: 15px;
  font-weight: 700;
  color: #101010;
  background: #fbbf24;
  border: 2px solid #101010;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-cart:hover {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #f59e0b;
}

.neo-btn-cart:active {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}

/* Neobrutalism 立即购买按钮 */
.neo-btn-buy {
  padding: 14px 28px;
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

.neo-btn-buy:hover {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #dc2626;
}

.neo-btn-buy:active {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}
</style>
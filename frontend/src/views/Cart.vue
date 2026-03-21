<template>
  <div class="cart-page">
    <div class="container page-container">
      <h2 class="page-title">购物车</h2>
      <el-alert v-if="errorText" :title="errorText" type="error" show-icon style="margin-bottom: 12px" />
      <el-empty v-if="!loading && !cartItems.length" description="购物车是空的" />
      <div v-else>
        <el-table v-loading="loading" :data="cartItems" style="width: 100%">
          <el-table-column prop="productImage" label="商品图片" width="120">
            <template #default="scope">
              <el-image :src="scope.row.productImage" style="width: 80px; height: 80px" fit="cover" />
            </template>
          </el-table-column>
          <el-table-column prop="productName" label="商品名称" />
          <el-table-column prop="price" label="单价" width="120">
            <template #default="scope">
              <span v-if="hasPrice(scope.row)">¥{{ resolveItemPrice(scope.row).toFixed(2) }}</span>
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="150">
            <template #default="scope">
              <el-input-number v-model="scope.row.quantity" :min="1" size="small" />
            </template>
          </el-table-column>
          <el-table-column label="小计" width="120">
            <template #default="scope">
              <span class="subtotal" v-if="hasPrice(scope.row)">¥{{ (scope.row.quantity * resolveItemPrice(scope.row)).toFixed(2) }}</span>
              <span v-else>--</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100">
            <template #default="scope">
              <button class="neo-btn-delete" @click="handleRemove(scope.row.productId)">删除</button>
            </template>
          </el-table-column>
        </el-table>
        <div class="checkout-section">
          <div class="total">
            <span>总计: </span>
            <span class="total-price">¥{{ totalPrice.toFixed(2) }}</span>
          </div>
          <button class="neo-btn-checkout" @click="handleCheckout">结算</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

const cartItems = ref([])
const loading = ref(false)
const errorText = ref('')
const productPriceMap = ref({})

const totalPrice = computed(() => cartItems.value.reduce((sum, item) => {
  if (!hasPrice(item)) {
    return sum
  }
  return sum + item.quantity * resolveItemPrice(item)
}, 0))

onMounted(() => {
  if (userStore.token) {
    loadCart()
  }
})

async function loadCart() {
  loading.value = true
  errorText.value = ''
  try {
    const res = await api.getCart(userStore.userInfo.id)
    const items = Array.isArray(res.data) ? res.data : []
    productPriceMap.value = await buildProductPriceMap(items)
    cartItems.value = items
    cartStore.setCartItems(cartItems.value)
  } catch (error) {
    errorText.value = api.getErrorMessage(error, '购物车加载失败')
  } finally {
    loading.value = false
  }
}

async function buildProductPriceMap(items) {
  const map = {}
  const ids = [...new Set(items.map(item => item.productId).filter(Boolean))]
  const details = await Promise.allSettled(ids.map(id => api.getProductById(id)))

  details.forEach((result, index) => {
    if (result.status === 'fulfilled') {
      const product = result.value?.data
      if (product?.id != null && product?.price != null) {
        map[product.id] = Number(product.price)
      }
      return
    }
    console.warn(`商品价格拉取失败: ${ids[index]}`)
  })

  return map
}

function resolveItemPrice(item) {
  if (item?.price != null) {
    return Number(item.price)
  }
  if (item?.productId != null && productPriceMap.value[item.productId] != null) {
    return Number(productPriceMap.value[item.productId])
  }
  return 0
}

function hasPrice(item) {
  return Number.isFinite(resolveItemPrice(item)) && resolveItemPrice(item) > 0
}

async function handleRemove(productId) {
  try {
    await api.removeFromCart({ userId: userStore.userInfo.id, productId })
    cartItems.value = cartItems.value.filter(item => item.productId !== productId)
    cartStore.removeCartItem(productId)
    // 刷新购物车数量（从 Redis 获取最新值）
    cartStore.fetchCartCount(userStore.userInfo.id)
    ElMessage.success('删除成功')
  } catch (error) {
    ElMessage.error('删除失败')
  }
}

async function handleCheckout() {
  if (!cartItems.value.length) {
    ElMessage.warning('购物车为空')
    return
  }
  try {
    const items = cartItems.value.map(item => ({ productId: item.productId, quantity: item.quantity }))
    await api.createOrder({ userId: userStore.userInfo.id, items })
    const behaviorTasks = cartItems.value
      .filter(item => item?.productId)
      .map(item => api.recordBehavior({
        userId: userStore.userInfo.id,
        productId: item.productId,
        behaviorType: 'buy'
      }))
    await Promise.allSettled(behaviorTasks)
    ElMessageBox.confirm('下单成功，是否查看订单?', '提示', { confirmButtonText: '查看订单', cancelButtonText: '继续购物', type: 'success' })
      .then(() => { router.push('/orders') })
      .catch(() => {})
    cartStore.clearCart()
    cartItems.value = []
  } catch (error) {
    ElMessage.error('下单失败')
  }
}
</script>

<style scoped>
.cart-page {
  padding: 20px 0 28px;
}
.container {
  max-width: 1240px;
}
.subtotal {
  color: #1d4ed8;
  font-weight: 800;
}
.checkout-section {
  margin-top: 20px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px;
  background: #fffef9;
  border: 3px solid #101010;
  border-radius: 14px;
  box-shadow: 6px 6px 0 #101010;
}
.total {
  font-size: var(--neo-fs-lg);
  font-weight: 700;
}
.total-price {
  font-size: var(--neo-fs-xxl);
  color: #1d4ed8;
  font-weight: 900;
  margin-left: 10px;
}

.cart-page :deep(.el-input-number) {
  border: 2px solid #101010;
  border-radius: 10px;
}

/* Neobrutalism 结算按钮 */
.neo-btn-checkout {
  padding: 14px 40px;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  background: #10b981;
  border: 2px solid #101010;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-checkout:hover {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #059669;
}

.neo-btn-checkout:active {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}

/* Neobrutalism 删除按钮 */
.neo-btn-delete {
  padding: 6px 12px;
  font-size: 12px;
  font-weight: 600;
  color: #fff;
  background: #ef4444;
  border: 2px solid #101010;
  border-radius: 8px;
  cursor: pointer;
  box-shadow: 2px 2px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-delete:hover {
  transform: translate(-1px, -1px);
  box-shadow: 3px 3px 0 #101010;
  background: #dc2626;
}

.neo-btn-delete:active {
  transform: translate(1px, 1px);
  box-shadow: 1px 1px 0 #101010;
}
</style>
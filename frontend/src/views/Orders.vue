<template>
  <div class="orders-page">
    <div class="container page-container">
      <h2 class="page-title">我的订单</h2>
      <el-alert v-if="errorText" :title="errorText" type="error" show-icon style="margin-bottom: 12px" />
      <el-skeleton v-if="loading" :rows="5" animated />
      <el-empty v-else-if="!orders.length" description="暂无订单" />
      <div v-else>
        <el-card v-for="order in orders" :key="order.id" class="order-card">
          <template #header>
            <div class="order-header">
              <span>订单号: {{ order.orderNo }}</span>
              <el-tag :type="getStatusType(order.status)">{{ getStatusText(order.status) }}</el-tag>
            </div>
          </template>
          <div class="order-items" v-if="getOrderItems(order).length">
            <div v-for="item in getOrderItems(order)" :key="item.id" class="order-item">
              <el-image :src="item.productImage" class="item-image" fit="cover" />
              <div class="item-info">
                <h4>{{ item.productName }}</h4>
                <p>¥{{ formatItemPrice(item) }} x {{ item.quantity }}</p>
              </div>
            </div>
          </div>
          <el-empty v-else description="订单项数据缺失" :image-size="64" />
          <div class="order-footer">
            <span class="total">合计: ¥{{ order.totalAmount }}</span>
            <button v-if="order.status === 0" class="neo-btn-pay" @click="handlePay(order)">去支付</button>
          </div>
        </el-card>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useUserStore } from '../store/user'

const userStore = useUserStore()
const orders = ref([])
const loading = ref(false)
const errorText = ref('')

const statusMap = {
  0: { text: '待支付', type: 'warning' },
  1: { text: '已支付', type: 'success' },
  2: { text: '已发货', type: 'info' },
  3: { text: '已完成', type: 'success' },
  4: { text: '已取消', type: 'danger' }
}

function getStatusText(status) {
  return statusMap[status]?.text || '未知'
}

function getStatusType(status) {
  return statusMap[status]?.type || 'info'
}

onMounted(async () => {
  loading.value = true
  errorText.value = ''
  try {
    const res = await api.getOrders(userStore.userInfo.id)
    orders.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    errorText.value = api.getErrorMessage(error, '订单加载失败')
  } finally {
    loading.value = false
  }
})

function getOrderItems(order) {
  if (Array.isArray(order?.items)) {
    return order.items
  }
  if (Array.isArray(order?.orderItems)) {
    return order.orderItems
  }
  return []
}

function formatItemPrice(item) {
  if (item?.price != null) {
    return Number(item.price).toFixed(2)
  }
  if (item?.totalPrice != null && item?.quantity) {
    return (Number(item.totalPrice) / Number(item.quantity)).toFixed(2)
  }
  return '0.00'
}

async function handlePay(order) {
  try {
    const res = await api.payOrder({ orderId: order.id, userId: userStore.userInfo.id })
    const paidOrder = res.data || {}
    order.status = paidOrder.status ?? 1
    ElMessage.success('支付成功')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '支付失败')
  }
}
</script>

<style scoped>
.orders-page {
  padding: 20px 0 28px;
}
.container {
  max-width: 1240px;
}
.order-card {
  margin-bottom: 20px;
}
.order-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 800;
}
.order-items {
  display: flex;
  gap: 20px;
  flex-wrap: wrap;
}
.order-item {
  display: flex;
  gap: 12px;
  width: 250px;
  padding: 10px;
  border: 2px solid #101010;
  border-radius: 10px;
  background: #fff;
}
.item-image {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  border: 2px solid #101010;
}
.item-info h4 {
  margin: 0 0 4px;
  font-size: var(--neo-fs-sm);
  font-weight: 800;
}
.item-info p {
  margin: 0;
  color: #374151;
  font-size: var(--neo-fs-sm);
}
.order-footer {
  margin-top: 16px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.total {
  font-size: var(--neo-fs-lg);
  font-weight: 900;
  color: #1d4ed8;
}

/* Neobrutalism 去支付按钮 */
.neo-btn-pay {
  padding: 8px 16px;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  background: #10b981;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-pay:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #059669;
}

.neo-btn-pay:active {
  transform: translate(1px, 1px);
  box-shadow: 1px 1px 0 #101010;
}
</style>
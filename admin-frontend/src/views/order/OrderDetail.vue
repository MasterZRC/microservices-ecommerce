<template>
  <div class="order-detail">
    <!-- Back Button & Title -->
    <div class="page-head">
      <el-button @click="$router.back()" class="back-btn">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <h1 class="neo-h2">订单详情</h1>
    </div>

    <el-skeleton v-if="loading" :rows="6" animated />
    <div v-else-if="order">
      <el-row :gutter="20">
        <!-- Order Info Card -->
        <el-col :span="16">
          <el-card class="info-card neo-panel">
            <template #header>
              <div class="card-header neo-h3">订单信息</div>
            </template>
            <el-descriptions :column="2" border>
              <el-descriptions-item label="订单号">{{ order.orderNo }}</el-descriptions-item>
              <el-descriptions-item label="订单状态">
                <el-tag :type="getStatusType(order.status)">{{ order.statusName }}</el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="用户ID">{{ order.userId }}</el-descriptions-item>
              <el-descriptions-item label="用户名">{{ order.userName }}</el-descriptions-item>
              <el-descriptions-item label="订单金额">
                <span class="amount">¥{{ order.totalAmount }}</span>
              </el-descriptions-item>
              <el-descriptions-item label="下单时间">{{ order.createTime }}</el-descriptions-item>
              <el-descriptions-item label="收货人">{{ order.receiverName }}</el-descriptions-item>
              <el-descriptions-item label="联系电话">{{ order.receiverPhone }}</el-descriptions-item>
              <el-descriptions-item label="收货地址" :span="2">{{ order.receiverAddress }}</el-descriptions-item>
              <el-descriptions-item label="订单备注" :span="2">{{ order.remark || '-' }}</el-descriptions-item>
            </el-descriptions>
          </el-card>
        </el-col>

        <!-- Action Panel Card -->
        <el-col :span="8">
          <el-card class="action-card neo-panel">
            <template #header>
              <div class="card-header neo-h3">订单操作</div>
            </template>
            <div class="action-list">
              <template v-if="order.status === 0">
                <el-button type="success" @click="updateStatus(1)" :loading="actionLoading">确认支付</el-button>
                <el-button type="danger" @click="updateStatus(4)" :loading="actionLoading">取消订单</el-button>
              </template>
              <template v-if="order.status === 1">
                <el-button type="primary" @click="updateStatus(2)" :loading="actionLoading">确认发货</el-button>
                <el-button type="danger" @click="updateStatus(4)" :loading="actionLoading">取消订单</el-button>
              </template>
              <template v-if="order.status === 2">
                <el-button type="warning" @click="updateStatus(3)" :loading="actionLoading">确认完成</el-button>
              </template>
              <template v-if="order.status === 3 || order.status === 4">
                <p class="no-action">该订单已完结，无法再操作</p>
              </template>
            </div>
          </el-card>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const route = useRoute()
const loading = ref(false)
const actionLoading = ref(false)
const order = ref(null)

function getStatusType(s) {
  const map = { 0: 'warning', 1: 'success', 2: 'primary', 3: 'info', 4: 'danger' }
  return map[s] || 'info'
}

async function loadOrder() {
  loading.value = true
  try {
    const res = await api.get(`/admin/orders/${route.params.id}`)
    order.value = res.data
  } catch (error) {
    ElMessage.error('加载订单详情失败')
  } finally {
    loading.value = false
  }
}

async function updateStatus(newStatus) {
  actionLoading.value = true
  try {
    await api.put(`/admin/orders/${route.params.id}/status`, { status: newStatus })
    ElMessage.success('状态更新成功')
    loadOrder()
  } catch (error) {
    ElMessage.error('状态更新失败')
  } finally {
    actionLoading.value = false
  }
}

onMounted(() => {
  loadOrder()
})
</script>

<style scoped>
.order-detail {
  max-width: 1100px;
}

.page-head {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
}

.back-btn {
  border-radius: 12px !important;
  border: 3px solid var(--neo-border) !important;
  box-shadow: 3px 3px 0 var(--neo-border) !important;
  font-weight: 700 !important;
}

.info-card,
.action-card {
  margin-bottom: 16px;
}

.info-card :deep(.el-card__header),
.action-card :deep(.el-card__header) {
  padding: 16px 24px !important;
  border-bottom: 2px solid var(--neo-border) !important;
  background: #ffe08a !important;
}

.card-header {
  font-size: 18px;
}

.amount {
  color: #dc2626;
  font-weight: 800;
  font-size: 16px;
}

.action-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.no-action {
  color: var(--neo-text-soft);
  font-size: var(--neo-fs-sm);
  text-align: center;
  padding: 20px 0;
  font-weight: 600;
}
</style>

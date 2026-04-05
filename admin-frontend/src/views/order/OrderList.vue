<template>
  <div class="order-list">
    <!-- Page Title -->
    <div class="page-head">
      <h1 class="neo-h2">订单管理</h1>
      <p class="neo-caption">查看和管理所有订单</p>
    </div>

    <!-- Filter Bar -->
    <div class="filter-panel neo-panel">
      <el-input
        v-model="orderNo"
        placeholder="订单号"
        clearable
        style="width: 200px"
        @keyup.enter="loadOrders"
      />
      <el-select v-model="status" placeholder="订单状态" clearable style="width: 160px" @change="loadOrders">
        <el-option label="待支付" :value="0" />
        <el-option label="已支付" :value="1" />
        <el-option label="已发货" :value="2" />
        <el-option label="已完成" :value="3" />
        <el-option label="已取消" :value="4" />
      </el-select>
      <el-button type="primary" @click="loadOrders">搜索</el-button>
    </div>

    <!-- Table -->
    <div class="table-card neo-panel">
      <el-table :data="orders" v-loading="loading">
        <el-table-column prop="orderNo" label="订单号" width="200" />
        <el-table-column prop="userName" label="用户" width="100" />
        <el-table-column prop="totalAmount" label="金额" width="120">
          <template #default="{ row }">
            <span class="amount">¥{{ row.totalAmount }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="receiverName" label="收货人" width="100" />
        <el-table-column prop="receiverPhone" label="电话" width="130" />
        <el-table-column prop="createTime" label="下单时间" width="160" />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" @click="$router.push(`/admin/orders/${row.id}`)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadOrders"
        />
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const loading = ref(false)
const orders = ref([])
const orderNo = ref('')
const status = ref(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

function getStatusType(s) {
  const map = { 0: 'warning', 1: 'success', 2: 'primary', 3: 'info', 4: 'danger' }
  return map[s] || 'info'
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await api.get('/admin/orders', {
      params: { page: page.value, size: size.value, orderNo: orderNo.value || null, status: status.value || null }
    })
    orders.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    ElMessage.error('加载订单列表失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadOrders()
})
</script>

<style scoped>
.order-list {
  max-width: 1200px;
}

.page-head {
  margin-bottom: 20px;
}

.page-head .neo-h2 {
  margin-bottom: 4px;
}

.filter-panel {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
  align-items: center;
  margin-bottom: 16px;
}

.table-card {
  padding: 0;
  overflow: hidden;
}

.table-card :deep(.el-table) {
  border-radius: 0;
  border: none;
}

.amount {
  color: #dc2626;
  font-weight: 800;
  font-size: 15px;
}

.pagination-wrap {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
  padding: 16px;
}
</style>

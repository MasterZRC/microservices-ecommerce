<template>
  <div class="dashboard">
    <!-- Page Title -->
    <div class="page-head">
      <h1 class="neo-h2">数据概览</h1>
      <p class="neo-caption">实时了解店铺经营状况</p>
    </div>

    <!-- Stats Cards -->
    <el-row :gutter="20" class="stats-row">
      <el-col :span="6" v-for="stat in stats" :key="stat.title">
        <div class="stat-card" :style="{ '--stat-color': stat.color, '--stat-bg': stat.bg }">
          <div class="stat-icon">
            <component :is="stat.icon" />
          </div>
          <div class="stat-info">
            <p class="stat-value">{{ stat.value }}</p>
            <p class="stat-title">{{ stat.title }}</p>
          </div>
        </div>
      </el-col>
    </el-row>

    <!-- Recent Orders Table -->
    <div class="table-card">
      <div class="table-header">
        <h3 class="neo-h3">最新订单</h3>
        <el-button type="primary" @click="$router.push('/admin/orders')">查看全部</el-button>
      </div>
      <el-table :data="recentOrders" v-loading="loading" :row-class-name="() => 'neo-table-row'">
        <el-table-column prop="orderNo" label="订单号" width="180" />
        <el-table-column prop="userName" label="用户" width="120" />
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
        <el-table-column prop="createTime" label="下单时间" />
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" @click="$router.push(`/admin/orders/${row.id}`)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, markRaw } from 'vue'
import { User, Goods, List, Money } from '@element-plus/icons-vue'
import api from '../api'

const loading = ref(false)
const statsData = ref({
  userCount: 0,
  productCount: 0,
  orderCount: 0,
  todaySales: 0
})
const recentOrders = ref([])

const stats = ref([
  { title: '用户总数', value: '0', icon: markRaw(User), color: '#1d4ed8', bg: '#dbeafe' },
  { title: '商品总数', value: '0', icon: markRaw(Goods), color: '#065f46', bg: '#d1fae5' },
  { title: '订单总数', value: '0', icon: markRaw(List), color: '#92400e', bg: '#fef3c7' },
  { title: '今日销售额', value: '¥0', icon: markRaw(Money), color: '#991b1b', bg: '#fee2e2' }
])

function getStatusType(status) {
  const map = { 0: 'warning', 1: 'success', 2: 'primary', 3: 'info', 4: 'danger' }
  return map[status] || 'info'
}

function formatNumber(num) {
  if (num >= 10000) return (num / 10000).toFixed(1) + 'w'
  return num.toLocaleString()
}

function formatAmount(amount) {
  if (!amount) return '¥0'
  return '¥' + Number(amount).toLocaleString('zh-CN', { minimumFractionDigits: 2 })
}

async function loadDashboard() {
  loading.value = true
  try {
    const [statsRes, ordersRes] = await Promise.all([
      api.get('/admin/dashboard/stats').catch(() => null),
      api.get('/admin/dashboard/recent-orders', { params: { limit: 10 } }).catch(() => null)
    ])

    if (statsRes?.data) {
      statsData.value = statsRes.data
      stats.value = [
        { title: '用户总数', value: formatNumber(statsRes.data.userCount || 0), icon: markRaw(User), color: '#1d4ed8', bg: '#dbeafe' },
        { title: '商品总数', value: formatNumber(statsRes.data.productCount || 0), icon: markRaw(Goods), color: '#065f46', bg: '#d1fae5' },
        { title: '订单总数', value: formatNumber(statsRes.data.orderCount || 0), icon: markRaw(List), color: '#92400e', bg: '#fef3c7' },
        { title: '今日销售额', value: formatAmount(statsRes.data.todaySales), icon: markRaw(Money), color: '#991b1b', bg: '#fee2e2' }
      ]
    }

    if (ordersRes?.data) {
      recentOrders.value = ordersRes.data
    }
  } catch (error) {
    console.error('Dashboard load error:', error)
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadDashboard()
})
</script>

<style scoped>
.dashboard {
  max-width: 1200px;
}

.page-head {
  margin-bottom: 24px;
}

.page-head .neo-h2 {
  margin-bottom: 4px;
}

.stats-row {
  margin-bottom: 24px;
}

.stat-card {
  background: var(--neo-surface);
  border: 3px solid var(--neo-border);
  border-radius: var(--neo-radius);
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: var(--neo-shadow);
  transition: all 0.15s ease;
  cursor: default;
}

.stat-card:hover {
  transform: translate(-2px, -2px);
  box-shadow: 8px 8px 0 var(--neo-border);
}

.stat-icon {
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: var(--stat-bg);
  border: 2px solid var(--neo-border);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--stat-color);
  font-size: 24px;
  flex-shrink: 0;
  box-shadow: 3px 3px 0 var(--neo-border);
}

.stat-icon .el-icon {
  font-size: 28px;
  color: var(--stat-color);
}

.stat-info {
  flex: 1;
}

.stat-value {
  font-size: 26px;
  font-weight: 900;
  color: var(--neo-text);
  line-height: 1.2;
  letter-spacing: -0.5px;
}

.stat-title {
  font-size: 13px;
  color: var(--neo-text-soft);
  margin-top: 4px;
  font-weight: 600;
}

.table-card {
  background: var(--neo-surface);
  border: 3px solid var(--neo-border);
  border-radius: var(--neo-radius);
  padding: 24px;
  box-shadow: var(--neo-shadow);
}

.table-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 20px;
}

.table-header .neo-h3 {
  font-size: 18px;
}

.amount {
  color: #dc2626;
  font-weight: 800;
  font-size: 15px;
}
</style>

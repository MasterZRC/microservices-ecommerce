<template>
  <div class="seckill-list">
    <!-- Page Title -->
    <div class="page-head">
      <h1 class="neo-h2">秒杀管理</h1>
      <p class="neo-caption">创建和管理秒杀活动</p>
    </div>

    <!-- Filter Bar -->
    <div class="filter-panel neo-panel">
      <el-input
        v-model="keyword"
        placeholder="搜索活动名称"
        clearable
        style="width: 220px"
        @keyup.enter="loadActivities"
      />
      <el-select v-model="status" placeholder="活动状态" clearable style="width: 160px" @change="loadActivities">
        <el-option label="进行中" :value="1" />
        <el-option label="已禁用" :value="0" />
        <el-option label="已结束" :value="2" />
      </el-select>
      <el-button type="primary" @click="loadActivities">搜索</el-button>
      <el-button type="success" @click="$router.push('/admin/seckill/create')">
        <el-icon><Plus /></el-icon>
        新增秒杀
      </el-button>
    </div>

    <!-- Table -->
    <div class="table-card neo-panel">
      <el-table :data="activities" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="活动名称" min-width="160" />
        <el-table-column prop="productName" label="商品名称" min-width="160" />
        <el-table-column prop="seckillPrice" label="秒杀价" width="100">
          <template #default="{ row }">
            <span class="seckill-price">¥{{ row.seckillPrice }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="80" />
        <el-table-column prop="soldCount" label="已售" width="80" />
        <el-table-column label="活动时间" width="200">
          <template #default="{ row }">
            {{ row.startTime }} ~ {{ row.endTime }}
          </template>
        </el-table-column>
        <el-table-column prop="statusName" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="getStatusType(row.status)" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" @click="$router.push(`/admin/seckill/edit/${row.id}`)">编辑</el-button>
            <el-button type="warning" @click="handleStock(row)">调整库存</el-button>
            <el-button type="danger" @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadActivities"
        />
      </div>
    </div>

    <!-- Stock Dialog -->
    <el-dialog v-model="stockDialogVisible" title="调整秒杀库存" width="420px">
      <el-form :model="stockForm" label-width="80px">
        <el-form-item label="活动名称">
          <span class="neo-body">{{ stockForm.name }}</span>
        </el-form-item>
        <el-form-item label="当前库存">
          <span class="neo-body">{{ stockForm.currentStock }}</span>
        </el-form-item>
        <el-form-item label="新库存">
          <el-input-number v-model="stockForm.stock" :min="0" style="width: 200px" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="stockDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitStock" :loading="stockLoading">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const loading = ref(false)
const activities = ref([])
const keyword = ref('')
const status = ref(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const stockDialogVisible = ref(false)
const stockLoading = ref(false)
const stockForm = ref({ id: null, name: '', currentStock: 0, stock: 0 })

function getStatusType(s) {
  return s === 1 ? 'success' : s === 2 ? 'info' : 'danger'
}

async function loadActivities() {
  loading.value = true
  try {
    const res = await api.get('/admin/seckill/activities', {
      params: { page: page.value, size: size.value, keyword: keyword.value || null, status: status.value || null }
    })
    activities.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    ElMessage.error('加载秒杀活动列表失败')
  } finally {
    loading.value = false
  }
}

function handleStock(row) {
  stockForm.value = { id: row.id, name: row.name, currentStock: row.stock, stock: row.stock }
  stockDialogVisible.value = true
}

async function submitStock() {
  stockLoading.value = true
  try {
    await api.put(`/admin/seckill/activities/${stockForm.value.id}/stock`, null, { params: { stock: stockForm.value.stock } })
    ElMessage.success('库存调整成功')
    stockDialogVisible.value = false
    loadActivities()
  } catch (error) {
    ElMessage.error('库存调整失败')
  } finally {
    stockLoading.value = false
  }
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm('确定要删除该秒杀活动吗？', '提示')
    await api.delete(`/admin/seckill/activities/${row.id}`)
    ElMessage.success('删除成功')
    loadActivities()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error('删除失败')
  }
}

onMounted(() => {
  loadActivities()
})
</script>

<style scoped>
.seckill-list {
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

.seckill-price {
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

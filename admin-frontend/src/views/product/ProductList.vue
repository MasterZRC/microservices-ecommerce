<template>
  <div class="product-list">
    <!-- Page Title -->
    <div class="page-head">
      <h1 class="neo-h2">商品管理</h1>
      <p class="neo-caption">管理商品的上架、下架及库存</p>
    </div>

    <!-- Filter Bar - Neo Panel Style -->
    <div class="filter-panel neo-panel">
      <el-input
        v-model="keyword"
        placeholder="搜索商品名称"
        clearable
        style="width: 220px"
        @clear="loadProducts"
        @keyup.enter="loadProducts"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select v-model="categoryId" placeholder="选择分类" clearable style="width: 180px" @change="loadProducts">
        <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
      </el-select>

      <el-select v-model="status" placeholder="商品状态" clearable style="width: 140px" @change="loadProducts">
        <el-option label="上架" :value="1" />
        <el-option label="下架" :value="0" />
      </el-select>

      <el-button type="primary" @click="loadProducts">搜索</el-button>
      <el-button type="success" @click="$router.push('/admin/products/create')">
        <el-icon><Plus /></el-icon>
        新增商品
      </el-button>
    </div>

    <!-- Table -->
    <div class="table-card neo-panel">
      <el-table :data="products" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="name" label="商品名称" min-width="180">
          <template #default="{ row }">
            <div class="product-name">
              <el-image v-if="row.imageUrl" :src="row.imageUrl" fit="cover" class="product-thumb" />
              <span>{{ row.name }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="categoryName" label="分类" width="100" />
        <el-table-column prop="price" label="价格" width="100">
          <template #default="{ row }">
            <span class="price">¥{{ row.price }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="80" />
        <el-table-column prop="sales" label="销量" width="80" />
        <el-table-column prop="statusName" label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">{{ row.statusName }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="160" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" @click="$router.push(`/admin/products/edit/${row.id}`)">编辑</el-button>
            <el-button type="warning" @click="handleStock(row)">调整库存</el-button>
            <el-button :type="row.status === 1 ? 'danger' : 'success'" @click="handleDelete(row)">{{ row.status === 1 ? '下架' : '上架' }}</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-wrap">
        <el-pagination
          v-model:current-page="page"
          :page-size="size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="loadProducts"
        />
      </div>
    </div>

    <!-- Stock Dialog -->
    <el-dialog v-model="stockDialogVisible" title="调整库存" width="420px">
      <el-form :model="stockForm" label-width="80px">
        <el-form-item label="商品名称">
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
import { Search, Plus } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../../api'

const loading = ref(false)
const products = ref([])
const categories = ref([])
const keyword = ref('')
const categoryId = ref(null)
const status = ref(null)
const page = ref(1)
const size = ref(10)
const total = ref(0)

const stockDialogVisible = ref(false)
const stockLoading = ref(false)
const stockForm = ref({ id: null, name: '', currentStock: 0, stock: 0 })

async function loadProducts() {
  loading.value = true
  try {
    const res = await api.get('/admin/products', {
      params: { page: page.value, size: size.value, keyword: keyword.value || null, categoryId: categoryId.value || null, status: status.value || null }
    })
    products.value = res.data.records || []
    total.value = res.data.total || 0
  } catch (error) {
    ElMessage.error('加载商品列表失败')
  } finally {
    loading.value = false
  }
}

async function loadCategories() {
  try {
    const res = await api.get('/admin/products/categories')
    categories.value = res.data.records || []
  } catch (error) {
    console.error('Load categories error:', error)
  }
}

function handleStock(row) {
  stockForm.value = { id: row.id, name: row.name, currentStock: row.stock, stock: row.stock }
  stockDialogVisible.value = true
}

async function submitStock() {
  stockLoading.value = true
  try {
    await api.put(`/admin/products/${stockForm.value.id}/stock`, null, { params: { stock: stockForm.value.stock } })
    ElMessage.success('库存调整成功')
    stockDialogVisible.value = false
    loadProducts()
  } catch (error) {
    ElMessage.error('库存调整失败')
  } finally {
    stockLoading.value = false
  }
}

async function handleDelete(row) {
  const action = row.status === 1 ? '下架' : '上架'
  try {
    await ElMessageBox.confirm(`确定要${action}该商品吗？`, '提示')
    await api.delete(`/admin/products/${row.id}`)
    ElMessage.success(`${action}成功`)
    loadProducts()
  } catch (error) {
    if (error !== 'cancel') ElMessage.error(`${action}失败`)
  }
}

onMounted(() => {
  loadProducts()
  loadCategories()
})
</script>

<style scoped>
.product-list {
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

.table-card :deep(.el-table__inner-wrapper) {
  border-radius: 0;
}

.product-name {
  display: flex;
  align-items: center;
  gap: 10px;
}

.product-thumb {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  flex-shrink: 0;
  border: 2px solid var(--neo-border);
}

.price {
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

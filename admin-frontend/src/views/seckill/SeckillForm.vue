<template>
  <div class="seckill-form">
    <!-- Page Title -->
    <div class="page-head">
      <el-button @click="$router.back()" class="back-btn">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <h1 class="neo-h2">{{ isEdit ? '编辑秒杀活动' : '创建秒杀活动' }}</h1>
    </div>

    <el-card class="form-card neo-panel">
      <template #header>
        <div class="card-header neo-h3">{{ isEdit ? '编辑秒杀活动' : '创建秒杀活动' }}</div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 600px">
        <el-form-item label="活动名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入活动名称" />
        </el-form-item>

        <el-form-item label="选择商品" prop="productId">
          <el-select v-model="form.productId" placeholder="请选择商品" style="width: 100%" filterable @change="onProductChange">
            <el-option
              v-for="p in products"
              :key="p.id"
              :label="`${p.name} - ¥${p.price}`"
              :value="p.id"
            />
          </el-select>
        </el-form-item>

        <el-form-item label="秒杀价格" prop="seckillPrice">
          <el-input-number v-model="form.seckillPrice" :min="0.01" :precision="2" style="width: 200px" />
        </el-form-item>

        <el-form-item label="秒杀库存" prop="stock">
          <el-input-number v-model="form.stock" :min="1" style="width: 200px" />
        </el-form-item>

        <el-form-item label="开始时间" prop="startTime">
          <el-date-picker
            v-model="form.startTime"
            type="datetime"
            placeholder="选择开始时间"
            style="width: 220px"
          />
        </el-form-item>

        <el-form-item label="结束时间" prop="endTime">
          <el-date-picker
            v-model="form.endTime"
            type="datetime"
            placeholder="选择结束时间"
            style="width: 220px"
          />
        </el-form-item>

        <el-form-item label="活动状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">启用</el-radio>
            <el-radio :label="0">禁用</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSubmit" :loading="loading">
            {{ isEdit ? '保存修改' : '创建秒杀' }}
          </el-button>
          <el-button @click="$router.back()">取消</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import api from '../../api'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const loading = ref(false)
const products = ref([])

const form = reactive({
  name: '',
  productId: null,
  seckillPrice: 0.01,
  stock: 100,
  startTime: null,
  endTime: null,
  status: 1
})

const rules = {
  name: [{ required: true, message: '请输入活动名称', trigger: 'blur' }],
  productId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  seckillPrice: [{ required: true, message: '请输入秒杀价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入秒杀库存', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

function onProductChange(productId) {
  const product = products.value.find(p => p.id === productId)
  if (product) {
    form.seckillPrice = Number(product.price) * 0.8
    form.name = product.name + ' 限时秒杀'
  }
}

async function loadProducts() {
  try {
    const res = await api.get('/admin/products', { params: { page: 1, size: 100, status: 1 } })
    products.value = res.data.records || []
  } catch (error) {
    console.error('Load products error:', error)
  }
}

async function loadActivity() {
  if (!isEdit.value) return
  try {
    const res = await api.get(`/admin/seckill/activities/${route.params.id}`)
    const a = res.data
    Object.assign(form, {
      name: a.name,
      productId: a.productId,
      seckillPrice: Number(a.seckillPrice),
      stock: a.stock,
      startTime: new Date(a.startTime),
      endTime: new Date(a.endTime),
      status: a.status
    })
  } catch (error) {
    ElMessage.error('加载秒杀活动信息失败')
  }
}

async function handleSubmit() {
  loading.value = true
  try {
    const payload = {
      ...form,
      startTime: form.startTime ? new Date(form.startTime).toISOString() : null,
      endTime: form.endTime ? new Date(form.endTime).toISOString() : null
    }
    if (isEdit.value) {
      await api.put(`/admin/seckill/activities/${route.params.id}`, payload)
      ElMessage.success('修改成功')
    } else {
      await api.post('/admin/seckill/activities', payload)
      ElMessage.success('创建成功')
    }
    router.push('/admin/seckill')
  } catch (error) {
    ElMessage.error(error.message || '操作失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadProducts()
  loadActivity()
})
</script>

<style scoped>
.seckill-form {
  max-width: 800px;
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

.form-card {
  padding: 0;
}

.form-card :deep(.el-card__header) {
  padding: 16px 24px !important;
  border-bottom: 2px solid var(--neo-border) !important;
  background: #ffe08a !important;
}

.card-header {
  font-size: 18px;
}
</style>

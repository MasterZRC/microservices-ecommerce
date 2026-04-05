<template>
  <div class="product-form">
    <!-- Page Title -->
    <div class="page-head">
      <el-button @click="$router.back()" class="back-btn">
        <el-icon><ArrowLeft /></el-icon>
        返回
      </el-button>
      <h1 class="neo-h2">{{ isEdit ? '编辑商品' : '新增商品' }}</h1>
    </div>

    <el-card class="form-card neo-panel">
      <template #header>
        <div class="card-header neo-h3">
          {{ isEdit ? '编辑商品' : '新增商品' }}
        </div>
      </template>

      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px" style="max-width: 600px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" placeholder="请输入商品名称" />
        </el-form-item>

        <el-form-item label="商品描述" prop="description">
          <el-input v-model="form.description" type="textarea" :rows="3" placeholder="请输入商品描述" />
        </el-form-item>

        <el-form-item label="商品分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="请选择分类" style="width: 100%">
            <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
          </el-select>
        </el-form-item>

        <el-form-item label="品牌" prop="brand">
          <el-input v-model="form.brand" placeholder="请输入品牌" />
        </el-form-item>

        <el-form-item label="商品价格" prop="price">
          <el-input-number v-model="form.price" :min="0.01" :precision="2" style="width: 200px" />
        </el-form-item>

        <el-form-item label="原价">
          <el-input-number v-model="form.originalPrice" :min="0.01" :precision="2" style="width: 200px" />
        </el-form-item>

        <el-form-item label="商品库存" prop="stock">
          <el-input-number v-model="form.stock" :min="0" style="width: 200px" />
        </el-form-item>

        <el-form-item label="商品图片">
          <el-input v-model="form.imageUrl" placeholder="请输入图片URL" />
        </el-form-item>

        <el-form-item label="商品状态">
          <el-radio-group v-model="form.status">
            <el-radio :label="1">上架</el-radio>
            <el-radio :label="0">下架</el-radio>
          </el-radio-group>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" @click="handleSubmit" :loading="loading">
            {{ isEdit ? '保存修改' : '创建商品' }}
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
const categories = ref([])

const form = reactive({
  name: '',
  description: '',
  categoryId: null,
  brand: '',
  price: 0.01,
  originalPrice: 0.01,
  stock: 0,
  imageUrl: '',
  status: 1
})

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  price: [{ required: true, message: '请输入价格', trigger: 'blur' }],
  stock: [{ required: true, message: '请输入库存', trigger: 'blur' }]
}

async function loadCategories() {
  try {
    const res = await api.get('/admin/products/categories')
    categories.value = res.data.records || []
  } catch (error) {
    console.error('Load categories error:', error)
  }
}

async function loadProduct() {
  if (!isEdit.value) return
  try {
    const res = await api.get(`/admin/products/${route.params.id}`)
    const p = res.data
    Object.assign(form, {
      name: p.name,
      description: p.description,
      categoryId: p.categoryId,
      brand: p.brand,
      price: Number(p.price),
      originalPrice: Number(p.originalPrice || p.price),
      stock: p.stock,
      imageUrl: p.imageUrl,
      status: p.status
    })
  } catch (error) {
    ElMessage.error('加载商品信息失败')
  }
}

async function handleSubmit() {
  loading.value = true
  try {
    if (isEdit.value) {
      await api.put(`/admin/products/${route.params.id}`, form)
      ElMessage.success('修改成功')
    } else {
      await api.post('/admin/products', form)
      ElMessage.success('创建成功')
    }
    router.push('/admin/products')
  } catch (error) {
    ElMessage.error(error.message || '操作失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadCategories()
  loadProduct()
})
</script>

<style scoped>
.product-form {
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

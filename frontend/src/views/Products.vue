<template>
  <div class="products-page">
    <div class="container page-container">
      <h2 class="page-title">商品列表</h2>
      <div class="filters">
        <el-select v-model="categoryId" placeholder="选择分类" clearable @change="loadProducts">
          <el-option v-for="cat in categories" :key="cat.id" :label="cat.name" :value="cat.id" />
        </el-select>
        <el-input v-model="keyword" placeholder="搜索商品" clearable @keyup.enter="loadProducts" style="width: 200px" />
        <button class="neo-btn-search" @click="loadProducts">搜索</button>
      </div>
      <el-alert v-if="errorText" :title="errorText" type="error" show-icon style="margin-bottom: 12px" />
      <el-skeleton v-if="loading" :rows="6" animated />
      <el-row v-else :gutter="20">
        <el-col :span="6" v-for="product in products" :key="product.id">
          <el-card shadow="hover" @click="handleViewProduct(product)">
            <div class="product-card">
              <el-image :src="product.imageUrl" fit="cover" />
              <div class="product-info">
                <h3>{{ product.name }}</h3>
                <p class="price">¥{{ product.price }}</p>
                <p class="sales">销量: {{ product.sales }}</p>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>
      <el-pagination v-if="!loading && total > 0" layout="prev, pager, next" :total="total" :page-size="size" :current-page="page" @current-change="handlePageChange" style="margin-top: 20px; justify-content: center; display: flex" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import api from '../api'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const products = ref([])
const categories = ref([])
const categoryId = ref(null)
const keyword = ref('')
const page = ref(1)
const size = ref(12)
const total = ref(0)
const loading = ref(false)
const errorText = ref('')

onMounted(() => {
  syncQueryToFilters()
  loadCategories()
  loadProducts()
})

watch(
  () => route.query,
  () => {
    syncQueryToFilters()
    page.value = 1
    loadProducts()
  }
)

function syncQueryToFilters() {
  keyword.value = route.query.keyword ? String(route.query.keyword) : ''
  categoryId.value = route.query.categoryId ? Number(route.query.categoryId) : null
}

async function loadCategories() {
  try {
    const res = await api.getCategories()
    categories.value = res.data || []
  } catch (error) {
    errorText.value = api.getErrorMessage(error, '分类加载失败')
  }
}

async function loadProducts() {
  loading.value = true
  errorText.value = ''
  try {
    const res = await api.getProducts({ page: page.value, pageSize: size.value, categoryId: categoryId.value, keyword: keyword.value })
    products.value = res.data?.products || []
    total.value = res.data?.total || 0
  } catch (error) {
    errorText.value = api.getErrorMessage(error, '商品加载失败')
  } finally {
    loading.value = false
  }
}

function handlePageChange(newPage) {
  page.value = newPage
  loadProducts()
}

async function handleViewProduct(product) {
  if (!product?.id) {
    return
  }

  if (userStore.token && userStore.userInfo?.id) {
    try {
      await api.recordBehavior({
        userId: userStore.userInfo.id,
        productId: product.id,
        behaviorType: 'click'
      })
    } catch (error) {
      console.warn('行为上报失败: click', product.id, error)
    }
  }

  router.push(`/product/${product.id}`)
}
</script>

<style scoped>
.products-page {
  padding: 20px 0 28px;
}
.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}
.filters {
  display: flex;
  gap: 12px;
  margin-bottom: 24px;
  padding: 14px;
  border: 3px solid #101010;
  border-radius: 16px;
  background: #fffef9;
  box-shadow: 6px 6px 0 #101010;
}
.product-card {
  cursor: pointer;
}
.product-card .el-image {
  width: 100%;
  height: 210px;
  border-radius: 12px;
}
.product-info h3 {
  font-size: var(--neo-fs-lg);
  margin: 12px 0 10px;
  color: #101010;
  font-weight: 800;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.product-info .price {
  font-size: var(--neo-fs-xl);
  color: #1d4ed8;
  font-weight: 700;
}
.product-info .sales {
  font-size: var(--neo-fs-sm);
  color: #374151;
}

.products-page :deep(.el-card) {
  border: 3px solid #101010 !important;
  box-shadow: 6px 6px 0 #101010 !important;
}

.products-page :deep(.el-card:hover) {
  transform: translate(-2px, -2px);
  box-shadow: 8px 8px 0 #101010 !important;
}

/* Neobrutalism 搜索按钮 */
.neo-btn-search {
  padding: 10px 20px;
  font-size: 14px;
  font-weight: 700;
  color: #fff;
  background: #3b82f6;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-search:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #2563eb;
}

.neo-btn-search:active {
  transform: translate(1px, 1px);
  box-shadow: 2px 2px 0 #101010;
}
</style>
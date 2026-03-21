import axios from 'axios'

const rawApiBaseUrl = (import.meta.env.VITE_API_BASE_URL || '/api').trim()
const normalizedApiBaseUrl = rawApiBaseUrl.endsWith('/')
  ? rawApiBaseUrl.slice(0, -1)
  : rawApiBaseUrl

const api = axios.create({
  baseURL: normalizedApiBaseUrl || '/api',
  timeout: 10000
})

function looksLikeMojibake(str) {
  return /[ÃÂÐÑØåäæçèéêëìíîïðñòóôõöùúûüýþÿ¤¢£§]/.test(str)
}

function decodeLatin1Utf8(str) {
  try {
    return decodeURIComponent(escape(str))
  } catch {
    return str
  }
}

function recoverMojibake(str) {
  if (typeof str !== 'string' || str.length === 0) return str

  let current = str
  for (let i = 0; i < 2; i++) {
    const decoded = decodeLatin1Utf8(current)
    if (decoded === current) break

    const decodedHasChinese = /[\u4e00-\u9fff]/.test(decoded)
    const currentHasChinese = /[\u4e00-\u9fff]/.test(current)

    if (decodedHasChinese && !currentHasChinese) {
      current = decoded
      continue
    }

    if (looksLikeMojibake(current) && !looksLikeMojibake(decoded) && !decoded.includes('�')) {
      current = decoded
      continue
    }

    break
  }

  return current
}

function normalizePossibleMojibake(value) {
  if (typeof value === 'string') {
    return recoverMojibake(value)
  }

  if (Array.isArray(value)) {
    return value.map(item => normalizePossibleMojibake(item))
  }

  if (value && typeof value === 'object') {
    const normalized = {}
    Object.keys(value).forEach(key => {
      normalized[key] = normalizePossibleMojibake(value[key])
    })
    return normalized
  }

  return value
}

function normalizeProductPage(payload) {
  if (Array.isArray(payload)) {
    return {
      products: payload,
      total: payload.length,
      page: 1,
      pageSize: payload.length
    }
  }

  if (payload && Array.isArray(payload.products)) {
    return payload
  }

  if (payload && Array.isArray(payload.records)) {
    return {
      products: payload.records,
      total: payload.total || payload.records.length,
      page: payload.page || 1,
      pageSize: payload.pageSize || payload.size || payload.records.length
    }
  }

  return {
    products: [],
    total: 0,
    page: 1,
    pageSize: 10
  }
}

function extractErrorMessage(error, fallback = '请求失败') {
  const message = error?.response?.data?.message || error?.message
  return typeof message === 'string' && message.trim() ? message : fallback
}

api.interceptors.request.use(config => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  response => ({
    ...response,
    data: normalizePossibleMojibake(response.data)
  }),
  error => {
    if (error?.response?.data) {
      error.response.data = normalizePossibleMojibake(error.response.data)
    }
    return Promise.reject(error)
  }
)

export default {
  getErrorMessage(error, fallback) {
    return extractErrorMessage(error, fallback)
  },

  // ====== 商品 ======
  getProducts(params) {
    const requestParams = { ...(params || {}) }
    if (requestParams.size && !requestParams.pageSize) {
      requestParams.pageSize = requestParams.size
      delete requestParams.size
    }
    return api.get('/product/list', { params: requestParams }).then(res => ({
      ...res,
      data: normalizeProductPage(res.data)
    }))
  },
  getProductById(id) {
    return api.get(`/product/${id}`)
  },
  createProduct(data) {
    return api.post('/product/create', data)
  },
  updateProduct(data) {
    return api.put('/product/update', data)
  },
  deleteProduct(id) {
    return api.delete(`/product/${id}`)
  },
  getCategories() {
    return api.get('/product/category/list')
  },

  // ====== 订单 ======
  createOrder(data) {
    return api.post('/order/create', data)
  },
  getOrders(userId) {
    return api.get('/order/list', { params: { userId } })
  },
  payOrder(params) {
    return api.post('/order/pay', null, { params })
  },
  getCart(userId) {
    return api.get('/order/cart/list', { params: { userId } })
  },
  getCartCount(userId) {
    return api.get('/order/cart/count', { params: { userId } })
  },
  addToCart(params) {
    return api.post('/order/cart/add', null, { params })
  },
  removeFromCart(params) {
    return api.delete('/order/cart/remove', { params })
  },

  // ====== 推荐 ======
  getRecommendations(userId) {
    return api.get('/recommendation/personal', { params: { userId: Number(userId) } })
  },
  getRecommendationProducts(userId, limit = 10) {
    return api.get('/recommendation/personal/products', { params: { userId: Number(userId), limit } })
  },
  getPopularProducts() {
    return api.get('/recommendation/popular')
  },
  getPopularProductCards(limit = 10) {
    return api.get('/recommendation/popular/products', { params: { limit } })
  },
  recordBehavior(params) {
    return api.post('/recommendation/behavior', null, { params })
  },

  // ====== 灰度 / A/B ======
  checkGrayUser(userId) {
    return api.get('/recommendation/gray/check', { params: { userId } })
  },

  // ====== A/B 指标仪表盘 ======
  getGrayStatus() {
    return api.get('/recommendation/gray/status')
  },
  getGrayMetrics(date) {
    return api.get('/recommendation/gray/metrics', { params: { date } })
  },
  getGrayCompare(date) {
    return api.get('/recommendation/gray/compare', { params: { date } })
  },
  getExperimentList() {
    return api.get('/recommendation/experiment/list')
  },

  // ====== 秒杀 ======
  startSeckill(params) {
    return api.post('/seckill/start', null, { params })
  },
  getSeckillStock(seckillProductId) {
    return api.get('/seckill/stock', { params: { seckillProductId } })
  },
  getSeckillProducts() {
    return api.get('/seckill/products')
  },
  getUpcomingSeckillProducts(limit = 6) {
    return api.get('/seckill/products/upcoming', { params: { limit } })
  },
  getSeckillActivity() {
    return api.get('/seckill/activity')
  },

  // ====== 用户 ======
  login(data) {
    return api.post('/user/login', data)
  },
  register(data) {
    return api.post('/user/register', data)
  },
  getUserInfo(userId) {
    return api.get(`/user/${userId}`)
  }
}

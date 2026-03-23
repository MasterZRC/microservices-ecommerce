<template>
  <div class="home">
    <div class="container">
      <section class="top-board">
        <aside class="category-panel">
          <h3 class="neo-h3">全部类目</h3>
          <!-- 加载中显示骨架屏 -->
          <ul v-if="categoryLoading" class="category-skeleton">
            <li v-for="i in 8" :key="i" class="skeleton-item">
              <div class="skeleton-line"></div>
            </li>
          </ul>
          <!-- 加载完成显示真实内容 -->
          <ul v-else>
            <li v-for="item in categoryNavItems" :key="item.key" @click="goProducts(item.id)">
              {{ item.name }}
            </li>
          </ul>
        </aside>

        <div class="hero-panel">
          <!-- 加载中显示骨架屏 -->
          <div v-if="seckillLoading" class="hero-banner hero-loading">
            <div class="loading-skeleton">
              <div class="skeleton-tag"></div>
              <div class="skeleton-title"></div>
              <div class="skeleton-subtitle"></div>
              <div class="skeleton-stats">
                <div class="skeleton-stat"></div>
                <div class="skeleton-stat"></div>
                <div class="skeleton-stat"></div>
              </div>
            </div>
          </div>
          <!-- 有秒杀数据时显示秒杀内容 -->
          <div v-else-if="seckillProducts.length > 0" class="hero-banner" @click="$router.push('/seckill')">
            <div class="hero-bg-elements">
              <div class="circle circle-1"></div>
              <div class="circle circle-2"></div>
              <div class="circle circle-3"></div>
              <div class="sparkle sparkle-1">✦</div>
              <div class="sparkle sparkle-2">✦</div>
              <div class="sparkle sparkle-3">✦</div>
            </div>
            <div class="hero-content">
              <div class="hero-tags">
                <span class="tag tag-main">限时补贴专区</span>
                <span class="tag tag-sub">HOT</span>
              </div>
              <h1 class="neo-h1">品质好货 低价直达</h1>
              <p class="hero-subtitle">每日精选爆款，最高立减 <span class="highlight">50%</span></p>
              <div class="hero-stats">
                <div class="stat-item">
                  <span class="stat-number">{{ seckillProducts.length * 1000 + '+' }}</span>
                  <span class="stat-label">人已购</span>
                </div>
                <div class="stat-divider"></div>
                <div class="stat-item">
                  <span class="stat-number">{{ seckillProducts.length * 100 + '+' }}</span>
                  <span class="stat-label">精选好物</span>
                </div>
                <div class="stat-divider"></div>
                <div class="stat-item">
                  <span class="stat-number">{{ seckillEndTime ? '活动中' : '48h' }}</span>
                  <span class="stat-label">限时抢购</span>
                </div>
              </div>
              <div class="hero-actions">
                <el-button type="primary" size="large" class="hero-btn">
                  马上抢购 <span class="btn-arrow">→</span>
                </el-button>
                <span class="hero-hint">爆款数量有限，先到先得</span>
              </div>
            </div>
            <div class="hero-right">
              <div class="hero-product-showcase">
                <div class="showcase-item" v-for="item in seckillProducts" :key="item.id">
                  <span class="showcase-discount">{{ item.discount || '-30%' }}</span>
                  <div class="showcase-img">{{ getProductEmoji(item.productName) }}</div>
                </div>
              </div>
              <div class="hero-countdown" v-if="seckillEndTime">
                <span class="countdown-label">距结束</span>
                <div class="countdown-time">
                  <el-countdown :value="seckillEndTime" format="HH:mm:ss" />
                </div>
              </div>
            </div>
          </div>
          <!-- 无秒杀数据时显示简洁占位 -->
          <div v-else class="hero-banner hero-placeholder">
            <div class="placeholder-content">
              <span class="placeholder-icon">🛍️</span>
              <span class="placeholder-text">暂无秒杀活动</span>
            </div>
          </div>
        </div>

        <aside class="quick-panel">
          <div class="user-box" v-if="userStore.token">
            <p class="welcome neo-body">你好，{{ userStore.userInfo.username || '用户' }}</p>
            <el-button size="small" type="primary" @click="$router.push('/orders')">查看订单</el-button>
          </div>
          <div class="user-box" v-else>
            <p class="welcome neo-body">Hi，欢迎来到电商平台</p>
            <el-button size="small" type="primary" @click="$router.push('/login')">立即登录</el-button>
          </div>

          <div class="quick-actions">
            <el-button plain @click="$router.push('/cart')">购物车</el-button>
            <el-button plain @click="$router.push('/orders')">我的订单</el-button>
            <el-button plain @click="$router.push('/seckill')">秒杀会场</el-button>
          </div>

          <div class="notice-box">
            <h4 class="neo-h3">平台快讯</h4>
            <p v-for="notice in noticeList" :key="notice" class="neo-caption">{{ notice }}</p>
          </div>
        </aside>
      </section>

      <section class="channel-section">
        <!-- 加载中显示骨架屏 -->
        <div v-if="categoryLoading" class="channel-grid channel-skeleton">
          <div class="channel-item" v-for="i in 6" :key="i">
            <div class="skeleton-icon"></div>
            <div class="skeleton-title"></div>
            <div class="skeleton-desc"></div>
          </div>
        </div>
        <!-- 加载完成显示真实内容 -->
        <div v-else class="channel-grid">
          <div class="channel-item" v-for="channel in channelItems" :key="channel.title" @click="goProducts(channel.categoryId)">
            <div class="icon" :style="{ background: channel.color }">{{ channel.icon }}</div>
            <p class="title neo-body">{{ channel.title }}</p>
            <p class="desc neo-caption">{{ channel.desc }}</p>
          </div>
        </div>
        <div class="activity-strip">
          <div class="activity-item" v-for="activity in activities" :key="activity.title">
            <h4 class="neo-h3">{{ activity.title }}</h4>
            <p class="neo-caption">{{ activity.desc }}</p>
          </div>
        </div>
      </section>

      <section class="products hot-zone">
        <div class="section-header">
          <h2 class="neo-h2">
            {{ recommendationTitle }}
            <span v-if="recommendationBadge" class="algo-badge">{{ recommendationBadge }}</span>
            <span v-else-if="isPersonalized" class="rec-badge">个性化推荐</span>
          </h2>
          <div class="header-actions">
            <button v-if="isPersonalized" class="neo-btn-refresh" @click="refreshRecommendations" :disabled="refreshing">
              <span class="refresh-icon" :class="{ spinning: refreshing }">↻</span>
              {{ refreshing ? '换一换中...' : '换一换' }}
            </button>
            <button class="neo-btn-outline" @click="$router.push('/products')">查看更多</button>
          </div>
        </div>
        <el-row :gutter="20">
          <el-col :span="6" v-for="product in hotProducts" :key="product.id">
            <el-card shadow="hover" @click="handleViewProduct(product)">
              <div class="product-card">
                <span class="product-badge" :class="{ 'rec-badge': isPersonalized }">{{ productBadge }}</span>
                <el-image :src="getProductImage(product)" fit="cover" />
                <div class="product-info">
                  <h3 class="neo-h3">{{ product.name }}</h3>
                  <p class="description">{{ product.description }}</p>
                  <p class="price">¥{{ product.price }}</p>
                  <div class="rec-reason" v-if="isPersonalized && product.recReason">
                    <span class="rec-icon">✨</span> {{ product.recReason }}
                  </div>
                  <button class="neo-btn-add" @click.stop="handleAddToCart(product)">
                    加入购物车
                  </button>
                </div>
              </div>
            </el-card>
          </el-col>
        </el-row>
      </section>

      <section class="floor-zone">
        <div class="floor-card" v-for="floor in floors" :key="floor.title">
          <div class="section-header">
            <h2 class="neo-h2">{{ floor.title }}</h2>
            <span>{{ floor.subtitle }}</span>
          </div>
          <el-row :gutter="16">
            <el-col :span="4" v-for="item in floor.items" :key="`${floor.title}-${item.id}`">
              <div class="mini-card" @click="handleViewProduct(item)">
                <el-image :src="getProductImage(item)" fit="cover" />
                <p class="name">{{ item.name }}</p>
                <p class="price">¥{{ item.price }}</p>
              </div>
            </el-col>
          </el-row>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useCartStore } from '../store/cart'
import { useUserStore } from '../store/user'
import { useRouter } from 'vue-router'

const cartStore = useCartStore()
const userStore = useUserStore()
const router = useRouter()

const products = ref([])
const refreshing = ref(false)
const floorProducts = ref({
  digitalHome: [],
  household: []
})
const categories = ref([])
const defaultImage = 'https://picsum.photos/400/400?random=999'
// 曝光埋点相关
const exposedProductIds = ref(new Set())
let exposureDebounceTimer = null

// 秒杀相关数据
const seckillProducts = ref([])
const seckillEndTime = ref(null)
const seckillLoading = ref(false)

// 分类相关
const categoryLoading = ref(false)
const channelLoading = ref(false)

const categoryFallback = ['手机数码', '电脑办公', '家用电器', '美妆个护', '服饰鞋包', '食品生鲜']
const channelIcons = ['🧧', '⚡', '📱', '💻', '🏠', '💄', '👟', '🍼', '🥬', '🎁']
const channelColors = ['#ffe6e6', '#fff0d6', '#e3f0ff', '#dff4ff', '#e8ffe8', '#ffe8f5', '#ece8ff', '#fff3e8', '#e7ffe8', '#f2ebff']

const promoCards = [
  { title: '百亿补贴', description: '大牌补贴\n折上再减', background: 'linear-gradient(135deg,#fff6e5,#ffe7b0)', icon: '💰' },
  { title: '新品首发', description: '新品尝鲜\n限时赠礼', background: 'linear-gradient(135deg,#e8f3ff,#d7eaff)', icon: '🎁' },
  { title: '品牌秒杀', description: '整点开抢\n爆款低价', background: 'linear-gradient(135deg,#fce8f3,#ffd7e9)', icon: '⚡' }
]

const noticeList = [
  '新客首单最高立减 30 元',
  '全场满 199 包邮到家',
  '爆款单品支持 7 天无忧退换'
]

const categoryNavItems = computed(() => {
  if (categories.value.length > 0) {
    return categories.value.map(item => ({
      key: `c-${item.id}`,
      id: Number(item.id),
      name: item.name
    }))
  }
  return categoryFallback.map((name, index) => ({
    key: `f-${index}`,
    id: null,
    name
  }))
})

const channelItems = computed(() => {
  const source = categories.value.length > 0 ? categories.value.slice(0, 6) : categoryFallback.map((name, index) => ({ id: index + 1, name }))
  return source.map((item, index) => ({
    icon: channelIcons[index % channelIcons.length],
    title: item.name,
    desc: '热销精选',
    color: channelColors[index % channelColors.length],
    categoryId: categories.value.length > 0 ? item.id : null
  }))
})

const activities = [
  { title: '品牌馆 5 折起', desc: '国际大牌专场，限时补贴' },
  { title: '超值满减', desc: '满 299 减 40，上不封顶' },
  { title: 'PLUS 专享', desc: '会员专属券，每日可领' },
  { title: '直播精选', desc: '边看边买，爆款直降' }
]

const hotProducts = computed(() => products.value.slice(0, 40))

// 是否显示个性化推荐（登录用户且有推荐结果）
const isPersonalized = computed(() => {
  return userStore.token && userStore.userInfo?.id && products.value.length > 0
})

// 推荐标题文本
const recommendationTitle = computed(() => {
  return isPersonalized.value ? '猜你喜欢' : '热卖精选'
})

// 商品卡片标签
const productBadge = computed(() => {
  return isPersonalized.value ? '推荐' : '热卖'
})

// 获取推荐算法信息
const recommendationBadge = ref('')

// 加载推荐算法信息
async function loadRecommendationInfo() {
  if (!userStore.token || !userStore.userInfo?.id) {
    recommendationBadge.value = ''
    return
  }
  try {
    const res = await api.checkGrayUser(userStore.userInfo.id)
    if (res?.data?.algorithm) {
      recommendationBadge.value = res.data.algorithm === 'deepfm' ? 'DeepFM智能推荐' : 'ItemCF协同过滤'
    }
  } catch (error) {
    console.error('获取推荐算法信息失败:', error)
  }
}

// 刷新推荐（换一换）
async function refreshRecommendations() {
  if (refreshing.value) return
  refreshing.value = true
  try {
    // 并行获取灰度状态和推荐结果
    const [res, grayRes] = await Promise.all([
        api.getRecommendationProducts(userStore.userInfo.id, 40).catch(() => null),
      api.checkGrayUser(userStore.userInfo.id).catch(() => null)
    ])

    const newProducts = res?.data?.products || []
    const isGray = grayRes?.data?.isGray || false

    if (newProducts.length > 0) {
      const shuffled = newProducts.sort(() => Math.random() - 0.5)
      products.value = generateRecReason(shuffled.slice(0, 40), isGray)
      ElMessage.success('已换一批推荐商品')
    }
  } catch (error) {
    console.error('刷新推荐失败:', error)
    ElMessage.error('刷新失败，请稍后重试')
  } finally {
    refreshing.value = false
  }
}

const floors = computed(() => {
  return [
    {
      title: '数码家电',
      subtitle: '潮流新品 · 热门好评',
      items: floorProducts.value.digitalHome
    },
    {
      title: '居家百货',
      subtitle: '实用精选 · 高性价比',
      items: floorProducts.value.household
    }
  ]
})

onMounted(async () => {
  await Promise.all([loadPopularProducts(), loadCategories(), loadFloorProducts(), loadRecommendationInfo(), loadSeckillData()])
})

async function loadSeckillData() {
  seckillLoading.value = true
  try {
    // 获取进行中的秒杀活动
    const [activityRes, productsRes] = await Promise.all([
      api.getSeckillActivity(),
      api.getSeckillProducts()
    ])

    // 设置结束时间
    if (activityRes.data?.hasActiveActivity && activityRes.data?.endTime) {
      seckillEndTime.value = new Date(activityRes.data.endTime).getTime()
    } else {
      seckillEndTime.value = null
    }

    // 获取秒杀商品列表
    let products = productsRes.data?.products || []
    if (products.length === 0) {
      // 如果没有进行中的秒杀，获取即将开始的
      const upcomingRes = await api.getUpcomingSeckillProducts(3)
      products = upcomingRes.data?.products || []
    }

    // 处理商品数据，计算折扣
    if (products.length > 0) {
      seckillProducts.value = products.slice(0, 3).map(item => {
        const originalPrice = Number(item.seckillPrice) * 1.5 // 估算原价
        const discount = Math.round((1 - Number(item.seckillPrice) / originalPrice) * 100)
        return {
          ...item,
          originalPrice: originalPrice.toFixed(2),
          discount: -discount + '%'
        }
      })
    } else {
      // 如果没有任何秒杀数据，清空
      seckillProducts.value = []
      seckillEndTime.value = null
    }
  } catch (error) {
    console.error('加载秒杀数据失败:', error)
    // 出错时清空数据，避免显示旧数据
    seckillProducts.value = []
    seckillEndTime.value = null
  } finally {
    seckillLoading.value = false
  }
}

async function loadCategories() {
  categoryLoading.value = true
  try {
    const res = await api.getCategories()
    categories.value = Array.isArray(res.data) ? res.data : []
  } catch (error) {
    console.error('Failed to load categories:', error)
  } finally {
    categoryLoading.value = false
  }
}

function goProducts(categoryId) {
  router.push({
    path: '/products',
    query: categoryId ? { categoryId } : undefined
  })
}

// 生成推荐理由
// 优先使用后端返回的真实推荐理由，只有在没有时才使用前端生成
function generateRecReason(products, isGray) {
  if (!products || products.length === 0) return products

  // 后端返回的推荐理由前缀
  const backendReasons = ['与你近期浏览的商品相似', '符合你偏好的商品类目', '为你精选推荐', '当前热门推荐']

  // 个性化推荐（灰度组）的推荐理由
  const personalizedReasons = [
    '根据您的喜好推荐',
    '智能匹配您的品味',
    '为您精选的好物',
    '猜您可能感兴趣',
    '热门商品推荐'
  ]

  // 非个性化推荐的推荐理由
  const popularReasons = [
    '本周热卖',
    '好评如潮',
    '限时特惠',
    '新品首发',
    '爆款推荐'
  ]

  return products.map((product, index) => {
    // 如果后端已经返回了真实推荐理由，直接使用
    const backendReason = product.recommendation_reason || product.recReason
    if (backendReason && backendReasons.some(r => backendReason.includes(r.split('，')[0]))) {
      return {
        ...product,
        recReason: backendReason
      }
    }

    // 否则根据是否为个性化推荐生成理由
    const reasons = isGray ? personalizedReasons : popularReasons
    const autoReason = reasons[index % reasons.length]

    return {
      ...product,
      recReason: backendReason || autoReason
    }
  })
}

async function loadPopularProducts() {
  try {
    // 优先使用个性化推荐（并行获取推荐和灰度状态，避免串行等待）
    if (userStore.token && userStore.userInfo?.id) {
      const [personalRes, grayRes] = await Promise.all([
        api.getRecommendationProducts(userStore.userInfo.id, 40),
        api.checkGrayUser(userStore.userInfo.id).catch(() => null)
      ])

      const personalProducts = personalRes?.data?.products || []
      const isGray = grayRes?.data?.isGray || false

      if (personalProducts.length > 0) {
        products.value = generateRecReason(personalProducts, isGray)
        // 记录曝光埋点
        await recordExposureBatch()
        return
      }
    }

    const popularCardsRes = await api.getPopularProductCards(48)
    const popularCards = popularCardsRes?.data?.products || []

    if (Array.isArray(popularCards) && popularCards.length > 0) {
      products.value = generateRecReason(popularCards, false)
      // 记录曝光埋点
      await recordExposureBatch()
      return
    }

    const popularRes = await api.getPopularProducts()
    const popularIds = popularRes?.data?.popularItems || []

    const listRes = await api.getProducts({ page: 1, pageSize: 40 })
    const allProducts = listRes.data?.products || []

    if (popularIds.length > 0) {
      const popularSet = new Set(popularIds.map(id => Number(id)))
      const popularProducts = allProducts.filter(item => popularSet.has(Number(item.id)))
      products.value = generateRecReason(popularProducts.length > 0 ? [...popularProducts, ...allProducts] : allProducts, false)
      // 记录曝光埋点
      await recordExposureBatch()
      return
    }

    products.value = allProducts
  } catch (error) {
    console.error('Failed to load products:', error)
    ElMessage.error('热门商品加载失败')
  }
}

// 曝光埋点：批量记录推荐商品曝光
async function recordExposureBatch() {
  // 防抖：避免频繁调用
  if (exposureDebounceTimer) {
    clearTimeout(exposureDebounceTimer)
  }
  exposureDebounceTimer = setTimeout(async () => {
    exposureDebounceTimer = null
    if (!userStore.token || !userStore.userInfo?.id) return
    if (products.value.length === 0) return

    // 获取待曝光的商品（避免重复曝光）
    const newExposures = products.value
      .filter(p => !exposedProductIds.value.has(p.id))
      .slice(0, 20)  // 最多曝光20个
      .map(p => Number(p.id))

    if (newExposures.length === 0) return

    // 记录已曝光
    newExposures.forEach(id => exposedProductIds.value.add(id))
    // 限制已曝光集合大小（避免内存泄漏）
    if (exposedProductIds.value.size > 200) {
      const arr = Array.from(exposedProductIds.value)
      exposedProductIds.value = new Set(arr.slice(-100))
    }

    try {
      await api.recordExposures({
        userId: userStore.userInfo.id,
        productIds: newExposures,
        recommendType: 'deepfm'
      })
      console.debug('曝光埋点已记录:', newExposures.length, '个商品')
    } catch (error) {
      console.warn('曝光埋点记录失败:', error)
      // 移除失败的曝光记录，下次重试
      newExposures.forEach(id => exposedProductIds.value.delete(id))
    }
  }, 500)  // 延迟500ms，等待页面渲染完成
}

async function loadFloorProducts() {
  try {
    const [electronicsRes, appliancesRes, householdRes] = await Promise.all([
      api.getProducts({ page: 1, pageSize: 8, categoryId: 1 }),
      api.getProducts({ page: 1, pageSize: 8, categoryId: 4 }),
      api.getProducts({ page: 1, pageSize: 8, categoryId: 5 })
    ])

    const electronics = electronicsRes?.data?.products || []
    const appliances = appliancesRes?.data?.products || []
    const household = householdRes?.data?.products || []

    floorProducts.value.digitalHome = uniqueById([...electronics, ...appliances]).slice(0, 6)
    floorProducts.value.household = uniqueById(household).slice(0, 6)
  } catch (error) {
    console.error('Failed to load floor products:', error)
  }
}

function getProductEmoji(name) {
  if (!name) return '📦'
  const nameLower = name.toLowerCase()
  if (nameLower.includes('手机') || nameLower.includes('iphone') || nameLower.includes('华为') || nameLower.includes('mate')) return '📱'
  if (nameLower.includes('电脑') || nameLower.includes('笔记本') || nameLower.includes('macbook') || nameLower.includes('拯救者')) return '💻'
  if (nameLower.includes('耳机') || nameLower.includes('buds') || nameLower.includes('airpods') || nameLower.includes('音响')) return '🎧'
  if (nameLower.includes('平板') || nameLower.includes('ipad') || nameLower.includes('pad')) return '📱'
  if (nameLower.includes('相机') || nameLower.includes('camera')) return '📷'
  if (nameLower.includes('手表') || nameLower.includes('watch')) return '⌚'
  if (nameLower.includes('电视') || nameLower.includes('tv') || nameLower.includes('电视')) return '📺'
  if (nameLower.includes('游戏') || nameLower.includes('switch') || nameLower.includes('ps5')) return '🎮'
  return '📦'
}

function uniqueById(list) {
  const result = []
  const seen = new Set()
  for (const item of list) {
    const id = Number(item?.id)
    if (!id || seen.has(id)) {
      continue
    }
    seen.add(id)
    result.push(item)
  }
  return result
}

function getProductImage(product) {
  return product.imageUrl || `${defaultImage}&id=${product.id || 0}`
}

async function handleViewProduct(product) {
  if (!product?.id) {
    return
  }
  await recordBehaviorSafe(product.id, 'click')
  router.push(`/product/${product.id}`)
}

async function recordBehaviorSafe(productId, behaviorType) {
  if (!userStore.token || !userStore.userInfo?.id || !productId) {
    return
  }
  try {
    await api.recordBehavior({
      userId: userStore.userInfo.id,
      productId,
      behaviorType
    })
  } catch (error) {
    console.warn('行为上报失败:', behaviorType, productId, error)
  }
}

async function handleAddToCart(product) {
  if (!userStore.token) {
    ElMessage.warning('请先登录')
    return
  }
  try {
    await api.addToCart({
      userId: userStore.userInfo.id,
      productId: product.id,
      productName: product.name,
      productImage: product.imageUrl,
      quantity: 1
    })
    await recordBehaviorSafe(product.id, 'cart')
    ElMessage.success('添加成功')
    cartStore.addCartItem({ productId: product.id, quantity: 1 })
    // 刷新购物车数量（从 Redis 获取最新值）
    cartStore.fetchCartCount(userStore.userInfo.id)
  } catch (error) {
    const msg = api.getErrorMessage(error, '添加失败')
    ElMessage.error(msg)
  }
}
</script>

<style scoped>
.home {
  padding: 6px 0 24px;
}

.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

.top-board {
  display: grid;
  grid-template-columns: 220px 1fr 260px;
  grid-template-rows: 400px;
  gap: 16px;
  margin-bottom: 26px;
}

.category-panel,
.quick-panel,
.hero-panel {
  background: #fffef9;
  border: 3px solid #101010;
  border-radius: 16px;
  box-shadow: 6px 6px 0 #101010;
  height: 100%;
  box-sizing: border-box;
}

.category-panel {
  padding: 10px 12px;
}

.category-panel h3 {
  font-size: var(--neo-fs-lg);
  margin-bottom: 6px;
  color: #0f172a;
}

.category-panel ul {
  list-style: none;
}

/* 分类骨架屏 */
.category-skeleton {
  list-style: none;
}

.category-skeleton .skeleton-item {
  padding: 5px 8px;
}

.category-skeleton .skeleton-line {
  height: 18px;
  background: #e0e0e0;
  border-radius: 6px;
  animation: pulse 1.5s ease-in-out infinite;
}

.category-skeleton .skeleton-item:nth-child(1) .skeleton-line { width: 60%; animation-delay: 0s; }
.category-skeleton .skeleton-item:nth-child(2) .skeleton-line { width: 80%; animation-delay: 0.05s; }
.category-skeleton .skeleton-item:nth-child(3) .skeleton-line { width: 70%; animation-delay: 0.1s; }
.category-skeleton .skeleton-item:nth-child(4) .skeleton-line { width: 75%; animation-delay: 0.15s; }
.category-skeleton .skeleton-item:nth-child(5) .skeleton-line { width: 65%; animation-delay: 0.2s; }
.category-skeleton .skeleton-item:nth-child(6) .skeleton-line { width: 80%; animation-delay: 0.25s; }
.category-skeleton .skeleton-item:nth-child(7) .skeleton-line { width: 70%; animation-delay: 0.3s; }
.category-skeleton .skeleton-item:nth-child(8) .skeleton-line { width: 60%; animation-delay: 0.35s; }

.channel-skeleton {
  height: 160px;
  box-sizing: border-box;
}

.channel-skeleton .channel-item {
  padding: 10px 8px;
  text-align: center;
  border: 2px solid transparent;
  cursor: default;
}

.channel-skeleton .skeleton-icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 8px;
  border-radius: 12px;
  background: #e0e0e0;
  animation: pulse 1.5s ease-in-out infinite;
}

.channel-skeleton .skeleton-title {
  height: 18px;
  width: 60%;
  margin: 0 auto 4px;
  background: #e0e0e0;
  border-radius: 4px;
  animation: pulse 1.5s ease-in-out infinite;
  animation-delay: 0.1s;
}

.channel-skeleton .skeleton-desc {
  height: 14px;
  width: 40%;
  margin: 0 auto;
  background: #e0e0e0;
  border-radius: 4px;
  animation: pulse 1.5s ease-in-out infinite;
  animation-delay: 0.2s;
}

.category-panel li {
  padding: 5px 8px;
  border-radius: 8px;
  color: #101010;
  border: 2px solid transparent;
  cursor: pointer;
  transition: all 0.2s ease;
}

.category-panel li:hover {
  background: #8be9fd;
  color: #101010;
  border-color: #101010;
}

.hero-panel {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}

.hero-banner {
  border-radius: 16px;
  padding: 28px;
  color: white;
  cursor: pointer;
  background: linear-gradient(125deg, #3b82f6 0%, #5b4dff 55%, #a855f7 100%);
  border: 3px solid #101010;
  box-shadow: 6px 6px 0 #101010;
  position: relative;
  overflow: hidden;
  height: 100%;
  box-sizing: border-box;
}

/* 骨架屏样式 */
.hero-loading {
  background: linear-gradient(125deg, #e0e0e0 0%, #f5f5f5 50%, #e0e0e0 100%);
  border: 3px solid #ccc;
  box-shadow: none;
  height: 100%;
  box-sizing: border-box;
}

.loading-skeleton {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px;
}

.skeleton-tag {
  width: 120px;
  height: 28px;
  background: #ccc;
  border-radius: 6px;
  animation: pulse 1.5s ease-in-out infinite;
}

.skeleton-title {
  width: 300px;
  height: 40px;
  background: #ccc;
  border-radius: 6px;
  animation: pulse 1.5s ease-in-out infinite;
  animation-delay: 0.1s;
}

.skeleton-subtitle {
  width: 250px;
  height: 24px;
  background: #ccc;
  border-radius: 6px;
  animation: pulse 1.5s ease-in-out infinite;
  animation-delay: 0.2s;
}

.skeleton-stats {
  display: flex;
  gap: 20px;
  margin-top: 10px;
}

.skeleton-stat {
  width: 80px;
  height: 50px;
  background: #ccc;
  border-radius: 6px;
  animation: pulse 1.5s ease-in-out infinite;
  animation-delay: 0.3s;
}

@keyframes pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.5; }
}

/* 占位状态 */
.hero-placeholder {
  background: linear-gradient(125deg, #f0f0f0 0%, #e0e0e0 50%, #f0f0f0 100%);
  border: 3px solid #ddd;
  box-shadow: 4px 4px 0 #ccc;
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  box-sizing: border-box;
}

.placeholder-content {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12px;
  color: #999;
}

.placeholder-icon {
  font-size: 48px;
}

.placeholder-text {
  font-size: 18px;
  font-weight: 500;
}

.hero-bg-elements {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.circle {
  position: absolute;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.08);
}

.circle-1 {
  width: 200px;
  height: 200px;
  top: -60px;
  right: -40px;
}

.circle-2 {
  width: 120px;
  height: 120px;
  bottom: -30px;
  left: 30%;
}

.circle-3 {
  width: 80px;
  height: 80px;
  top: 40%;
  right: 20%;
}

.sparkle {
  position: absolute;
  font-size: 18px;
  color: rgba(255, 255, 255, 0.6);
  animation: sparkle 2s ease-in-out infinite;
}

.sparkle-1 {
  top: 20px;
  left: 30%;
  animation-delay: 0s;
}

.sparkle-2 {
  top: 60px;
  right: 15%;
  animation-delay: 0.5s;
}

.sparkle-3 {
  bottom: 30px;
  left: 15%;
  animation-delay: 1s;
}

@keyframes sparkle {
  0%, 100% {
    opacity: 0.3;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1.2);
  }
}

.hero-content {
  position: relative;
  z-index: 1;
}

.hero-tags {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}

.tag {
  display: inline-block;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.35);
  padding: 3px 10px;
  border-radius: 999px;
}

.tag-main {
  background: rgba(255, 255, 255, 0.25);
  font-weight: 600;
}

.tag-sub {
  background: #ff6b35;
  border-color: #ff6b35;
  animation: pulse 1.5s ease-in-out infinite;
}

@keyframes pulse {
  0%, 100% { transform: scale(1); }
  50% { transform: scale(1.05); }
}

.hero-content h1 {
  font-size: var(--neo-fs-xxl);
  margin-bottom: 8px;
  letter-spacing: -0.4px;
}

.hero-subtitle {
  opacity: 0.92;
  margin-bottom: 16px;
  font-size: 16px;
}

.hero-subtitle .highlight {
  color: #fbbf24;
  font-weight: 700;
  font-size: 20px;
}

.hero-stats {
  display: flex;
  align-items: center;
  gap: 16px;
  margin-bottom: 18px;
  padding: 14px 18px;
  background: rgba(0, 0, 0, 0.2);
  border-radius: 12px;
  width: fit-content;
}

.stat-item {
  display: flex;
  flex-direction: column;
  align-items: center;
}

.stat-number {
  font-size: 18px;
  font-weight: 700;
  color: #fbbf24;
}

.stat-label {
  font-size: 11px;
  opacity: 0.8;
}

.stat-divider {
  width: 1px;
  height: 32px;
  background: rgba(255, 255, 255, 0.3);
}

.hero-actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.hero-btn {
  font-size: 16px;
  padding: 12px 28px;
  border-radius: 12px;
}

.btn-arrow {
  margin-left: 6px;
  transition: transform 0.2s;
}

.hero-btn:hover .btn-arrow {
  transform: translateX(4px);
}

.hero-hint {
  font-size: 12px;
  opacity: 0.75;
}

@keyframes float {
  0%, 100% {
    transform: translateY(0) rotate(0deg);
  }
  50% {
    transform: translateY(-8px) rotate(5deg);
  }
}

.hero-right {
  position: absolute;
  right: 20px;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  flex-direction: column;
  gap: 12px;
  z-index: 2;
}

.hero-product-showcase {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.showcase-item {
  display: flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.15);
  border-radius: 8px;
  padding: 6px 10px;
  backdrop-filter: blur(4px);
}

.showcase-discount {
  background: #ff6b35;
  color: white;
  font-size: 11px;
  font-weight: 700;
  padding: 2px 6px;
  border-radius: 4px;
}

.showcase-img {
  font-size: 20px;
}

.hero-countdown {
  background: rgba(0, 0, 0, 0.3);
  border-radius: 10px;
  padding: 10px 14px;
  text-align: center;
}

.countdown-label {
  display: block;
  font-size: 11px;
  opacity: 0.8;
  margin-bottom: 4px;
}

.countdown-time {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 2px;
}

.time-block {
  background: white;
  color: #333;
  font-size: 14px;
  font-weight: 700;
  padding: 2px 4px;
  border-radius: 4px;
  min-width: 20px;
}

.time-sep {
  color: white;
  font-weight: 700;
}

.hero-content .tag {
  display: inline-block;
  font-size: 12px;
  background: rgba(255, 255, 255, 0.2);
  border: 1px solid rgba(255, 255, 255, 0.35);
  padding: 3px 10px;
  border-radius: 999px;
  margin-bottom: 10px;
}

.hero-content h1 {
  font-size: var(--neo-fs-xxl);
  margin-bottom: 8px;
  letter-spacing: -0.4px;
}

.hero-content p {
  opacity: 0.92;
  margin-bottom: 12px;
}

.promo-row {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

.promo-card {
  border-radius: 12px;
  padding: 14px;
  border: 3px solid #101010;
  box-shadow: 4px 4px 0 #101010;
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  transition: transform 0.2s;
}

.promo-card:hover {
  transform: translateY(-2px);
}

.promo-icon {
  font-size: 28px;
  flex-shrink: 0;
}

.promo-text {
  flex: 1;
  min-width: 0;
}

.promo-card h4 {
  font-size: var(--neo-fs-md);
  margin-bottom: 2px;
}

.promo-card p {
  font-size: var(--neo-fs-sm);
  color: #475569;
  white-space: nowrap;
}

.promo-arrow {
  font-size: 18px;
  color: #94a3b8;
  transition: transform 0.2s;
}

.promo-card:hover .promo-arrow {
  transform: translateX(4px);
  color: #3b82f6;
}

.quick-panel {
  padding: 14px;
}

.welcome {
  font-size: var(--neo-fs-md);
  color: #334155;
  margin-bottom: 8px;
}

.quick-actions {
  display: grid;
  gap: 8px;
  margin: 14px 0;
}

.quick-actions :deep(.el-button) {
  justify-content: flex-start;
}

.notice-box h4 {
  font-size: var(--neo-fs-md);
  color: #0f172a;
  margin-bottom: 6px;
}

.notice-box p {
  font-size: var(--neo-fs-sm);
  color: #64748b;
  line-height: 1.7;
}

.products {
  margin-bottom: 24px;
}

.channel-section {
  display: grid;
  grid-template-columns: 1fr;
  gap: 14px;
  margin-bottom: 22px;
}

.channel-grid {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
  border-radius: 16px;
  border: 3px solid #101010;
  background: #fffef9;
  box-shadow: 6px 6px 0 #101010;
  height: 160px;
  box-sizing: border-box;
}

.channel-item {
  border-radius: 12px;
  padding: 10px 8px;
  text-align: center;
  border: 2px solid #101010;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.channel-item:hover {
  transform: translate(-2px, -2px);
}

.channel-item .icon {
  width: 44px;
  height: 44px;
  margin: 0 auto 8px;
  border-radius: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
}

.channel-item .title {
  font-size: var(--neo-fs-md);
  color: #0f172a;
  margin-bottom: 4px;
}

.channel-item .desc {
  font-size: var(--neo-fs-sm);
  color: #64748b;
}

.activity-strip {
  padding: 14px;
  border-radius: 16px;
  border: 3px solid #101010;
  background: #fffef9;
  box-shadow: 6px 6px 0 #101010;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.activity-item {
  border-radius: 12px;
  padding: 10px;
  background: #dbeafe;
  border: 2px solid #101010;
}

.activity-item h4 {
  font-size: var(--neo-fs-md);
  margin-bottom: 4px;
  color: #0f172a;
}

.activity-item p {
  font-size: var(--neo-fs-sm);
  color: #64748b;
}

.section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.section-header h2 {
  margin: 0;
  font-size: var(--neo-fs-xl);
  font-weight: 900;
  letter-spacing: -0.4px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.section-header span {
  color: #64748b;
  font-size: 14px;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.product-card {
  cursor: pointer;
  position: relative;
}

.product-card :deep(.el-image) {
  border-radius: 12px;
}

.product-card .el-image {
  width: 100%;
  height: 260px;
}

/* 商品卡片标签 (移至上方统一管理) */

/* 推荐标签样式 */
.rec-badge {
  display: inline-block;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 8px;
  background: linear-gradient(135deg, #667eea, #764ba2);
  color: #fff;
  border: 2px solid #101010;
  box-shadow: 2px 2px 0 #101010;
}

.algo-badge {
  display: inline-block;
  padding: 4px 10px;
  font-size: 12px;
  font-weight: 600;
  border-radius: 8px;
  background: linear-gradient(135deg, #f59e0b, #d97706);
  color: #fff;
  border: 2px solid #101010;
  box-shadow: 2px 2px 0 #101010;
}

/* Neobrutalism 换一换按钮 */
.neo-btn-refresh {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #101010;
  background: #fbbf24;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-refresh:hover:not(:disabled) {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
}

.neo-btn-refresh:active:not(:disabled) {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 #101010;
}

.neo-btn-refresh:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}

.refresh-icon {
  font-size: 16px;
  display: inline-block;
}

.refresh-icon.spinning {
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}

/* Neobrutalism 查看更多按钮 */
.neo-btn-outline {
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
  color: #3b82f6;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-outline:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #f0f9ff;
}

.neo-btn-outline:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 #101010;
}

/* 商品卡片标签 */
.product-badge {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 2;
  font-size: 12px;
  font-weight: 700;
  padding: 5px 10px;
  border-radius: 8px;
  color: #fff;
  border: 2px solid #101010;
  box-shadow: 2px 2px 0 #101010;
  background: linear-gradient(135deg, #ef4444, #dc2626);
}

.product-badge.rec-badge {
  background: linear-gradient(135deg, #8b5cf6, #7c3aed);
}

/* 推荐理由 */
.rec-reason {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #7c3aed;
  margin-bottom: 8px;
  padding: 6px 10px;
  background: #f5f3ff;
  border-radius: 8px;
  border: 2px solid #101010;
}

.rec-icon {
  font-size: 14px;
}

/* Neobrutalism 加入购物车按钮 */
.neo-btn-add {
  width: 100%;
  padding: 10px 16px;
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

.neo-btn-add:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #059669;
}

.neo-btn-add:active {
  transform: translate(2px, 2px);
  box-shadow: 1px 1px 0 #101010;
}

.products :deep(.el-card) {
  transition: transform 0.22s ease, box-shadow 0.22s ease;
}

.products :deep(.el-card:hover) {
  transform: translate(-2px, -2px);
  box-shadow: 8px 8px 0 #101010 !important;
}

.product-info {
  padding: 14px 2px 2px;
}

.product-info h3 {
  font-size: var(--neo-fs-lg);
  margin-bottom: 10px;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.product-info .description {
  font-size: var(--neo-fs-sm);
  color: #64748b;
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.product-info .price {
  font-size: var(--neo-fs-xl);
  color: #1d4ed8;
  font-weight: 700;
  margin-bottom: 10px;
}

.rec-reason {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: #8b5cf6;
  margin-bottom: 8px;
  padding: 4px 8px;
  background: #f5f3ff;
  border-radius: 4px;
}

.floor-zone {
  display: grid;
  gap: 18px;
}

.floor-card {
  padding: 16px;
  border-radius: 16px;
  border: 3px solid #101010;
  background: #fffef9;
  box-shadow: 6px 6px 0 #101010;
}

.mini-card {
  border-radius: 12px;
  padding: 10px;
  background: #fff;
  border: 2px solid #101010;
  cursor: pointer;
  transition: transform 0.15s ease;
}

.mini-card:hover {
  transform: translate(-2px, -2px);
}

.mini-card .el-image {
  width: 100%;
  height: 130px;
  border-radius: 10px;
}

.mini-card .name {
  font-size: var(--neo-fs-md);
  margin: 8px 0 6px;
  color: #0f172a;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.mini-card .price {
  color: #0a84ff;
  font-weight: 700;
}

@media (max-width: 1100px) {
  .top-board {
    grid-template-columns: 1fr;
    grid-template-rows: auto;
  }

  .channel-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .activity-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .promo-row {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .activity-strip {
    grid-template-columns: 1fr;
  }
}
</style>
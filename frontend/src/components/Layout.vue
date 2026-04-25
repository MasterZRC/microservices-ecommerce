<template>
  <div class="layout">
    <header class="header">
      <div class="header-main">
        <div class="container main-row">
          <div class="logo" @click="$router.push('/')">电商平台</div>

          <div class="search-zone">
            <div class="search-box">
              <el-input
                v-model="headerKeyword"
                placeholder="输入商品名称 / 品牌 / 型号"
                clearable
                @keyup.enter="handleHeaderSearch"
              />
              <button class="neo-btn-header-search" @click="handleHeaderSearch">搜索</button>
            </div>
            <div class="hot-keywords">
              <span
                v-for="(item, idx) in hotKeywords"
                :key="item"
                :class="{ active: idx < 2 }"
                @click="goKeyword(item)"
              >
                {{ item }}
              </span>
            </div>
          </div>

          <div class="header-right">
            <el-badge :value="cartStore.cartCount" class="cart-badge">
              <button class="neo-btn-cart-icon" @click="$router.push('/cart')">🛒</button>
            </el-badge>
            <el-dropdown v-if="userStore.token" @command="handleCommand">
              <span class="user-dropdown">
                <el-avatar :size="32">{{ userStore.userInfo.username?.[0] || 'U' }}</el-avatar>
                <span>{{ userStore.userInfo.username }}</span>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="orders">我的订单</el-dropdown-item>
                  <el-dropdown-item command="logout">退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <button v-else class="neo-btn-login-header" @click="$router.push('/login')">登录</button>
          </div>
        </div>
      </div>

      <div class="header-nav">
        <div class="container nav-row">
          <nav class="nav">
            <el-menu mode="horizontal" :ellipsis="false" router>
              <el-menu-item index="/home">首页</el-menu-item>
              <el-menu-item index="/products">商品列表</el-menu-item>
              <el-menu-item index="/seckill">秒杀活动</el-menu-item>
              <el-menu-item index="/ab-dashboard">算法仪表盘</el-menu-item>
            </el-menu>
          </nav>
          <div class="nav-quick">
            <span @click="$router.push('/products')">百亿补贴</span>
            <span @click="$router.push('/products')">品牌闪购</span>
            <span @click="$router.push('/seckill')">今日秒杀</span>
          </div>
        </div>
      </div>
    </header>
    <main class="main">
      <router-view />
    </main>
    <footer class="footer">
      <div class="container">
        <p>&copy; 2026 电商平台 - 微服务架构演示</p>
      </div>
    </footer>

    <!-- AI 购物助手浮动入口（仅登录后显示）-->
    <AgentChat v-if="userStore.token" />
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { Search, ShoppingCart } from '@element-plus/icons-vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../store/user'
import { useCartStore } from '../store/cart'
import AgentChat from './AgentChat.vue'

const router = useRouter()
const userStore = useUserStore()
const cartStore = useCartStore()

onMounted(() => {
  // 页面加载时获取购物车数量
  if (userStore.userInfo?.id) {
    cartStore.fetchCartCount(userStore.userInfo.id)
  }
})
const headerKeyword = ref('')
const hotKeywords = ['手机', '笔记本', '家电', '美妆', '运动鞋', '零食']

function goKeyword(keyword) {
  router.push({ path: '/products', query: { keyword } })
}

function handleHeaderSearch() {
  router.push({
    path: '/products',
    query: headerKeyword.value ? { keyword: headerKeyword.value } : undefined
  })
}

function handleCommand(command) {
  if (command === 'logout') {
    userStore.logout()
    router.push('/login')
  } else if (command === 'orders') {
    router.push('/orders')
  }
}
</script>

<style scoped>
.layout {
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

.header {
  background: #ffd84d;
  border-bottom: 3px solid #101010;
  box-shadow: 0 5px 0 #101010;
  position: sticky;
  top: 0;
  z-index: 100;
}

.container {
  max-width: 1240px;
  margin: 0 auto;
  padding: 0 24px;
}

.header-main {
  border-bottom: 3px solid #101010;
}

.main-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 84px;
}

.logo {
  font-size: var(--neo-fs-xl);
  font-weight: 900;
  letter-spacing: -0.5px;
  color: #101010;
  cursor: pointer;
}

.search-zone {
  flex: 1;
  margin: 0 34px;
}

.search-box {
  display: grid;
  grid-template-columns: 1fr 96px;
  gap: 10px;
}

.search-box :deep(.el-input__wrapper) {
  background: #fff !important;
}

.hot-keywords {
  margin-top: 8px;
  display: flex;
  gap: 14px;
  font-size: var(--neo-fs-xs);
  color: #64748b;
}

.hot-keywords span {
  cursor: pointer;
}

.hot-keywords span.active {
  color: #d92f2f;
  font-weight: 700;
}

.header-nav {
  background: #fffef7;
}

.nav-row {
  height: 50px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.nav {
  flex: 1;
}

.nav :deep(.el-menu) {
  border: none;
  background: transparent;
}

.nav :deep(.el-menu-item) {
  border-radius: 10px;
  margin: 0 4px;
  transition: all 0.2s ease;
  border: 2px solid transparent;
}

.nav :deep(.el-menu-item.is-active) {
  color: #101010;
  background: #76e4f7;
  border-color: #101010;
}

.nav-quick {
  display: flex;
  gap: 16px;
  font-size: var(--neo-fs-s);
  color: #64748b;
}

.nav-quick span {
  cursor: pointer;
}

.nav-quick span:hover {
  color: #101010;
  text-decoration: underline;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 16px;
}

.cart-badge :deep(.el-badge__content) {
  top: 8px;
  right: 8px;
}

/* Neobrutalism 搜索按钮 */
.neo-btn-header-search {
  padding: 10px 18px;
  font-size: 14px;
  font-weight: 700;
  color: #101010;
  background: #fbbf24;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-header-search:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #f59e0b;
}

.neo-btn-header-search:active {
  transform: translate(1px, 1px);
  box-shadow: 2px 2px 0 #101010;
}

/* Neobrutalism 购物车图标按钮 */
.neo-btn-cart-icon {
  width: 40px;
  height: 40px;
  font-size: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fff;
  border: 2px solid #101010;
  border-radius: 10px;
  cursor: pointer;
  box-shadow: 3px 3px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-cart-icon:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #f0f9ff;
}

.neo-btn-cart-icon:active {
  transform: translate(1px, 1px);
  box-shadow: 2px 2px 0 #101010;
}

/* Neobrutalism 登录按钮 */
.neo-btn-login-header {
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

.neo-btn-login-header:hover {
  transform: translate(-1px, -1px);
  box-shadow: 4px 4px 0 #101010;
  background: #2563eb;
}

.neo-btn-login-header:active {
  transform: translate(1px, 1px);
  box-shadow: 2px 2px 0 #101010;
}

.user-dropdown {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  color: #101010;
  font-weight: 700;
  font-size: var(--neo-fs-sm);
}

.main {
  flex: 1;
  padding: 28px 0;
}

.footer {
  background: #fffef7;
  border-top: 3px solid #101010;
  padding: 18px 0;
  text-align: center;
  color: #1f2937;
  font-weight: 600;
  font-size: var(--neo-fs-sm);
}

@media (max-width: 1100px) {
  .main-row {
    height: auto;
    padding: 12px 0;
    flex-direction: column;
    align-items: stretch;
    gap: 10px;
  }

  .search-zone {
    margin: 0;
  }

  .header-right {
    justify-content: flex-end;
  }

  .nav-quick {
    display: none;
  }
}
</style>
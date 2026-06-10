<template>
  <div class="admin-layout">
    <!-- Sidebar -->
    <aside class="sidebar">
      <div class="sidebar-header">
        <div class="logo">
          <span class="logo-icon">🛍️</span>
          <span class="logo-text">商家管理中心</span>
        </div>
      </div>
      <el-menu
        :default-active="activeMenu"
        class="sidebar-menu"
        background-color="var(--admin-sidebar-bg)"
        text-color="var(--neo-text)"
        active-text-color="var(--neo-primary)"
        :router="true"
      >
        <el-menu-item index="/admin/dashboard">
          <el-icon><Odometer /></el-icon>
          <span>首页概览</span>
        </el-menu-item>
        <el-menu-item index="/admin/products">
          <el-icon><Goods /></el-icon>
          <span>商品管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/orders">
          <el-icon><List /></el-icon>
          <span>订单管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/seckill">
          <el-icon><Lightning /></el-icon>
          <span>秒杀管理</span>
        </el-menu-item>
        <el-menu-item index="/admin/seckill/demo">
          <el-icon><DataLine /></el-icon>
          <span>秒杀压测</span>
        </el-menu-item>
        <el-menu-item index="/admin/ai-insights">
          <el-icon><MagicStick /></el-icon>
          <span>AI 经营助手</span>
        </el-menu-item>
      </el-menu>
    </aside>

    <!-- Main Content -->
    <div class="main-wrapper">
      <!-- Header -->
      <header class="header">
        <div class="header-left">
          <h2 class="page-title">{{ currentTitle }}</h2>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="admin-user">
              <el-avatar :size="32" style="background: var(--neo-primary); border: 2px solid var(--neo-border);">
                {{ adminStore.userInfo.username?.[0] || 'A' }}
              </el-avatar>
              <span class="username">{{ adminStore.userInfo.username || '管理员' }}</span>
              <span class="role-tag" v-if="adminStore.userInfo.role">
                {{ adminStore.userInfo.role }}
              </span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">
                  <el-icon><SwitchButton /></el-icon>
                  退出登录
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </header>

      <!-- Content -->
      <main class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Odometer, Goods, List, Lightning, DataLine, MagicStick, SwitchButton } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { useAdminStore } from '../store/admin'
import api from '../api'

const route = useRoute()
const router = useRouter()
const adminStore = useAdminStore()

const activeMenu = computed(() => route.path)

const currentTitle = computed(() => {
  return route.meta.title || '商家管理中心'
})

async function handleCommand(command) {
  if (command === 'logout') {
    try {
      await api.post('/admin/auth/logout')
    } catch (e) {
      // ignore logout API error
    }
    adminStore.logout()
    ElMessage.success('已退出登录')
    router.push('/admin/login')
  }
}
</script>

<style scoped>
.admin-layout {
  display: flex;
  min-height: 100vh;
}

/* Sidebar - Neo Brutalism Warm White Style */
.sidebar {
  width: var(--admin-sidebar-width);
  background: var(--neo-surface);
  border-right: 3px solid var(--neo-border);
  display: flex;
  flex-direction: column;
  position: fixed;
  top: 0;
  left: 0;
  bottom: 0;
  z-index: 100;
  box-shadow: 4px 0 0 var(--neo-border);
}

.sidebar-header {
  height: var(--admin-header-height);
  display: flex;
  align-items: center;
  padding: 0 20px;
  border-bottom: 3px solid var(--neo-border);
  background: #ffd84d;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-icon {
  font-size: 26px;
}

.logo-text {
  font-size: 15px;
  font-weight: 900;
  color: var(--neo-text);
  letter-spacing: 0.5px;
}

/* Sidebar Menu */
.sidebar-menu {
  flex: 1;
  border-right: none !important;
  padding: 12px 10px;
  background: transparent !important;
}

.sidebar-menu .el-menu-item {
  height: 50px;
  line-height: 50px;
  font-size: 14px;
  font-weight: 700;
  margin: 4px 0;
  border-radius: 12px;
  border: 2px solid transparent;
  transition: all 0.15s ease;
}

.sidebar-menu .el-menu-item.is-active {
  background: #dbeafe !important;
  border-color: var(--neo-border);
  color: var(--neo-primary) !important;
}

.sidebar-menu .el-menu-item:hover {
  background: #f0f9ff !important;
  border-color: var(--neo-border);
  color: var(--neo-primary) !important;
}

.sidebar-menu .el-icon {
  margin-right: 10px;
  font-size: 18px;
}

/* Main Wrapper */
.main-wrapper {
  flex: 1;
  margin-left: var(--admin-sidebar-width);
  display: flex;
  flex-direction: column;
  min-height: 100vh;
}

/* Header - Neo Brutalism Yellow */
.header {
  height: var(--admin-header-height);
  background: #ffd84d;
  border-bottom: 3px solid var(--neo-border);
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  position: sticky;
  top: 0;
  z-index: 50;
  box-shadow: 0 4px 0 var(--neo-border);
}

.header-left {
  display: flex;
  align-items: center;
}

.page-title {
  font-size: 18px;
  font-weight: 900;
  color: var(--neo-text);
}

.header-right {
  display: flex;
  align-items: center;
}

.admin-user {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: 12px;
  border: 2px solid transparent;
  transition: all 0.15s ease;
  background: var(--neo-surface);
}

.admin-user:hover {
  border-color: var(--neo-border);
  box-shadow: 3px 3px 0 var(--neo-border);
}

.username {
  font-size: 14px;
  font-weight: 700;
  color: var(--neo-text);
}

.role-tag {
  font-size: 11px;
  padding: 2px 8px;
  background: var(--neo-primary);
  color: #fff;
  border-radius: 999px;
  font-weight: 700;
  border: 2px solid var(--neo-border);
}

/* Content */
.content {
  flex: 1;
  padding: 24px;
}
</style>

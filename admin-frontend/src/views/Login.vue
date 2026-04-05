<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-header">
        <div class="logo-icon">🛍️</div>
        <h1 class="neo-h2">商家管理中心</h1>
        <p class="login-subtitle">请登录以继续管理您的店铺</p>
      </div>

      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        class="login-form"
        @submit.prevent="handleLogin"
      >
        <el-form-item prop="username">
          <el-input
            v-model="form.username"
            placeholder="请输入用户名"
            size="large"
            :prefix-icon="User"
          />
        </el-form-item>

        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            size="large"
            :prefix-icon="Lock"
            show-password
            @keyup.enter="handleLogin"
          />
        </el-form-item>

        <el-form-item>
          <el-button
            type="primary"
            size="large"
            :loading="loading"
            class="login-btn"
            @click="handleLogin"
          >
            {{ loading ? '登录中...' : '登 录' }}
          </el-button>
        </el-form-item>
      </el-form>

      <div class="login-tip">
        <p>默认账号: <strong>admin</strong> / <strong>admin123</strong></p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useAdminStore } from '../store/admin'
import api from '../api'

const router = useRouter()
const adminStore = useAdminStore()

const formRef = ref()
const loading = ref(false)

const form = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  if (loading.value) return

  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await api.post('/admin/auth/login', {
      username: form.username,
      password: form.password
    })

    adminStore.setToken(res.data.token)
    adminStore.setUserInfo({
      adminId: res.data.adminId,
      username: res.data.username,
      nickname: res.data.nickname,
      role: res.data.role,
      avatar: res.data.avatar
    })

    ElMessage.success('登录成功')
    router.push('/admin/dashboard')
  } catch (error) {
    ElMessage.error(error.message || '登录失败，请检查用户名和密码')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--neo-bg);
  background-image: repeating-linear-gradient(
      -45deg,
      #f7f3e9,
      #f7f3e9 20px,
      #f2eddf 20px,
      #f2eddf 40px
    );
}

.login-card {
  width: 420px;
  background: var(--neo-surface);
  border: 3px solid var(--neo-border);
  border-radius: var(--neo-radius);
  box-shadow: var(--neo-shadow);
  padding: 40px;
}

.login-header {
  text-align: center;
  margin-bottom: 32px;
}

.logo-icon {
  font-size: 52px;
  margin-bottom: 14px;
}

.login-header .neo-h2 {
  font-size: 26px;
  margin-bottom: 8px;
}

.login-subtitle {
  font-size: var(--neo-fs-sm);
  color: var(--neo-text-soft);
}

.login-form {
  margin-top: 8px;
}

.login-form :deep(.el-input__wrapper) {
  padding: 12px 16px;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 18px;
}

.login-btn {
  width: 100%;
  height: 48px;
  font-size: 16px;
  font-weight: 800;
  border-radius: 12px !important;
  background: var(--neo-primary) !important;
}

.login-tip {
  text-align: center;
  margin-top: 20px;
  padding-top: 18px;
  border-top: 2px solid var(--neo-border);
  font-size: var(--neo-fs-s);
  color: var(--neo-text-soft);
}
</style>

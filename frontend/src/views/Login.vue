<template>
  <div class="login-page">
    <div class="login-container">
      <el-card class="login-card">
        <template #header>
          <h2>用户登录</h2>
        </template>
        <el-form :model="form" :rules="rules" ref="formRef" label-width="80px">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
          </el-form-item>
          <el-form-item>
            <button class="neo-btn-login" @click="handleLogin" :disabled="loading">
              {{ loading ? '登录中...' : '登录' }}
            </button>
          </el-form-item>
          <el-form-item>
            <el-link type="primary" @click="$router.push('/register')">还没有账号？立即注册</el-link>
          </el-form-item>
        </el-form>
      </el-card>
    </div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api from '../api'
import { useUserStore } from '../store/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref(null)
const loading = ref(false)
const form = ref({ username: '', password: '' })
const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

async function handleLogin() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  
  loading.value = true
  try {
    const res = await api.login(form.value)
    const token = res.data?.token
    const user = res.data?.user || {
      id: res.data?.userId,
      username: res.data?.username,
      nickname: res.data?.nickname,
      avatar: res.data?.avatar
    }
    userStore.setToken(token)
    userStore.setUserInfo(user)
    ElMessage.success('登录成功')
    router.push('/home')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || '登录失败')
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
  background: #f7f3e9;
}
.login-container {
  width: 420px;
}
.login-card {
  border-radius: 16px;
}
.login-card h2 {
  text-align: center;
  font-size: var(--neo-fs-xxl);
  font-weight: 900;
  letter-spacing: -0.4px;
  margin: 0;
}

.login-page :deep(.el-form-item__label) {
  font-weight: 800;
  color: #111827;
  font-size: var(--neo-fs-sm);
}

/* Neobrutalism 登录按钮 */
.neo-btn-login {
  width: 100%;
  padding: 14px 20px;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  background: #3b82f6;
  border: 2px solid #101010;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-login:hover:not(:disabled) {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #2563eb;
}

.neo-btn-login:active:not(:disabled) {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}

.neo-btn-login:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
</style>
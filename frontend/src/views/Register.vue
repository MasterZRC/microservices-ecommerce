<template>
  <div class="register-page">
    <div class="register-container">
      <el-card class="register-card">
        <template #header>
          <h2>用户注册</h2>
        </template>
        <el-form :model="form" :rules="rules" ref="formRef" label-width="88px">
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="请输入密码" show-password />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" show-password />
          </el-form-item>
          <el-form-item label="昵称" prop="nickname">
            <el-input v-model="form.nickname" placeholder="请输入昵称（可选）" />
          </el-form-item>
          <el-form-item label="邮箱" prop="email">
            <el-input v-model="form.email" placeholder="请输入邮箱（可选）" />
          </el-form-item>
          <el-form-item label="手机号" prop="phone">
            <el-input v-model="form.phone" placeholder="请输入手机号（可选）" />
          </el-form-item>
          <el-form-item>
            <button class="neo-btn-register" @click="handleRegister" :disabled="loading">
              {{ loading ? '注册中...' : '注册并登录' }}
            </button>
          </el-form-item>
          <el-form-item>
            <el-link type="primary" @click="$router.push('/login')">已有账号？去登录</el-link>
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
const form = ref({
  username: '',
  password: '',
  confirmPassword: '',
  nickname: '',
  email: '',
  phone: ''
})

const validateConfirmPassword = (_rule, value, callback) => {
  if (!value) {
    callback(new Error('请再次输入密码'))
    return
  }
  if (value !== form.value.password) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
  confirmPassword: [{ validator: validateConfirmPassword, trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function handleRegister() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const payload = {
      username: form.value.username,
      password: form.value.password,
      nickname: form.value.nickname || undefined,
      email: form.value.email || undefined,
      phone: form.value.phone || undefined
    }
    const res = await api.register(payload)

    const token = res.data?.token
    const user = res.data?.user || {
      id: res.data?.userId,
      username: res.data?.username,
      nickname: res.data?.nickname,
      avatar: res.data?.avatar
    }

    userStore.setToken(token)
    userStore.setUserInfo(user)
    ElMessage.success('注册成功')
    router.push('/home')
  } catch (error) {
    ElMessage.error(error?.response?.data?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f7f3e9;
}
.register-container {
  width: 460px;
}
.register-card {
  border-radius: 16px;
}
.register-card h2 {
  text-align: center;
  font-size: var(--neo-fs-xxl);
  font-weight: 900;
  letter-spacing: -0.4px;
  margin: 0;
}

.register-page :deep(.el-form-item__label) {
  font-weight: 800;
  color: #111827;
  font-size: var(--neo-fs-sm);
}

/* Neobrutalism 注册按钮 */
.neo-btn-register {
  width: 100%;
  padding: 14px 20px;
  font-size: 15px;
  font-weight: 700;
  color: #fff;
  background: #8b5cf6;
  border: 2px solid #101010;
  border-radius: 12px;
  cursor: pointer;
  box-shadow: 4px 4px 0 #101010;
  transition: all 0.15s ease;
}

.neo-btn-register:hover:not(:disabled) {
  transform: translate(-2px, -2px);
  box-shadow: 5px 5px 0 #101010;
  background: #7c3aed;
}

.neo-btn-register:active:not(:disabled) {
  transform: translate(2px, 2px);
  box-shadow: 2px 2px 0 #101010;
}

.neo-btn-register:disabled {
  opacity: 0.7;
  cursor: not-allowed;
}
</style>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { login, register } from '@/api/auth'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('login')
const loading = ref(false)

const loginForm = ref({ username: '', password: '' })
const registerForm = ref({ username: '', password: '', nickname: '', email: '' })

async function handleLogin() {
  loading.value = true
  try {
    const data = await login(loginForm.value)
    // expiresIn 交给 store 记下到期时刻，请求层据此提前续期
    userStore.setTokens({
      access: data.accessToken,
      refresh: data.refreshToken,
      expiresIn: data.expiresIn
    })
    userStore.setProfile(data.user)
    ElMessage.success('登录成功')
    // 登录前被拦截的页面存在 query.redirect 里
    router.push(route.query.redirect || { name: 'assets' })
  } catch {
    // 错误提示由 utils/request.js 统一处理
  } finally {
    loading.value = false
  }
}

async function handleRegister() {
  loading.value = true
  try {
    await register(registerForm.value)
    ElMessage.success('注册成功，请登录')
    loginForm.value = { username: registerForm.value.username, password: '' }
    activeTab.value = 'login'
  } catch {
    // 同上
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="auth">
    <el-card shadow="never" class="auth__card">
      <template #header>AIHub</template>

      <el-tabs v-model="activeTab">
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent="handleLogin">
            <el-form-item label="用户名">
              <el-input v-model="loginForm.username" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input
                v-model="loginForm.password"
                type="password"
                show-password
                autocomplete="current-password"
                @keyup.enter="handleLogin"
              />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="auth__submit" @click="handleLogin">
              登录
            </el-button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent="handleRegister">
            <el-form-item label="用户名（3-50 字符）">
              <el-input v-model="registerForm.username" />
            </el-form-item>
            <el-form-item label="密码（8-72 字符，BCrypt 只使用前 72 字节）">
              <el-input v-model="registerForm.password" type="password" show-password />
            </el-form-item>
            <el-form-item label="昵称（可选）">
              <el-input v-model="registerForm.nickname" />
            </el-form-item>
            <el-form-item label="邮箱（可选）">
              <el-input v-model="registerForm.email" />
            </el-form-item>
            <el-button type="primary" :loading="loading" class="auth__submit" @click="handleRegister">
              注册
            </el-button>
          </el-form>
        </el-tab-pane>
      </el-tabs>

      <el-button link class="auth__back" @click="router.push({ name: 'assets' })">返回资源列表</el-button>
    </el-card>
  </div>
</template>

<style scoped>
.auth {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 70vh;
}

.auth__card {
  width: 440px;
}

.auth__submit {
  width: 100%;
}

.auth__back {
  margin-top: 12px;
}
</style>

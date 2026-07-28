<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { CircleAlert, LoaderCircle, ShieldCheck } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { http } from '@/api/http'
import { markBrowserSessionAuthenticated } from '@/router'

const router = useRouter()
const state = ref<'loading' | 'error'>('loading')
const message = ref('正在使用钉钉身份建立安全会话…')

onMounted(async () => {
  const query = new URLSearchParams(window.location.search)
  const authorizationCode = query.get('authCode') ?? query.get('code')
  const stateToken = query.get('state')
  if (!authorizationCode || !stateToken) {
    state.value = 'error'
    message.value = '登录回调参数不完整，请返回登录页重新发起钉钉登录。'
    return
  }
  try {
    const response = await http.post<{
      expiresIn: number
      displayName: string
    }>('/auth/dingtalk/exchange', { authorizationCode, state: stateToken })
    markBrowserSessionAuthenticated()
    await router.replace('/')
    window.location.reload()
  } catch (error) {
    state.value = 'error'
    message.value = error instanceof Error ? error.message : '钉钉登录失败'
  }
})
</script>

<template>
  <main class="auth-screen">
    <section class="auth-card">
      <div class="auth-mark">
        <LoaderCircle v-if="state === 'loading'" :size="28" class="spin" />
        <CircleAlert v-else :size="28" />
      </div>
      <span class="eyebrow">DINGTALK SECURE LOGIN</span>
      <h1>{{ state === 'loading' ? '正在验证身份' : '无法完成登录' }}</h1>
      <p>{{ message }}</p>
      <RouterLink v-if="state === 'error'" to="/login" class="callback-link">返回登录页</RouterLink>
      <div class="secure-note"><ShieldCheck :size="16" /> 授权码仅由服务端向钉钉换取身份</div>
    </section>
  </main>
</template>

<style scoped>
.callback-link {
  display: inline-flex;
  margin-top: 22px;
  color: #2f5e50;
  font-size: 12px;
  text-decoration: none;
  border-bottom: 1px solid currentColor;
}
</style>

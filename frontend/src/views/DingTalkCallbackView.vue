<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { CircleAlert, LoaderCircle, ShieldCheck } from '@lucide/vue'
import { useRouter } from 'vue-router'
import { http } from '@/api/http'
import { markBrowserSessionAuthenticated } from '@/router'
import { useI18n } from '@/i18n'

const router = useRouter()
const { t } = useI18n()
const state = ref<'loading' | 'error'>('loading')
const message = ref(t('copy.0125'))

onMounted(async () => {
  const query = new URLSearchParams(window.location.search)
  const authorizationCode = query.get('authCode') ?? query.get('code')
  const stateToken = query.get('state')
  if (!authorizationCode || !stateToken) {
    state.value = 'error'
    message.value = t('copy.dingtalk.incomplete')
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
    message.value = error instanceof Error ? error.message : t('copy.0126')
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
      <h1>{{ state === 'loading' ? t('copy.0127') : t('copy.0128') }}</h1>
      <p>{{ message }}</p>
      <RouterLink v-if="state === 'error'" to="/login" class="callback-link">{{ t('copy.0129') }}</RouterLink>
      <div class="secure-note"><ShieldCheck :size="16" /> {{ t('copy.0130') }}</div>
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

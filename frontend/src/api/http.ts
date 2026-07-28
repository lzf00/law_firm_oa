import axios from 'axios'
import { useI18n } from '@/i18n'

export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
})

http.interceptors.request.use((config) => {
  const { locale } = useI18n()
  const token = sessionStorage.getItem('law_oa_access_token')
  config.headers['Accept-Language'] = locale.value
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  } else if (sessionStorage.getItem('law_oa_demo_entered') === 'true') {
    config.headers['X-Dev-User'] = 'admin'
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      error.response?.status === 401
      && sessionStorage.getItem('law_oa_access_token')
      && !window.location.pathname.startsWith('/auth/dingtalk/callback')
    ) {
      sessionStorage.removeItem('law_oa_access_token')
      window.location.assign('/login?reason=session-expired')
    }
    const payload = error.response?.data
    const message = payload?.message ?? '系统暂时无法处理请求'
    return Promise.reject(new Error(message))
  },
)

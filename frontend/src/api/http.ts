import axios from 'axios'
import { useI18n } from '@/i18n'

export const http = axios.create({
  baseURL: '/api',
  timeout: 15_000,
  withCredentials: true,
})

http.interceptors.request.use((config) => {
  const { locale } = useI18n()
  config.headers['Accept-Language'] = locale.value
  if (sessionStorage.getItem('law_oa_demo_entered') === 'true') {
    config.headers['X-Dev-User'] = 'admin'
  }
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (
      error.response?.status === 401
      && !window.location.pathname.startsWith('/auth/dingtalk/callback')
      && !window.location.pathname.startsWith('/login')
    ) {
      window.location.assign('/login?reason=session-expired')
    }
    const payload = error.response?.data
    const message = payload?.message ?? '系统暂时无法处理请求'
    return Promise.reject(new Error(message))
  },
)

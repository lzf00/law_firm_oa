import { computed, reactive } from 'vue'
import { http } from '@/api/http'
import type { TenantConfiguration } from '@/api/types'
import { useI18n } from '@/i18n'

const tenant = reactive<TenantConfiguration>({
  organizationId: '',
  brandNameZh: '文森律师事务所',
  brandNameEn: 'Winson Partners and Legal Consultants',
  shortNameZh: '文森',
  shortNameEn: 'WINSON',
  defaultLocale: 'zh-CN',
  supportedLocales: ['zh-CN', 'en-US'],
  primaryTimezone: 'Asia/Dubai',
  baseCurrency: 'AED',
  websiteUrl: 'https://www.winsonglobal.com/',
})
let loadingPromise: Promise<void> | null = null

export function initializeTenant() {
  if (!loadingPromise) {
    loadingPromise = http.get<TenantConfiguration>('/public/tenant-config')
      .then((response) => {
        Object.assign(tenant, response.data)
      })
      .catch(() => undefined)
  }
  return loadingPromise
}

export function useTenant() {
  const { locale } = useI18n()
  return {
    tenant,
    brandName: computed(() => locale.value === 'en-US' ? tenant.brandNameEn : tenant.brandNameZh),
    shortName: computed(() => locale.value === 'en-US' ? tenant.shortNameEn : tenant.shortNameZh),
  }
}

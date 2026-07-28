<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { BriefcaseBusiness, Plus, Search } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter, Office } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { useRouter } from 'vue-router'
import { formatLegalCode } from '@/legalFormat'

const matters = ref<Matter[]>([])
const offices = ref<Office[]>([])
const users = ref<Array<{ id: string; displayName: string }>>([])
const loading = ref(false)
const query = ref('')
const activeStatus = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
const router = useRouter()
const form = reactive({
  matterNumber: '',
  title: '',
  matterType: 'CROSS_BORDER',
  responsibleUserId: '',
  officeId: '',
  countryCode: '',
  jurisdiction: '',
  workingLanguage: 'zh-CN',
  billingCurrency: '',
})
const selectedOffice = computed(() => offices.value.find((office) => office.id === form.officeId))
const statusFilters = computed(() => [
  { value: '', label: t('copy.0362') },
  { value: 'ACTIVE', label: t('copy.0363') },
  { value: 'CONFLICT_REVIEW', label: t('copy.0364') },
  { value: 'ARCHIVED', label: t('copy.0365') },
])
let searchTimer: number | undefined

async function loadMatters() {
  loading.value = true
  try {
    matters.value = (await http.get<Matter[]>('/matters', {
      params: {
        status: activeStatus.value || undefined,
        query: query.value.trim() || undefined,
      },
    })).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0258'))
  } finally {
    loading.value = false
  }
}

async function load() {
  try {
    const [officeResult, userResult, meResult] = await Promise.all([
      http.get<Office[]>('/offices'),
      http.get<Array<{ id: string; displayName: string }>>('/organization/users'),
      http.get<CurrentUser>('/me'),
    ])
    offices.value = officeResult.data
    users.value = userResult.data
    if (!form.responsibleUserId) form.responsibleUserId = meResult.data.userId
    if (!form.officeId) selectOffice(offices.value[0]?.id ?? '')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0258'))
  }
  await loadMatters()
}

function selectOffice(officeId: string) {
  form.officeId = officeId
  const office = offices.value.find((item) => item.id === officeId)
  if (office) {
    form.countryCode = office.countryCode
    form.billingCurrency = office.defaultCurrency
  }
}

async function createMatter() {
  if (!form.matterNumber.trim() || !form.title.trim()) {
    return ElMessage.warning(t('copy.0366'))
  }
  saving.value = true
  try {
    await http.post('/matters', {
      ...form,
      clientIds: [],
      parties: [],
    })
    dialogVisible.value = false
    Object.assign(form, {
      matterNumber: '',
      title: '',
      jurisdiction: '',
    })
    await load()
    ElMessage.success(t('copy.0367'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0259'))
  } finally {
    saving.value = false
  }
}

onMounted(load)
watch([query, activeStatus], () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(loadMatters, query.value ? 280 : 0)
})
onBeforeUnmount(() => window.clearTimeout(searchTimer))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">MATTER MANAGEMENT</span>
        <h2>{{ t('headline.matters') }}</h2>
        <p>{{ t('copy.0260') }}</p>
      </div>
      <button class="primary-action" @click="dialogVisible = true"><Plus :size="17" /> {{ t('copy.0368') }}</button>
    </div>

    <div class="toolbar">
      <label class="search-box">
        <Search :size="17" />
        <input v-model="query" :placeholder="t('copy.0261')" />
      </label>
      <div class="filter-tabs">
        <button
          v-for="filter in statusFilters"
          :key="filter.value"
          :class="{ active: activeStatus === filter.value }"
          @click="activeStatus = filter.value"
        >{{ filter.label }}</button>
      </div>
    </div>

    <div class="panel table-panel" tabindex="0">
      <div v-if="loading" class="empty-state">{{ t('copy.0116') }}</div>
      <table v-else>
        <thead>
          <tr><th>{{ t('copy.0262') }}</th><th>{{ t('copy.0263') }}</th><th>{{ t('copy.0264') }}</th><th>{{ t('copy.0251') }}</th><th>{{ t('copy.0265') }}</th><th>{{ t('copy.0189') }}</th></tr>
        </thead>
        <tbody>
          <tr
            v-for="matter in matters"
            :key="matter.id"
            class="clickable-row"
            tabindex="0"
            @click="router.push(`/matters/${matter.id}`)"
            @keydown.enter="router.push(`/matters/${matter.id}`)"
          >
            <td><span class="mono">{{ matter.matterNumber }}</span></td>
            <td><strong>{{ matter.title }}</strong><small>{{ formatLegalCode(matter.confidentialityLevel, locale) }}</small></td>
            <td><strong>{{ locale === 'en-US' ? matter.officeNameEn : matter.officeNameZh }}</strong><small>{{ matter.billingCurrency }} · {{ formatLegalCode(matter.workingLanguage, locale) }}</small></td>
            <td>{{ matter.jurisdiction || matter.countryCode || '—' }}</td>
            <td>{{ matter.responsibleName }}</td>
            <td><span class="status-pill">{{ formatLegalCode(matter.status, locale) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && matters.length === 0" class="empty-state">
        <BriefcaseBusiness :size="32" />
        <strong>{{ t('copy.0117') }}</strong>
        <span>{{ t('copy.0266') }}</span>
      </div>
    </div>

    <ElDialog
      v-model="dialogVisible"
      :title="t('copy.0369')"
      width="min(720px, 92vw)"
    >
      <form class="dialog-form two-column-form" @submit.prevent="createMatter">
        <label><span>{{ t('copy.0370') }}</span><input v-model="form.matterNumber" required /></label>
        <label><span>{{ t('copy.0246') }}</span><select v-model="form.matterType"><option value="CROSS_BORDER">{{ formatLegalCode('CROSS_BORDER', locale) }}</option><option value="LITIGATION">{{ formatLegalCode('LITIGATION', locale) }}</option><option value="ARBITRATION">{{ formatLegalCode('ARBITRATION', locale) }}</option><option value="CORPORATE">{{ formatLegalCode('CORPORATE', locale) }}</option></select></label>
        <label class="full-field"><span>{{ t('copy.0245') }}</span><input v-model="form.title" required /></label>
        <label><span>{{ t('copy.0222') }}</span><select v-model="form.responsibleUserId"><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ t('copy.0247') }}</span><select :value="form.officeId" @change="selectOffice(($event.target as HTMLSelectElement).value)"><option v-for="office in offices" :key="office.id" :value="office.id">{{ locale === 'en-US' ? office.nameEn : office.nameZh }}</option></select></label>
        <label><span>{{ t('copy.0251') }}</span><input v-model="form.jurisdiction" :placeholder="selectedOffice ? `${selectedOffice.countryCode} · ${selectedOffice.cityEn}` : ''" /></label>
        <label><span>{{ t('copy.0252') }}</span><select v-model="form.workingLanguage"><option value="zh-CN">{{ formatLegalCode('zh-CN', locale) }}</option><option value="en-US">{{ formatLegalCode('en-US', locale) }}</option><option value="ar">{{ formatLegalCode('ar', locale) }}</option></select></label>
        <label><span>{{ t('copy.0250') }}</span><input v-model="form.countryCode" maxlength="2" /></label>
        <label><span>{{ t('copy.0253') }}</span><input v-model="form.billingCurrency" maxlength="3" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? 'Saving…' : (t('copy.0371')) }}</button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.clickable-row { cursor: pointer; }
.clickable-row:hover { background: white; }
.clickable-row:focus-visible { outline: 2px solid var(--brass); outline-offset: -2px; }
</style>

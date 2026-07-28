<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { BriefcaseBusiness, Plus, Search } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter, Office } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { useRouter } from 'vue-router'

const matters = ref<Matter[]>([])
const offices = ref<Office[]>([])
const users = ref<Array<{ id: string; displayName: string }>>([])
const loading = ref(false)
const query = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const { locale } = useI18n()
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

async function load() {
  loading.value = true
  try {
    const [matterResult, officeResult, userResult, meResult] = await Promise.all([
      http.get<Matter[]>('/matters'),
      http.get<Office[]>('/offices'),
      http.get<Array<{ id: string; displayName: string }>>('/organization/users'),
      http.get<CurrentUser>('/me'),
    ])
    matters.value = matterResult.data
    offices.value = officeResult.data
    users.value = userResult.data
    if (!form.responsibleUserId) form.responsibleUserId = meResult.data.userId
    if (!form.officeId) selectOffice(offices.value[0]?.id ?? '')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '案件加载失败')
  } finally {
    loading.value = false
  }
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
    return ElMessage.warning(locale.value === 'en-US'
      ? 'Matter number and title are required'
      : '请填写案号和案件名称')
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
    ElMessage.success(locale.value === 'en-US' ? 'Matter created' : '案件创建成功')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '案件创建失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">MATTER MANAGEMENT</span>
        <h2>{{ t('headline.matters') }}</h2>
        <p>按承办关系管理团队、期限、文档与卷宗，敏感案件独立授权。</p>
      </div>
      <button class="primary-action" @click="dialogVisible = true"><Plus :size="17" /> {{ locale === 'en-US' ? 'New matter' : '新建案件' }}</button>
    </div>

    <div class="toolbar">
      <label class="search-box">
        <Search :size="17" />
        <input v-model="query" placeholder="搜索案号、案件名称或承办律师" />
      </label>
      <div class="filter-tabs">
        <button class="active">全部</button><button>在办</button><button>待立案</button><button>已归档</button>
      </div>
    </div>

    <div class="panel table-panel">
      <div v-if="loading" class="empty-state">正在加载案件…</div>
      <table v-else>
        <thead>
          <tr><th>案号 / No.</th><th>案件 / Matter</th><th>办公室 / Office</th><th>司法辖区 / Jurisdiction</th><th>承办律师 / Counsel</th><th>状态 / Status</th></tr>
        </thead>
        <tbody>
          <tr
            v-for="matter in matters.filter((item) => `${item.matterNumber}${item.title}${item.responsibleName}`.toLowerCase().includes(query.toLowerCase()))"
            :key="matter.id"
            class="clickable-row"
            tabindex="0"
            @click="router.push(`/matters/${matter.id}`)"
            @keydown.enter="router.push(`/matters/${matter.id}`)"
          >
            <td><span class="mono">{{ matter.matterNumber }}</span></td>
            <td><strong>{{ matter.title }}</strong><small>{{ matter.confidentialityLevel }}</small></td>
            <td><strong>{{ locale === 'en-US' ? matter.officeNameEn : matter.officeNameZh }}</strong><small>{{ matter.billingCurrency }} · {{ matter.workingLanguage }}</small></td>
            <td>{{ matter.jurisdiction || matter.countryCode || '—' }}</td>
            <td>{{ matter.responsibleName }}</td>
            <td><span class="status-pill">{{ matter.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!loading && matters.length === 0" class="empty-state">
        <BriefcaseBusiness :size="32" />
        <strong>尚未创建案件</strong>
        <span>先完成利益冲突检索，再发起立案。</span>
      </div>
    </div>

    <ElDialog
      v-model="dialogVisible"
      :title="locale === 'en-US' ? 'Create cross-border matter' : '新建跨境案件'"
      width="min(720px, 92vw)"
    >
      <form class="dialog-form two-column-form" @submit.prevent="createMatter">
        <label><span>{{ locale === 'en-US' ? 'Matter number' : '案号' }}</span><input v-model="form.matterNumber" required /></label>
        <label><span>{{ locale === 'en-US' ? 'Matter type' : '案件类型' }}</span><select v-model="form.matterType"><option value="CROSS_BORDER">Cross-border / 跨境业务</option><option value="LITIGATION">Litigation / 诉讼</option><option value="ARBITRATION">Arbitration / 仲裁</option><option value="CORPORATE">Corporate / 公司业务</option></select></label>
        <label class="full-field"><span>{{ locale === 'en-US' ? 'Matter title' : '案件名称' }}</span><input v-model="form.title" required /></label>
        <label><span>{{ locale === 'en-US' ? 'Responsible counsel' : '承办律师' }}</span><select v-model="form.responsibleUserId"><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ locale === 'en-US' ? 'Lead office' : '承办办公室' }}</span><select :value="form.officeId" @change="selectOffice(($event.target as HTMLSelectElement).value)"><option v-for="office in offices" :key="office.id" :value="office.id">{{ locale === 'en-US' ? office.nameEn : office.nameZh }}</option></select></label>
        <label><span>{{ locale === 'en-US' ? 'Jurisdiction' : '司法辖区' }}</span><input v-model="form.jurisdiction" :placeholder="selectedOffice ? `${selectedOffice.countryCode} · ${selectedOffice.cityEn}` : ''" /></label>
        <label><span>{{ locale === 'en-US' ? 'Working language' : '工作语言' }}</span><select v-model="form.workingLanguage"><option value="zh-CN">中文 Chinese</option><option value="en-US">English 英文</option><option value="ar">العربية Arabic</option></select></label>
        <label><span>{{ locale === 'en-US' ? 'Country code' : '国家代码' }}</span><input v-model="form.countryCode" maxlength="2" /></label>
        <label><span>{{ locale === 'en-US' ? 'Billing currency' : '结算币种' }}</span><input v-model="form.billingCurrency" maxlength="3" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? 'Saving…' : (locale === 'en-US' ? 'Create matter' : '创建案件') }}</button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.clickable-row { cursor: pointer; }
.clickable-row:hover { background: white; }
.clickable-row:focus-visible { outline: 2px solid var(--brass); outline-offset: -2px; }
</style>

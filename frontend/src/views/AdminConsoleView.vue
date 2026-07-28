<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Building2, GitBranch, PlugZap, Settings2, ShieldCheck, UsersRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Settings {
  brandNameZh: string
  brandNameEn: string
  shortNameZh: string
  shortNameEn: string
  defaultLocale: string
  primaryTimezone: string
  baseCurrency: string
  websiteUrl?: string
}

interface User {
  id: string
  username: string
  displayName: string
  email?: string
  status: string
  roleCodes: string[]
  offices: Array<{ officeId: string; accessLevel: string; primary: boolean }>
}

interface Role {
  code: string
  name: string
  userCount: number
  permissions: string[]
}

interface Integration {
  integrationType: string
  providerCode: string
  configurationStatus: string
  healthStatus: string
  message: string
  checkedAt: string
}

interface WorkflowRule {
  id: string
  businessType: string
  assigneeRoleCode?: string
  reminderMinutes: number
  escalationMinutes: number
  priority: number
  enabled: boolean
}

const { locale, t } = useI18n()
const loading = ref(true)
const settings = ref<Settings | null>(null)
const users = ref<User[]>([])
const roles = ref<Role[]>([])
const integrations = ref<Integration[]>([])
const rules = ref<WorkflowRule[]>([])

function firmName() {
  if (!settings.value) return ''
  return locale.value === 'en-US' ? settings.value.brandNameEn : settings.value.brandNameZh
}

function healthLabel(status: string) {
  if (status === 'UP') return t('p1.up')
  if (status === 'DOWN') return t('p1.down')
  return t('p1.unknown')
}

async function load() {
  loading.value = true
  try {
    const [settingsResult, userResult, roleResult, integrationResult, ruleResult] = await Promise.all([
      http.get<Settings>('/admin/settings'),
      http.get<{ items: User[] }>('/admin/users', { params: { page: 1, size: 30 } }),
      http.get<Role[]>('/admin/roles'),
      http.get<Integration[]>('/admin/integrations'),
      http.get<WorkflowRule[]>('/admin/workflow-rules'),
    ])
    settings.value = settingsResult.data
    users.value = userResult.data.items
    roles.value = roleResult.data
    integrations.value = integrationResult.data
    rules.value = ruleResult.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page admin-page">
    <header class="page-intro admin-intro">
      <div>
        <span class="eyebrow">IDENTITY · POLICY · INTEGRATIONS · DELIVERY</span>
        <h2>{{ t('page.admin') }}</h2>
        <p>{{ t('p1.adminHeadline') }}</p>
      </div>
      <Settings2 :size="34" />
    </header>

    <div v-if="loading" class="panel empty-state">{{ t('shell.connecting') }}</div>
    <div v-else class="admin-grid">
      <section class="panel settings-card">
        <div class="section-heading"><Building2 :size="20" /><h3>{{ t('p1.settings') }}</h3></div>
        <strong class="firm-name">{{ firmName() }}</strong>
        <dl v-if="settings">
          <div><dt>Locale</dt><dd>{{ settings.defaultLocale }}</dd></div>
          <div><dt>Timezone</dt><dd>{{ settings.primaryTimezone }}</dd></div>
          <div><dt>Currency</dt><dd>{{ settings.baseCurrency }}</dd></div>
        </dl>
      </section>

      <section class="panel">
        <div class="section-heading"><UsersRound :size="20" /><h3>{{ t('p1.users') }}</h3><span>{{ users.length }}</span></div>
        <div class="record-list">
          <article v-for="item in users" :key="item.id">
            <div><strong>{{ item.displayName }}</strong><span>{{ item.username }} · {{ item.email }}</span></div>
            <div class="right"><strong>{{ formatLegalCode(item.status, locale) }}</strong><span>{{ item.roleCodes.map((role) => formatLegalCode(role, locale)).join(' · ') }}</span></div>
          </article>
        </div>
      </section>

      <section class="panel">
        <div class="section-heading"><ShieldCheck :size="20" /><h3>{{ t('p1.roles') }}</h3><span>{{ roles.length }}</span></div>
        <div class="record-list">
          <article v-for="item in roles" :key="item.code">
            <div><strong>{{ item.name }}</strong><span>{{ item.code }}</span></div>
            <div class="right"><strong>{{ item.userCount }}</strong><span>{{ item.permissions.length }}</span></div>
          </article>
        </div>
      </section>

      <section class="panel integrations-card">
        <div class="section-heading"><PlugZap :size="20" /><h3>{{ t('p1.integrations') }}</h3></div>
        <div class="integration-grid">
          <article v-for="item in integrations" :key="item.integrationType" :class="item.healthStatus.toLowerCase()">
            <span class="health-dot" />
            <div><strong>{{ item.integrationType }}</strong><small>{{ item.providerCode }}</small></div>
            <div class="health"><strong>{{ healthLabel(item.healthStatus) }}</strong><small>{{ item.configurationStatus }}</small></div>
          </article>
        </div>
      </section>

      <section class="panel rules-card">
        <div class="section-heading"><GitBranch :size="20" /><h3>{{ t('p1.workflowRules') }}</h3><span>{{ rules.length }}</span></div>
        <div v-if="rules.length" class="record-list">
          <article v-for="item in rules" :key="item.id">
            <div><strong>{{ item.businessType }}</strong><span>{{ item.assigneeRoleCode || t('p1.unknown') }}</span></div>
            <div class="right"><strong>P{{ item.priority }}</strong><span>{{ item.reminderMinutes }} / {{ item.escalationMinutes }}</span></div>
          </article>
        </div>
        <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
      </section>
    </div>
  </section>
</template>

<style scoped>
.admin-intro { background: linear-gradient(125deg, #eef2f0, #fff 54%, #eee9df); }
.admin-intro > svg { color: #2c5445; }
.admin-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.integrations-card, .rules-card { grid-column: 1 / -1; }
.section-heading { display: flex; align-items: center; gap: .55rem; margin-bottom: .8rem; }
.section-heading h3 { margin: 0; flex: 1; }
.section-heading > span { padding: .15rem .5rem; border-radius: 99px; background: #edf1ee; font-size: .78rem; }
.firm-name { display: block; font-family: Georgia, serif; font-size: 1.35rem; margin: .8rem 0 1.2rem; }
dl { display: grid; grid-template-columns: repeat(3, 1fr); gap: .7rem; margin: 0; }
dl div { padding: .75rem; background: #f6f7f5; border-radius: 10px; }
dt, small, .record-list span { color: var(--text-muted); font-size: .76rem; }
dd { margin: .25rem 0 0; font-weight: 700; overflow-wrap: anywhere; }
.record-list article { display: flex; justify-content: space-between; gap: 1rem; padding: .75rem 0; border-top: 1px solid var(--border-color); }
.record-list span, .integration-grid small { display: block; margin-top: .18rem; }
.right { text-align: right; }
.integration-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: .65rem; }
.integration-grid article { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: .65rem; padding: .85rem; border: 1px solid var(--border-color); border-radius: 12px; }
.health { text-align: right; }
.health-dot { width: .65rem; height: .65rem; border-radius: 50%; background: #a9afac; }
.up .health-dot { background: #2c8b63; box-shadow: 0 0 0 4px #e2f1e9; }
.down .health-dot { background: #b0473f; box-shadow: 0 0 0 4px #f5e5e3; }
.empty-copy { color: var(--text-muted); }
@media (max-width: 900px) {
  .admin-grid, .integration-grid { grid-template-columns: 1fr; }
  .integrations-card, .rules-card { grid-column: auto; }
}
</style>

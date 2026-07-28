<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Activity,
  ArrowLeft,
  CalendarClock,
  FileSignature,
  FileText,
  FolderArchive,
  Archive as ArchiveIcon,
  CheckCircle2,
  PauseCircle,
  Pencil,
  PlayCircle,
  ShieldCheck,
  Stamp,
  Users,
  XCircle,
} from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import type { Matter, Office, OrganizationUser } from '@/api/types'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface MatterDetail {
  summary: Matter
  courtName?: string
  caseNumber?: string
  description?: string
  parties: Array<{ partyId: string; partyName: string; partyRole: string; side: string }>
}

interface MatterWorkspace {
  detail: MatterDetail
  team: Array<{
    userId: string
    displayName: string
    memberRole: string
    canDownload: boolean
    joinedAt: string
  }>
  conflicts: Array<{
    id: string
    requestNumber: string
    proposedMatterTitle: string
    status: string
    riskLevel?: string
    decision?: string
    createdAt: string
  }>
  contracts: Array<{
    id: string
    contractNumber: string
    title: string
    status: string
    amount?: number
    currency: string
  }>
  approvals: Array<{
    id: string
    businessType: string
    businessId: string
    processDefinitionKey: string
    status: string
    decision?: string
    startedAt: string
    completedAt?: string
  }>
  archives: Array<{
    id: string
    archiveNumber: string
    title: string
    status: string
    itemCount: number
  }>
  deadlines: Array<{
    id: string
    title: string
    dueAt: string
    priority: string
    status: string
    ownerName: string
  }>
  documents: Array<{
    id: string
    logicalName: string
    documentType: string
    versionNumber?: number
    versionStatus?: string
    ingestionStatus?: string
    confidentialityLevel: string
  }>
  activity: Array<{
    id: string
    eventType: string
    title: string
    description?: string
    eventAt: string
    createdByName: string
  }>
}

const route = useRoute()
const router = useRouter()
const { locale, t } = useI18n()
const workspace = ref<MatterWorkspace | null>(null)
const loading = ref(true)
const editVisible = ref(false)
const saving = ref(false)
const transitioning = ref(false)
const offices = ref<Office[]>([])
const users = ref<OrganizationUser[]>([])
const editForm = reactive({
  title: '',
  matterType: '',
  responsibleUserId: '',
  openedAt: '',
  courtName: '',
  caseNumber: '',
  description: '',
  officeId: '',
  countryCode: '',
  jurisdiction: '',
  workingLanguage: 'zh-CN',
  billingCurrency: 'CNY',
})

const isEnglish = computed(() => locale.value === 'en-US')
const officeName = computed(() => {
  const matter = workspace.value?.detail.summary
  return isEnglish.value ? matter?.officeNameEn : matter?.officeNameZh
})
const lifecycleActions = computed(() => {
  const status = workspace.value?.detail.summary.status
  if (status === 'CONFLICT_REVIEW') {
    return [
      { status: 'ACTIVE', labelKey: 'copy.lifecycle.activate', icon: PlayCircle },
      { status: 'REJECTED', labelKey: 'copy.lifecycle.reject', icon: XCircle },
    ]
  }
  if (status === 'ACTIVE') {
    return [
      { status: 'SUSPENDED', labelKey: 'copy.lifecycle.suspend', icon: PauseCircle },
      { status: 'CLOSED', labelKey: 'copy.lifecycle.close', icon: CheckCircle2 },
    ]
  }
  if (status === 'SUSPENDED') {
    return [
      { status: 'ACTIVE', labelKey: 'copy.lifecycle.resume', icon: PlayCircle },
      { status: 'CLOSED', labelKey: 'copy.lifecycle.close', icon: CheckCircle2 },
    ]
  }
  if (status === 'CLOSED') {
    return [{ status: 'ARCHIVED', labelKey: 'copy.lifecycle.archive', icon: ArchiveIcon }]
  }
  return []
})

function dateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(t('copy.0257'), {
    year: 'numeric',
    month: 'short',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

async function load() {
  loading.value = true
  try {
    workspace.value = (await http.get<MatterWorkspace>(
      `/matters/${String(route.params.id)}/workspace`,
    )).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0202'))
  } finally {
    loading.value = false
  }
}

function openEdit() {
  if (!workspace.value) return
  const { summary } = workspace.value.detail
  Object.assign(editForm, {
    title: summary.title,
    matterType: summary.matterType,
    responsibleUserId: summary.responsibleUserId,
    openedAt: summary.openedAt ?? '',
    courtName: workspace.value.detail.courtName ?? '',
    caseNumber: workspace.value.detail.caseNumber ?? '',
    description: workspace.value.detail.description ?? '',
    officeId: summary.officeId ?? '',
    countryCode: summary.countryCode ?? '',
    jurisdiction: summary.jurisdiction ?? '',
    workingLanguage: summary.workingLanguage,
    billingCurrency: summary.billingCurrency,
  })
  editVisible.value = true
}

async function saveMatter() {
  if (!workspace.value || !editForm.title.trim() || !editForm.responsibleUserId || !editForm.officeId) {
    ElMessage.warning(t('copy.0203'))
    return
  }
  saving.value = true
  try {
    await http.put(`/matters/${workspace.value.detail.summary.id}`, {
      ...editForm,
      openedAt: editForm.openedAt || null,
      courtName: editForm.courtName || null,
      caseNumber: editForm.caseNumber || null,
      description: editForm.description || null,
      jurisdiction: editForm.jurisdiction || null,
    })
    editVisible.value = false
    await load()
    ElMessage.success(t('copy.0204'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0205'))
  } finally {
    saving.value = false
  }
}

async function transition(targetStatus: string) {
  if (!workspace.value || transitioning.value) return
  try {
    const result = await ElMessageBox.prompt(
      t('copy.0206'),
      t('copy.0207'),
      {
        confirmButtonText: t('copy.0208'),
        cancelButtonText: t('copy.0079'),
        inputType: 'textarea',
        inputPattern: /\S+/,
        inputErrorMessage: t('copy.0209'),
      },
    )
    transitioning.value = true
    await http.post(`/matters/${workspace.value.detail.summary.id}/lifecycle`, {
      targetStatus,
      reason: result.value,
    })
    await load()
    ElMessage.success(t('copy.0210'))
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : t('copy.0211'))
  } finally {
    transitioning.value = false
  }
}

onMounted(async () => {
  const [officeResult, userResult] = await Promise.all([
    http.get<Office[]>('/offices'),
    http.get<OrganizationUser[]>('/organization/users'),
  ])
  offices.value = officeResult.data
  users.value = userResult.data
  await load()
})
</script>

<template>
  <section class="module-page matter-workspace">
    <button class="back-link" type="button" @click="router.push('/matters')">
      <ArrowLeft :size="16" /> {{ t('copy.0212') }}
    </button>

    <div v-if="loading" class="panel empty-state">
      {{ t('copy.0213') }}
    </div>

    <template v-else-if="workspace">
      <header class="workspace-hero">
        <div>
          <span class="eyebrow">MATTER COMMAND CENTER</span>
          <div class="matter-number">{{ workspace.detail.summary.matterNumber }}</div>
          <h2>{{ workspace.detail.summary.title }}</h2>
          <p>
            {{ formatLegalCode(workspace.detail.summary.matterType, locale) }} ·
            {{ officeName || workspace.detail.summary.countryCode || '—' }} ·
            {{ workspace.detail.summary.jurisdiction || t('copy.0214') }}
          </p>
        </div>
        <div class="hero-actions">
          <button class="hero-button" type="button" @click="openEdit"><Pencil :size="15" /> {{ t('copy.0215') }}</button>
          <button
            v-for="action in lifecycleActions"
            :key="action.status"
            class="hero-button"
            type="button"
            :disabled="transitioning"
            @click="transition(action.status)"
          >
            <component :is="action.icon" :size="15" /> {{ t(action.labelKey) }}
          </button>
        </div>
        <div class="hero-status">
          <span>{{ t('copy.0216') }}</span>
          <strong>{{ formatLegalCode(workspace.detail.summary.status, locale) }}</strong>
          <small>{{ workspace.detail.summary.confidentialityLevel }}</small>
        </div>
      </header>

      <div class="workspace-stats">
        <div><Users :size="18" /><span>{{ t('copy.0217') }}</span><strong>{{ workspace.team.length }}</strong></div>
        <div><CalendarClock :size="18" /><span>{{ t('copy.0218') }}</span><strong>{{ workspace.deadlines.filter((item) => item.status === 'OPEN').length }}</strong></div>
        <div><FileText :size="18" /><span>{{ t('copy.0219') }}</span><strong>{{ workspace.documents.length }}</strong></div>
        <div><FolderArchive :size="18" /><span>{{ t('copy.0220') }}</span><strong>{{ workspace.archives.length }}</strong></div>
      </div>

      <div class="workspace-grid">
        <article class="panel workspace-card overview-card">
          <div class="workspace-heading"><ShieldCheck :size="19" /><h3>{{ t('copy.0221') }}</h3></div>
          <dl class="facts">
            <div><dt>{{ t('copy.0222') }}</dt><dd>{{ workspace.detail.summary.responsibleName }}</dd></div>
            <div><dt>{{ t('copy.0223') }}</dt><dd>{{ workspace.detail.summary.matterNumber }}<span v-if="workspace.detail.caseNumber"> · {{ workspace.detail.caseNumber }}</span></dd></div>
            <div><dt>{{ t('copy.0224') }}</dt><dd>{{ workspace.detail.courtName || '—' }}</dd></div>
            <div><dt>{{ t('copy.0225') }}</dt><dd>{{ formatLegalCode(workspace.detail.summary.workingLanguage, locale) }} · {{ workspace.detail.summary.billingCurrency }}</dd></div>
          </dl>
          <p class="matter-description">{{ workspace.detail.description || t('copy.0226') }}</p>
          <div class="party-strip">
            <span v-for="party in workspace.detail.parties" :key="`${party.partyId}-${party.partyRole}`">
              <strong>{{ party.partyName }}</strong> · {{ party.partyRole }} / {{ party.side }}
            </span>
            <small v-if="!workspace.detail.parties.length">{{ t('copy.0227') }}</small>
          </div>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><Users :size="19" /><h3>{{ t('copy.0228') }}</h3></div>
          <ul class="compact-list">
            <li v-for="member in workspace.team" :key="member.userId">
              <div><strong>{{ member.displayName }}</strong><span>{{ formatLegalCode(member.memberRole, locale) }}</span></div>
              <span class="status-pill">{{ member.canDownload ? t('copy.0229') : t('copy.0230') }}</span>
            </li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><CalendarClock :size="19" /><h3>{{ t('copy.0231') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.deadlines.slice(0, 6)" :key="item.id">
              <div><strong>{{ item.title }}</strong><span>{{ dateTime(item.dueAt) }} · {{ item.ownerName }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.priority, locale) }} · {{ formatLegalCode(item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.deadlines.length" class="list-empty">{{ t('copy.0232') }}</li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><FileSignature :size="19" /><h3>{{ t('copy.0233') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.contracts" :key="item.id">
              <div><strong>{{ item.title }}</strong><span>{{ item.contractNumber }}<template v-if="item.amount"> · {{ item.currency }} {{ item.amount.toLocaleString() }}</template></span></div>
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.contracts.length" class="list-empty">{{ t('copy.0234') }}</li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><Stamp :size="19" /><h3>{{ t('copy.0235') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.approvals.slice(0, 4)" :key="item.id">
              <div><strong>{{ item.processDefinitionKey }}</strong><span>{{ item.businessType }} · {{ dateTime(item.startedAt) }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.decision || item.status, locale) }}</span>
            </li>
            <li v-for="item in workspace.conflicts.slice(0, 4)" :key="item.id">
              <div><strong>{{ item.requestNumber }} · {{ item.proposedMatterTitle }}</strong><span>{{ item.riskLevel || t('copy.0236') }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.decision || item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.approvals.length && !workspace.conflicts.length" class="list-empty">
              {{ t('copy.0237') }}
            </li>
          </ul>
        </article>

        <article class="panel workspace-card wide-card">
          <div class="workspace-heading"><FileText :size="19" /><h3>{{ t('copy.0238') }}</h3></div>
          <div class="document-archive-grid">
            <div>
              <h4>{{ t('copy.0239') }}</h4>
              <ul class="compact-list">
                <li v-for="item in workspace.documents.slice(0, 8)" :key="item.id">
                  <div><strong>{{ item.logicalName }}</strong><span>{{ formatLegalCode(item.documentType, locale) }} · V{{ item.versionNumber || '—' }}</span></div>
                  <span class="status-pill">{{ formatLegalCode(item.ingestionStatus || item.versionStatus || item.confidentialityLevel, locale) }}</span>
                </li>
                <li v-if="!workspace.documents.length" class="list-empty">{{ t('copy.0240') }}</li>
              </ul>
            </div>
            <div>
              <h4>{{ t('copy.0220') }}</h4>
              <ul class="compact-list">
                <li v-for="item in workspace.archives" :key="item.id">
                  <div><strong>{{ item.title }}</strong><span>{{ item.archiveNumber }} · {{ item.itemCount }} {{ t('copy.0066') }}</span></div>
                  <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
                </li>
                <li v-if="!workspace.archives.length" class="list-empty">{{ t('copy.0241') }}</li>
              </ul>
            </div>
          </div>
        </article>

        <article class="panel workspace-card wide-card">
          <div class="workspace-heading"><Activity :size="19" /><h3>{{ t('copy.0242') }}</h3></div>
          <ol class="activity-list">
            <li v-for="item in workspace.activity" :key="item.id">
              <time>{{ dateTime(item.eventAt) }}</time>
              <div><strong>{{ item.title }}</strong><span>{{ item.description || item.eventType }} · {{ item.createdByName }}</span></div>
            </li>
            <li v-if="!workspace.activity.length" class="list-empty">{{ t('copy.0243') }}</li>
          </ol>
        </article>
      </div>

      <ElDialog
        v-model="editVisible"
        :title="t('copy.0244')"
        width="min(760px, 94vw)"
      >
        <form class="dialog-form two-column-form" @submit.prevent="saveMatter">
          <label class="full-field"><span>{{ t('copy.0245') }}</span><input v-model="editForm.title" required maxlength="300" /></label>
          <label><span>{{ t('copy.0246') }}</span><input v-model="editForm.matterType" required maxlength="80" /></label>
          <label><span>{{ t('copy.0222') }}</span><select v-model="editForm.responsibleUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
          <label><span>{{ t('copy.0247') }}</span><select v-model="editForm.officeId" required><option v-for="office in offices" :key="office.id" :value="office.id">{{ isEnglish ? office.nameEn : office.nameZh }}</option></select></label>
          <label><span>{{ t('copy.0248') }}</span><input v-model="editForm.openedAt" type="date" /></label>
          <label><span>{{ t('copy.0224') }}</span><input v-model="editForm.courtName" maxlength="300" /></label>
          <label><span>{{ t('copy.0249') }}</span><input v-model="editForm.caseNumber" maxlength="150" /></label>
          <label><span>{{ t('copy.0250') }}</span><input v-model="editForm.countryCode" maxlength="2" /></label>
          <label><span>{{ t('copy.0251') }}</span><input v-model="editForm.jurisdiction" maxlength="200" /></label>
          <label><span>{{ t('copy.0252') }}</span><select v-model="editForm.workingLanguage"><option value="zh-CN">{{ formatLegalCode('zh-CN', locale) }}</option><option value="en-US">{{ formatLegalCode('en-US', locale) }}</option><option value="ar">{{ formatLegalCode('ar', locale) }}</option></select></label>
          <label><span>{{ t('copy.0253') }}</span><input v-model="editForm.billingCurrency" maxlength="3" /></label>
          <label class="full-field"><span>{{ t('copy.0254') }}</span><textarea v-model="editForm.description" rows="4" maxlength="4000" /></label>
          <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('copy.0255') : t('copy.0256') }}</button>
        </form>
      </ElDialog>
    </template>
  </section>
</template>

<style scoped>
.matter-workspace { padding-top: 10px; }
.back-link { display: inline-flex; align-items: center; gap: 7px; margin: 0 0 18px; padding: 0; border: 0; background: transparent; color: var(--forest-2); cursor: pointer; font-size: 12px; }
.workspace-hero { min-height: 220px; display: grid; grid-template-columns: minmax(0, 1fr) auto 180px; align-items: end; gap: 24px; padding: 36px 40px; background: var(--forest); color: white; border-bottom: 4px solid var(--brass); }
.workspace-hero h2 { max-width: 850px; margin: 8px 0 0; font: 700 31px/1.35 "Songti SC", serif; }
.workspace-hero p { margin: 13px 0 0; color: #bdcbc5; font-size: 12px; }
.matter-number { color: #d4b46f; font: 600 12px Georgia, monospace; letter-spacing: .08em; }
.hero-status { min-width: 170px; padding: 17px; border: 1px solid rgba(255,255,255,.18); }
.hero-status span, .hero-status strong, .hero-status small { display: block; }
.hero-status span { color: #aebdb6; font-size: 9px; letter-spacing: .12em; }
.hero-status strong { margin: 10px 0 6px; color: #e0c486; font: 600 19px Georgia, serif; }
.hero-status small { color: #aebdb6; font-size: 9px; }
.hero-actions { display: flex; flex-direction: column; gap: 7px; }
.hero-button { min-height: 34px; display: inline-flex; align-items: center; justify-content: flex-start; gap: 7px; padding: 0 11px; border: 1px solid rgba(255,255,255,.22); background: rgba(255,255,255,.04); color: white; cursor: pointer; font-size: 10px; }
.hero-button:hover { border-color: #d4b46f; color: #e0c486; }
.hero-button:disabled { opacity: .45; cursor: wait; }
.workspace-stats { display: grid; grid-template-columns: repeat(4, 1fr); margin-bottom: 18px; border: 1px solid var(--line); border-top: 0; background: var(--paper-light); }
.workspace-stats > div { min-height: 88px; display: grid; grid-template-columns: 28px 1fr auto; align-items: center; gap: 8px; padding: 18px 22px; border-right: 1px solid var(--line); color: var(--forest-2); }
.workspace-stats > div:last-child { border-right: 0; }
.workspace-stats span { color: var(--muted); font-size: 10px; }
.workspace-stats strong { color: var(--ink); font: 600 24px Georgia, serif; }
.workspace-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 18px; }
.workspace-card { min-width: 0; }
.workspace-heading { min-height: 66px; display: flex; align-items: center; gap: 10px; padding: 16px 21px; border-bottom: 1px solid var(--line); color: var(--forest-2); }
.workspace-heading h3 { margin: 0; color: var(--ink); font: 700 17px "Songti SC", serif; }
.overview-card, .wide-card { grid-column: 1 / -1; }
.facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1px; margin: 0; background: var(--line); }
.facts div { min-height: 76px; padding: 16px 20px; background: white; }
.facts dt { color: var(--muted); font-size: 9px; }
.facts dd { margin: 8px 0 0; font-size: 12px; }
.matter-description { margin: 0; padding: 20px; color: var(--ink-soft); font-size: 12px; line-height: 1.8; }
.party-strip { display: flex; flex-wrap: wrap; gap: 8px; padding: 0 20px 20px; }
.party-strip span, .party-strip small { padding: 7px 9px; border: 1px solid var(--line); color: var(--muted); font-size: 9px; }
.party-strip strong { color: var(--ink); }
.compact-list, .activity-list { margin: 0; padding: 0 20px; list-style: none; }
.compact-list li { min-height: 67px; display: flex; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--line); }
.compact-list li:last-child { border-bottom: 0; }
.compact-list strong, .compact-list span { display: block; }
.compact-list strong { font-size: 12px; }
.compact-list div > span { margin-top: 5px; color: var(--muted); font-size: 9px; }
.compact-list .status-pill { flex: 0 0 auto; font-size: 8px; }
.compact-list .list-empty, .activity-list .list-empty { min-height: 100px; justify-content: center; color: var(--muted); font-size: 11px; }
.document-archive-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0; }
.document-archive-grid > div:first-child { border-right: 1px solid var(--line); }
.document-archive-grid h4 { margin: 0; padding: 15px 20px; background: #eeeae1; color: var(--muted); font-size: 10px; letter-spacing: .08em; }
.activity-list li { display: grid; grid-template-columns: 180px 1fr; gap: 20px; padding: 16px 0; border-bottom: 1px solid var(--line); }
.activity-list li:last-child { border-bottom: 0; }
.activity-list time { color: var(--brass); font: 600 11px Georgia, serif; }
.activity-list strong, .activity-list span { display: block; }
.activity-list strong { font-size: 12px; }
.activity-list span { margin-top: 5px; color: var(--muted); font-size: 9px; }
@media (max-width: 900px) {
  .workspace-stats, .facts { grid-template-columns: repeat(2, 1fr); }
  .workspace-grid { grid-template-columns: 1fr; }
  .overview-card, .wide-card { grid-column: auto; }
  .document-archive-grid { grid-template-columns: 1fr; }
  .document-archive-grid > div:first-child { border-right: 0; border-bottom: 1px solid var(--line); }
}
@media (max-width: 620px) {
  .workspace-hero { grid-template-columns: 1fr; align-items: flex-start; padding: 28px 22px; }
  .hero-actions { width: 100%; }
  .hero-status { width: 100%; }
  .workspace-stats { grid-template-columns: 1fr 1fr; }
  .workspace-stats > div { grid-template-columns: 24px 1fr; padding: 14px; }
  .workspace-stats strong { grid-column: 2; }
  .facts { grid-template-columns: 1fr; }
  .activity-list li { grid-template-columns: 1fr; gap: 6px; }
}
</style>

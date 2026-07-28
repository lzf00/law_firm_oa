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
const { locale } = useI18n()
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
      { status: 'ACTIVE', zh: '确认立案', en: 'Activate', icon: PlayCircle },
      { status: 'REJECTED', zh: '拒绝立案', en: 'Reject', icon: XCircle },
    ]
  }
  if (status === 'ACTIVE') {
    return [
      { status: 'SUSPENDED', zh: '暂停案件', en: 'Suspend', icon: PauseCircle },
      { status: 'CLOSED', zh: '结案', en: 'Close', icon: CheckCircle2 },
    ]
  }
  if (status === 'SUSPENDED') {
    return [
      { status: 'ACTIVE', zh: '恢复办理', en: 'Resume', icon: PlayCircle },
      { status: 'CLOSED', zh: '结案', en: 'Close', icon: CheckCircle2 },
    ]
  }
  if (status === 'CLOSED') {
    return [{ status: 'ARCHIVED', zh: '正式归档', en: 'Archive', icon: ArchiveIcon }]
  }
  return []
})

function text(zh: string, en: string) {
  return isEnglish.value ? en : zh
}

function dateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(isEnglish.value ? 'en-US' : 'zh-CN', {
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
    ElMessage.error(error instanceof Error ? error.message : text('案件工作台加载失败', 'Failed to load matter workspace'))
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
    ElMessage.warning(text('请填写案件名称、承办律师和办公室', 'Title, responsible counsel and office are required'))
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
    ElMessage.success(text('案件资料已更新', 'Matter updated'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('案件更新失败', 'Failed to update matter'))
  } finally {
    saving.value = false
  }
}

async function transition(targetStatus: string) {
  if (!workspace.value || transitioning.value) return
  try {
    const result = await ElMessageBox.prompt(
      text('请填写本次状态变更原因，该内容将进入审计记录。', 'Enter a reason. It will be included in the audit trail.'),
      text('案件状态变更', 'Matter lifecycle change'),
      {
        confirmButtonText: text('确认变更', 'Confirm'),
        cancelButtonText: text('取消', 'Cancel'),
        inputType: 'textarea',
        inputPattern: /\S+/,
        inputErrorMessage: text('必须填写变更原因', 'A reason is required'),
      },
    )
    transitioning.value = true
    await http.post(`/matters/${workspace.value.detail.summary.id}/lifecycle`, {
      targetStatus,
      reason: result.value,
    })
    await load()
    ElMessage.success(text('案件状态已更新', 'Matter status updated'))
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : text('状态变更失败', 'Lifecycle update failed'))
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
      <ArrowLeft :size="16" /> {{ text('返回案件列表', 'Back to matters') }}
    </button>

    <div v-if="loading" class="panel empty-state">
      {{ text('正在加载案件工作台…', 'Loading matter workspace…') }}
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
            {{ workspace.detail.summary.jurisdiction || text('未填写司法辖区', 'Jurisdiction not set') }}
          </p>
        </div>
        <div class="hero-actions">
          <button class="hero-button" type="button" @click="openEdit"><Pencil :size="15" /> {{ text('编辑资料', 'Edit') }}</button>
          <button
            v-for="action in lifecycleActions"
            :key="action.status"
            class="hero-button"
            type="button"
            :disabled="transitioning"
            @click="transition(action.status)"
          >
            <component :is="action.icon" :size="15" /> {{ isEnglish ? action.en : action.zh }}
          </button>
        </div>
        <div class="hero-status">
          <span>{{ text('案件状态', 'Matter status') }}</span>
          <strong>{{ formatLegalCode(workspace.detail.summary.status, locale) }}</strong>
          <small>{{ workspace.detail.summary.confidentialityLevel }}</small>
        </div>
      </header>

      <div class="workspace-stats">
        <div><Users :size="18" /><span>{{ text('团队成员', 'Team') }}</span><strong>{{ workspace.team.length }}</strong></div>
        <div><CalendarClock :size="18" /><span>{{ text('未结期限', 'Deadlines') }}</span><strong>{{ workspace.deadlines.filter((item) => item.status === 'OPEN').length }}</strong></div>
        <div><FileText :size="18" /><span>{{ text('案件文档', 'Documents') }}</span><strong>{{ workspace.documents.length }}</strong></div>
        <div><FolderArchive :size="18" /><span>{{ text('电子卷宗', 'Archives') }}</span><strong>{{ workspace.archives.length }}</strong></div>
      </div>

      <div class="workspace-grid">
        <article class="panel workspace-card overview-card">
          <div class="workspace-heading"><ShieldCheck :size="19" /><h3>{{ text('案件概览', 'Matter overview') }}</h3></div>
          <dl class="facts">
            <div><dt>{{ text('承办律师', 'Responsible counsel') }}</dt><dd>{{ workspace.detail.summary.responsibleName }}</dd></div>
            <div><dt>{{ text('案号 / 法院案号', 'Matter / court no.') }}</dt><dd>{{ workspace.detail.summary.matterNumber }}<span v-if="workspace.detail.caseNumber"> · {{ workspace.detail.caseNumber }}</span></dd></div>
            <div><dt>{{ text('法院 / 机构', 'Court / authority') }}</dt><dd>{{ workspace.detail.courtName || '—' }}</dd></div>
            <div><dt>{{ text('工作语言 / 币种', 'Language / currency') }}</dt><dd>{{ formatLegalCode(workspace.detail.summary.workingLanguage, locale) }} · {{ workspace.detail.summary.billingCurrency }}</dd></div>
          </dl>
          <p class="matter-description">{{ workspace.detail.description || text('暂无案件说明。', 'No matter description.') }}</p>
          <div class="party-strip">
            <span v-for="party in workspace.detail.parties" :key="`${party.partyId}-${party.partyRole}`">
              <strong>{{ party.partyName }}</strong> · {{ party.partyRole }} / {{ party.side }}
            </span>
            <small v-if="!workspace.detail.parties.length">{{ text('暂未关联当事人', 'No linked parties') }}</small>
          </div>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><Users :size="19" /><h3>{{ text('办案团队', 'Matter team') }}</h3></div>
          <ul class="compact-list">
            <li v-for="member in workspace.team" :key="member.userId">
              <div><strong>{{ member.displayName }}</strong><span>{{ formatLegalCode(member.memberRole, locale) }}</span></div>
              <span class="status-pill">{{ member.canDownload ? text('可下载', 'Download') : text('仅查看', 'View only') }}</span>
            </li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><CalendarClock :size="19" /><h3>{{ text('关键期限', 'Key deadlines') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.deadlines.slice(0, 6)" :key="item.id">
              <div><strong>{{ item.title }}</strong><span>{{ dateTime(item.dueAt) }} · {{ item.ownerName }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.priority, locale) }} · {{ formatLegalCode(item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.deadlines.length" class="list-empty">{{ text('暂无期限', 'No deadlines') }}</li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><FileSignature :size="19" /><h3>{{ text('关联合同', 'Related contracts') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.contracts" :key="item.id">
              <div><strong>{{ item.title }}</strong><span>{{ item.contractNumber }}<template v-if="item.amount"> · {{ item.currency }} {{ item.amount.toLocaleString() }}</template></span></div>
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.contracts.length" class="list-empty">{{ text('暂无关联合同', 'No related contracts') }}</li>
          </ul>
        </article>

        <article class="panel workspace-card">
          <div class="workspace-heading"><Stamp :size="19" /><h3>{{ text('审批与冲突检索', 'Approvals & conflicts') }}</h3></div>
          <ul class="compact-list">
            <li v-for="item in workspace.approvals.slice(0, 4)" :key="item.id">
              <div><strong>{{ item.processDefinitionKey }}</strong><span>{{ item.businessType }} · {{ dateTime(item.startedAt) }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.decision || item.status, locale) }}</span>
            </li>
            <li v-for="item in workspace.conflicts.slice(0, 4)" :key="item.id">
              <div><strong>{{ item.requestNumber }} · {{ item.proposedMatterTitle }}</strong><span>{{ item.riskLevel || text('待评估', 'Pending review') }}</span></div>
              <span class="status-pill">{{ formatLegalCode(item.decision || item.status, locale) }}</span>
            </li>
            <li v-if="!workspace.approvals.length && !workspace.conflicts.length" class="list-empty">
              {{ text('暂无审批或冲突检索记录', 'No approval or conflict records') }}
            </li>
          </ul>
        </article>

        <article class="panel workspace-card wide-card">
          <div class="workspace-heading"><FileText :size="19" /><h3>{{ text('案件文档与卷宗', 'Documents & archives') }}</h3></div>
          <div class="document-archive-grid">
            <div>
              <h4>{{ text('文档', 'Documents') }}</h4>
              <ul class="compact-list">
                <li v-for="item in workspace.documents.slice(0, 8)" :key="item.id">
                  <div><strong>{{ item.logicalName }}</strong><span>{{ formatLegalCode(item.documentType, locale) }} · V{{ item.versionNumber || '—' }}</span></div>
                  <span class="status-pill">{{ formatLegalCode(item.versionStatus || item.confidentialityLevel, locale) }}</span>
                </li>
                <li v-if="!workspace.documents.length" class="list-empty">{{ text('暂无案件文档', 'No matter documents') }}</li>
              </ul>
            </div>
            <div>
              <h4>{{ text('电子卷宗', 'Archives') }}</h4>
              <ul class="compact-list">
                <li v-for="item in workspace.archives" :key="item.id">
                  <div><strong>{{ item.title }}</strong><span>{{ item.archiveNumber }} · {{ item.itemCount }} {{ text('份文件', 'files') }}</span></div>
                  <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
                </li>
                <li v-if="!workspace.archives.length" class="list-empty">{{ text('暂无电子卷宗', 'No archives') }}</li>
              </ul>
            </div>
          </div>
        </article>

        <article class="panel workspace-card wide-card">
          <div class="workspace-heading"><Activity :size="19" /><h3>{{ text('案件动态', 'Matter activity') }}</h3></div>
          <ol class="activity-list">
            <li v-for="item in workspace.activity" :key="item.id">
              <time>{{ dateTime(item.eventAt) }}</time>
              <div><strong>{{ item.title }}</strong><span>{{ item.description || item.eventType }} · {{ item.createdByName }}</span></div>
            </li>
            <li v-if="!workspace.activity.length" class="list-empty">{{ text('暂无案件动态', 'No matter activity') }}</li>
          </ol>
        </article>
      </div>

      <ElDialog
        v-model="editVisible"
        :title="text('编辑案件资料', 'Edit matter')"
        width="min(760px, 94vw)"
      >
        <form class="dialog-form two-column-form" @submit.prevent="saveMatter">
          <label class="full-field"><span>{{ text('案件名称', 'Matter title') }}</span><input v-model="editForm.title" required maxlength="300" /></label>
          <label><span>{{ text('案件类型', 'Matter type') }}</span><input v-model="editForm.matterType" required maxlength="80" /></label>
          <label><span>{{ text('承办律师', 'Responsible counsel') }}</span><select v-model="editForm.responsibleUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
          <label><span>{{ text('承办办公室', 'Lead office') }}</span><select v-model="editForm.officeId" required><option v-for="office in offices" :key="office.id" :value="office.id">{{ isEnglish ? office.nameEn : office.nameZh }}</option></select></label>
          <label><span>{{ text('立案日期', 'Opened date') }}</span><input v-model="editForm.openedAt" type="date" /></label>
          <label><span>{{ text('法院 / 机构', 'Court / authority') }}</span><input v-model="editForm.courtName" maxlength="300" /></label>
          <label><span>{{ text('法院案号', 'Court case number') }}</span><input v-model="editForm.caseNumber" maxlength="150" /></label>
          <label><span>{{ text('国家代码', 'Country code') }}</span><input v-model="editForm.countryCode" maxlength="2" /></label>
          <label><span>{{ text('司法辖区', 'Jurisdiction') }}</span><input v-model="editForm.jurisdiction" maxlength="200" /></label>
          <label><span>{{ text('工作语言', 'Working language') }}</span><select v-model="editForm.workingLanguage"><option value="zh-CN">中文 Chinese</option><option value="en-US">English 英文</option><option value="ar">العربية Arabic</option></select></label>
          <label><span>{{ text('结算币种', 'Billing currency') }}</span><input v-model="editForm.billingCurrency" maxlength="3" /></label>
          <label class="full-field"><span>{{ text('案件说明', 'Description') }}</span><textarea v-model="editForm.description" rows="4" maxlength="4000" /></label>
          <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? text('保存中…', 'Saving…') : text('保存案件', 'Save matter') }}</button>
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

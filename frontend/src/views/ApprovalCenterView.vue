<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  BellRing,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  Clock3,
  FileCheck2,
  History,
  RefreshCw,
  Send,
  ShieldCheck,
  Stamp,
  XCircle,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface WorkflowTask {
  id: string
  name: string
  processInstanceId: string
  businessKey?: string
  businessType: string
  businessId: string
  assignee?: string
  candidateGroup?: string
  status: string
  createdAt: string
  completedAt?: string
  decision?: string
}

interface TransferTarget {
  userId: string
  username: string
  displayName: string
}

interface SealRequest {
  id: string
  sealName: string
  purpose: string
  copies: number
  status: string
  requestedByName: string
  createdAt: string
}

type ApprovalAction = 'APPROVE' | 'REJECT' | 'TRANSFER'
type DeskTab = 'pending' | 'history' | 'seal'

const tasks = ref<WorkflowTask[]>([])
const history = ref<WorkflowTask[]>([])
const sealRequests = ref<SealRequest[]>([])
const loading = ref(true)
const processing = ref('')
const reminding = ref('')
const actionVisible = ref(false)
const selectedTask = ref<WorkflowTask | null>(null)
const action = ref<ApprovalAction>('APPROVE')
const comment = ref('')
const targetUserId = ref('')
const transferTargets = ref<TransferTarget[]>([])
const activeTab = ref<DeskTab>('pending')
const historyFilter = ref<'ALL' | 'APPROVED' | 'REJECTED'>('ALL')
const { locale, tp } = useI18n()
const route = useRoute()
const router = useRouter()

const actionTitle = computed(() => {
  if (action.value === 'APPROVE') return t('copy.0021')
  if (action.value === 'REJECT') return t('copy.0022')
  return t('copy.0023')
})
const historyItems = computed(() => historyFilter.value === 'ALL'
  ? history.value
  : history.value.filter((item) => item.status === historyFilter.value))
const submittedSeals = computed(() =>
  sealRequests.value.filter((item) => ['SUBMITTED', 'APPROVED'].includes(item.status)).length,
)
const oldestHours = computed(() => {
  if (!tasks.value.length) return 0
  return Math.max(...tasks.value.map((task) =>
    Math.floor((Date.now() - new Date(task.createdAt).getTime()) / 3_600_000),
  ))
})

async function load() {
  loading.value = true
  try {
    const [taskResponse, approvedResponse, rejectedResponse, sealResponse] = await Promise.all([
      http.get<WorkflowTask[]>('/workflows/tasks'),
      http.get<WorkflowTask[]>('/workflows/inbox?status=APPROVED'),
      http.get<WorkflowTask[]>('/workflows/inbox?status=REJECTED'),
      http.get<SealRequest[]>('/seals/requests'),
    ])
    tasks.value = taskResponse.data
    history.value = [...approvedResponse.data, ...rejectedResponse.data]
      .sort((a, b) => new Date(b.completedAt ?? b.createdAt).getTime()
        - new Date(a.completedAt ?? a.createdAt).getTime())
    sealRequests.value = sealResponse.data
    const requestedId = typeof route.query.taskId === 'string' ? route.query.taskId : ''
    selectedTask.value = tasks.value.find((task) => task.id === requestedId)
      ?? tasks.value[0]
      ?? null
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0024'))
  } finally {
    loading.value = false
  }
}

async function selectTask(task: WorkflowTask) {
  selectedTask.value = task
  await router.replace({ query: { ...route.query, taskId: task.id } })
}

async function openAction(task: WorkflowTask, nextAction: ApprovalAction) {
  selectedTask.value = task
  action.value = nextAction
  comment.value = ''
  targetUserId.value = ''
  transferTargets.value = []
  if (nextAction === 'TRANSFER') {
    try {
      transferTargets.value = (await http.get<TransferTarget[]>(
        `/workflows/tasks/${task.id}/transfer-targets`,
      )).data
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : t('copy.0025'))
      return
    }
  }
  actionVisible.value = true
}

async function submitAction() {
  const task = selectedTask.value
  if (!task) return
  if (action.value === 'REJECT' && !comment.value.trim()) {
    ElMessage.warning(t('copy.0026'))
    return
  }
  if (action.value === 'TRANSFER' && !targetUserId.value) {
    ElMessage.warning(t('copy.0027'))
    return
  }
  processing.value = task.id
  try {
    const headers = { 'Idempotency-Key': crypto.randomUUID() }
    if (action.value === 'TRANSFER') {
      await http.post(`/workflows/tasks/${task.id}/transfer`, {
        targetUserId: targetUserId.value,
        comment: comment.value.trim() || null,
      }, { headers })
    } else {
      await http.post(`/workflows/tasks/${task.id}/complete`, {
        decision: action.value,
        comment: comment.value.trim() || null,
        variables: { approved: action.value === 'APPROVE' },
      }, { headers })
    }
    actionVisible.value = false
    ElMessage.success(
      action.value === 'APPROVE'
        ? t('copy.0028')
        : action.value === 'REJECT'
          ? t('copy.0029')
          : t('copy.0030'),
    )
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0031'))
  } finally {
    processing.value = ''
  }
}

async function remind(task: WorkflowTask) {
  reminding.value = task.id
  try {
    await http.post(`/workflows/tasks/${task.id}/remind`)
    ElMessage.success(t('copy.0032'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0033'))
  } finally {
    reminding.value = ''
  }
}

function fullDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function ageLabel(value: string) {
  const hours = Math.max(0, Math.floor((Date.now() - new Date(value).getTime()) / 3_600_000))
  if (hours < 1) return t('ap.justEntered')
  if (hours < 24) return tp('ap.waitingHours', { count: hours })
  const days = Math.floor(hours / 24)
  return tp('ap.waitingDays', { count: days })
}

onMounted(load)
</script>

<template>
  <section class="approval-desk">
    <header class="approval-hero">
      <div>
        <span class="eyebrow">DECISION & EVIDENCE</span>
        <h2>{{ t('ap.heroTitle') }}</h2>
        <p>{{ t('ap.heroDescription') }}</p>
      </div>
      <button class="refresh-action" :disabled="loading" @click="load">
        <RefreshCw :size="16" :class="{ spinning: loading }" />
        {{ t('ap.refresh') }}
      </button>
    </header>

    <section class="approval-metrics" :aria-label="t('ap.overview')">
      <article><FileCheck2 :size="18" /><div><span>{{ t('ap.forMe') }}</span><strong>{{ tasks.length }}</strong></div></article>
      <article :class="{ danger: oldestHours >= 24 }"><Clock3 :size="18" /><div><span>{{ t('ap.oldest') }}</span><strong>{{ oldestHours }}h</strong></div></article>
      <article><History :size="18" /><div><span>{{ t('ap.recent') }}</span><strong>{{ history.length }}</strong></div></article>
      <article><Stamp :size="18" /><div><span>{{ t('ap.sealFlow') }}</span><strong>{{ submittedSeals }}</strong></div></article>
    </section>

    <nav class="desk-tabs" :aria-label="t('ap.sections')">
      <button :class="{ active: activeTab === 'pending' }" @click="activeTab = 'pending'">
        <FileCheck2 :size="15" /> {{ t('ap.pending') }} <span>{{ tasks.length }}</span>
      </button>
      <button :class="{ active: activeTab === 'history' }" @click="activeTab = 'history'">
        <History :size="15" /> {{ t('ap.history') }} <span>{{ history.length }}</span>
      </button>
      <button :class="{ active: activeTab === 'seal' }" @click="activeTab = 'seal'">
        <Stamp :size="15" /> {{ t('ap.seals') }} <span>{{ sealRequests.length }}</span>
      </button>
    </nav>

    <div v-if="activeTab === 'pending'" class="decision-layout">
      <section class="decision-queue">
        <header class="queue-heading">
          <div><span class="eyebrow">MY QUEUE</span><h3>{{ t('ap.queueTitle') }}</h3></div>
          <span>{{ tasks.length }} {{ t('ap.items') }}</span>
        </header>
        <div v-if="loading" class="approval-empty">{{ t('ap.loading') }}</div>
        <article
          v-for="task in tasks"
          v-else
          :key="task.id"
          class="approval-row task-row"
          :class="{ selected: selectedTask?.id === task.id }"
          @click="selectTask(task)"
        >
          <span class="approval-icon"><FileCheck2 :size="18" /></span>
          <span class="approval-copy">
            <span class="approval-meta">{{ formatLegalCode(task.businessType, locale) }} · {{ ageLabel(task.createdAt) }}</span>
            <strong>{{ task.name }}</strong>
            <small>{{ task.businessKey || task.processInstanceId }}</small>
          </span>
          <span class="row-actions">
            <button :aria-label="t('copy.0039')" @click.stop="openAction(task, 'APPROVE')"><CheckCircle2 :size="14" /></button>
            <button class="reject" :aria-label="t('copy.0040')" @click.stop="openAction(task, 'REJECT')"><XCircle :size="14" /></button>
            <button :aria-label="t('copy.0041')" @click.stop="openAction(task, 'TRANSFER')"><Send :size="14" /></button>
            <button :aria-label="t('copy.0042')" :disabled="reminding === task.id" @click.stop="remind(task)"><BellRing :size="14" /></button>
          </span>
          <ChevronRight :size="16" />
        </article>
        <div v-if="!loading && !tasks.length" class="approval-empty">
          <CheckCircle2 :size="36" />
          <strong>{{ t('copy.0043') }}</strong>
          <span>{{ t('copy.0044') }}</span>
        </div>
      </section>

      <aside class="decision-panel">
        <template v-if="selectedTask">
          <header>
            <span class="eyebrow">{{ formatLegalCode(selectedTask.businessType, locale) }}</span>
            <h3>{{ selectedTask.name }}</h3>
            <p>{{ selectedTask.businessKey || selectedTask.processInstanceId }}</p>
          </header>
          <dl>
            <div><dt>{{ t('ap.entered') }}</dt><dd>{{ fullDate(selectedTask.createdAt) }}</dd></div>
            <div><dt>{{ t('ap.assignee') }}</dt><dd>{{ selectedTask.assignee || formatLegalCode(selectedTask.candidateGroup, locale) }}</dd></div>
            <div><dt>{{ t('ap.businessType') }}</dt><dd>{{ formatLegalCode(selectedTask.businessType, locale) }}</dd></div>
            <div><dt>{{ t('ap.process') }}</dt><dd>{{ selectedTask.processInstanceId }}</dd></div>
          </dl>
          <div class="decision-note">
            <ShieldCheck :size="17" />
            <p><strong>{{ t('ap.syncTitle') }}</strong><span>{{ t('ap.syncHint') }}</span></p>
          </div>
          <footer class="decision-actions">
            <button class="approve-action" :aria-label="t('copy.0039')" @click="openAction(selectedTask, 'APPROVE')"><CheckCircle2 :size="16" /> {{ t('copy.0039') }}</button>
            <button class="reject-action" :aria-label="t('copy.0040')" @click="openAction(selectedTask, 'REJECT')"><XCircle :size="16" /> {{ t('copy.0040') }}</button>
            <button class="secondary-action" :aria-label="t('copy.0041')" @click="openAction(selectedTask, 'TRANSFER')"><Send :size="15" /> {{ t('copy.0041') }}</button>
            <button class="secondary-action" :aria-label="t('copy.0042')" :disabled="reminding === selectedTask.id" @click="remind(selectedTask)"><BellRing :size="15" /> {{ t('copy.0042') }}</button>
          </footer>
        </template>
        <div v-else class="approval-empty"><FileCheck2 :size="34" /><strong>{{ t('ap.select') }}</strong></div>
      </aside>
    </div>

    <section v-else-if="activeTab === 'history'" class="history-panel">
      <header class="queue-heading">
        <div><span class="eyebrow">DECISION LEDGER</span><h3>{{ t('ap.historyTitle') }}</h3></div>
        <select v-model="historyFilter">
          <option value="ALL">{{ t('ap.allOutcomes') }}</option>
          <option value="APPROVED">{{ formatLegalCode('APPROVED', locale) }}</option>
          <option value="REJECTED">{{ formatLegalCode('REJECTED', locale) }}</option>
        </select>
      </header>
      <div class="history-table">
        <article v-for="item in historyItems" :key="`${item.id}-${item.status}`">
          <span class="history-mark" :class="{ rejected: item.status === 'REJECTED' }"><component :is="item.status === 'REJECTED' ? XCircle : CheckCircle2" :size="17" /></span>
          <span><strong>{{ item.name }}</strong><small>{{ formatLegalCode(item.businessType, locale) }} · {{ item.businessKey || item.processInstanceId }}</small></span>
          <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
          <time>{{ fullDate(item.completedAt || item.createdAt) }}</time>
        </article>
        <div v-if="!historyItems.length" class="approval-empty">{{ t('ap.noHistory') }}</div>
      </div>
    </section>

    <section v-else class="seal-panel">
      <header class="queue-heading">
        <div><span class="eyebrow">SEAL REGISTER</span><h3>{{ t('ap.sealTitle') }}</h3></div>
        <Stamp :size="20" />
      </header>
      <div class="seal-grid">
        <article v-for="item in sealRequests" :key="item.id">
          <div><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span><time>{{ fullDate(item.createdAt) }}</time></div>
          <h4>{{ item.purpose }}</h4>
          <p>{{ item.sealName }} · {{ item.copies }} {{ t('copy.0046') }}</p>
          <small>{{ t('ap.requestedBy') }} · {{ item.requestedByName }}</small>
        </article>
        <div v-if="!sealRequests.length" class="approval-empty">{{ t('copy.0047') }}</div>
      </div>
    </section>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="min(540px, 92vw)">
      <div v-if="selectedTask" class="action-dialog">
        <div class="action-context">
          <span class="approval-icon"><FileCheck2 :size="18" /></span>
          <div><strong>{{ selectedTask.name }}</strong><span>{{ formatLegalCode(selectedTask.businessType, locale) }} · {{ selectedTask.businessKey }}</span></div>
        </div>
        <label v-if="action === 'TRANSFER'">
          <span>{{ t('copy.0048') }}</span>
          <select v-model="targetUserId" data-testid="transfer-target">
            <option value="">{{ t('copy.0049') }}</option>
            <option v-for="target in transferTargets" :key="target.userId" :value="target.userId">
              {{ target.displayName }} · {{ target.username }}
            </option>
          </select>
          <small v-if="!transferTargets.length">{{ t('copy.0050') }}</small>
        </label>
        <label>
          <span>{{ action === 'REJECT' ? t('copy.0051') : t('copy.0052') }}</span>
          <textarea v-model="comment" rows="5" maxlength="500" :placeholder="t('copy.0053')" />
        </label>
        <div v-if="action === 'REJECT'" class="reject-warning"><CircleAlert :size="15" /> {{ t('ap.rejectWarning') }}</div>
        <button class="primary-action full" :disabled="processing === selectedTask.id" @click="submitAction">
          {{ processing === selectedTask.id ? t('copy.0054') : actionTitle }}
        </button>
      </div>
    </ElDialog>
  </section>
</template>

<style scoped>
.approval-desk { display: grid; gap: 18px; }
.approval-hero { position: relative; overflow: hidden; display: flex; justify-content: space-between; gap: 24px; align-items: flex-end; padding: 28px 30px; color: #f7f3e9; background:
  radial-gradient(circle at 90% 0, rgba(198,166,95,.18), transparent 36%),
  linear-gradient(125deg, #142e28, #1a4439); border: 1px solid #2a5146; border-radius: 16px; }
.approval-hero h2 { margin: 8px 0; color: #fffdf8; font-family: Georgia, "Songti SC", serif; font-size: clamp(27px, 3vw, 38px); font-weight: 500; }
.approval-hero p { max-width: 680px; margin: 0; color: rgba(246,243,234,.7); font-size: 11px; line-height: 1.8; }
.approval-hero .eyebrow { color: #d1b675; }
.refresh-action { display: inline-flex; gap: 7px; align-items: center; min-height: 38px; padding: 0 14px; color: #183c33; background: #f3eee1; border: 0; border-radius: 9px; cursor: pointer; }
.spinning { animation: spin 1s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
.approval-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.approval-metrics article { display: flex; gap: 12px; align-items: center; padding: 16px 18px; background: var(--paper-light); border: 1px solid var(--line); border-radius: 12px; }
.approval-metrics svg { color: #315e50; }
.approval-metrics div { display: flex; flex: 1; align-items: baseline; justify-content: space-between; }
.approval-metrics span { color: var(--muted); font-size: 9px; }
.approval-metrics strong { font-family: Georgia, serif; font-size: 24px; font-weight: 500; }
.approval-metrics article.danger { color: #87372f; border-color: #e3c3bb; background: #fff9f6; }
.approval-metrics article.danger svg { color: #87372f; }
.desk-tabs { display: flex; gap: 6px; padding: 5px; background: #e9e7df; border: 1px solid var(--line); border-radius: 11px; }
.desk-tabs button { display: inline-flex; gap: 7px; align-items: center; min-height: 38px; padding: 0 14px; color: var(--muted); background: transparent; border: 0; border-radius: 8px; cursor: pointer; }
.desk-tabs button.active { color: #214d40; background: var(--paper-light); box-shadow: 0 1px 4px rgba(28,49,41,.08); }
.desk-tabs button span { min-width: 18px; padding: 2px 5px; color: inherit; background: rgba(44,93,78,.09); border-radius: 999px; font-size: 8px; }
.decision-layout { display: grid; grid-template-columns: minmax(360px, .85fr) minmax(480px, 1.15fr); gap: 16px; align-items: start; }
.decision-queue, .decision-panel, .history-panel, .seal-panel { overflow: hidden; background: var(--paper-light); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-sm); }
.decision-queue, .decision-panel { min-height: 520px; }
.queue-heading { display: flex; align-items: center; justify-content: space-between; gap: 14px; padding: 20px 21px; border-bottom: 1px solid var(--line); }
.queue-heading h3 { margin: 5px 0 0; font-family: Georgia, "Songti SC", serif; font-size: 18px; font-weight: 500; }
.queue-heading > span { color: var(--muted); font-size: 9px; }
.queue-heading select { min-width: 150px; }
.approval-row.task-row { display: grid; grid-template-columns: 38px minmax(0, 1fr) auto auto; gap: 12px; align-items: center; min-height: 80px; padding: 13px 17px; border-bottom: 1px solid var(--line); cursor: pointer; }
.approval-row.task-row:hover { background: #f5f3ed; }
.approval-row.task-row.selected { background: #edf2ee; box-shadow: inset 3px 0 #315e50; }
.approval-icon { display: grid; width: 36px; height: 36px; place-items: center; color: #315e50; background: #e8efea; border-radius: 9px; }
.approval-copy { min-width: 0; display: grid; gap: 5px; }
.approval-copy strong, .approval-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.approval-copy strong { font-size: 11px; }
.approval-copy small { color: var(--muted); font-size: 8px; }
.approval-meta { color: #836d3f; font-size: 8px; text-transform: uppercase; letter-spacing: .05em; }
.row-actions { display: flex; gap: 4px; }
.row-actions button { display: grid; width: 29px; height: 29px; place-items: center; color: #315e50; background: #f5f5ef; border: 1px solid var(--line); border-radius: 7px; cursor: pointer; }
.row-actions button.reject { color: #8a3d35; }
.decision-panel { position: sticky; top: 92px; padding: 25px; }
.decision-panel h3 { margin: 8px 0; font-family: Georgia, "Songti SC", serif; font-size: 25px; font-weight: 500; }
.decision-panel header p { margin: 0; color: var(--muted); font-size: 9px; }
.decision-panel dl { display: grid; grid-template-columns: repeat(2, 1fr); margin: 22px 0; border: 1px solid var(--line); border-radius: 10px; }
.decision-panel dl div { padding: 13px 15px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.decision-panel dl div:nth-child(2n) { border-right: 0; }
.decision-panel dl div:nth-last-child(-n+2) { border-bottom: 0; }
.decision-panel dt { color: var(--muted); font-size: 8px; text-transform: uppercase; letter-spacing: .07em; }
.decision-panel dd { overflow: hidden; margin: 5px 0 0; font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.decision-note { display: flex; gap: 11px; padding: 14px; color: #315e50; background: #eef2ec; border-radius: 9px; }
.decision-note p { margin: 0; }
.decision-note strong, .decision-note span { display: block; }
.decision-note strong { font-size: 9px; }
.decision-note span { margin-top: 5px; color: #65736d; font-size: 8px; line-height: 1.55; }
.decision-actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 22px; padding-top: 18px; border-top: 1px solid var(--line); }
.approve-action, .reject-action { display: inline-flex; gap: 7px; align-items: center; min-height: 38px; padding: 0 14px; color: #fff; border: 0; border-radius: 8px; cursor: pointer; }
.approve-action { background: #28584a; }
.reject-action { background: #8b3c33; }
.history-table article { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto 120px; gap: 13px; align-items: center; min-height: 74px; padding: 12px 18px; border-bottom: 1px solid var(--line); }
.history-mark { display: grid; width: 32px; height: 32px; place-items: center; color: #34705e; background: #e8f1eb; border-radius: 50%; }
.history-mark.rejected { color: #8a3d35; background: #f7e7e2; }
.history-table strong, .history-table small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-table strong { font-size: 10px; }
.history-table small, .history-table time { margin-top: 4px; color: var(--muted); font-size: 8px; }
.history-table time { text-align: right; }
.seal-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; padding: 18px; }
.seal-grid article { padding: 17px; background: #f7f5ef; border: 1px solid var(--line); border-radius: 10px; }
.seal-grid article > div { display: flex; align-items: center; justify-content: space-between; }
.seal-grid time { color: var(--muted); font-size: 8px; }
.seal-grid h4 { margin: 16px 0 8px; font-family: Georgia, "Songti SC", serif; font-size: 16px; font-weight: 500; }
.seal-grid p, .seal-grid small { color: var(--muted); font-size: 9px; }
.seal-grid small { display: block; margin-top: 13px; padding-top: 11px; border-top: 1px solid var(--line); }
.approval-empty { min-height: 240px; display: flex; flex-direction: column; gap: 8px; align-items: center; justify-content: center; padding: 30px; color: var(--muted); font-size: 10px; text-align: center; }
.approval-empty strong { color: var(--ink); font-size: 12px; }
.action-dialog { display: grid; gap: 18px; }
.action-context { display: flex; gap: 11px; align-items: center; padding: 14px; background: #efede6; border-radius: 9px; }
.action-context div { min-width: 0; }
.action-context strong, .action-context span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.action-context strong { font-size: 11px; }
.action-context span { margin-top: 5px; color: var(--muted); font-size: 8px; }
.action-dialog label { display: grid; gap: 7px; }
.action-dialog label > span { color: var(--muted); font-size: 9px; }
.action-dialog label > small { color: #8a3d35; font-size: 8px; }
.reject-warning { display: flex; gap: 8px; align-items: flex-start; padding: 11px; color: #81382f; background: #fff6f2; border: 1px solid #eccdc5; border-radius: 8px; font-size: 8px; line-height: 1.5; }
.full { width: 100%; justify-content: center; }

@media (max-width: 1000px) {
  .approval-metrics { grid-template-columns: repeat(2, 1fr); }
  .decision-layout { grid-template-columns: minmax(310px, .8fr) minmax(400px, 1.2fr); }
}
@media (max-width: 760px) {
  .approval-hero { align-items: flex-start; flex-direction: column; }
  .decision-layout { grid-template-columns: 1fr; }
  .decision-panel { position: static; }
  .seal-grid { grid-template-columns: 1fr; }
}
@media (max-width: 520px) {
  .approval-metrics { grid-template-columns: 1fr 1fr; }
  .desk-tabs { overflow-x: auto; }
  .desk-tabs button { flex: 0 0 auto; }
  .decision-panel dl { grid-template-columns: 1fr; }
  .decision-panel dl div { border-right: 0; }
  .history-table article { grid-template-columns: 32px minmax(0, 1fr) auto; }
  .history-table time { grid-column: 2 / 4; text-align: left; }
  .approval-row.task-row { grid-template-columns: 34px minmax(0, 1fr) auto; }
  .approval-row.task-row > svg { display: none; }
  .row-actions { grid-column: 2 / 4; }
}
</style>

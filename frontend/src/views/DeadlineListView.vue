<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  AlertTriangle,
  CalendarClock,
  CheckCircle2,
  Clock3,
  History,
  Pencil,
  Plus,
  RefreshCw,
  RotateCcw,
  Search,
  ShieldCheck,
  UserRound,
  XCircle,
} from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Deadline {
  id: string
  matterId: string
  matterNumber: string
  matterTitle: string
  title: string
  dueAt: string
  deadlineType: string
  priority: string
  status: string
  ownerUserId: string
  ownerName: string
  reminderPolicy: string
  sourceType: string
  sourceReference?: string
  calculationNote?: string
  createdByName: string
  completedAt?: string
  completedByName?: string
  completionNote?: string
  cancelledAt?: string
  cancelledByName?: string
  cancellationReason?: string
  version: number
  eventCount: number
  reminderCount: number
}

interface DeadlineEvent {
  id: string
  action: string
  actorDisplayName: string
  fromStatus?: string
  toStatus?: string
  note?: string
  occurredAt: string
}

interface DeadlineReminder {
  id: string
  recipientName: string
  reminderDate: string
  daysBefore: number
  createdAt: string
}

interface DeadlineDetail {
  deadline: Deadline
  events: DeadlineEvent[]
  reminders: DeadlineReminder[]
}

interface MatterTeam {
  team: Array<{ userId: string; displayName: string; memberRole: string }>
}

const copyKeys = [
  'kicker', 'title', 'subtitle', 'guide', 'new', 'register', 'search', 'all',
  'open', 'overdue', 'dueSoon', 'completed', 'cancelled', 'loading', 'empty',
  'select', 'matter', 'owner', 'dueAt', 'type', 'priority', 'reminders',
  'source', 'sourceReference', 'calculation', 'deadlineName', 'evidence', 'dispatches',
  'noEvents', 'noDispatches', 'edit', 'complete', 'cancel', 'reopen',
  'save', 'saving', 'required', 'futureRequired', 'actionReason',
  'completePrompt', 'cancelPrompt', 'reopenPrompt', 'success', 'failed',
  'refresh', 'riskHint', 'sourceHint', 'registeredBy', 'version', 'close',
  'courtOrder', 'statute', 'clientSource', 'internalSource', 'otherSource',
] as const
const copy = computed(() => Object.fromEntries(
  copyKeys.map((key) => [key, t(`deadlineOps.${key}`)]),
) as Record<(typeof copyKeys)[number], string>)

const { locale } = useI18n()
const deadlines = ref<Deadline[]>([])
const detail = ref<DeadlineDetail | null>(null)
const matters = ref<Matter[]>([])
const currentUser = ref<CurrentUser | null>(null)
const matterTeam = ref<MatterTeam['team']>([])
const selectedId = ref('')
const query = ref('')
const statusFilter = ref('')
const loading = ref(true)
const detailLoading = ref(false)
const teamLoading = ref(false)
const saving = ref(false)
const actionId = ref('')
const dialogVisible = ref(false)
const editingId = ref('')

const form = reactive({
  expectedVersion: 0,
  matterId: '',
  title: '',
  dueAt: '',
  deadlineType: 'COURT',
  ownerUserId: '',
  priority: 'NORMAL',
  reminderDays: '7,3,1',
  sourceType: 'COURT_ORDER',
  sourceReference: '',
  calculationNote: '',
})

const now = () => Date.now()
const filtered = computed(() => deadlines.value.filter((item) => {
  const text = `${item.title} ${item.matterNumber} ${item.matterTitle} ${item.ownerName}`.toLowerCase()
  return (!query.value.trim() || text.includes(query.value.trim().toLowerCase()))
    && (!statusFilter.value || item.status === statusFilter.value)
}))
const metric = (status: string) => deadlines.value.filter((item) => item.status === status).length
const dueSoonCount = computed(() => deadlines.value.filter((item) => {
  const left = new Date(item.dueAt).getTime() - now()
  return ['OPEN', 'OVERDUE'].includes(item.status) && left >= 0 && left <= 7 * 86_400_000
}).length)
const selected = computed(() => detail.value?.deadline)
const canManage = computed(() => currentUser.value?.permissions.includes('DEADLINE_MANAGE') ?? false)

async function load() {
  loading.value = true
  try {
    const [deadlineResponse, matterResponse, meResponse] = await Promise.all([
      http.get<Deadline[]>('/deadlines'),
      http.get<Matter[]>('/matters'),
      http.get<CurrentUser>('/me'),
    ])
    deadlines.value = deadlineResponse.data
    matters.value = matterResponse.data
    currentUser.value = meResponse.data
    const target = selectedId.value && deadlines.value.some((item) => item.id === selectedId.value)
      ? selectedId.value
      : deadlines.value[0]?.id
    if (target) await selectDeadline(target)
    else {
      selectedId.value = ''
      detail.value = null
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    loading.value = false
  }
}

async function selectDeadline(id: string) {
  selectedId.value = id
  detailLoading.value = true
  try {
    detail.value = (await http.get<DeadlineDetail>(`/deadlines/${id}`)).data
  } catch (error) {
    detail.value = null
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    detailLoading.value = false
  }
}

async function loadMatterTeam(matterId: string, preferredOwner = '') {
  matterTeam.value = []
  if (!matterId) return
  teamLoading.value = true
  try {
    matterTeam.value = (await http.get<MatterTeam>(`/matters/${matterId}/workspace`)).data.team
    if (!matterTeam.value.some((member) => member.userId === preferredOwner)) {
      form.ownerUserId = matterTeam.value.find((member) => member.userId === currentUser.value?.userId)?.userId
        ?? matterTeam.value[0]?.userId
        ?? ''
    } else {
      form.ownerUserId = preferredOwner
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    teamLoading.value = false
  }
}

function reminderDays(policy?: string) {
  try {
    return JSON.parse(policy || '{}').daysBefore?.join(', ') || '7, 3, 1'
  } catch {
    return '7, 3, 1'
  }
}

function localDateTime(instant?: string) {
  if (!instant) return ''
  const date = new Date(instant)
  return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
}

async function openForm(deadline?: Deadline) {
  editingId.value = deadline?.id ?? ''
  Object.assign(form, {
    expectedVersion: deadline?.version ?? 0,
    matterId: deadline?.matterId ?? matters.value[0]?.id ?? '',
    title: deadline?.title ?? '',
    dueAt: localDateTime(deadline?.dueAt),
    deadlineType: deadline?.deadlineType ?? 'COURT',
    ownerUserId: deadline?.ownerUserId ?? '',
    priority: deadline?.priority ?? 'NORMAL',
    reminderDays: reminderDays(deadline?.reminderPolicy),
    sourceType: deadline?.sourceType ?? 'COURT_ORDER',
    sourceReference: deadline?.sourceReference ?? '',
    calculationNote: deadline?.calculationNote ?? '',
  })
  await loadMatterTeam(form.matterId, deadline?.ownerUserId)
  dialogVisible.value = true
}

async function save() {
  if (!form.matterId || !form.title.trim() || !form.dueAt || !form.ownerUserId) {
    ElMessage.warning(copy.value.required)
    return
  }
  const dueAt = new Date(form.dueAt)
  if (dueAt.getTime() <= now()) {
    ElMessage.warning(copy.value.futureRequired)
    return
  }
  const reminderDaysBefore = form.reminderDays.split(/[，,]/)
    .map((value) => Number.parseInt(value.trim(), 10))
    .filter((value) => Number.isInteger(value) && value >= 0 && value <= 365)
  saving.value = true
  try {
    const payload = {
      ...(editingId.value ? { expectedVersion: form.expectedVersion } : {}),
      matterId: form.matterId,
      title: form.title.trim(),
      dueAt: dueAt.toISOString(),
      deadlineType: form.deadlineType,
      ownerUserId: form.ownerUserId,
      priority: form.priority,
      reminderDaysBefore,
      sourceType: form.sourceType,
      sourceReference: form.sourceReference.trim() || null,
      calculationNote: form.calculationNote.trim() || null,
    }
    const response = editingId.value
      ? await http.put<DeadlineDetail>(`/deadlines/${editingId.value}`, payload)
      : await http.post<DeadlineDetail>('/deadlines', payload)
    dialogVisible.value = false
    selectedId.value = response.data.deadline.id
    await load()
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

async function lifecycle(action: 'complete' | 'cancel' | 'reopen') {
  if (!selected.value) return
  const prompts = {
    complete: copy.value.completePrompt,
    cancel: copy.value.cancelPrompt,
    reopen: copy.value.reopenPrompt,
  }
  try {
    const result = await ElMessageBox.prompt(prompts[action], copy.value.actionReason, {
      inputType: 'textarea',
      inputValidator: (value) => Boolean(value?.trim()) || copy.value.required,
      confirmButtonText: formatLegalCode(action.toUpperCase(), locale.value),
      cancelButtonText: copy.value.close,
    })
    actionId.value = action
    const response = await http.post<DeadlineDetail>(`/deadlines/${selected.value.id}/${action}`, {
      expectedVersion: selected.value.version,
      note: result.value.trim(),
    })
    detail.value = response.data
    await load()
    ElMessage.success(copy.value.success)
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
    }
  } finally {
    actionId.value = ''
  }
}

function dateTime(value?: string) {
  return value
    ? new Intl.DateTimeFormat(locale.value, {
      month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit',
    }).format(new Date(value))
    : '—'
}

function daysLabel(item: Deadline) {
  if (item.status === 'COMPLETED') return copy.value.completed
  if (item.status === 'CANCELLED') return copy.value.cancelled
  const days = Math.ceil((new Date(item.dueAt).getTime() - now()) / 86_400_000)
  if (days < 0) return t('copy.dynamic.daysOverdue').replace('{count}', String(-days))
  return t('copy.dynamic.daysLeft').replace('{count}', String(days))
}

watch(() => form.matterId, (matterId, previous) => {
  if (dialogVisible.value && matterId && matterId !== previous) loadMatterTeam(matterId)
})

onMounted(load)
</script>

<template>
  <section class="module-page deadline-workbench">
    <header class="deadline-hero">
      <div>
        <span class="eyebrow">{{ copy.kicker }}</span>
        <h2>{{ copy.title }}</h2>
        <p>{{ copy.subtitle }}</p>
      </div>
      <button class="primary-action" :disabled="loading" @click="openForm()"><Plus :size="17" /> {{ copy.new }}</button>
    </header>

    <div class="workflow-guide">
      <ShieldCheck :size="17" />
      <span>{{ copy.guide }}</span>
      <small>{{ copy.riskHint }}</small>
    </div>

    <div class="deadline-metrics">
      <button :class="{ active: statusFilter === 'OPEN' }" @click="statusFilter = statusFilter === 'OPEN' ? '' : 'OPEN'"><small>{{ copy.open }}</small><strong>{{ metric('OPEN') }}</strong></button>
      <button class="risk" :class="{ active: statusFilter === 'OVERDUE' }" @click="statusFilter = statusFilter === 'OVERDUE' ? '' : 'OVERDUE'"><small>{{ copy.overdue }}</small><strong>{{ metric('OVERDUE') }}</strong></button>
      <button><small>{{ copy.dueSoon }}</small><strong>{{ dueSoonCount }}</strong></button>
      <button :class="{ active: statusFilter === 'COMPLETED' }" @click="statusFilter = statusFilter === 'COMPLETED' ? '' : 'COMPLETED'"><small>{{ copy.completed }}</small><strong>{{ metric('COMPLETED') }}</strong></button>
    </div>

    <div class="deadline-layout">
      <aside class="deadline-register panel">
        <div class="panel-heading">
          <div><CalendarClock :size="18" /><h3>{{ copy.register }}</h3></div>
          <button class="icon-action" :title="copy.refresh" @click="load"><RefreshCw :size="16" /></button>
        </div>
        <div class="register-filters">
          <label><Search :size="15" /><input v-model="query" :placeholder="copy.search" /></label>
          <select v-model="statusFilter" :aria-label="copy.all">
            <option value="">{{ copy.all }}</option>
            <option value="OPEN">{{ copy.open }}</option>
            <option value="OVERDUE">{{ copy.overdue }}</option>
            <option value="COMPLETED">{{ copy.completed }}</option>
            <option value="CANCELLED">{{ copy.cancelled }}</option>
          </select>
        </div>
        <div v-if="loading" class="empty-state">{{ copy.loading }}</div>
        <div v-else-if="filtered.length" class="deadline-list">
          <button
            v-for="item in filtered"
            :key="item.id"
            class="deadline-row"
            :class="{ active: selectedId === item.id, overdue: item.status === 'OVERDUE' }"
            @click="selectDeadline(item.id)"
          >
            <div class="row-date"><strong>{{ dateTime(item.dueAt).slice(0, 5) }}</strong><small>{{ daysLabel(item) }}</small></div>
            <div class="row-copy"><strong>{{ item.title }}</strong><span>{{ item.matterNumber }} · {{ item.matterTitle }}</span><small><UserRound :size="12" />{{ item.ownerName }}</small></div>
            <span class="priority-pill" :class="item.priority.toLowerCase()">{{ formatLegalCode(item.priority, locale) }}</span>
          </button>
        </div>
        <div v-else class="empty-state"><CalendarClock :size="30" /><strong>{{ copy.empty }}</strong></div>
      </aside>

      <main class="deadline-detail panel">
        <div v-if="detailLoading" class="empty-state">{{ copy.loading }}</div>
        <template v-else-if="selected">
          <header class="detail-header">
            <div>
              <div class="detail-tags">
                <span class="status-pill" :class="selected.status.toLowerCase()">{{ formatLegalCode(selected.status, locale) }}</span>
                <span class="status-pill">{{ formatLegalCode(selected.deadlineType, locale) }}</span>
                <span class="status-pill">{{ formatLegalCode(selected.priority, locale) }}</span>
              </div>
              <h3>{{ selected.title }}</h3>
              <p>{{ selected.matterNumber }} · {{ selected.matterTitle }}</p>
            </div>
            <div class="detail-actions">
              <button v-if="['OPEN', 'OVERDUE'].includes(selected.status)" class="secondary-action" @click="openForm(selected)"><Pencil :size="15" />{{ copy.edit }}</button>
              <button v-if="['OPEN', 'OVERDUE'].includes(selected.status)" class="primary-action" :disabled="Boolean(actionId)" @click="lifecycle('complete')"><CheckCircle2 :size="15" />{{ copy.complete }}</button>
              <button v-if="['OPEN', 'OVERDUE'].includes(selected.status)" class="danger-action" :disabled="Boolean(actionId)" @click="lifecycle('cancel')"><XCircle :size="15" />{{ copy.cancel }}</button>
              <button v-if="canManage && ['COMPLETED', 'CANCELLED'].includes(selected.status)" class="secondary-action" :disabled="Boolean(actionId)" @click="lifecycle('reopen')"><RotateCcw :size="15" />{{ copy.reopen }}</button>
            </div>
          </header>

          <dl class="deadline-facts">
            <div><dt>{{ copy.dueAt }}</dt><dd>{{ dateTime(selected.dueAt) }}</dd></div>
            <div><dt>{{ copy.owner }}</dt><dd>{{ selected.ownerName }}</dd></div>
            <div><dt>{{ copy.source }}</dt><dd>{{ formatLegalCode(selected.sourceType, locale) }}</dd></div>
            <div><dt>{{ copy.reminders }}</dt><dd>{{ reminderDays(selected.reminderPolicy) }}</dd></div>
            <div><dt>{{ copy.version }}</dt><dd>V{{ selected.version }}</dd></div>
          </dl>

          <section class="source-card">
            <AlertTriangle :size="19" />
            <div><strong>{{ selected.sourceReference || copy.sourceHint }}</strong><span>{{ selected.calculationNote || '—' }}</span></div>
          </section>

          <section class="detail-section">
            <div class="section-heading"><div><History :size="18" /><h4>{{ copy.evidence }}</h4></div><strong>{{ detail?.events.length }}</strong></div>
            <ol v-if="detail?.events.length" class="event-timeline">
              <li v-for="event in detail.events" :key="event.id">
                <span class="event-dot"></span>
                <div>
                  <strong>{{ formatLegalCode(event.action, locale) }}</strong>
                  <span>{{ event.actorDisplayName }} · {{ dateTime(event.occurredAt) }}</span>
                  <small v-if="event.note">{{ event.note }}</small>
                </div>
              </li>
            </ol>
            <div v-else class="inline-empty"><Clock3 :size="17" />{{ copy.noEvents }}</div>
          </section>

          <section class="detail-section">
            <div class="section-heading"><div><RefreshCw :size="18" /><h4>{{ copy.dispatches }}</h4></div><strong>{{ detail?.reminders.length }}</strong></div>
            <div v-if="detail?.reminders.length" class="dispatch-list">
              <div v-for="item in detail.reminders" :key="item.id">
                <span>{{ item.reminderDate }}</span><strong>{{ item.recipientName }}</strong><small>{{ item.daysBefore }}</small>
              </div>
            </div>
            <div v-else class="inline-empty"><Clock3 :size="17" />{{ copy.noDispatches }}</div>
          </section>
        </template>
        <div v-else class="empty-state detail-empty"><CalendarClock :size="34" /><strong>{{ copy.select }}</strong></div>
      </main>
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? copy.edit : copy.new" width="min(760px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label class="full-field"><span>{{ copy.matter }}</span><select v-model="form.matterId" :disabled="Boolean(editingId)" required><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <label class="full-field"><span>{{ copy.deadlineName }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ copy.dueAt }}</span><input v-model="form.dueAt" type="datetime-local" required /></label>
        <label><span>{{ copy.owner }}</span><select v-model="form.ownerUserId" :disabled="teamLoading" required><option v-for="member in matterTeam" :key="member.userId" :value="member.userId">{{ member.displayName }} · {{ formatLegalCode(member.memberRole, locale) }}</option></select></label>
        <label><span>{{ copy.type }}</span><select v-model="form.deadlineType"><option value="COURT">{{ formatLegalCode('COURT', locale) }}</option><option value="COURT_DEADLINE">{{ formatLegalCode('COURT_DEADLINE', locale) }}</option><option value="ARBITRATION">{{ formatLegalCode('ARBITRATION', locale) }}</option><option value="FILING">{{ formatLegalCode('FILING', locale) }}</option><option value="INTERNAL">{{ formatLegalCode('INTERNAL', locale) }}</option><option value="OTHER">{{ formatLegalCode('OTHER', locale) }}</option></select></label>
        <label><span>{{ copy.priority }}</span><select v-model="form.priority"><option v-for="priority in ['LOW','NORMAL','HIGH','URGENT']" :key="priority" :value="priority">{{ formatLegalCode(priority, locale) }}</option></select></label>
        <label><span>{{ copy.source }}</span><select v-model="form.sourceType"><option value="COURT_ORDER">{{ copy.courtOrder }}</option><option value="STATUTE">{{ copy.statute }}</option><option value="CLIENT">{{ copy.clientSource }}</option><option value="INTERNAL">{{ copy.internalSource }}</option><option value="OTHER">{{ copy.otherSource }}</option></select></label>
        <label><span>{{ copy.reminders }}</span><input v-model="form.reminderDays" placeholder="7,3,1" /></label>
        <label class="full-field"><span>{{ copy.sourceReference }}</span><input v-model="form.sourceReference" maxlength="500" /></label>
        <label class="full-field"><span>{{ copy.calculation }}</span><textarea v-model="form.calculationNote" maxlength="4000"></textarea></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving || teamLoading">{{ saving ? copy.saving : copy.save }}</button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.deadline-workbench { --ink:#17372d; --gold:#a88743; --line:#ded9ce; }
.deadline-hero { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding:4px 0 20px; border-bottom:1px solid var(--line); }
.deadline-hero h2 { margin:8px 0 6px; font-family:var(--font-display); font-size:clamp(28px,3vw,42px); color:var(--ink); }
.deadline-hero p { margin:0; color:#65736e; }
.workflow-guide { display:flex; align-items:center; gap:10px; margin:16px 0 10px; padding:12px 14px; border-left:3px solid var(--gold); background:#f0f4f1; color:#486159; font-size:12px; }
.workflow-guide small { margin-left:auto; color:#8a6a32; }
.deadline-metrics { display:grid; grid-template-columns:repeat(4,1fr); gap:1px; margin-bottom:14px; border:1px solid var(--line); background:var(--line); }
.deadline-metrics button { display:flex; align-items:center; justify-content:space-between; padding:11px 14px; border:0; background:#fffefa; color:#56635d; cursor:pointer; }
.deadline-metrics button:hover,.deadline-metrics button.active { background:#eef4f0; }.deadline-metrics button.risk strong { color:#a7443c; }
.deadline-metrics strong { color:var(--ink); font-size:20px; }
.deadline-layout { display:grid; grid-template-columns:minmax(340px,.9fr) minmax(0,1.65fr); gap:14px; align-items:start; min-width:0; }
.panel { border:1px solid var(--line); border-radius:2px; background:#fffefa; box-shadow:0 8px 24px rgba(25,45,37,.04); }
.deadline-register,.deadline-detail { min-width:0; min-height:680px; padding:18px; }
.panel-heading,.section-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px; }
.panel-heading>div,.section-heading>div { display:flex; align-items:center; gap:9px; color:var(--ink); }.panel-heading h3,.section-heading h4 { margin:0; }.section-heading strong { color:#8a6a32; }
.icon-action { display:grid; place-items:center; border:0; background:transparent; color:#426254; cursor:pointer; }
.register-filters { display:grid; grid-template-columns:1fr 118px; gap:8px; margin-bottom:12px; }.register-filters label { display:flex; align-items:center; gap:7px; padding:0 9px; border:1px solid #d9d5ca; background:#fff; }
.register-filters input,.register-filters select { width:100%; border:1px solid #d9d5ca; background:#fff; padding:9px; color:#2c443b; outline:none; }.register-filters label input { border:0; padding-left:0; }
.deadline-list { display:grid; gap:7px; max-height:600px; overflow:auto; padding-right:3px; }
.deadline-row { display:grid; grid-template-columns:60px minmax(0,1fr) auto; gap:10px; align-items:center; width:100%; padding:12px; border:1px solid #e0ddd3; border-left:3px solid transparent; background:#fff; color:inherit; text-align:left; cursor:pointer; }
.deadline-row:hover,.deadline-row.active { border-color:#7d978b; border-left-color:#2e604d; background:#f3f7f4; }.deadline-row.overdue { border-left-color:#b85b50; }
.row-date { display:grid; gap:3px; color:#28463a; }.row-date strong { font-family:Georgia,serif; font-size:16px; }.row-date small { color:#a04d44; font-size:9px; }
.row-copy { display:grid; gap:4px; min-width:0; }.row-copy strong,.row-copy span { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }.row-copy strong { color:#253f36; }.row-copy span,.row-copy small { color:#5d6a64; font-size:10px; }.row-copy small { display:flex; align-items:center; gap:4px; }
.priority-pill,.status-pill { display:inline-flex; align-items:center; padding:4px 7px; border:1px solid #cdd8d2; background:#edf3ef; color:#214a3b; font-size:10px; white-space:nowrap; }.priority-pill.high,.priority-pill.urgent,.status-pill.overdue { border-color:#e2b9b3; background:#fff0ed; color:#98473d; }.status-pill.completed { border-color:#b9d2c4; background:#eef7f1; }
.detail-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; padding-bottom:18px; border-bottom:1px solid #e6e2d8; }.detail-header h3 { margin:8px 0 6px; color:var(--ink); font-family:var(--font-display); font-size:26px; }.detail-header p { margin:0; color:#64716b; font-size:11px; }
.detail-tags,.detail-actions { display:flex; flex-wrap:wrap; gap:7px; }
.secondary-action,.primary-action,.danger-action { display:inline-flex; align-items:center; justify-content:center; gap:7px; min-height:38px; padding:8px 12px; border-radius:2px; font-weight:650; cursor:pointer; }.secondary-action { border:1px solid #becbc5; background:#f8faf8; color:#2d5747; }.primary-action { border:1px solid #183d30; background:#183d30; color:#fff; }.danger-action { border:1px solid #c89891; background:#fff8f6; color:#954c43; }
.secondary-action:disabled,.primary-action:disabled,.danger-action:disabled { opacity:.45; cursor:not-allowed; }.full { width:100%; }
.deadline-facts { display:grid; grid-template-columns:1.25fr 1fr 1fr .8fr .45fr; margin:14px 0; border:1px solid #e3dfd5; background:#f9f8f3; }.deadline-facts div { padding:11px 12px; border-right:1px solid #e3dfd5; }.deadline-facts div:last-child { border:0; }.deadline-facts dt { color:#4f5e57; font-size:10px; }.deadline-facts dd { margin:5px 0 0; color:#2e463d; font-size:12px; font-weight:650; }
.source-card { display:grid; grid-template-columns:auto 1fr; gap:10px; padding:12px; border-left:3px solid var(--gold); background:#fbf7ed; color:#83672e; }.source-card div { display:grid; gap:4px; }.source-card span { color:#6c736d; font-size:11px; line-height:1.5; }
.detail-section { padding-top:20px; }.event-timeline { list-style:none; margin:0; padding:0 0 0 4px; }.event-timeline li { position:relative; display:grid; grid-template-columns:14px 1fr; gap:9px; padding-bottom:14px; }.event-timeline li:not(:last-child)::before { content:''; position:absolute; left:5px; top:12px; bottom:0; border-left:1px solid #d6dad6; }.event-dot { position:relative; z-index:1; width:11px; height:11px; margin-top:3px; border:2px solid #628174; border-radius:50%; background:#fff; }.event-timeline div { display:grid; gap:3px; }.event-timeline strong { color:#334d43; font-size:12px; }.event-timeline span,.event-timeline small { color:#4e5e57; font-size:10px; }
.dispatch-list { display:grid; gap:1px; border:1px solid #e4e1d8; background:#e4e1d8; }.dispatch-list div { display:grid; grid-template-columns:110px 1fr auto; gap:12px; padding:9px 11px; background:#fff; color:#5d6963; font-size:11px; }.dispatch-list strong { color:#2f493f; }.inline-empty { display:flex; align-items:center; gap:8px; color:#56625c; font-size:12px; }
.empty-state { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; min-height:180px; color:#87908b; text-align:center; }.detail-empty { min-height:620px; }
.dialog-form textarea { min-height:90px; padding:10px; border:1px solid #d8d5ca; resize:vertical; }
@media (max-width:1100px) { .deadline-layout { grid-template-columns:1fr; }.deadline-register { min-height:auto; }.deadline-list { max-height:360px; }.deadline-facts { grid-template-columns:repeat(3,1fr); } }
@media (max-width:760px) { .deadline-hero { align-items:flex-start; flex-direction:column; }.workflow-guide { align-items:flex-start; }.workflow-guide small { display:none; }.deadline-metrics { grid-template-columns:1fr 1fr; }.deadline-layout { grid-template-columns:minmax(0,1fr); }.deadline-register,.deadline-detail { min-height:auto; padding:14px; overflow:hidden; }.register-filters { grid-template-columns:1fr; }.deadline-row { grid-template-columns:55px minmax(0,1fr); }.priority-pill { grid-column:2; justify-self:start; }.detail-header { flex-direction:column; }.detail-actions { width:100%; }.detail-actions button { flex:1; }.deadline-facts { grid-template-columns:1fr 1fr; }.dispatch-list div { grid-template-columns:1fr auto; }.dispatch-list strong { grid-row:2; } }
</style>

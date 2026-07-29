<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  BellRing,
  BriefcaseBusiness,
  CalendarClock,
  CheckCircle2,
  CircleAlert,
  FileCheck2,
  ListChecks,
  Scale,
  Sparkles,
} from '@lucide/vue'
import { http } from '@/api/http'
import type { CurrentUser, Matter } from '@/api/types'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Deadline {
  id: string
  matterId: string
  matterNumber: string
  matterTitle: string
  title: string
  dueAt: string
  priority: string
  deadlineType: string
  status: string
  ownerName: string
}

interface WorkflowTask {
  id: string
  name: string
  businessType: string
  businessId: string
  businessKey?: string
  createdAt: string
}

interface WorkTask {
  id: string
  title: string
  status: string
  priority: string
  ownerUserId: string
  ownerName: string
  dueAt?: string
  relatedBusinessLabel?: string
}

interface ActionItem {
  id: string
  kind: 'DEADLINE' | 'TASK' | 'APPROVAL'
  title: string
  context: string
  dueAt?: string
  priority: string
  href: string
  overdue: boolean
}

const { locale, t, tp } = useI18n()
const matters = ref<Matter[]>([])
const deadlines = ref<Deadline[]>([])
const workflowTasks = ref<WorkflowTask[]>([])
const workTasks = ref<WorkTask[]>([])
const currentUser = ref<CurrentUser | null>(null)
const unreadCount = ref(0)
const loading = ref(true)
const loadFailed = ref(false)

const activeMatters = computed(() => matters.value.filter((item) =>
  !['CLOSED', 'ARCHIVED', 'REJECTED'].includes(item.status),
))
const activeWorkTasks = computed(() => workTasks.value.filter((item) =>
  ['TODO', 'IN_PROGRESS'].includes(item.status),
))
const myWorkTasks = computed(() => activeWorkTasks.value.filter((item) =>
  item.ownerUserId === currentUser.value?.userId,
))
const overdueCount = computed(() => [
  ...deadlines.value.filter((item) =>
    ['OPEN', 'OVERDUE'].includes(item.status) && new Date(item.dueAt).getTime() < Date.now(),
  ),
  ...activeWorkTasks.value.filter((item) =>
    item.dueAt && new Date(item.dueAt).getTime() < Date.now(),
  ),
].length)
const weekDeadlineCount = computed(() => deadlines.value.filter((item) => {
  const remaining = new Date(item.dueAt).getTime() - Date.now()
  return ['OPEN', 'OVERDUE'].includes(item.status)
    && remaining >= 0
    && remaining <= 7 * 86_400_000
}).length)
const actionItems = computed<ActionItem[]>(() => {
  const deadlineItems: ActionItem[] = deadlines.value
    .filter((item) => ['OPEN', 'OVERDUE'].includes(item.status))
    .map((item) => ({
      id: item.id,
      kind: 'DEADLINE',
      title: item.title,
      context: `${item.matterNumber} · ${item.matterTitle}`,
      dueAt: item.dueAt,
      priority: item.priority,
      href: `/deadlines?id=${item.id}`,
      overdue: new Date(item.dueAt).getTime() < Date.now(),
    }))
  const taskItems: ActionItem[] = activeWorkTasks.value.map((item) => ({
    id: item.id,
    kind: 'TASK',
    title: item.title,
    context: item.relatedBusinessLabel || `${t('db.owner')}: ${item.ownerName}`,
    dueAt: item.dueAt,
    priority: item.priority,
    href: `/tasks?id=${item.id}`,
    overdue: Boolean(item.dueAt && new Date(item.dueAt).getTime() < Date.now()),
  }))
  const approvalItems: ActionItem[] = workflowTasks.value.map((item) => ({
    id: item.id,
    kind: 'APPROVAL',
    title: item.name,
    context: `${formatLegalCode(item.businessType, locale.value)} · ${item.businessKey ?? item.businessId}`,
    dueAt: item.createdAt,
    priority: 'HIGH',
    href: `/approvals?taskId=${item.id}`,
    overdue: false,
  }))
  const weight = (item: ActionItem) => {
    if (item.overdue) return 0
    if (item.kind === 'APPROVAL') return 1
    if (item.priority === 'URGENT') return 2
    if (item.priority === 'HIGH') return 3
    return 4
  }
  return [...deadlineItems, ...taskItems, ...approvalItems]
    .sort((a, b) => weight(a) - weight(b)
      || new Date(a.dueAt ?? '2999-12-31').getTime() - new Date(b.dueAt ?? '2999-12-31').getTime())
    .slice(0, 8)
})
const todayLabel = computed(() => new Intl.DateTimeFormat(locale.value, {
  weekday: 'long',
  year: 'numeric',
  month: 'long',
  day: 'numeric',
}).format(new Date()))
const greeting = computed(() => {
  const hour = new Date().getHours()
  const name = currentUser.value?.displayName ?? t('db.defaultName')
  if (hour < 12) return tp('db.greetingMorning', { name })
  if (hour < 18) return tp('db.greetingAfternoon', { name })
  return tp('db.greetingEvening', { name })
})
const stats = computed(() => [
  {
    label: t('db.activeMatters'),
    value: activeMatters.value.length,
    hint: t('db.activeMattersHint'),
    icon: BriefcaseBusiness,
    href: '/matters',
  },
  {
    label: t('db.myTasks'),
    value: myWorkTasks.value.length,
    hint: t('db.myTasksHint'),
    icon: ListChecks,
    href: '/tasks',
  },
  {
    label: t('db.myApprovals'),
    value: workflowTasks.value.length,
    hint: t('db.myApprovalsHint'),
    icon: FileCheck2,
    href: '/approvals',
  },
  {
    label: t('db.riskAlerts'),
    value: overdueCount.value + weekDeadlineCount.value,
    hint: overdueCount.value
      ? tp('db.overdueCount', { count: overdueCount.value })
      : tp('db.dueCount', { count: weekDeadlineCount.value }),
    icon: CircleAlert,
    href: '/deadlines',
    danger: overdueCount.value > 0,
  },
])

async function load() {
  loading.value = true
  loadFailed.value = false
  try {
    const [matterResponse, deadlineResponse, approvalResponse, workTaskResponse, meResponse, unreadResponse] =
      await Promise.all([
        http.get<Matter[]>('/matters'),
        http.get<Deadline[]>('/deadlines'),
        http.get<WorkflowTask[]>('/workflows/tasks'),
        http.get<WorkTask[]>('/work-tasks'),
        http.get<CurrentUser>('/me'),
        http.get<{ count: number }>('/notifications/unread-count'),
      ])
    matters.value = matterResponse.data
    deadlines.value = deadlineResponse.data
    workflowTasks.value = approvalResponse.data
    workTasks.value = workTaskResponse.data
    currentUser.value = meResponse.data
    unreadCount.value = unreadResponse.data.count
  } catch {
    loadFailed.value = true
  } finally {
    loading.value = false
  }
}

function shortDate(value?: string) {
  if (!value) return t('db.noDue')
  return new Intl.DateTimeFormat(locale.value, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function kindLabel(kind: ActionItem['kind']) {
  if (kind === 'DEADLINE') return t('db.deadline')
  if (kind === 'APPROVAL') return t('db.approval')
  return t('db.task')
}

onMounted(load)
</script>

<template>
  <div class="lawyer-dashboard">
    <section class="briefing-hero">
      <div class="briefing-copy">
        <span class="eyebrow">{{ todayLabel }}</span>
        <h2>{{ greeting }}</h2>
        <p>{{ t('db.heroDescription') }}</p>
      </div>
      <div class="briefing-mark" aria-hidden="true"><Scale :size="29" /><span>DAILY<br />BRIEF</span></div>
    </section>

    <section class="dashboard-stats" :aria-label="t('db.todayOverview')">
      <RouterLink v-for="stat in stats" :key="stat.label" :to="stat.href" :class="{ danger: stat.danger }">
        <span class="stat-icon"><component :is="stat.icon" :size="18" /></span>
        <span class="stat-copy"><small>{{ stat.label }}</small><strong>{{ loading ? '—' : stat.value }}</strong><em>{{ stat.hint }}</em></span>
        <ArrowRight :size="15" />
      </RouterLink>
    </section>

    <section v-if="loadFailed" class="dashboard-error" role="alert">
      <CircleAlert :size="18" />
      <span>{{ t('db.loadFailed') }}</span>
      <button class="secondary-action" @click="load">{{ t('db.retry') }}</button>
    </section>

    <section class="dashboard-body">
      <article class="priority-docket">
        <header class="dashboard-heading">
          <div><span class="eyebrow">PRIORITY DOCKET</span><h3>{{ t('db.priorityTitle') }}</h3></div>
          <span class="docket-count">{{ actionItems.length }} {{ t('db.actionItems') }}</span>
        </header>
        <div v-if="loading" class="dashboard-empty">{{ t('db.loading') }}</div>
        <RouterLink
          v-for="(item, index) in actionItems"
          v-else
          :key="`${item.kind}-${item.id}`"
          :to="item.href"
          class="docket-row"
          :class="{ overdue: item.overdue }"
        >
          <span class="docket-index">{{ String(index + 1).padStart(2, '0') }}</span>
          <span class="docket-main">
            <span class="docket-meta"><span>{{ kindLabel(item.kind) }}</span><em>{{ item.context }}</em></span>
            <strong>{{ item.title }}</strong>
          </span>
          <span class="docket-due">
            <CircleAlert v-if="item.overdue" :size="14" />
            <CalendarClock v-else :size="14" />
            {{ item.overdue ? t('db.overdue') : shortDate(item.dueAt) }}
          </span>
          <ArrowRight :size="15" />
        </RouterLink>
        <div v-if="!loading && !actionItems.length" class="dashboard-empty">
          <CheckCircle2 :size="34" />
          <strong>{{ t('db.noActions') }}</strong>
          <span>{{ t('db.noActionsHint') }}</span>
        </div>
      </article>

      <aside class="dashboard-side">
        <article class="focus-card">
          <div class="focus-top"><Sparkles :size="17" /><span>{{ t('db.focus') }}</span></div>
          <strong v-if="overdueCount">{{ t('db.focusOverdue') }}</strong>
          <strong v-else-if="workflowTasks.length">{{ t('db.focusApprovals') }}</strong>
          <strong v-else>{{ t('db.focusMatter') }}</strong>
          <p>{{ overdueCount
            ? tp('db.focusOverdueHint', { count: overdueCount })
            : workflowTasks.length
              ? tp('db.focusApprovalHint', { count: workflowTasks.length })
              : t('db.focusStableHint') }}</p>
        </article>

        <article class="matter-glance">
          <header class="dashboard-heading">
            <div><span class="eyebrow">MATTER GLANCE</span><h3>{{ t('db.activeMatterTitle') }}</h3></div>
            <RouterLink to="/matters">{{ t('db.all') }}</RouterLink>
          </header>
          <RouterLink v-for="matter in activeMatters.slice(0, 5)" :key="matter.id" :to="`/matters/${matter.id}`">
            <span class="matter-monogram">{{ matter.matterNumber.slice(-2) }}</span>
            <span><strong>{{ matter.title }}</strong><small>{{ matter.matterNumber }} · {{ matter.responsibleName }}</small></span>
            <span class="status-pill">{{ formatLegalCode(matter.status, locale) }}</span>
          </RouterLink>
          <div v-if="!activeMatters.length" class="compact-empty">{{ t('db.noMatters') }}</div>
        </article>

        <RouterLink to="/approvals" class="notification-card">
          <span><BellRing :size="18" /></span>
          <div><strong>{{ unreadCount }} {{ t('db.unread') }}</strong><small>{{ t('db.unreadHint') }}</small></div>
          <ArrowRight :size="15" />
        </RouterLink>
      </aside>
    </section>
  </div>
</template>

<style scoped>
.lawyer-dashboard { display: grid; gap: 18px; }
.briefing-hero { position: relative; overflow: hidden; display: flex; justify-content: space-between; gap: 28px; align-items: center; padding: 32px 34px; background:
  linear-gradient(90deg, rgba(247,245,238,.97), rgba(244,244,237,.88)),
  repeating-linear-gradient(0deg, transparent 0 23px, rgba(32,69,58,.04) 23px 24px);
  border: 1px solid var(--line); border-left: 4px solid #b79751; border-radius: 14px;
}
.briefing-copy { position: relative; z-index: 1; }
.briefing-hero h2 { margin: 8px 0 9px; font-family: Georgia, "Songti SC", serif; font-size: clamp(27px, 3vw, 39px); font-weight: 500; letter-spacing: -.02em; }
.briefing-hero p { max-width: 750px; margin: 0; color: var(--muted); font-size: 11px; line-height: 1.8; }
.briefing-mark { display: flex; gap: 10px; align-items: center; min-width: 130px; padding-left: 22px; color: #325e50; border-left: 1px solid #d4d5cd; }
.briefing-mark span { font-family: Georgia, serif; font-size: 10px; line-height: 1.35; letter-spacing: .18em; }
.dashboard-stats { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.dashboard-stats > a { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 17px; color: inherit; text-decoration: none; background: var(--paper-light); border: 1px solid var(--line); border-radius: 12px; transition: transform .16s ease, border-color .16s ease, box-shadow .16s ease; }
.dashboard-stats > a:hover { transform: translateY(-2px); border-color: #aebdb4; box-shadow: var(--shadow-sm); }
.dashboard-stats > a.danger { border-color: #e2c4bc; background: #fffaf7; }
.stat-icon { display: grid; width: 37px; height: 37px; place-items: center; color: #315e50; background: #eaf0eb; border-radius: 9px; }
.danger .stat-icon { color: #84392f; background: #f7e7e2; }
.stat-copy { min-width: 0; display: grid; grid-template-columns: 1fr auto; gap: 2px 8px; align-items: baseline; }
.stat-copy small { color: var(--muted); font-size: 9px; }
.stat-copy strong { grid-row: 1 / 3; grid-column: 2; font-family: Georgia, serif; font-size: 26px; font-weight: 500; }
.stat-copy em { overflow: hidden; color: #66736d; font-size: 8px; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.dashboard-body { display: grid; grid-template-columns: minmax(0, 1.42fr) minmax(320px, .72fr); gap: 16px; align-items: start; }
.priority-docket, .focus-card, .matter-glance, .notification-card { background: var(--paper-light); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-sm); }
.priority-docket { overflow: hidden; }
.dashboard-heading { display: flex; justify-content: space-between; gap: 14px; align-items: center; padding: 21px 22px; border-bottom: 1px solid var(--line); }
.dashboard-heading h3 { margin: 5px 0 0; font-family: Georgia, "Songti SC", serif; font-size: 19px; font-weight: 500; }
.dashboard-heading > a { color: var(--green); font-size: 9px; text-decoration: none; }
.docket-count { padding: 5px 8px; color: #315e50; background: #eaf0eb; border-radius: 999px; font-size: 8px; }
.docket-row { display: grid; grid-template-columns: 28px minmax(0, 1fr) auto auto; gap: 14px; align-items: center; min-height: 74px; padding: 12px 19px; color: inherit; text-decoration: none; border-bottom: 1px solid var(--line); transition: background .15s ease; }
.docket-row:last-child { border-bottom: 0; }
.docket-row:hover { background: #f5f3ed; }
.docket-row.overdue { background-image: linear-gradient(90deg, rgba(144,57,48,.065), transparent 55%); }
.docket-index { color: #6f5c36; font-family: Georgia, serif; font-size: 12px; font-weight: 600; }
.docket-main { min-width: 0; display: grid; gap: 6px; }
.docket-main > strong { overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.docket-meta { display: flex; gap: 8px; align-items: center; min-width: 0; color: var(--muted); font-size: 8px; }
.docket-meta > span { flex: 0 0 auto; color: #355f52; text-transform: uppercase; letter-spacing: .05em; }
.docket-meta em { overflow: hidden; font-style: normal; text-overflow: ellipsis; white-space: nowrap; }
.docket-due { display: flex; gap: 5px; align-items: center; color: var(--muted); font-size: 9px; white-space: nowrap; }
.overdue .docket-due { color: #88372f; }
.dashboard-side { display: grid; gap: 14px; }
.focus-card { padding: 22px; color: #f6f2e8; background:
  radial-gradient(circle at 90% 0, rgba(205,173,101,.18), transparent 38%),
  #17382f; border-color: #244a40; }
.focus-top { display: flex; gap: 7px; align-items: center; color: #d2b877; font-size: 9px; text-transform: uppercase; letter-spacing: .1em; }
.focus-card > strong { display: block; margin-top: 21px; font-family: Georgia, "Songti SC", serif; font-size: 19px; font-weight: 500; }
.focus-card p { margin: 9px 0 0; color: rgba(244,241,231,.68); font-size: 10px; line-height: 1.75; }
.matter-glance { overflow: hidden; }
.matter-glance .dashboard-heading { padding: 17px 18px; }
.matter-glance > a:not(.dashboard-heading a) { display: grid; grid-template-columns: 32px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 12px 16px; color: inherit; text-decoration: none; border-bottom: 1px solid var(--line); }
.matter-glance > a:last-child { border-bottom: 0; }
.matter-monogram { display: grid; width: 30px; height: 30px; place-items: center; color: #325e50; background: #eaf0eb; border-radius: 8px; font-family: Georgia, serif; font-size: 10px; }
.matter-glance a > span:nth-child(2) { min-width: 0; }
.matter-glance a strong, .matter-glance a small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.matter-glance a strong { font-size: 10px; }
.matter-glance a small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.notification-card { display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 16px; color: inherit; text-decoration: none; }
.notification-card > span { display: grid; width: 35px; height: 35px; place-items: center; color: #775f2d; background: #f2ead5; border-radius: 9px; }
.notification-card strong, .notification-card small { display: block; }
.notification-card strong { font-size: 10px; }
.notification-card small { margin-top: 4px; color: var(--muted); font-size: 8px; line-height: 1.4; }
.dashboard-empty { min-height: 240px; display: flex; flex-direction: column; gap: 8px; align-items: center; justify-content: center; padding: 30px; color: var(--muted); font-size: 10px; text-align: center; }
.dashboard-empty strong { color: var(--ink); font-size: 12px; }
.compact-empty { padding: 24px; color: var(--muted); font-size: 9px; text-align: center; }
.dashboard-error { display: flex; gap: 10px; align-items: center; padding: 12px 14px; color: #7e372f; background: #fff7f3; border: 1px solid #e9c8bf; border-radius: 10px; font-size: 10px; }
.dashboard-error .secondary-action { margin-left: auto; }

@media (max-width: 1100px) {
  .dashboard-stats { grid-template-columns: repeat(2, 1fr); }
  .dashboard-body { grid-template-columns: minmax(0, 1fr) 310px; }
}
@media (max-width: 820px) {
  .briefing-mark { display: none; }
  .dashboard-body { grid-template-columns: 1fr; }
}
@media (max-width: 560px) {
  .briefing-hero { padding: 24px 21px; }
  .dashboard-stats { grid-template-columns: 1fr; }
  .docket-row { grid-template-columns: 24px minmax(0, 1fr) auto; }
  .docket-row > svg { display: none; }
  .docket-due { grid-column: 2 / 4; }
}
</style>

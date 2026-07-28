<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CalendarDays, CircleAlert, FileSignature, Scale } from '@lucide/vue'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
const matters = ref<Matter[]>([])
const deadlines = ref<Deadline[]>([])
const workflowTasks = ref<WorkflowTask[]>([])
const loading = ref(true)
const activeMatters = computed(() => matters.value.filter((item) => item.status !== 'CLOSED').length)
const weekDeadlineCount = computed(() => deadlines.value.filter((item) => {
  const remaining = new Date(item.dueAt).getTime() - Date.now()
  return item.status === 'OPEN' && remaining >= 0 && remaining <= 7 * 86_400_000
}).length)
const conflictReviewCount = computed(() =>
  matters.value.filter((item) => item.status === 'CONFLICT_REVIEW').length,
)

interface Deadline {
  id: string
  matterTitle: string
  title: string
  dueAt: string
  priority: string
  deadlineType: string
  status: string
}

interface WorkflowTask {
  id: string
}

onMounted(async () => {
  try {
    const [matterResponse, deadlineResponse, taskResponse] = await Promise.all([
      http.get<Matter[]>('/matters'),
      http.get<Deadline[]>('/deadlines'),
      http.get<WorkflowTask[]>('/workflows/tasks'),
    ])
    matters.value = matterResponse.data
    deadlines.value = deadlineResponse.data
    workflowTasks.value = taskResponse.data
  } finally {
    loading.value = false
  }
})

const stats = computed(() => [
  { label: text('在办案件', 'Active matters'), value: activeMatters.value, trend: text('按成员权限统计', 'Based on your access'), icon: Scale },
  { label: text('七日内期限', 'Due in 7 days'), value: weekDeadlineCount.value, trend: text('请优先处理法定期限', 'Prioritize statutory deadlines'), icon: CalendarDays, danger: weekDeadlineCount.value > 0 },
  { label: text('待我审批', 'My approvals'), value: workflowTasks.value.length, trend: text('来自流程引擎', 'From workflow engine'), icon: FileSignature },
  { label: text('风险提示', 'Risk alerts'), value: conflictReviewCount.value, trend: text('待完成冲突复核', 'Conflict review required'), icon: CircleAlert, danger: conflictReviewCount.value > 0 },
])

function shortDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, { month: '2-digit', day: '2-digit' })
    .format(new Date(value))
}
</script>

<template>
  <div class="dashboard">
    <section class="hero-panel">
      <div>
        <span class="eyebrow">MONDAY · JUL 27</span>
        <h2>{{ t('headline.dashboard') }}</h2>
        <p>{{ text('期限、审批与风险信号已汇总。系统只展示你有权查看的案件内容。', 'Deadlines, approvals and risk signals are consolidated. Only authorized matter content is shown.') }}</p>
      </div>
      <RouterLink to="/matters" class="primary-action">
        {{ text('查看全部案件', 'View all matters') }} <ArrowRight :size="17" />
      </RouterLink>
    </section>

    <section class="stat-grid" :aria-label="text('工作概览', 'Work overview')">
      <article v-for="stat in stats" :key="stat.label" class="stat-card">
        <div class="stat-icon" :class="{ danger: stat.danger }"><component :is="stat.icon" :size="20" /></div>
        <span>{{ stat.label }}</span>
        <strong>{{ stat.value }}</strong>
        <small :class="{ danger: stat.danger }">{{ stat.trend }}</small>
      </article>
    </section>

    <section class="dashboard-grid">
      <article class="panel case-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">ACTIVE MATTERS</span>
            <h3>{{ text('最近案件', 'Recent matters') }}</h3>
          </div>
          <RouterLink to="/matters">{{ text('查看全部', 'View all') }}</RouterLink>
        </div>
        <div v-if="loading" class="empty-state">{{ text('正在加载案件…', 'Loading matters…') }}</div>
        <div v-else-if="matters.length === 0" class="empty-state">{{ text('尚未创建案件', 'No matters yet') }}</div>
        <RouterLink
          v-for="matter in matters.slice(0, 5)"
          v-else
          :key="matter.id"
          to="/matters"
          class="case-row"
        >
          <span class="case-number">{{ matter.matterNumber }}</span>
          <div class="case-main">
            <strong>{{ matter.title }}</strong>
            <span>{{ formatLegalCode(matter.matterType, locale) }} · {{ matter.responsibleName }}</span>
          </div>
          <span class="status-pill">{{ formatLegalCode(matter.status, locale) }}</span>
          <ArrowRight :size="16" />
        </RouterLink>
      </article>

      <article class="panel timeline-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">UPCOMING</span>
            <h3>{{ text('近期节点', 'Upcoming deadlines') }}</h3>
          </div>
        </div>
        <ol v-if="deadlines.length" class="timeline">
          <li v-for="deadline in deadlines.slice(0, 5)" :key="deadline.id">
            <time>{{ shortDate(deadline.dueAt) }}</time>
            <div><strong>{{ deadline.title }}</strong><span>{{ deadline.matterTitle }}</span></div>
            <span class="priority" :class="{ high: deadline.priority === 'HIGH' }">
              {{ formatLegalCode(deadline.priority === 'HIGH' ? 'URGENT' : deadline.deadlineType, locale) }}
            </span>
          </li>
        </ol>
        <div v-else class="empty-state">{{ text('暂无近期节点', 'No upcoming deadlines') }}</div>
      </article>
    </section>
  </div>
</template>

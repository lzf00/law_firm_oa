<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CalendarDays, CircleAlert, FileSignature, Scale } from '@lucide/vue'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t } from '@/i18n'

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
  { label: '在办案件', value: activeMatters.value, trend: '按成员权限统计', icon: Scale },
  { label: '七日内期限', value: weekDeadlineCount.value, trend: '请优先处理法定期限', icon: CalendarDays, danger: weekDeadlineCount.value > 0 },
  { label: '待我审批', value: workflowTasks.value.length, trend: '来自流程引擎', icon: FileSignature },
  { label: '风险提示', value: conflictReviewCount.value, trend: '待完成冲突复核', icon: CircleAlert, danger: conflictReviewCount.value > 0 },
])

function shortDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit' })
    .format(new Date(value))
}
</script>

<template>
  <div class="dashboard">
    <section class="hero-panel">
      <div>
        <span class="eyebrow">MONDAY · JUL 27</span>
        <h2>{{ t('headline.dashboard') }}</h2>
        <p>期限、审批与风险信号已汇总。系统只展示你有权查看的案件内容。</p>
      </div>
      <RouterLink to="/matters" class="primary-action">
        查看全部案件 <ArrowRight :size="17" />
      </RouterLink>
    </section>

    <section class="stat-grid" aria-label="工作概览">
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
            <h3>最近案件</h3>
          </div>
          <RouterLink to="/matters">查看全部</RouterLink>
        </div>
        <div v-if="loading" class="empty-state">正在加载案件…</div>
        <div v-else-if="matters.length === 0" class="empty-state">尚未创建案件</div>
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
            <span>{{ matter.matterType }} · {{ matter.responsibleName }}</span>
          </div>
          <span class="status-pill">{{ matter.status }}</span>
          <ArrowRight :size="16" />
        </RouterLink>
      </article>

      <article class="panel timeline-panel">
        <div class="panel-heading">
          <div>
            <span class="eyebrow">UPCOMING</span>
            <h3>近期节点</h3>
          </div>
        </div>
        <ol v-if="deadlines.length" class="timeline">
          <li v-for="deadline in deadlines.slice(0, 5)" :key="deadline.id">
            <time>{{ shortDate(deadline.dueAt) }}</time>
            <div><strong>{{ deadline.title }}</strong><span>{{ deadline.matterTitle }}</span></div>
            <span class="priority" :class="{ high: deadline.priority === 'HIGH' }">
              {{ deadline.priority === 'HIGH' ? '紧急' : deadline.deadlineType }}
            </span>
          </li>
        </ol>
        <div v-else class="empty-state">暂无近期节点</div>
      </article>
    </section>
  </div>
</template>

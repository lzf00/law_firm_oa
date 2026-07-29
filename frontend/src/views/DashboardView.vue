<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowRight, CalendarDays, CircleAlert, FileSignature, Scale } from '@lucide/vue'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
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
const todayLabel = computed(() => new Intl.DateTimeFormat(locale.value, {
  weekday: 'long',
  month: 'long',
  day: 'numeric',
}).format(new Date()).toLocaleUpperCase(locale.value))
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 12) return t('dashboard.greetingMorning')
  if (hour < 18) return t('dashboard.greetingAfternoon')
  return t('dashboard.greetingEvening')
})

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
  { label: t('copy.0103'), value: activeMatters.value, trend: t('copy.0104'), icon: Scale },
  { label: t('copy.0105'), value: weekDeadlineCount.value, trend: t('copy.0106'), icon: CalendarDays, danger: weekDeadlineCount.value > 0 },
  { label: t('copy.0107'), value: workflowTasks.value.length, trend: t('copy.0108'), icon: FileSignature },
  { label: t('copy.0109'), value: conflictReviewCount.value, trend: t('copy.0110'), icon: CircleAlert, danger: conflictReviewCount.value > 0 },
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
        <span class="eyebrow">{{ todayLabel }}</span>
        <h2>{{ greeting }}</h2>
        <p>{{ t('copy.0111') }}</p>
      </div>
      <RouterLink to="/matters" class="primary-action">
        {{ t('copy.0112') }} <ArrowRight :size="17" />
      </RouterLink>
    </section>

    <section class="stat-grid" :aria-label="t('copy.0113')">
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
            <h3>{{ t('copy.0114') }}</h3>
          </div>
          <RouterLink to="/matters">{{ t('copy.0115') }}</RouterLink>
        </div>
        <div v-if="loading" class="empty-state">{{ t('copy.0116') }}</div>
        <div v-else-if="matters.length === 0" class="empty-state">{{ t('copy.0117') }}</div>
        <RouterLink
          v-for="matter in matters.slice(0, 5)"
          v-else
          :key="matter.id"
          :to="`/matters/${matter.id}`"
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
            <h3>{{ t('copy.0118') }}</h3>
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
        <div v-else class="empty-state">{{ t('copy.0119') }}</div>
      </article>
    </section>
  </div>
</template>

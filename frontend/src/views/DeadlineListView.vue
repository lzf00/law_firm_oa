<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CalendarClock, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

interface Deadline {
  id: string
  matterNumber: string
  matterTitle: string
  title: string
  dueAt: string
  deadlineType: string
  priority: string
  status: string
  ownerName: string
}

const deadlines = ref<Deadline[]>([])
const loading = ref(true)
const now = new Date()
const openCount = computed(() => deadlines.value.filter((item) => item.status === 'OPEN').length)

onMounted(async () => {
  try {
    deadlines.value = (await http.get<Deadline[]>('/deadlines')).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '期限加载失败')
  } finally {
    loading.value = false
  }
})

function formatDate(value: string) {
  return new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function daysLeft(value: string) {
  return Math.ceil((new Date(value).getTime() - now.getTime()) / 86_400_000)
}
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">DEADLINES · {{ openCount }} OPEN</span>
        <h2>{{ t('headline.deadlines') }}</h2>
        <p>法定期限与内部任务分开标识，默认按 7、3、1 天逐级提醒。</p>
      </div>
      <button class="primary-action"><Plus :size="17" /> 新建期限</button>
    </div>

    <div class="deadline-board panel">
      <div v-if="loading" class="empty-state">正在汇总期限…</div>
      <article v-for="deadline in deadlines" v-else :key="deadline.id" class="deadline-row">
        <div class="deadline-date" :class="{ urgent: daysLeft(deadline.dueAt) <= 3 }">
          <strong>{{ formatDate(deadline.dueAt).slice(0, 5) }}</strong>
          <span>{{ daysLeft(deadline.dueAt) < 0 ? `逾期 ${-daysLeft(deadline.dueAt)} 天` : `剩余 ${daysLeft(deadline.dueAt)} 天` }}</span>
        </div>
        <div class="deadline-main">
          <strong>{{ deadline.title }}</strong>
          <span>{{ deadline.matterNumber }} · {{ deadline.matterTitle }}</span>
        </div>
        <div class="deadline-meta"><span>{{ deadline.deadlineType }}</span><strong>{{ deadline.ownerName }}</strong></div>
        <span class="status-pill">{{ deadline.priority }}</span>
      </article>
      <div v-if="!loading && deadlines.length === 0" class="empty-state">
        <CalendarClock :size="32" /><strong>没有待办期限</strong>
      </div>
    </div>
  </section>
</template>

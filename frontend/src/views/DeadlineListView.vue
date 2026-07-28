<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarClock, Pencil, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter, OrganizationUser } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'

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
}

const deadlines = ref<Deadline[]>([])
const matters = ref<Matter[]>([])
const users = ref<OrganizationUser[]>([])
const loading = ref(true)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref('')
const now = new Date()
const { locale } = useI18n()
const isEnglish = computed(() => locale.value === 'en-US')
const openCount = computed(() => deadlines.value.filter((item) => item.status === 'OPEN').length)
const form = reactive({
  matterId: '',
  title: '',
  dueAt: '',
  deadlineType: 'COURT',
  ownerUserId: '',
  priority: 'NORMAL',
  reminderDays: '7,3,1',
})

async function load() {
  loading.value = true
  try {
    const [deadlineResponse, matterResponse, userResponse] = await Promise.all([
      http.get<Deadline[]>('/deadlines'),
      http.get<Matter[]>('/matters'),
      http.get<OrganizationUser[]>('/organization/users'),
    ])
    deadlines.value = deadlineResponse.data
    matters.value = matterResponse.data
    users.value = userResponse.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not load deadlines' : '期限加载失败'))
  } finally {
    loading.value = false
  }
}

function reminderDays(policy?: string) {
  try {
    return JSON.parse(policy || '{}').daysBefore?.join(',') || '7,3,1'
  } catch {
    return '7,3,1'
  }
}

function localDateTime(instant?: string) {
  if (!instant) return ''
  const date = new Date(instant)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function openForm(deadline?: Deadline) {
  editingId.value = deadline?.id ?? ''
  Object.assign(form, {
    matterId: deadline?.matterId ?? matters.value[0]?.id ?? '',
    title: deadline?.title ?? '',
    dueAt: localDateTime(deadline?.dueAt),
    deadlineType: deadline?.deadlineType ?? 'COURT',
    ownerUserId: deadline?.ownerUserId ?? users.value[0]?.id ?? '',
    priority: deadline?.priority ?? 'NORMAL',
    reminderDays: reminderDays(deadline?.reminderPolicy),
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.matterId || !form.title.trim() || !form.dueAt || !form.ownerUserId) {
    ElMessage.warning(isEnglish.value ? 'Matter, title, due date and owner are required' : '请填写案件、标题、到期时间和负责人')
    return
  }
  const dueAt = new Date(form.dueAt)
  if (dueAt.getTime() <= Date.now()) {
    ElMessage.warning(isEnglish.value ? 'Due date must be in the future' : '到期时间必须晚于当前时间')
    return
  }
  const reminderDaysBefore = form.reminderDays.split(/[，,]/)
    .map((value) => Number.parseInt(value.trim(), 10))
    .filter((value) => Number.isInteger(value) && value >= 0 && value <= 365)
  saving.value = true
  try {
    const payload = {
      matterId: form.matterId,
      title: form.title,
      dueAt: dueAt.toISOString(),
      deadlineType: form.deadlineType,
      ownerUserId: form.ownerUserId,
      priority: form.priority,
      reminderDaysBefore,
    }
    if (editingId.value) await http.put(`/deadlines/${editingId.value}`, payload)
    else await http.post('/deadlines', payload)
    dialogVisible.value = false
    await load()
    ElMessage.success(isEnglish.value ? 'Deadline saved' : '期限已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not save deadline' : '期限保存失败'))
  } finally {
    saving.value = false
  }
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function daysLeft(value: string) {
  return Math.ceil((new Date(value).getTime() - now.getTime()) / 86_400_000)
}

onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">DEADLINES · {{ openCount }} OPEN</span>
        <h2>{{ t('headline.deadlines') }}</h2>
        <p>{{ isEnglish ? 'Separate statutory deadlines from internal tasks and configure progressive reminders.' : '法定期限与内部任务分开标识，并可配置逐级提醒。' }}</p>
      </div>
      <button class="primary-action" @click="openForm()"><Plus :size="17" /> {{ isEnglish ? 'New deadline' : '新建期限' }}</button>
    </div>

    <div class="deadline-board panel">
      <div v-if="loading" class="empty-state">{{ isEnglish ? 'Loading deadlines…' : '正在汇总期限…' }}</div>
      <article v-for="deadline in deadlines" v-else :key="deadline.id" class="deadline-row">
        <div class="deadline-date" :class="{ urgent: daysLeft(deadline.dueAt) <= 3 }">
          <strong>{{ formatDate(deadline.dueAt).slice(0, 5) }}</strong>
          <span>{{ daysLeft(deadline.dueAt) < 0 ? (isEnglish ? `${-daysLeft(deadline.dueAt)} days overdue` : `逾期 ${-daysLeft(deadline.dueAt)} 天`) : (isEnglish ? `${daysLeft(deadline.dueAt)} days left` : `剩余 ${daysLeft(deadline.dueAt)} 天`) }}</span>
        </div>
        <div class="deadline-main"><strong>{{ deadline.title }}</strong><span>{{ deadline.matterNumber }} · {{ deadline.matterTitle }}</span><small>{{ isEnglish ? 'Reminders' : '提醒' }}：{{ reminderDays(deadline.reminderPolicy) }} {{ isEnglish ? 'days before' : '天前' }}</small></div>
        <div class="deadline-meta"><span>{{ deadline.deadlineType }}</span><strong>{{ deadline.ownerName }}</strong></div>
        <span class="status-pill">{{ deadline.priority }}</span>
        <button class="table-action" @click="openForm(deadline)"><Pencil :size="15" /> {{ isEnglish ? 'Edit' : '编辑' }}</button>
      </article>
      <div v-if="!loading && deadlines.length === 0" class="empty-state"><CalendarClock :size="32" /><strong>{{ isEnglish ? 'No open deadlines' : '没有待办期限' }}</strong></div>
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? (isEnglish ? 'Edit deadline' : '编辑期限') : (isEnglish ? 'New deadline' : '新建期限')" width="min(680px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label class="full-field"><span>{{ isEnglish ? 'Matter' : '关联案件' }}</span><select v-model="form.matterId" required><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <label class="full-field"><span>{{ isEnglish ? 'Deadline title' : '期限标题' }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ isEnglish ? 'Due at' : '到期时间' }}</span><input v-model="form.dueAt" type="datetime-local" required /></label>
        <label><span>{{ isEnglish ? 'Owner' : '负责人' }}</span><select v-model="form.ownerUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ isEnglish ? 'Type' : '期限类型' }}</span><select v-model="form.deadlineType"><option value="COURT">{{ isEnglish ? 'Court' : '法院期限' }}</option><option value="ARBITRATION">{{ isEnglish ? 'Arbitration' : '仲裁期限' }}</option><option value="FILING">{{ isEnglish ? 'Filing' : '申报期限' }}</option><option value="INTERNAL">{{ isEnglish ? 'Internal' : '内部期限' }}</option></select></label>
        <label><span>{{ isEnglish ? 'Priority' : '优先级' }}</span><select v-model="form.priority"><option value="LOW">{{ isEnglish ? 'Low' : '低' }}</option><option value="NORMAL">{{ isEnglish ? 'Normal' : '普通' }}</option><option value="HIGH">{{ isEnglish ? 'High' : '高' }}</option><option value="URGENT">{{ isEnglish ? 'Urgent' : '紧急' }}</option></select></label>
        <label class="full-field"><span>{{ isEnglish ? 'Reminder days before (comma separated)' : '提前提醒天数（逗号分隔）' }}</span><input v-model="form.reminderDays" placeholder="7,3,1" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (isEnglish ? 'Save deadline' : '保存期限') }}</button>
      </form>
    </ElDialog>
  </section>
</template>

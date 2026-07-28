<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarClock, Pencil, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter, OrganizationUser } from '@/api/types'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
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
    if (dialogVisible.value) {
      if (!form.matterId) form.matterId = matters.value[0]?.id ?? ''
      if (!form.ownerUserId) form.ownerUserId = users.value[0]?.id ?? ''
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0120'))
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
    ElMessage.warning(t('copy.0121'))
    return
  }
  const dueAt = new Date(form.dueAt)
  if (dueAt.getTime() <= Date.now()) {
    ElMessage.warning(t('copy.0122'))
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
    ElMessage.success(t('copy.0123'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0124'))
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
        <p>{{ t('copy.0341') }}</p>
      </div>
      <button class="primary-action" :disabled="loading" @click="openForm()"><Plus :size="17" /> {{ t('copy.0342') }}</button>
    </div>

    <div class="deadline-board panel">
      <div v-if="loading" class="empty-state">{{ t('copy.0343') }}</div>
      <article v-for="deadline in deadlines" v-else :key="deadline.id" class="deadline-row">
        <div class="deadline-date" :class="{ urgent: daysLeft(deadline.dueAt) <= 3 }">
          <strong>{{ formatDate(deadline.dueAt).slice(0, 5) }}</strong>
          <span>{{ daysLeft(deadline.dueAt) < 0 ? tp('copy.dynamic.daysOverdue', { count: -daysLeft(deadline.dueAt) }) : tp('copy.dynamic.daysLeft', { count: daysLeft(deadline.dueAt) }) }}</span>
        </div>
        <div class="deadline-main"><strong>{{ deadline.title }}</strong><span>{{ deadline.matterNumber }} · {{ deadline.matterTitle }}</span><small>{{ t('copy.0344') }}：{{ reminderDays(deadline.reminderPolicy) }} {{ t('copy.0345') }}</small></div>
        <div class="deadline-meta"><span>{{ formatLegalCode(deadline.deadlineType, locale) }}</span><strong>{{ deadline.ownerName }}</strong></div>
        <span class="status-pill">{{ formatLegalCode(deadline.priority, locale) }}</span>
        <button class="table-action" @click="openForm(deadline)"><Pencil :size="15" /> {{ t('copy.0326') }}</button>
      </article>
      <div v-if="!loading && deadlines.length === 0" class="empty-state"><CalendarClock :size="32" /><strong>{{ t('copy.0346') }}</strong></div>
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? (t('copy.0347')) : (t('copy.0342'))" width="min(680px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label class="full-field"><span>{{ t('copy.0070') }}</span><select v-model="form.matterId" required><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <label class="full-field"><span>{{ t('copy.0348') }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ t('copy.0349') }}</span><input v-model="form.dueAt" type="datetime-local" required /></label>
        <label><span>{{ t('copy.0310') }}</span><select v-model="form.ownerUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ t('copy.0350') }}</span><select v-model="form.deadlineType"><option value="COURT">{{ t('copy.0351') }}</option><option value="ARBITRATION">{{ t('copy.0352') }}</option><option value="FILING">{{ t('copy.0353') }}</option><option value="INTERNAL">{{ t('copy.0354') }}</option></select></label>
        <label><span>{{ t('copy.0015') }}</span><select v-model="form.priority"><option value="LOW">{{ t('copy.0355') }}</option><option value="NORMAL">{{ t('copy.0356') }}</option><option value="HIGH">{{ t('copy.0357') }}</option><option value="URGENT">{{ t('copy.0358') }}</option></select></label>
        <label class="full-field"><span>{{ t('copy.0359') }}</span><input v-model="form.reminderDays" placeholder="7,3,1" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (t('copy.0360')) }}</button>
      </form>
    </ElDialog>
  </section>
</template>

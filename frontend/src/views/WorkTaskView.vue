<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowRight, ListTodo, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface User { id: string; displayName: string }
interface Task {
  id: string; title: string; description?: string; status: string; priority: string
  ownerName: string; assignerName: string; dueAt?: string; commentCount: number
}
const tasks = ref<Task[]>([])
const users = ref<User[]>([])
const dialog = ref(false)
const form = reactive({ title: '', description: '', ownerUserId: '', dueAt: '', priority: 'NORMAL' })
const columns = [
  { code: 'TODO' },
  { code: 'IN_PROGRESS' },
  { code: 'DONE' },
]
const grouped = computed(() => Object.fromEntries(columns.map((column) => [
  column.code, tasks.value.filter((task) => task.status === column.code),
])))
async function load() {
  const [taskResult, userResult] = await Promise.all([
    http.get<Task[]>('/work-tasks'), http.get<User[]>('/organization/users'),
  ])
  tasks.value = taskResult.data
  users.value = userResult.data
  if (!form.ownerUserId) form.ownerUserId = users.value[0]?.id ?? ''
}
async function create() {
  try {
    await http.post('/work-tasks', {
      ...form, dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
    })
    dialog.value = false
    Object.assign(form, { title: '', description: '', dueAt: '', priority: 'NORMAL' })
    await load()
    ElMessage.success(t('copy.0294'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('copy.0295')) }
}
async function advance(task: Task) {
  const status = task.status === 'TODO' ? 'IN_PROGRESS' : 'DONE'
  await http.patch(`/work-tasks/${task.id}/status`, { status })
  await load()
  ElMessage.success(status === 'DONE' ? t('copy.0296') : t('copy.0297'))
}
onMounted(() => load().catch(() => ElMessage.error(t('copy.0298'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">TEAM TASKS</span><h2>{{ t('headline.tasks') }}</h2><p>{{ t('copy.0299') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ t('copy.0300') }}</button>
    </div>
    <div class="kanban">
      <section v-for="column in columns" :key="column.code" class="kanban-column">
        <header><span>{{ formatLegalCode(column.code, locale) }}</span><strong>{{ grouped[column.code]?.length || 0 }}</strong></header>
        <article v-for="task in grouped[column.code]" :key="task.id" class="task-card">
          <div><span class="status-pill">{{ formatLegalCode(task.priority, locale) }}</span><small>{{ task.commentCount }} {{ t('copy.0301') }}</small></div>
          <h3>{{ task.title }}</h3><p>{{ task.description || t('copy.0302') }}</p>
          <footer><span>{{ task.ownerName }}</span><time>{{ task.dueAt ? new Date(task.dueAt).toLocaleDateString(locale) : t('copy.0303') }}</time></footer>
          <button v-if="task.status !== 'DONE'" class="secondary-action compact-action" @click="advance(task)">{{ task.status === 'TODO' ? t('copy.0304') : t('copy.0305') }} <ArrowRight :size="14" /></button>
        </article>
        <div v-if="!grouped[column.code]?.length" class="kanban-empty"><ListTodo :size="24" /> {{ t('copy.0306') }}</div>
      </section>
    </div>
    <el-dialog v-model="dialog" :title="t('copy.0307')" width="520px">
      <div class="dialog-form">
        <label><span>{{ t('copy.0308') }}</span><input v-model="form.title" :placeholder="t('copy.0309')" /></label>
        <label><span>{{ t('copy.0310') }}</span><select v-model="form.ownerUserId"><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ t('copy.0311') }}</span><input v-model="form.dueAt" type="datetime-local" /></label>
        <label><span>{{ t('copy.0015') }}</span><select v-model="form.priority"><option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option><option value="HIGH">{{ formatLegalCode('HIGH', locale) }}</option><option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option></select></label>
        <label><span>{{ t('copy.0312') }}</span><textarea v-model="form.description" rows="5" :placeholder="t('copy.0313')"></textarea></label>
      </div>
      <template #footer><button class="primary-action" @click="create">{{ t('copy.0314') }}</button></template>
    </el-dialog>
  </section>
</template>

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
    ElMessage.success(text('协作任务已分派', 'Task assigned'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : text('任务创建失败', 'Failed to create task')) }
}
async function advance(task: Task) {
  const status = task.status === 'TODO' ? 'IN_PROGRESS' : 'DONE'
  await http.patch(`/work-tasks/${task.id}/status`, { status })
  await load()
  ElMessage.success(status === 'DONE' ? text('任务已完成', 'Task completed') : text('任务已开始', 'Task started'))
}
onMounted(() => load().catch(() => ElMessage.error(text('任务加载失败', 'Failed to load tasks'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">TEAM TASKS</span><h2>{{ t('headline.tasks') }}</h2><p>{{ text('任务按待开始、进行中、已完成流转，并保留分派人、参与人、评论和操作审计。', 'Tasks move from to-do through in-progress to done, preserving assignment, participants, comments and audit history.') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ text('新建任务', 'New task') }}</button>
    </div>
    <div class="kanban">
      <section v-for="column in columns" :key="column.code" class="kanban-column">
        <header><span>{{ formatLegalCode(column.code, locale) }}</span><strong>{{ grouped[column.code]?.length || 0 }}</strong></header>
        <article v-for="task in grouped[column.code]" :key="task.id" class="task-card">
          <div><span class="status-pill">{{ formatLegalCode(task.priority, locale) }}</span><small>{{ task.commentCount }} {{ text('条评论', 'comments') }}</small></div>
          <h3>{{ task.title }}</h3><p>{{ task.description || text('暂无补充说明', 'No additional details') }}</p>
          <footer><span>{{ task.ownerName }}</span><time>{{ task.dueAt ? new Date(task.dueAt).toLocaleDateString(locale) : text('未设期限', 'No due date') }}</time></footer>
          <button v-if="task.status !== 'DONE'" class="secondary-action compact-action" @click="advance(task)">{{ task.status === 'TODO' ? text('开始处理', 'Start') : text('标记完成', 'Complete') }} <ArrowRight :size="14" /></button>
        </article>
        <div v-if="!grouped[column.code]?.length" class="kanban-empty"><ListTodo :size="24" /> {{ text('暂无任务', 'No tasks') }}</div>
      </section>
    </div>
    <el-dialog v-model="dialog" :title="text('新建协作任务', 'Create team task')" width="520px">
      <div class="dialog-form">
        <label><span>{{ text('任务标题', 'Task title') }}</span><input v-model="form.title" :placeholder="text('明确、可验收的任务名称', 'A clear and testable task name')" /></label>
        <label><span>{{ text('负责人', 'Owner') }}</span><select v-model="form.ownerUserId"><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ text('完成期限', 'Due date') }}</span><input v-model="form.dueAt" type="datetime-local" /></label>
        <label><span>{{ text('优先级', 'Priority') }}</span><select v-model="form.priority"><option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option><option value="HIGH">{{ formatLegalCode('HIGH', locale) }}</option><option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option></select></label>
        <label><span>{{ text('任务说明', 'Description') }}</span><textarea v-model="form.description" rows="5" :placeholder="text('说明交付物与验收标准', 'Describe the deliverable and acceptance criteria')"></textarea></label>
      </div>
      <template #footer><button class="primary-action" @click="create">{{ text('确认分派', 'Assign task') }}</button></template>
    </el-dialog>
  </section>
</template>

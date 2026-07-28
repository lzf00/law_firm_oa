<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowRight, ListTodo, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

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
  { code: 'TODO', name: '待开始' },
  { code: 'IN_PROGRESS', name: '进行中' },
  { code: 'DONE', name: '已完成' },
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
    ElMessage.success('协作任务已分派')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '任务创建失败') }
}
async function advance(task: Task) {
  const status = task.status === 'TODO' ? 'IN_PROGRESS' : 'DONE'
  await http.patch(`/work-tasks/${task.id}/status`, { status })
  await load()
  ElMessage.success(status === 'DONE' ? '任务已完成' : '任务已开始')
}
onMounted(() => load().catch(() => ElMessage.error('任务加载失败')))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">TEAM TASKS</span><h2>{{ t('headline.tasks') }}</h2><p>任务按待开始、进行中、已完成流转，并保留分派人、参与人、评论和操作审计。</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> 新建任务</button>
    </div>
    <div class="kanban">
      <section v-for="column in columns" :key="column.code" class="kanban-column">
        <header><span>{{ column.name }}</span><strong>{{ grouped[column.code]?.length || 0 }}</strong></header>
        <article v-for="task in grouped[column.code]" :key="task.id" class="task-card">
          <div><span class="status-pill">{{ task.priority }}</span><small>{{ task.commentCount }} 条评论</small></div>
          <h3>{{ task.title }}</h3><p>{{ task.description || '暂无补充说明' }}</p>
          <footer><span>{{ task.ownerName }}</span><time>{{ task.dueAt ? new Date(task.dueAt).toLocaleDateString('zh-CN') : '未设期限' }}</time></footer>
          <button v-if="task.status !== 'DONE'" class="secondary-action compact-action" @click="advance(task)">{{ task.status === 'TODO' ? '开始处理' : '标记完成' }} <ArrowRight :size="14" /></button>
        </article>
        <div v-if="!grouped[column.code]?.length" class="kanban-empty"><ListTodo :size="24" /> 暂无任务</div>
      </section>
    </div>
    <el-dialog v-model="dialog" title="新建协作任务" width="520px">
      <div class="dialog-form">
        <label><span>任务标题</span><input v-model="form.title" placeholder="明确、可验收的任务名称" /></label>
        <label><span>负责人</span><select v-model="form.ownerUserId"><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>完成期限</span><input v-model="form.dueAt" type="datetime-local" /></label>
        <label><span>优先级</span><select v-model="form.priority"><option value="NORMAL">普通</option><option value="HIGH">重要</option><option value="URGENT">紧急</option></select></label>
        <label><span>任务说明</span><textarea v-model="form.description" rows="5" placeholder="说明交付物与验收标准"></textarea></label>
      </div>
      <template #footer><button class="primary-action" @click="create">确认分派</button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  BellRing,
  CheckCircle2,
  FileCheck2,
  Send,
  Stamp,
  XCircle,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface WorkflowTask {
  id: string
  name: string
  processInstanceId: string
  businessKey?: string
  businessType: string
  businessId: string
  assignee?: string
  candidateGroup?: string
  status: string
  createdAt: string
}

interface TransferTarget {
  userId: string
  username: string
  displayName: string
}

interface SealRequest {
  id: string
  sealName: string
  purpose: string
  copies: number
  status: string
  requestedByName: string
  createdAt: string
}

type ApprovalAction = 'APPROVE' | 'REJECT' | 'TRANSFER'

const tasks = ref<WorkflowTask[]>([])
const sealRequests = ref<SealRequest[]>([])
const loading = ref(true)
const processing = ref('')
const reminding = ref('')
const actionVisible = ref(false)
const selectedTask = ref<WorkflowTask | null>(null)
const action = ref<ApprovalAction>('APPROVE')
const comment = ref('')
const targetUserId = ref('')
const transferTargets = ref<TransferTarget[]>([])
const { locale } = useI18n()
const isEnglish = computed(() => locale.value === 'en-US')

const actionTitle = computed(() => {
  if (action.value === 'APPROVE') return text('通过审批', 'Approve task')
  if (action.value === 'REJECT') return text('驳回审批', 'Reject task')
  return text('转交审批', 'Transfer task')
})

function text(zh: string, en: string) {
  return isEnglish.value ? en : zh
}

async function load() {
  loading.value = true
  try {
    const [taskResponse, sealResponse] = await Promise.all([
      http.get<WorkflowTask[]>('/workflows/tasks'),
      http.get<SealRequest[]>('/seals/requests'),
    ])
    tasks.value = taskResponse.data
    sealRequests.value = sealResponse.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('审批数据加载失败', 'Failed to load approvals'))
  } finally {
    loading.value = false
  }
}

async function openAction(task: WorkflowTask, nextAction: ApprovalAction) {
  selectedTask.value = task
  action.value = nextAction
  comment.value = ''
  targetUserId.value = ''
  transferTargets.value = []
  if (nextAction === 'TRANSFER') {
    try {
      transferTargets.value = (await http.get<TransferTarget[]>(
        `/workflows/tasks/${task.id}/transfer-targets`,
      )).data
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : text('无法加载可转交人员', 'Could not load eligible users'))
      return
    }
  }
  actionVisible.value = true
}

async function submitAction() {
  const task = selectedTask.value
  if (!task) return
  if (action.value === 'REJECT' && !comment.value.trim()) {
    ElMessage.warning(text('驳回必须填写原因', 'A rejection reason is required'))
    return
  }
  if (action.value === 'TRANSFER' && !targetUserId.value) {
    ElMessage.warning(text('请选择有权处理该业务的转交人员', 'Select an eligible recipient'))
    return
  }
  processing.value = task.id
  try {
    const headers = { 'Idempotency-Key': crypto.randomUUID() }
    if (action.value === 'TRANSFER') {
      await http.post(`/workflows/tasks/${task.id}/transfer`, {
        targetUserId: targetUserId.value,
        comment: comment.value.trim() || null,
      }, { headers })
    } else {
      await http.post(`/workflows/tasks/${task.id}/complete`, {
        decision: action.value,
        comment: comment.value.trim() || null,
        variables: { approved: action.value === 'APPROVE' },
      }, { headers })
    }
    actionVisible.value = false
    ElMessage.success(
      action.value === 'APPROVE'
        ? text('审批已通过', 'Task approved')
        : action.value === 'REJECT'
          ? text('审批已驳回', 'Task rejected')
          : text('审批已转交', 'Task transferred'),
    )
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('审批操作失败', 'Approval action failed'))
  } finally {
    processing.value = ''
  }
}

async function remind(task: WorkflowTask) {
  reminding.value = task.id
  try {
    await http.post(`/workflows/tasks/${task.id}/remind`)
    ElMessage.success(text('催办通知已发送，30 分钟内不会重复发送', 'Reminder sent; another can be sent after 30 minutes'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('催办失败', 'Reminder failed'))
  } finally {
    reminding.value = ''
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">APPROVAL DESK</span>
        <h2>{{ t('headline.approvals') }}</h2>
        <p>{{ text('通过、驳回、转交和催办均经过资格校验，并写入不可抵赖的审批审计链。', 'Approve, reject, transfer and remind actions are eligibility-checked and fully audited.') }}</p>
      </div>
      <button class="primary-action" :disabled="loading" @click="load"><FileCheck2 :size="17" /> {{ text('刷新待办', 'Refresh') }}</button>
    </div>

    <div class="approval-grid">
      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">MY TASKS</span><h3>{{ text('我的审批待办', 'My approval tasks') }}</h3></div>
          <span class="status-pill">{{ tasks.length }} {{ text('项', 'items') }}</span>
        </div>
        <div v-if="loading" class="empty-state">{{ text('正在同步流程引擎…', 'Syncing workflow engine…') }}</div>
        <div v-else-if="tasks.length" class="approval-list">
          <article v-for="task in tasks" :key="task.id" class="approval-row task-row">
            <div class="approval-icon"><FileCheck2 :size="19" /></div>
            <div>
              <strong>{{ task.name }}</strong>
              <span>{{ task.businessType }} · {{ task.businessKey || task.processInstanceId }}</span>
              <small>{{ new Date(task.createdAt).toLocaleString(locale) }} · {{ task.assignee || task.candidateGroup }}</small>
            </div>
            <div class="approval-actions">
              <button class="table-action" :aria-label="text('通过', 'Approve')" @click="openAction(task, 'APPROVE')"><CheckCircle2 :size="15" /></button>
              <button class="table-action reject" :aria-label="text('驳回', 'Reject')" @click="openAction(task, 'REJECT')"><XCircle :size="15" /></button>
              <button class="table-action" :aria-label="text('转交', 'Transfer')" @click="openAction(task, 'TRANSFER')"><Send :size="15" /></button>
              <button class="table-action" :aria-label="text('催办', 'Remind')" :disabled="reminding === task.id" @click="remind(task)"><BellRing :size="15" /></button>
            </div>
          </article>
        </div>
        <div v-else class="empty-state">
          <CheckCircle2 :size="34" />
          <strong>{{ text('待办已清空', 'No pending tasks') }}</strong>
          <span>{{ text('你有权限处理的任务会出现在这里。', 'Tasks you are eligible to handle will appear here.') }}</span>
        </div>
      </div>

      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">SEAL REQUESTS</span><h3>{{ text('用印申请台账', 'Seal request register') }}</h3></div>
          <Stamp :size="21" />
        </div>
        <div v-if="sealRequests.length" class="approval-list">
          <article v-for="item in sealRequests" :key="item.id" class="approval-row compact-row">
            <div>
              <strong>{{ item.purpose }}</strong>
              <span>{{ item.sealName }} · {{ item.copies }} {{ text('份', 'copies') }} · {{ item.requestedByName }}</span>
              <small>{{ new Date(item.createdAt).toLocaleString(locale) }}</small>
            </div>
            <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
          </article>
        </div>
        <div v-else class="empty-state">{{ text('暂无用印申请', 'No seal requests') }}</div>
      </div>
    </div>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="min(540px, 92vw)">
      <div v-if="selectedTask" class="action-dialog">
        <div class="action-context">
          <strong>{{ selectedTask.name }}</strong>
          <span>{{ selectedTask.businessType }} · {{ selectedTask.businessKey }}</span>
        </div>
        <label v-if="action === 'TRANSFER'">
          <span>{{ text('可转交人员', 'Eligible recipient') }}</span>
          <select v-model="targetUserId" data-testid="transfer-target">
            <option value="">{{ text('请选择', 'Select') }}</option>
            <option v-for="target in transferTargets" :key="target.userId" :value="target.userId">
              {{ target.displayName }} · {{ target.username }}
            </option>
          </select>
          <small v-if="!transferTargets.length">{{ text('没有其他符合业务权限范围的人员', 'No other eligible users') }}</small>
        </label>
        <label>
          <span>
            {{ action === 'REJECT'
              ? text('驳回原因（必填）', 'Rejection reason (required)')
              : text('处理意见', 'Comment') }}
          </span>
          <textarea v-model="comment" rows="4" maxlength="500" :placeholder="text('最多 500 字', 'Up to 500 characters')" />
        </label>
        <button class="primary-action full" :disabled="processing === selectedTask.id" @click="submitAction">
          {{ processing === selectedTask.id ? text('处理中…', 'Processing…') : actionTitle }}
        </button>
      </div>
    </ElDialog>
  </section>
</template>

<style scoped>
.task-row { grid-template-columns: 42px minmax(0, 1fr) auto; }
.approval-actions { display: flex; gap: 5px; }
.approval-actions .table-action { flex: 0 0 32px; }
.approval-actions .reject { color: var(--oxblood); }
.action-dialog { display: grid; gap: 18px; }
.action-context { padding: 14px; background: #eeeae1; }
.action-context strong, .action-context span { display: block; }
.action-context strong { font-size: 13px; }
.action-context span { margin-top: 5px; color: var(--muted); font-size: 9px; }
.action-dialog label > span, .action-dialog label > small { display: block; margin-bottom: 7px; color: var(--ink-soft); font-size: 11px; }
.action-dialog label > small { margin: 7px 0 0; color: var(--muted); font-size: 9px; }
.action-dialog select, .action-dialog textarea { width: 100%; padding: 10px 11px; border: 1px solid var(--line); background: white; color: var(--ink); }
.action-dialog select { height: 43px; padding: 0 11px; }
@media (max-width: 680px) {
  .task-row { grid-template-columns: 36px 1fr; padding: 14px 0; }
  .approval-actions { grid-column: 2; }
}
</style>

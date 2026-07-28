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
  if (action.value === 'APPROVE') return t('copy.0021')
  if (action.value === 'REJECT') return t('copy.0022')
  return t('copy.0023')
})

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
    ElMessage.error(error instanceof Error ? error.message : t('copy.0024'))
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
      ElMessage.error(error instanceof Error ? error.message : t('copy.0025'))
      return
    }
  }
  actionVisible.value = true
}

async function submitAction() {
  const task = selectedTask.value
  if (!task) return
  if (action.value === 'REJECT' && !comment.value.trim()) {
    ElMessage.warning(t('copy.0026'))
    return
  }
  if (action.value === 'TRANSFER' && !targetUserId.value) {
    ElMessage.warning(t('copy.0027'))
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
        ? t('copy.0028')
        : action.value === 'REJECT'
          ? t('copy.0029')
          : t('copy.0030'),
    )
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0031'))
  } finally {
    processing.value = ''
  }
}

async function remind(task: WorkflowTask) {
  reminding.value = task.id
  try {
    await http.post(`/workflows/tasks/${task.id}/remind`)
    ElMessage.success(t('copy.0032'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0033'))
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
        <p>{{ t('copy.0034') }}</p>
      </div>
      <button class="primary-action" :disabled="loading" @click="load"><FileCheck2 :size="17" /> {{ t('copy.0035') }}</button>
    </div>

    <div class="approval-grid">
      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">MY TASKS</span><h3>{{ t('copy.0036') }}</h3></div>
          <span class="status-pill">{{ tasks.length }} {{ t('copy.0037') }}</span>
        </div>
        <div v-if="loading" class="empty-state">{{ t('copy.0038') }}</div>
        <div v-else-if="tasks.length" class="approval-list">
          <article v-for="task in tasks" :key="task.id" class="approval-row task-row">
            <div class="approval-icon"><FileCheck2 :size="19" /></div>
            <div>
              <strong>{{ task.name }}</strong>
              <span>{{ task.businessType }} · {{ task.businessKey || task.processInstanceId }}</span>
              <small>{{ new Date(task.createdAt).toLocaleString(locale) }} · {{ task.assignee || task.candidateGroup }}</small>
            </div>
            <div class="approval-actions">
              <button class="table-action" :aria-label="t('copy.0039')" @click="openAction(task, 'APPROVE')"><CheckCircle2 :size="15" /></button>
              <button class="table-action reject" :aria-label="t('copy.0040')" @click="openAction(task, 'REJECT')"><XCircle :size="15" /></button>
              <button class="table-action" :aria-label="t('copy.0041')" @click="openAction(task, 'TRANSFER')"><Send :size="15" /></button>
              <button class="table-action" :aria-label="t('copy.0042')" :disabled="reminding === task.id" @click="remind(task)"><BellRing :size="15" /></button>
            </div>
          </article>
        </div>
        <div v-else class="empty-state">
          <CheckCircle2 :size="34" />
          <strong>{{ t('copy.0043') }}</strong>
          <span>{{ t('copy.0044') }}</span>
        </div>
      </div>

      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">SEAL REQUESTS</span><h3>{{ t('copy.0045') }}</h3></div>
          <Stamp :size="21" />
        </div>
        <div v-if="sealRequests.length" class="approval-list">
          <article v-for="item in sealRequests" :key="item.id" class="approval-row compact-row">
            <div>
              <strong>{{ item.purpose }}</strong>
              <span>{{ item.sealName }} · {{ item.copies }} {{ t('copy.0046') }} · {{ item.requestedByName }}</span>
              <small>{{ new Date(item.createdAt).toLocaleString(locale) }}</small>
            </div>
            <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
          </article>
        </div>
        <div v-else class="empty-state">{{ t('copy.0047') }}</div>
      </div>
    </div>

    <ElDialog v-model="actionVisible" :title="actionTitle" width="min(540px, 92vw)">
      <div v-if="selectedTask" class="action-dialog">
        <div class="action-context">
          <strong>{{ selectedTask.name }}</strong>
          <span>{{ selectedTask.businessType }} · {{ selectedTask.businessKey }}</span>
        </div>
        <label v-if="action === 'TRANSFER'">
          <span>{{ t('copy.0048') }}</span>
          <select v-model="targetUserId" data-testid="transfer-target">
            <option value="">{{ t('copy.0049') }}</option>
            <option v-for="target in transferTargets" :key="target.userId" :value="target.userId">
              {{ target.displayName }} · {{ target.username }}
            </option>
          </select>
          <small v-if="!transferTargets.length">{{ t('copy.0050') }}</small>
        </label>
        <label>
          <span>
            {{ action === 'REJECT'
              ? t('copy.0051')
              : t('copy.0052') }}
          </span>
          <textarea v-model="comment" rows="4" maxlength="500" :placeholder="t('copy.0053')" />
        </label>
        <button class="primary-action full" :disabled="processing === selectedTask.id" @click="submitAction">
          {{ processing === selectedTask.id ? t('copy.0054') : actionTitle }}
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

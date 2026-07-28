<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { CheckCircle2, FileCheck2, Stamp } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

interface WorkflowTask {
  id: string
  name: string
  processInstanceId: string
  businessKey?: string
  assignee?: string
  createdAt: string
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

const tasks = ref<WorkflowTask[]>([])
const sealRequests = ref<SealRequest[]>([])
const loading = ref(true)
const completing = ref('')

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
    ElMessage.error(error instanceof Error ? error.message : '审批数据加载失败')
  } finally {
    loading.value = false
  }
}

async function complete(task: WorkflowTask) {
  completing.value = task.id
  try {
    await http.post(`/workflows/tasks/${task.id}/complete`, {
      comment: '在 OA 审批中心确认通过',
      variables: { approved: true },
    })
    ElMessage.success(`“${task.name}”已处理`)
    await load()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '审批失败')
  } finally {
    completing.value = ''
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
        <p>立案、合同和用印按岗位候选组流转，处理动作与意见写入审计链。</p>
      </div>
      <button class="primary-action" @click="load"><FileCheck2 :size="17" /> 刷新待办</button>
    </div>

    <div class="approval-grid">
      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">MY TASKS</span><h3>我的审批待办</h3></div>
          <span class="status-pill">{{ tasks.length }} 项</span>
        </div>
        <div v-if="loading" class="empty-state">正在同步流程引擎…</div>
        <div v-else-if="tasks.length" class="approval-list">
          <article v-for="task in tasks" :key="task.id" class="approval-row">
            <div class="approval-icon"><FileCheck2 :size="19" /></div>
            <div>
              <strong>{{ task.name }}</strong>
              <span>{{ task.businessKey || task.processInstanceId }}</span>
              <small>{{ new Date(task.createdAt).toLocaleString('zh-CN') }}</small>
            </div>
            <button class="table-action approve" :disabled="completing === task.id" @click="complete(task)">
              <CheckCircle2 :size="16" /> {{ completing === task.id ? '处理中' : '通过' }}
            </button>
          </article>
        </div>
        <div v-else class="empty-state">
          <CheckCircle2 :size="34" />
          <strong>待办已清空</strong>
          <span>你有权限处理的任务会出现在这里。</span>
        </div>
      </div>

      <div class="panel">
        <div class="panel-heading">
          <div><span class="eyebrow">SEAL REQUESTS</span><h3>用印申请台账</h3></div>
          <Stamp :size="21" />
        </div>
        <div v-if="sealRequests.length" class="approval-list">
          <article v-for="item in sealRequests" :key="item.id" class="approval-row compact-row">
            <div>
              <strong>{{ item.purpose }}</strong>
              <span>{{ item.sealName }} · {{ item.copies }} 份 · {{ item.requestedByName }}</span>
              <small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</small>
            </div>
            <span class="status-pill">{{ item.status }}</span>
          </article>
        </div>
        <div v-else class="empty-state">暂无用印申请</div>
      </div>
    </div>
  </section>
</template>

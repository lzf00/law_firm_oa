<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarDays, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface Leave {
  id: string; requestNumber: string; applicantName: string; leaveType: string
  startAt: string; endAt: string; durationHours: number; reason: string; status: string
}
const items = ref<Leave[]>([])
const dialog = ref(false)
const form = reactive({
  leaveType: 'ANNUAL', startAt: '', endAt: '', durationHours: 8, reason: '', emergencyContact: '',
})
const pending = computed(() => items.value.filter((item) => item.status === 'SUBMITTED').length)
const leaveTypes = ['ANNUAL', 'PERSONAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'OTHER']
async function load() { items.value = (await http.get<Leave[]>('/leave-requests')).data }
async function createAndSubmit() {
  try {
    const created = (await http.post<Leave>('/leave-requests', {
      ...form,
      startAt: new Date(form.startAt).toISOString(),
      endAt: new Date(form.endAt).toISOString(),
    })).data
    await http.post(`/leave-requests/${created.id}/submit`, null, {
      headers: { 'Idempotency-Key': `leave-ui-${created.id}` },
    })
    dialog.value = false
    await load()
    ElMessage.success(text('请假申请已提交，进入两级审批', 'Leave request submitted for two-level approval'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : text('请假提交失败', 'Failed to submit leave request')) }
}
onMounted(() => load().catch(() => ElMessage.error(text('请假数据加载失败', 'Failed to load leave requests'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">LEAVE · {{ pending }} PENDING</span><h2>{{ t('headline.leave') }}</h2><p>{{ text('支持年假、事假、病假等类型，负责人审批后由人事复核登记。', 'Supports multiple leave types, with manager approval followed by HR review and registration.') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ text('请假申请', 'Request leave') }}</button>
    </div>
    <div class="panel table-panel">
      <table>
        <thead><tr><th>{{ text('申请编号', 'Request no.') }}</th><th>{{ text('申请人 / 类型', 'Applicant / type') }}</th><th>{{ text('时间', 'Dates') }}</th><th>{{ text('时长', 'Duration') }}</th><th>{{ text('事由', 'Reason') }}</th><th>{{ text('状态', 'Status') }}</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td class="mono">{{ item.requestNumber }}</td>
            <td><strong>{{ item.applicantName }}</strong><small>{{ formatLegalCode(item.leaveType, locale) }}</small></td>
            <td><strong>{{ new Date(item.startAt).toLocaleString(locale) }}</strong><small>{{ text('至', 'to') }} {{ new Date(item.endAt).toLocaleString(locale) }}</small></td>
            <td>{{ item.durationHours }} {{ text('小时', 'hours') }}</td><td>{{ item.reason }}</td><td><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!items.length" class="empty-state"><CalendarDays :size="30" /> {{ text('暂无请假记录', 'No leave requests') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="text('提交请假申请', 'Submit leave request')" width="540px">
      <div class="dialog-form two-column-form">
        <label><span>{{ text('请假类型', 'Leave type') }}</span><select v-model="form.leaveType"><option v-for="code in leaveTypes" :key="code" :value="code">{{ formatLegalCode(code, locale) }}</option></select></label>
        <label><span>{{ text('请假时长（小时）', 'Duration (hours)') }}</span><input v-model.number="form.durationHours" type="number" min="0.25" step="0.25" /></label>
        <label><span>{{ text('开始时间', 'Start') }}</span><input v-model="form.startAt" type="datetime-local" /></label>
        <label><span>{{ text('结束时间', 'End') }}</span><input v-model="form.endAt" type="datetime-local" /></label>
        <label class="full-field"><span>{{ text('请假事由', 'Reason') }}</span><textarea v-model="form.reason" rows="4" :placeholder="text('请说明请假原因', 'Explain the reason for leave')"></textarea></label>
        <label class="full-field"><span>{{ text('紧急联系人', 'Emergency contact') }}</span><input v-model="form.emergencyContact" :placeholder="text('选填', 'Optional')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="createAndSubmit"><Send :size="16" /> {{ text('创建并提交审批', 'Create and submit') }}</button></template>
    </el-dialog>
  </section>
</template>

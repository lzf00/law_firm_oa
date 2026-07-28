<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarDays, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

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
const labels: Record<string, string> = {
  ANNUAL: '年假', PERSONAL: '事假', SICK: '病假', MARRIAGE: '婚假', MATERNITY: '产假', OTHER: '其他',
}
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
    ElMessage.success('请假申请已提交，进入两级审批')
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : '请假提交失败') }
}
onMounted(() => load().catch(() => ElMessage.error('请假数据加载失败')))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">LEAVE · {{ pending }} PENDING</span><h2>{{ t('headline.leave') }}</h2><p>支持年假、事假、病假等类型，负责人审批后由人事复核登记。</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> 请假申请</button>
    </div>
    <div class="panel table-panel">
      <table>
        <thead><tr><th>申请编号</th><th>申请人 / 类型</th><th>时间</th><th>时长</th><th>事由</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td class="mono">{{ item.requestNumber }}</td>
            <td><strong>{{ item.applicantName }}</strong><small>{{ labels[item.leaveType] || item.leaveType }}</small></td>
            <td><strong>{{ new Date(item.startAt).toLocaleString('zh-CN') }}</strong><small>至 {{ new Date(item.endAt).toLocaleString('zh-CN') }}</small></td>
            <td>{{ item.durationHours }} 小时</td><td>{{ item.reason }}</td><td><span class="status-pill">{{ item.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!items.length" class="empty-state"><CalendarDays :size="30" /> 暂无请假记录</div>
    </div>
    <el-dialog v-model="dialog" title="提交请假申请" width="540px">
      <div class="dialog-form two-column-form">
        <label><span>请假类型</span><select v-model="form.leaveType"><option v-for="(label, code) in labels" :key="code" :value="code">{{ label }}</option></select></label>
        <label><span>请假时长（小时）</span><input v-model.number="form.durationHours" type="number" min="0.25" step="0.25" /></label>
        <label><span>开始时间</span><input v-model="form.startAt" type="datetime-local" /></label>
        <label><span>结束时间</span><input v-model="form.endAt" type="datetime-local" /></label>
        <label class="full-field"><span>请假事由</span><textarea v-model="form.reason" rows="4" placeholder="请说明请假原因"></textarea></label>
        <label class="full-field"><span>紧急联系人</span><input v-model="form.emergencyContact" placeholder="选填" /></label>
      </div>
      <template #footer><button class="primary-action" @click="createAndSubmit"><Send :size="16" /> 创建并提交审批</button></template>
    </el-dialog>
  </section>
</template>

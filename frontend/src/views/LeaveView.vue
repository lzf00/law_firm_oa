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
    ElMessage.success(t('copy.0179'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('copy.0180')) }
}
onMounted(() => load().catch(() => ElMessage.error(t('copy.0181'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">LEAVE · {{ pending }} PENDING</span><h2>{{ t('headline.leave') }}</h2><p>{{ t('copy.0182') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ t('copy.0183') }}</button>
    </div>
    <div class="panel table-panel" tabindex="0">
      <table>
        <thead><tr><th>{{ t('copy.0184') }}</th><th>{{ t('copy.0185') }}</th><th>{{ t('copy.0186') }}</th><th>{{ t('copy.0187') }}</th><th>{{ t('copy.0188') }}</th><th>{{ t('copy.0189') }}</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td class="mono">{{ item.requestNumber }}</td>
            <td><strong>{{ item.applicantName }}</strong><small>{{ formatLegalCode(item.leaveType, locale) }}</small></td>
            <td><strong>{{ new Date(item.startAt).toLocaleString(locale) }}</strong><small>{{ t('copy.0190') }} {{ new Date(item.endAt).toLocaleString(locale) }}</small></td>
            <td>{{ item.durationHours }} {{ t('copy.0191') }}</td><td>{{ item.reason }}</td><td><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!items.length" class="empty-state"><CalendarDays :size="30" /> {{ t('copy.0192') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="t('copy.0193')" width="540px">
      <div class="dialog-form two-column-form">
        <label><span>{{ t('copy.0194') }}</span><select v-model="form.leaveType"><option v-for="code in leaveTypes" :key="code" :value="code">{{ formatLegalCode(code, locale) }}</option></select></label>
        <label><span>{{ t('copy.0195') }}</span><input v-model.number="form.durationHours" type="number" min="0.25" step="0.25" /></label>
        <label><span>{{ t('copy.0196') }}</span><input v-model="form.startAt" type="datetime-local" /></label>
        <label><span>{{ t('copy.0197') }}</span><input v-model="form.endAt" type="datetime-local" /></label>
        <label class="full-field"><span>{{ t('copy.0198') }}</span><textarea v-model="form.reason" rows="4" :placeholder="t('copy.0199')"></textarea></label>
        <label class="full-field"><span>{{ t('copy.0200') }}</span><input v-model="form.emergencyContact" :placeholder="t('copy.0201')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="createAndSubmit"><Send :size="16" /> {{ t('copy.0178') }}</button></template>
    </el-dialog>
  </section>
</template>

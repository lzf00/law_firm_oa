<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CalendarDays, CircleCheck, Clock3, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface Leave {
  id: string; requestNumber: string; applicantName: string; leaveType: string
  startAt: string; endAt: string; durationHours: number; reason: string; status: string
  officeNameZh?: string; officeNameEn?: string
}
const items = ref<Leave[]>([])
const dialog = ref(false)
const busy = ref(false)
const statusFilter = ref<'ALL' | 'SUBMITTED' | 'APPROVED'>('ALL')
const form = reactive({
  leaveType: 'ANNUAL', startAt: '', endAt: '', durationHours: 8, reason: '', emergencyContact: '',
})
const pending = computed(() => items.value.filter((item) => item.status === 'SUBMITTED').length)
const approved = computed(() => items.value.filter((item) => item.status === 'APPROVED').length)
const totalHours = computed(() => items.value.reduce((sum, item) => sum + Number(item.durationHours), 0))
const filteredItems = computed(() => items.value.filter((item) =>
  statusFilter.value === 'ALL' || item.status === statusFilter.value))
const canSubmit = computed(() =>
  Boolean(form.startAt && form.endAt && form.reason.trim())
  && Number(form.durationHours) >= 0.25
  && new Date(form.endAt).getTime() > new Date(form.startAt).getTime())
const leaveTypes = ['ANNUAL', 'PERSONAL', 'SICK', 'MARRIAGE', 'MATERNITY', 'OTHER']
async function load() { items.value = (await http.get<Leave[]>('/leave-requests')).data }
function toLocalInput(date: Date) {
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}
function openDialog() {
  if (!form.startAt) {
    const start = new Date()
    start.setMinutes(0, 0, 0)
    start.setHours(start.getHours() + 1)
    form.startAt = toLocalInput(start)
    form.endAt = toLocalInput(new Date(start.getTime() + 8 * 60 * 60 * 1000))
  }
  dialog.value = true
}
async function createAndSubmit() {
  if (!canSubmit.value || busy.value) return
  busy.value = true
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
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0180'))
  } finally {
    busy.value = false
  }
}
onMounted(() => load().catch(() => ElMessage.error(t('copy.0181'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">LEAVE · {{ pending }} PENDING</span><h2>{{ t('headline.leave') }}</h2><p>{{ t('copy.0182') }}</p></div>
      <button class="primary-action" @click="openDialog"><Plus :size="17" /> {{ t('copy.0183') }}</button>
    </div>
    <section class="leave-summary" :aria-label="t('leave.summary')">
      <article><span><Clock3 :size="18" /></span><div><small>{{ t('leave.pending') }}</small><strong>{{ pending }}</strong></div></article>
      <article><span><CircleCheck :size="18" /></span><div><small>{{ t('leave.approved') }}</small><strong>{{ approved }}</strong></div></article>
      <article><span><CalendarDays :size="18" /></span><div><small>{{ t('leave.recordedHours') }}</small><strong>{{ totalHours }}</strong></div></article>
      <div class="leave-filters">
        <button type="button" :class="{ active: statusFilter === 'ALL' }" @click="statusFilter = 'ALL'">{{ t('announcement.all') }}</button>
        <button type="button" :class="{ active: statusFilter === 'SUBMITTED' }" @click="statusFilter = 'SUBMITTED'">{{ t('leave.pending') }}</button>
        <button type="button" :class="{ active: statusFilter === 'APPROVED' }" @click="statusFilter = 'APPROVED'">{{ t('leave.approved') }}</button>
      </div>
    </section>
    <div class="panel table-panel" tabindex="0">
      <table>
        <thead><tr><th>{{ t('copy.0184') }}</th><th>{{ t('copy.0185') }}</th><th>{{ t('copy.0186') }}</th><th>{{ t('copy.0187') }}</th><th>{{ t('copy.0188') }}</th><th>{{ t('copy.0189') }}</th></tr></thead>
        <tbody>
          <tr v-for="item in filteredItems" :key="item.id">
            <td class="mono">{{ item.requestNumber }}</td>
            <td><strong>{{ item.applicantName }}</strong><small>{{ formatLegalCode(item.leaveType, locale) }} · {{ (locale === 'en-US' ? item.officeNameEn : item.officeNameZh) || '—' }}</small></td>
            <td><strong>{{ new Date(item.startAt).toLocaleString(locale) }}</strong><small>{{ t('copy.0190') }} {{ new Date(item.endAt).toLocaleString(locale) }}</small></td>
            <td>{{ item.durationHours }} {{ t('copy.0191') }}</td><td>{{ item.reason }}</td><td><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-if="!filteredItems.length" class="empty-state"><CalendarDays :size="30" /> {{ t('copy.0192') }}</div>
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
      <template #footer><button class="primary-action" :disabled="!canSubmit || busy" @click="createAndSubmit"><Send :size="16" /> {{ busy ? t('common.saving') : t('copy.0178') }}</button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.leave-summary {
  display: grid; grid-template-columns: repeat(3, minmax(130px, .55fr)) minmax(310px, 1.4fr);
  gap: 1px; margin-bottom: 14px; overflow: hidden; border: 1px solid var(--line);
  border-radius: 12px; background: var(--line); box-shadow: var(--shadow-sm);
}
.leave-summary > article {
  min-height: 76px; display: flex; align-items: center; gap: 11px;
  padding: 13px 15px; background: white;
}
.leave-summary > article > span {
  width: 36px; height: 36px; display: grid; place-items: center;
  border-radius: 9px; background: var(--forest-3); color: var(--forest-2);
}
.leave-summary small, .leave-summary strong { display: block; }
.leave-summary small { color: var(--muted); font-size: 8px; }
.leave-summary strong { margin-top: 3px; font: 650 20px/1 Georgia, serif; }
.leave-filters {
  display: flex; align-items: center; justify-content: flex-end; gap: 5px;
  padding: 12px 14px; background: white;
}
.leave-filters button {
  min-height: 34px; padding: 0 12px; border: 0; border-radius: 8px;
  background: transparent; color: var(--muted); cursor: pointer; font-size: 9px; font-weight: 650;
}
.leave-filters button:hover { background: var(--forest-3); color: var(--forest-2); }
.leave-filters button.active { background: var(--forest); color: white; }
.table-panel tbody tr { transition: background .16s ease; }
.table-panel tbody tr:hover { background: #f7faf8; }
@media (max-width: 1000px) {
  .leave-summary { grid-template-columns: repeat(3, 1fr); }
  .leave-filters { grid-column: 1 / -1; justify-content: flex-start; }
}
@media (max-width: 700px) {
  .leave-summary { grid-template-columns: 1fr; }
  .leave-filters { grid-column: auto; overflow-x: auto; }
  .leave-filters button { flex: 1; min-width: max-content; }
}
</style>

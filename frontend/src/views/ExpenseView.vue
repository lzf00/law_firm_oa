<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleDollarSign, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
interface ExpenseItem { amount: number; category: string; description: string }
interface Expense {
  id: string; claimNumber: string; applicantName: string; title: string; purpose: string
  totalAmount: number; status: string; createdAt: string; items: ExpenseItem[]
}
const items = ref<Expense[]>([])
const dialog = ref(false)
const today = new Date().toISOString().slice(0, 10)
const form = reactive({ title: '', purpose: '', category: 'TRANSPORT', occurredOn: today, description: '', amount: 0 })
const expenseCategories = [
  { value: 'TRANSPORT', labelKey: 'copy.expense.transport' },
  { value: 'LODGING', labelKey: 'copy.expense.lodging' },
  { value: 'ENTERTAINMENT', labelKey: 'copy.expense.entertainment' },
  { value: 'OFFICE', labelKey: 'copy.expense.office' },
  { value: 'OTHER', labelKey: 'copy.expense.other' },
]
const totalApproved = computed(() => items.value.filter((item) => ['APPROVED', 'PAID'].includes(item.status)).reduce((sum, item) => sum + Number(item.totalAmount), 0))
async function load() { items.value = (await http.get<Expense[]>('/expense-claims')).data }
async function createAndSubmit() {
  try {
    const created = (await http.post<Expense>('/expense-claims', {
      title: form.title,
      purpose: form.purpose,
      items: [{ category: form.category, occurredOn: form.occurredOn, description: form.description, amount: form.amount }],
    })).data
    await http.post(`/expense-claims/${created.id}/submit`, null, {
      headers: { 'Idempotency-Key': `expense-ui-${created.id}` },
    })
    dialog.value = false
    await load()
    ElMessage.success(t('copy.0159'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('copy.0160')) }
}
async function pay(item: Expense) {
  await http.post(`/expense-claims/${item.id}/pay`)
  await load()
  ElMessage.success(t('copy.0161'))
}
function money(value: number) { return new Intl.NumberFormat(locale.value, { style: 'currency', currency: 'CNY' }).format(value) }
onMounted(() => load().catch(() => ElMessage.error(t('copy.0162'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">EXPENSES · {{ money(totalApproved) }} APPROVED</span><h2>{{ t('headline.expenses') }}</h2><p>{{ t('copy.0163') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ t('copy.0164') }}</button>
    </div>
    <div class="expense-grid">
      <article v-for="item in items" :key="item.id" class="expense-card panel">
        <div class="expense-amount">{{ money(item.totalAmount) }}</div>
        <span class="mono">{{ item.claimNumber }}</span><h3>{{ item.title }}</h3><p>{{ item.purpose }}</p>
        <div class="expense-meta"><span>{{ item.applicantName }}</span><span>{{ item.items.length }} {{ t('copy.0165') }}</span><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span></div>
        <button v-if="item.status === 'APPROVED'" class="secondary-action compact-action" @click="pay(item)">{{ t('copy.0166') }}</button>
      </article>
      <div v-if="!items.length" class="panel empty-state"><CircleDollarSign :size="30" /> {{ t('copy.0167') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="t('copy.0168')" width="560px">
      <div class="dialog-form two-column-form">
        <label class="full-field"><span>{{ t('copy.0169') }}</span><input v-model="form.title" :placeholder="t('copy.0170')" /></label>
        <label class="full-field"><span>{{ t('copy.0171') }}</span><input v-model="form.purpose" :placeholder="t('copy.0172')" /></label>
        <label><span>{{ t('copy.0173') }}</span><select v-model="form.category"><option v-for="category in expenseCategories" :key="category.value" :value="category.value">{{ t(category.labelKey) }}</option></select></label>
        <label><span>{{ t('copy.0174') }}</span><input v-model="form.occurredOn" type="date" /></label>
        <label><span>{{ t('copy.0175') }}</span><input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label>
        <label><span>{{ t('copy.0176') }}</span><input v-model="form.description" :placeholder="t('copy.0177')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="createAndSubmit"><Send :size="16" /> {{ t('copy.0178') }}</button></template>
    </el-dialog>
  </section>
</template>

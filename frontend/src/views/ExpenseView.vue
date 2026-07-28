<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CircleDollarSign, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface ExpenseItem { amount: number; category: string; description: string }
interface Expense {
  id: string; claimNumber: string; applicantName: string; title: string; purpose: string
  totalAmount: number; status: string; createdAt: string; items: ExpenseItem[]
}
const items = ref<Expense[]>([])
const dialog = ref(false)
const today = new Date().toISOString().slice(0, 10)
const form = reactive({ title: '', purpose: '', category: '交通费', occurredOn: today, description: '', amount: 0 })
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
    ElMessage.success(text('报销单已提交，进入负责人和财务两级审批', 'Expense claim submitted for manager and finance approval'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : text('报销提交失败', 'Failed to submit expense claim')) }
}
async function pay(item: Expense) {
  await http.post(`/expense-claims/${item.id}/pay`)
  await load()
  ElMessage.success(text('付款状态已登记', 'Payment status recorded'))
}
function money(value: number) { return new Intl.NumberFormat(locale.value, { style: 'currency', currency: 'CNY' }).format(value) }
onMounted(() => load().catch(() => ElMessage.error(text('报销数据加载失败', 'Failed to load expense claims'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">EXPENSES · {{ money(totalApproved) }} APPROVED</span><h2>{{ t('headline.expenses') }}</h2><p>{{ text('报销总额由明细自动汇总，审批通过后由财务登记付款，支持关联案件和票据文档。', 'Line items calculate claim totals automatically; finance records payment after approval, with matter and receipt links supported.') }}</p></div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ text('新建报销', 'New claim') }}</button>
    </div>
    <div class="expense-grid">
      <article v-for="item in items" :key="item.id" class="expense-card panel">
        <div class="expense-amount">{{ money(item.totalAmount) }}</div>
        <span class="mono">{{ item.claimNumber }}</span><h3>{{ item.title }}</h3><p>{{ item.purpose }}</p>
        <div class="expense-meta"><span>{{ item.applicantName }}</span><span>{{ item.items.length }} {{ text('笔明细', 'items') }}</span><span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span></div>
        <button v-if="item.status === 'APPROVED'" class="secondary-action compact-action" @click="pay(item)">{{ text('登记付款', 'Record payment') }}</button>
      </article>
      <div v-if="!items.length" class="panel empty-state"><CircleDollarSign :size="30" /> {{ text('暂无报销记录', 'No expense claims') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="text('新建费用报销', 'New expense claim')" width="560px">
      <div class="dialog-form two-column-form">
        <label class="full-field"><span>{{ text('报销标题', 'Claim title') }}</span><input v-model="form.title" :placeholder="text('如：上海出差交通住宿', 'e.g. Shanghai business trip travel and lodging')" /></label>
        <label class="full-field"><span>{{ text('费用用途', 'Business purpose') }}</span><input v-model="form.purpose" :placeholder="text('说明业务目的', 'Describe the business purpose')" /></label>
        <label><span>{{ text('费用类别', 'Category') }}</span><select v-model="form.category"><option>交通费</option><option>住宿费</option><option>招待费</option><option>办公费</option><option>其他</option></select></label>
        <label><span>{{ text('发生日期', 'Date incurred') }}</span><input v-model="form.occurredOn" type="date" /></label>
        <label><span>{{ text('金额（元）', 'Amount (CNY)') }}</span><input v-model.number="form.amount" type="number" min="0.01" step="0.01" /></label>
        <label><span>{{ text('明细说明', 'Item description') }}</span><input v-model="form.description" :placeholder="text('费用明细', 'Expense details')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="createAndSubmit"><Send :size="16" /> {{ text('创建并提交审批', 'Create and submit') }}</button></template>
    </el-dialog>
  </section>
</template>

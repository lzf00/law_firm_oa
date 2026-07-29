<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Check,
  CircleDollarSign,
  FileCheck2,
  FileSearch,
  Fingerprint,
  Plus,
  ReceiptText,
  Send,
  ShieldCheck,
  Trash2,
  Upload,
  WalletCards,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface ExpenseItem {
  id: string
  amount: number
  category: string
  occurredOn: string
  description: string
  receiptDocumentId?: string
  receiptVersionId?: string
  receiptFilename?: string
}

interface Expense {
  id: string
  claimNumber: string
  applicantUserId: string
  applicantName: string
  title: string
  purpose: string
  matterId?: string
  matterNumber?: string
  totalAmount: number
  currency: string
  status: string
  createdAt: string
  reviewStage?: string
  reviewTaskName?: string
  reviewGroup?: string
  items: ExpenseItem[]
}

interface ExpenseLineForm {
  localId: number
  category: string
  occurredOn: string
  description: string
  amount: number
  file?: File
}

interface UploadTicket {
  uploadId: string
  uploadUrl: string
  requiredContentType: string
}

const { locale } = useI18n()
const today = new Date().toISOString().slice(0, 10)
let nextLineId = 1
const claims = ref<Expense[]>([])
const matters = ref<Matter[]>([])
const currentUser = ref<CurrentUser | null>(null)
const dialog = ref(false)
const loading = ref(true)
const saving = ref(false)
const form = reactive({
  title: '',
  purpose: '',
  matterId: '',
  items: [newLine()] as ExpenseLineForm[],
})
const expenseCategories = [
  { value: 'TRANSPORT', labelKey: 'copy.expense.transport' },
  { value: 'LODGING', labelKey: 'copy.expense.lodging' },
  { value: 'ENTERTAINMENT', labelKey: 'copy.expense.entertainment' },
  { value: 'OFFICE', labelKey: 'copy.expense.office' },
  { value: 'OTHER', labelKey: 'copy.expense.other' },
]
const workflowStages = [
  'expense.stageDraft',
  'expense.stageManager',
  'expense.stageFinance',
  'expense.stageApproved',
  'expense.stagePaid',
]

const totalApproved = computed(() => claims.value
  .filter((item) => ['APPROVED', 'PAID'].includes(item.status))
  .reduce((sum, item) => sum + Number(item.totalAmount), 0))
const pendingCount = computed(() =>
  claims.value.filter((item) => item.status === 'SUBMITTED').length)
const claimTotal = computed(() =>
  form.items.reduce((sum, item) => sum + Number(item.amount || 0), 0))
const canPay = computed(() =>
  Boolean(currentUser.value?.permissions.includes('EXPENSE_PAY')))

function newLine(): ExpenseLineForm {
  return {
    localId: nextLineId++,
    category: 'TRANSPORT',
    occurredOn: today,
    description: '',
    amount: 0,
  }
}

function money(value: number, currency = currentUser.value?.defaultCurrency ?? 'CNY') {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(Number(value || 0))
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(value))
}

function stageIndex(item: Expense) {
  if (item.status === 'PAID') return 4
  if (item.status === 'APPROVED') return 3
  if (item.status === 'SUBMITTED' && item.reviewStage === 'financeReview') return 2
  if (item.status === 'SUBMITTED') return 1
  return 0
}

function currentStageLabel(item: Expense) {
  return t(workflowStages[stageIndex(item)] ?? 'expense.stageDraft')
}

async function load() {
  loading.value = true
  try {
    const [claimResult, matterResult, meResult] = await Promise.all([
      http.get<Expense[]>('/expense-claims'),
      http.get<Matter[]>('/matters'),
      http.get<CurrentUser>('/me'),
    ])
    claims.value = claimResult.data
    matters.value = matterResult.data
    currentUser.value = meResult.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('expense.failed'))
  } finally {
    loading.value = false
  }
}

function openDialog() {
  form.title = ''
  form.purpose = ''
  form.matterId = matters.value[0]?.id ?? ''
  form.items = [newLine()]
  dialog.value = true
}

function removeLine(localId: number) {
  if (form.items.length === 1) return
  form.items = form.items.filter((item) => item.localId !== localId)
}

function selectFile(event: Event, line: ExpenseLineForm) {
  line.file = (event.target as HTMLInputElement).files?.[0]
}

async function sha256(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('')
}

async function uploadReceipt(line: ExpenseLineForm) {
  if (!line.file) return null
  if (!form.matterId) throw new Error(t('expense.matterRequired'))
  const hash = await sha256(line.file)
  const duplicate = (await http.post<{ duplicate: boolean }>(
    '/expense-claims/receipt-check',
    { matterId: form.matterId, sha256: hash },
  )).data.duplicate
  if (duplicate) throw new Error(t('expense.duplicate'))
  const ticket = (await http.post<UploadTicket>('/documents/uploads', {
    matterId: form.matterId,
    logicalName: `${form.title || t('expense.receipt')} - ${line.description || line.category}`,
    documentType: 'EXPENSE_RECEIPT',
    originalFilename: line.file.name,
    contentType: line.file.type || 'application/pdf',
    sizeBytes: line.file.size,
    sha256: hash,
  })).data
  const uploaded = await fetch(ticket.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': ticket.requiredContentType },
    body: line.file,
  })
  if (!uploaded.ok) throw new Error(t('expense.uploadFailed'))
  return (await http.post<{ id: string }>(
    `/documents/uploads/${ticket.uploadId}/complete`,
  )).data.id
}

async function createAndSubmit() {
  saving.value = true
  try {
    const receiptIds = await Promise.all(form.items.map(uploadReceipt))
    const created = (await http.post<Expense>('/expense-claims', {
      title: form.title,
      purpose: form.purpose,
      matterId: form.matterId || null,
      items: form.items.map((item, index) => ({
        category: item.category,
        occurredOn: item.occurredOn,
        description: item.description,
        amount: Number(item.amount),
        receiptDocumentId: receiptIds[index],
      })),
    })).data
    await http.post(`/expense-claims/${created.id}/submit`, null, {
      headers: { 'Idempotency-Key': `expense-ui-${created.id}` },
    })
    dialog.value = false
    await load()
    ElMessage.success(t('expense.saved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('expense.failed'))
  } finally {
    saving.value = false
  }
}

async function pay(item: Expense) {
  try {
    await http.post(`/expense-claims/${item.id}/pay`)
    await load()
    ElMessage.success(t('copy.0161'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('expense.failed'))
  }
}

async function previewReceipt(item: ExpenseItem) {
  if (!item.receiptDocumentId || !item.receiptVersionId) return
  try {
    const ticket = (await http.post<{ downloadUrl: string }>(
      `/documents/${item.receiptDocumentId}/versions/${item.receiptVersionId}/preview-url`,
    )).data
    window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('expense.failed'))
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page expense-page">
    <header class="page-intro expense-intro">
      <div>
        <span class="eyebrow">{{ t('expense.kicker') }}</span>
        <h2>{{ t('headline.expenses') }}</h2>
        <p>{{ t('expense.intro') }}</p>
      </div>
      <button class="primary-action" type="button" @click="openDialog">
        <Plus :size="17" />{{ t('expense.newClaim') }}
      </button>
    </header>

    <section class="expense-metrics" aria-label="Expense summary">
      <article>
        <span><CircleDollarSign :size="18" /></span>
        <div><small>{{ t('expense.totalApproved') }}</small><strong>{{ money(totalApproved) }}</strong></div>
      </article>
      <article>
        <span><FileCheck2 :size="18" /></span>
        <div><small>{{ t('expense.awaitingReview') }}</small><strong>{{ pendingCount }}</strong></div>
      </article>
      <article>
        <span><Fingerprint :size="18" /></span>
        <div><small>{{ t('expense.receiptProtected') }}</small><strong>SHA-256</strong></div>
      </article>
    </section>

    <div v-if="loading" class="panel empty-state">{{ t('shell.connecting') }}</div>
    <section v-else-if="claims.length" class="expense-list">
      <article v-for="item in claims" :key="item.id" class="expense-card panel">
        <header>
          <div class="claim-mark"><ReceiptText :size="19" /></div>
          <div class="claim-heading">
            <span class="mono">{{ item.claimNumber }}</span>
            <h3>{{ item.title }}</h3>
            <p>{{ item.purpose }}</p>
          </div>
          <div class="claim-amount">
            <strong>{{ money(item.totalAmount, item.currency) }}</strong>
            <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
          </div>
        </header>

        <div class="claim-context">
          <span>{{ item.applicantName }}</span>
          <span>{{ item.matterNumber || '—' }}</span>
          <span>{{ formatDate(item.createdAt) }}</span>
          <span>{{ item.items.length }} {{ t('copy.0165') }}</span>
        </div>

        <div class="review-track" :aria-label="t('expense.workflow')">
          <span
            v-for="(stage, index) in workflowStages"
            :key="stage"
            :class="{ completed: index < stageIndex(item), active: index === stageIndex(item) }"
          >
            <i><Check v-if="index < stageIndex(item)" :size="10" />{{ index < stageIndex(item) ? '' : index + 1 }}</i>
            <em>{{ t(stage) }}</em>
          </span>
        </div>

        <div class="claim-lines">
          <article v-for="line in item.items" :key="line.id">
            <div>
              <strong>{{ formatLegalCode(line.category, locale) }}</strong>
              <span>{{ line.description }} · {{ formatDate(line.occurredOn) }}</span>
            </div>
            <strong>{{ money(line.amount, item.currency) }}</strong>
            <button
              v-if="line.receiptVersionId"
              type="button"
              :aria-label="t('expense.previewReceipt')"
              @click="previewReceipt(line)"
            >
              <FileSearch :size="14" />{{ line.receiptFilename || t('expense.previewReceipt') }}
            </button>
            <span v-else class="no-receipt">—</span>
          </article>
        </div>

        <footer>
          <span><ShieldCheck :size="15" />{{ t('expense.workflow') }} · {{ currentStageLabel(item) }}</span>
          <button
            v-if="canPay && item.status === 'APPROVED'"
            class="secondary-action compact-action"
            type="button"
            @click="pay(item)"
          ><WalletCards :size="15" />{{ t('expense.pay') }}</button>
        </footer>
      </article>
    </section>
    <div v-else class="panel empty-state">
      <CircleDollarSign :size="30" />
      <strong>{{ t('expense.noClaims') }}</strong>
    </div>

    <el-dialog
      v-model="dialog"
      :title="t('expense.newClaim')"
      width="min(880px, 96vw)"
      top="4vh"
      append-to-body
      class="expense-dialog"
    >
      <div class="expense-form">
        <section class="claim-fields">
          <label>
            <span>{{ t('expense.claimTitle') }}</span>
            <input v-model="form.title" :placeholder="t('copy.0170')" />
          </label>
          <label>
            <span>{{ t('expense.purpose') }}</span>
            <input v-model="form.purpose" :placeholder="t('copy.0172')" />
          </label>
          <label class="full-field">
            <span>{{ t('expense.matter') }}</span>
            <select v-model="form.matterId">
              <option value="">{{ t('expense.selectMatter') }}</option>
              <option v-for="matter in matters" :key="matter.id" :value="matter.id">
                {{ matter.matterNumber }} · {{ matter.title }}
              </option>
            </select>
          </label>
        </section>

        <section class="line-editor">
          <header>
            <div><ReceiptText :size="18" /><strong>{{ t('expense.items') }}</strong></div>
            <button type="button" @click="form.items.push(newLine())">
              <Plus :size="14" />{{ t('expense.addItem') }}
            </button>
          </header>
          <article v-for="(line, index) in form.items" :key="line.localId">
            <span class="line-number">{{ String(index + 1).padStart(2, '0') }}</span>
            <div class="line-fields">
              <label>
                <span>{{ t('expense.category') }}</span>
                <select v-model="line.category">
                  <option v-for="category in expenseCategories" :key="category.value" :value="category.value">
                    {{ t(category.labelKey) }}
                  </option>
                </select>
              </label>
              <label>
                <span>{{ t('expense.date') }}</span>
                <input v-model="line.occurredOn" type="date" />
              </label>
              <label>
                <span>{{ t('expense.amount') }}</span>
                <input v-model.number="line.amount" type="number" min="0.01" step="0.01" />
              </label>
              <label class="description-field">
                <span>{{ t('expense.description') }}</span>
                <input v-model="line.description" :placeholder="t('copy.0177')" />
              </label>
              <label class="receipt-field">
                <span>{{ t('expense.receipt') }}</span>
                <input
                  type="file"
                  accept=".pdf,.png,.jpg,.jpeg,application/pdf,image/png,image/jpeg"
                  :aria-label="t('expense.chooseReceipt')"
                  @change="selectFile($event, line)"
                />
                <small>{{ line.file?.name || t('expense.receiptHint') }}</small>
              </label>
            </div>
            <button
              class="remove-line"
              type="button"
              :disabled="form.items.length === 1"
              :aria-label="t('expense.removeItem')"
              @click="removeLine(line.localId)"
            ><Trash2 :size="15" /></button>
          </article>
        </section>

        <aside class="claim-total">
          <span><Fingerprint :size="17" />{{ t('expense.receiptProtected') }}</span>
          <div><small>{{ t('expense.total') }}</small><strong>{{ money(claimTotal) }}</strong></div>
        </aside>
      </div>
      <template #footer>
        <button
          class="primary-action"
          type="button"
          :disabled="saving || !form.title || !form.purpose || form.items.some((item) => !item.description || item.amount <= 0)"
          @click="createAndSubmit"
        >
          <Upload v-if="saving" :size="16" />
          <Send v-else :size="16" />
          {{ saving ? t('expense.uploading') : t('expense.submit') }}
        </button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.expense-page { --expense-blue: #294b55; --expense-gold: #765713; }
.expense-intro {
  margin-bottom: 14px;
  background:
    radial-gradient(circle at 88% 16%, rgba(41,75,85,.12), transparent 32%),
    linear-gradient(128deg, #f8f4eb, #fff 56%, #e9f0f1);
}
.expense-metrics {
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px; margin-bottom: 14px;
}
.expense-metrics article {
  display: flex; align-items: center; gap: 12px; min-width: 0;
  padding: 13px 15px; border: 1px solid var(--line); border-radius: var(--radius-md);
  background: #fff; box-shadow: var(--shadow-sm);
}
.expense-metrics article > span {
  display: grid; place-items: center; width: 36px; height: 36px;
  border-radius: 11px; background: #edf3f3; color: var(--expense-blue);
}
.expense-metrics small, .expense-metrics strong { display: block; }
.expense-metrics small { color: var(--muted); font-size: 8px; }
.expense-metrics strong { margin-top: 4px; color: var(--expense-blue); font: 700 14px/1.2 "Songti SC", serif; }
.expense-list { display: grid; gap: 12px; }
.expense-card { padding: 0; overflow: hidden; }
.expense-card > header {
  display: grid; grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center; gap: 12px; padding: 16px;
  background: linear-gradient(180deg, #fff, #fcfbf8);
}
.claim-mark {
  display: grid; place-items: center; width: 38px; height: 38px;
  border-radius: 12px; background: #edf3f3; color: var(--expense-blue);
}
.claim-heading { min-width: 0; }
.claim-heading .mono { color: var(--expense-gold); font-size: 8px; letter-spacing: .08em; }
.claim-heading h3 { margin: 4px 0 0; color: var(--ink); font: 700 15px/1.25 "Songti SC", serif; }
.claim-heading p { margin: 5px 0 0; color: var(--muted); font-size: 9px; line-height: 1.5; }
.claim-amount { text-align: right; }
.claim-amount strong { display: block; color: var(--expense-blue); font: 700 17px/1.2 "Songti SC", serif; }
.claim-amount .status-pill { margin-top: 7px; }
.claim-context {
  display: flex; flex-wrap: wrap; gap: 8px 18px; padding: 9px 16px;
  border-top: 1px solid var(--line); border-bottom: 1px solid var(--line);
  background: #faf9f6; color: var(--muted); font-size: 8px;
}
.review-track {
  display: grid; grid-template-columns: repeat(5, 1fr); padding: 14px 18px 12px;
  border-bottom: 1px solid var(--line);
}
.review-track > span { position: relative; display: grid; justify-items: center; gap: 5px; min-width: 0; }
.review-track > span::before {
  content: ""; position: absolute; top: 10px; right: 50%; width: 100%;
  height: 1px; background: var(--line); transform: translateX(-50%); z-index: 0;
}
.review-track > span:first-child::before { display: none; }
.review-track i {
  position: relative; z-index: 1; display: grid; place-items: center;
  width: 20px; height: 20px; border: 1px solid var(--line); border-radius: 50%;
  background: #fff; color: var(--muted); font: 700 8px/1 sans-serif;
}
.review-track em { color: var(--muted); font-size: 7px; font-style: normal; text-align: center; }
.review-track .completed i, .review-track .active i { border-color: var(--expense-blue); background: var(--expense-blue); color: white; }
.review-track .completed::before, .review-track .active::before { background: var(--expense-blue); }
.review-track .active em { color: var(--expense-blue); font-weight: 700; }
.claim-lines { padding: 0 16px; }
.claim-lines > article {
  display: grid; grid-template-columns: minmax(0, 1fr) auto minmax(160px, auto);
  align-items: center; gap: 12px; padding: 11px 0; border-bottom: 1px solid var(--line);
}
.claim-lines > article:last-child { border-bottom: 0; }
.claim-lines strong, .claim-lines span { display: block; }
.claim-lines > article > div strong { font-size: 9px; }
.claim-lines > article > div span { margin-top: 4px; color: var(--muted); font-size: 8px; }
.claim-lines > article > strong { color: var(--expense-blue); font: 700 11px/1.2 "Songti SC", serif; }
.claim-lines button {
  display: inline-flex; align-items: center; justify-content: flex-end; gap: 5px;
  padding: 0; border: 0; background: none; color: var(--expense-gold);
  font-size: 8px; cursor: pointer; overflow-wrap: anywhere;
}
.no-receipt { color: var(--muted); text-align: right; }
.expense-card > footer {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  min-height: 52px; padding: 10px 16px; border-top: 1px solid var(--line); background: #faf9f6;
}
.expense-card > footer > span { display: inline-flex; align-items: center; gap: 6px; color: var(--expense-blue); font-size: 8px; }
.expense-form { display: grid; gap: 14px; }
.claim-fields { display: grid; grid-template-columns: repeat(2, 1fr); gap: 12px; }
.claim-fields label, .line-fields label { display: grid; gap: 6px; }
.claim-fields .full-field { grid-column: 1 / -1; }
.claim-fields span, .line-fields label > span { color: var(--ink-soft); font-size: 8px; }
.claim-fields input, .claim-fields select, .line-fields input, .line-fields select {
  width: 100%; min-height: 39px; padding: 9px 10px; border: 1px solid var(--line);
  border-radius: 9px; background: #fff; color: var(--ink); font: inherit;
}
.line-editor { border: 1px solid var(--line); border-radius: 13px; overflow: hidden; }
.line-editor > header {
  display: flex; align-items: center; justify-content: space-between; gap: 12px;
  padding: 11px 13px; background: #f5f2e9;
}
.line-editor > header > div, .line-editor > header button {
  display: inline-flex; align-items: center; gap: 6px;
}
.line-editor > header strong { color: var(--expense-blue); font-size: 10px; }
.line-editor > header button { padding: 6px 8px; border: 1px solid #d7ccb4; border-radius: 8px; background: #fff; color: var(--expense-gold); font-size: 8px; cursor: pointer; }
.line-editor > article {
  display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 10px;
  padding: 13px; border-top: 1px solid var(--line);
}
.line-number { display: grid; place-items: center; width: 25px; height: 25px; border-radius: 8px; background: #edf3f3; color: var(--expense-blue); font: 700 8px/1 monospace; }
.line-fields { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 10px; min-width: 0; }
.line-fields .description-field, .line-fields .receipt-field { grid-column: span 3; }
.receipt-field input[type="file"] { padding: 6px; }
.receipt-field small { color: var(--muted); font-size: 7px; line-height: 1.45; }
.remove-line { align-self: start; display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid var(--line); border-radius: 9px; background: #fff; color: var(--oxblood); cursor: pointer; }
.remove-line:disabled { opacity: .35; cursor: default; }
.claim-total {
  display: flex; align-items: center; justify-content: space-between; gap: 14px;
  padding: 13px 15px; border-radius: 12px; background: var(--expense-blue); color: #fff;
}
.claim-total > span { display: inline-flex; align-items: center; gap: 7px; color: #d4dfdc; font-size: 8px; }
.claim-total div { text-align: right; }
.claim-total small, .claim-total strong { display: block; }
.claim-total small { color: #c0cfcb; font-size: 8px; }
.claim-total strong { margin-top: 3px; font: 700 18px/1.2 "Songti SC", serif; }
:global(.expense-dialog .el-dialog__body) { max-height: 76vh; overflow-y: auto; }
@media (max-width: 760px) {
  .expense-metrics { grid-template-columns: 1fr; }
  .expense-card > header { grid-template-columns: auto 1fr; }
  .claim-amount { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; text-align: left; }
  .claim-amount .status-pill { margin-top: 0; }
  .review-track { padding-inline: 8px; }
  .review-track em { font-size: 6px; }
  .claim-lines > article { grid-template-columns: 1fr auto; }
  .claim-lines button, .claim-lines .no-receipt { grid-column: 1 / -1; justify-content: flex-start; text-align: left; }
  .claim-fields, .line-fields { grid-template-columns: 1fr; }
  .claim-fields .full-field, .line-fields .description-field, .line-fields .receipt-field { grid-column: auto; }
  .expense-card > footer { align-items: stretch; flex-direction: column; }
}
</style>

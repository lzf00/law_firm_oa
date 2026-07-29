<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Banknote,
  BarChart3,
  BriefcaseBusiness,
  CheckCircle2,
  Clock3,
  FileCheck2,
  Landmark,
  Plus,
  ReceiptText,
  RefreshCw,
  Send,
  UserRoundCheck,
  WalletCards,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter } from '@/api/types'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Report {
  officeId?: string
  approvedWip: number
  unbilledTime: number
  issuedRevenue: number
  receivables: number
  collectedAmount: number
  utilizationPercent: number
}

interface Engagement {
  id: string
  officeId: string
  matterId: string
  matterNumber: string
  engagementNumber: string
  title: string
  currency: string
  status: string
  feeType: string
  rateAmount: number
  capAmount?: number
  taxRate: number
  effectiveFrom: string
  effectiveTo?: string
}

interface TimeEntry {
  id: string
  officeId: string
  matterId: string
  matterNumber: string
  professionalUserId: string
  professionalName: string
  workDate: string
  minutes: number
  description: string
  billable: boolean
  currency: string
  amount: number
  status: string
}

interface InvoiceLine {
  id: string
  description: string
  amount: number
}

interface Invoice {
  id: string
  officeId: string
  engagementId: string
  matterNumber: string
  invoiceNumber?: string
  currency: string
  status: string
  totalAmount: number
  paidAmount: number
  outstandingAmount: number
  dueDate?: string
  lines: InvoiceLine[]
}

const { locale } = useI18n()
const today = new Date().toISOString().slice(0, 10)
const defaultDueDate = new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10)
const currentUser = ref<CurrentUser | null>(null)
const matters = ref<Matter[]>([])
const loading = ref(true)
const busy = ref(false)
const report = ref<Report | null>(null)
const engagements = ref<Engagement[]>([])
const timeEntries = ref<TimeEntry[]>([])
const invoices = ref<Invoice[]>([])
const engagementDialog = ref(false)
const timeDialog = ref(false)
const invoiceDialog = ref(false)
const paymentDialog = ref(false)
const collectionDialog = ref(false)

const engagementForm = reactive({
  matterId: '',
  title: '',
  feeType: 'HOURLY',
  rateAmount: 0,
  capAmount: undefined as number | undefined,
  taxRate: 0,
  effectiveFrom: today,
  effectiveTo: '',
})
const timeForm = reactive({
  matterId: '',
  workDate: today,
  minutes: 60,
  description: '',
  billable: true,
})
const invoiceForm = reactive({
  engagementId: '',
  dueDate: defaultDueDate,
  fixedFeeDescription: '',
  fixedFeeAmount: undefined as number | undefined,
})
const paymentForm = reactive({
  invoiceId: '',
  paymentReference: '',
  receivedOn: today,
  payerName: '',
  amount: 0,
  method: 'BANK_TRANSFER',
  bankReference: '',
})
const collectionForm = reactive({
  invoiceId: '',
  activityType: 'EMAIL',
  notes: '',
  nextActionAt: '',
})

const canViewFinance = computed(() => hasPermission('FINANCE_VIEW'))
const canManageFinance = computed(() => hasPermission('FINANCE_MANAGE'))
const canCreateTime = computed(() => hasPermission('TIME_ENTRY_CREATE'))
const canApproveTime = computed(() => hasPermission('TIME_ENTRY_APPROVE'))
const canManageInvoices = computed(() => hasPermission('INVOICE_MANAGE'))
const canManagePayments = computed(() => hasPermission('PAYMENT_MANAGE'))
const approvedEngagements = computed(() =>
  engagements.value.filter((item) => item.status === 'APPROVED'))
const payableInvoices = computed(() =>
  invoices.value.filter((item) =>
    ['ISSUED', 'PARTIALLY_PAID'].includes(item.status)
    && Number(item.outstandingAmount) > 0))
const selectedPaymentInvoice = computed(() =>
  payableInvoices.value.find((item) => item.id === paymentForm.invoiceId))
const selectedCollectionInvoice = computed(() =>
  invoices.value.find((item) => item.id === collectionForm.invoiceId))

function hasPermission(permission: string) {
  return Boolean(currentUser.value?.permissions.includes(permission))
}

function money(value: number, currency = 'CNY') {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(Number(value || 0))
}

function formatDate(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(value))
}

async function load() {
  loading.value = true
  try {
    const [meResult, matterResult, timeResult] = await Promise.all([
      http.get<CurrentUser>('/me'),
      http.get<Matter[]>('/matters'),
      http.get<{ items: TimeEntry[] }>('/finance/time-entries', {
        params: { page: 1, size: 100 },
      }),
    ])
    currentUser.value = meResult.data
    matters.value = matterResult.data
    timeEntries.value = timeResult.data.items
    if (!timeForm.matterId) timeForm.matterId = matters.value[0]?.id ?? ''
    if (meResult.data.permissions.includes('FINANCE_VIEW')) {
      const [reportResult, engagementResult, invoiceResult] = await Promise.all([
        http.get<Report>('/finance/reports'),
        http.get<Engagement[]>('/finance/engagements'),
        http.get<{ items: Invoice[] }>('/finance/invoices', {
          params: { page: 1, size: 100 },
        }),
      ])
      report.value = reportResult.data
      engagements.value = engagementResult.data
      invoices.value = invoiceResult.data.items
    } else {
      report.value = null
      engagements.value = []
      invoices.value = []
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('finance.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function mutate(action: () => Promise<unknown>, close?: () => void) {
  busy.value = true
  try {
    await action()
    close?.()
    await load()
    ElMessage.success(t('finance.saved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('finance.actionFailed'))
  } finally {
    busy.value = false
  }
}

function openEngagementDialog() {
  engagementForm.matterId = matters.value[0]?.id ?? ''
  engagementDialog.value = true
}

function openTimeDialog() {
  timeForm.matterId = timeForm.matterId || matters.value[0]?.id || ''
  timeDialog.value = true
}

function openInvoiceDialog() {
  invoiceForm.engagementId = approvedEngagements.value[0]?.id ?? ''
  invoiceDialog.value = true
}

function openPaymentDialog(invoice?: Invoice) {
  const selected = invoice ?? payableInvoices.value[0]
  paymentForm.invoiceId = selected?.id ?? ''
  paymentForm.paymentReference = `PAY-${today.replace(/-/g, '')}-${String(Date.now()).slice(-6)}`
  paymentForm.payerName = ''
  paymentForm.amount = Number(selected?.outstandingAmount ?? 0)
  paymentForm.bankReference = ''
  paymentDialog.value = true
}

function openCollectionDialog(invoice: Invoice) {
  collectionForm.invoiceId = invoice.id
  collectionForm.activityType = 'EMAIL'
  collectionForm.notes = ''
  collectionForm.nextActionAt = ''
  collectionDialog.value = true
}

function syncPaymentAmount() {
  paymentForm.amount = Number(selectedPaymentInvoice.value?.outstandingAmount ?? 0)
}

async function createEngagement() {
  await mutate(() => http.post('/finance/engagements', {
    matterId: engagementForm.matterId,
    title: engagementForm.title,
    feeType: engagementForm.feeType,
    rateAmount: Number(engagementForm.rateAmount),
    capAmount: engagementForm.capAmount || null,
    taxRate: Number(engagementForm.taxRate),
    effectiveFrom: engagementForm.effectiveFrom,
    effectiveTo: engagementForm.effectiveTo || null,
  }), () => {
    engagementDialog.value = false
    engagementForm.title = ''
  })
}

async function approveEngagement(item: Engagement) {
  await mutate(() => http.post(`/finance/engagements/${item.id}/approve`))
}

async function createTimeEntry() {
  await mutate(() => http.post('/finance/time-entries', {
    matterId: timeForm.matterId,
    workDate: timeForm.workDate,
    minutes: Number(timeForm.minutes),
    description: timeForm.description,
    billable: timeForm.billable,
  }), () => {
    timeDialog.value = false
    timeForm.description = ''
  })
}

async function transitionTime(item: TimeEntry, action: 'submit' | 'approve') {
  await mutate(() => http.post(`/finance/time-entries/${item.id}/${action}`))
}

async function draftInvoice() {
  await mutate(() => http.post('/finance/invoices/draft', {
    engagementId: invoiceForm.engagementId,
    dueDate: invoiceForm.dueDate,
    fixedFeeDescription: invoiceForm.fixedFeeDescription || null,
    fixedFeeAmount: invoiceForm.fixedFeeAmount || null,
  }), () => {
    invoiceDialog.value = false
    invoiceForm.fixedFeeDescription = ''
    invoiceForm.fixedFeeAmount = undefined
  })
}

async function transitionInvoice(item: Invoice, action: 'review' | 'issue') {
  await mutate(() => http.post(`/finance/invoices/${item.id}/${action}`))
}

async function recordPayment() {
  const invoice = selectedPaymentInvoice.value
  if (!invoice) return
  await mutate(() => http.post('/finance/payments', {
    officeId: invoice.officeId,
    paymentReference: paymentForm.paymentReference,
    receivedOn: paymentForm.receivedOn,
    payerName: paymentForm.payerName,
    currency: invoice.currency,
    amount: Number(paymentForm.amount),
    method: paymentForm.method,
    bankReference: paymentForm.bankReference || null,
    allocations: [{
      invoiceId: invoice.id,
      amount: Number(paymentForm.amount),
    }],
  }), () => {
    paymentDialog.value = false
  })
}

async function recordCollection() {
  const invoice = selectedCollectionInvoice.value
  if (!invoice) return
  await mutate(() => http.post(
    `/finance/invoices/${invoice.id}/collections`,
    {
      activityType: collectionForm.activityType,
      notes: collectionForm.notes,
      nextActionAt: collectionForm.nextActionAt
        ? new Date(collectionForm.nextActionAt).toISOString()
        : null,
    },
  ), () => {
    collectionDialog.value = false
  })
}

onMounted(load)
</script>

<template>
  <section class="module-page finance-page">
    <header class="page-intro finance-intro">
      <div>
        <span class="eyebrow">{{ t('finance.kicker') }}</span>
        <h2>{{ t('page.finance') }}</h2>
        <p>{{ t('p1.financeHeadline') }}</p>
      </div>
      <div class="finance-header-actions">
        <button class="secondary-action" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="16" />{{ t('finance.refresh') }}
        </button>
        <button v-if="canCreateTime" class="primary-action" type="button" @click="openTimeDialog">
          <Clock3 :size="16" />{{ t('finance.logTime') }}
        </button>
      </div>
    </header>

    <div v-if="loading" class="panel empty-state">{{ t('shell.connecting') }}</div>
    <template v-else>
      <section v-if="!canViewFinance" class="personal-mode panel">
        <span><Clock3 :size="21" /></span>
        <div>
          <strong>{{ t('finance.personalMode') }}</strong>
          <p>{{ t('finance.personalModeHint') }}</p>
        </div>
      </section>

      <section v-if="report" class="metric-grid" :aria-label="t('p1.report')">
        <article><span>{{ t('p1.wip') }}</span><strong>{{ money(report.approvedWip) }}</strong></article>
        <article><span>{{ t('p1.unbilled') }}</span><strong>{{ money(report.unbilledTime) }}</strong></article>
        <article><span>{{ t('p1.revenue') }}</span><strong>{{ money(report.issuedRevenue) }}</strong></article>
        <article><span>{{ t('p1.receivables') }}</span><strong>{{ money(report.receivables) }}</strong></article>
        <article><span>{{ t('p1.collected') }}</span><strong>{{ money(report.collectedAmount) }}</strong></article>
        <article><span>{{ t('p1.utilization') }}</span><strong>{{ report.utilizationPercent }}%</strong></article>
      </section>

      <section v-if="canViewFinance" class="operation-bar panel">
        <div>
          <span class="operation-icon"><Landmark :size="22" /></span>
          <div>
            <strong>{{ t('finance.operationCenter') }}</strong>
            <p>{{ t('finance.operationHint') }}</p>
          </div>
        </div>
        <div class="operation-actions">
          <button v-if="canManageFinance" class="secondary-action" type="button" @click="openEngagementDialog">
            <BriefcaseBusiness :size="16" />{{ t('finance.newEngagement') }}
          </button>
          <button v-if="canManageInvoices" class="secondary-action" type="button" :disabled="!approvedEngagements.length" @click="openInvoiceDialog">
            <ReceiptText :size="16" />{{ t('finance.generateInvoice') }}
          </button>
          <button v-if="canManagePayments" class="primary-action" type="button" :disabled="!payableInvoices.length" @click="openPaymentDialog()">
            <Banknote :size="16" />{{ t('finance.recordPayment') }}
          </button>
        </div>
      </section>

      <div class="finance-grid">
        <section v-if="canViewFinance" class="panel finance-panel">
          <div class="section-heading">
            <span><BriefcaseBusiness :size="19" /></span>
            <div><h3>{{ t('p1.engagements') }}</h3><small>{{ engagements.length }}</small></div>
          </div>
          <div v-if="engagements.length" class="record-list">
            <article v-for="item in engagements" :key="item.id">
              <div class="record-copy">
                <strong>{{ item.title }}</strong>
                <span>{{ item.matterNumber }} · {{ item.engagementNumber }}</span>
                <small>{{ formatDate(item.effectiveFrom) }} · {{ formatLegalCode(item.feeType, locale) }}</small>
              </div>
              <div class="record-side">
                <strong>{{ money(item.rateAmount, item.currency) }}</strong>
                <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
                <button
                  v-if="canManageFinance && ['DRAFT', 'PENDING_APPROVAL'].includes(item.status)"
                  type="button"
                  class="text-action"
                  :disabled="busy"
                  @click="approveEngagement(item)"
                >{{ t('finance.approveEngagement') }}</button>
              </div>
            </article>
          </div>
          <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        </section>

        <section class="panel finance-panel" :class="{ 'full-panel': !canViewFinance }">
          <div class="section-heading">
            <span><Clock3 :size="19" /></span>
            <div><h3>{{ t('p1.timeEntries') }}</h3><small>{{ timeEntries.length }}</small></div>
          </div>
          <div v-if="timeEntries.length" class="record-list">
            <article v-for="item in timeEntries" :key="item.id">
              <div class="record-copy">
                <strong>{{ item.matterNumber }}</strong>
                <span>{{ item.professionalName }} · {{ formatDate(item.workDate) }}</span>
                <small>{{ item.description }}</small>
              </div>
              <div class="record-side">
                <strong>{{ item.minutes }} {{ t('p1.minutes') }}</strong>
                <span>{{ money(item.amount, item.currency) }}</span>
                <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
                <button
                  v-if="item.status === 'DRAFT' && item.professionalUserId === currentUser?.userId"
                  type="button"
                  class="text-action"
                  :disabled="busy"
                  @click="transitionTime(item, 'submit')"
                >{{ t('finance.submitTime') }}</button>
                <button
                  v-if="item.status === 'SUBMITTED' && canApproveTime && item.professionalUserId !== currentUser?.userId"
                  type="button"
                  class="text-action"
                  :disabled="busy"
                  @click="transitionTime(item, 'approve')"
                >{{ t('finance.approveTime') }}</button>
                <small v-else-if="item.status === 'SUBMITTED' && canApproveTime && item.professionalUserId === currentUser?.userId" class="self-note">
                  {{ t('finance.selfApproval') }}
                </small>
              </div>
            </article>
          </div>
          <div v-else class="compact-empty">
            <Clock3 :size="28" />
            <span>{{ matters.length ? t('p1.noData') : t('finance.noAccessibleMatters') }}</span>
          </div>
        </section>

        <section v-if="canViewFinance" class="panel finance-panel invoice-panel">
          <div class="section-heading">
            <span><BarChart3 :size="19" /></span>
            <div><h3>{{ t('p1.invoices') }}</h3><small>{{ invoices.length }}</small></div>
          </div>
          <div v-if="invoices.length" class="invoice-list">
            <article v-for="item in invoices" :key="item.id">
              <div class="invoice-identity">
                <span><FileCheck2 :size="18" /></span>
                <div>
                  <strong>{{ item.invoiceNumber || item.matterNumber }}</strong>
                  <small>{{ item.matterNumber }} · {{ tp('finance.invoiceLines', { count: item.lines.length }) }}</small>
                </div>
              </div>
              <div class="invoice-value">
                <strong>{{ money(item.totalAmount, item.currency) }}</strong>
                <span>{{ t('p1.outstanding') }} {{ money(item.outstandingAmount, item.currency) }}</span>
              </div>
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
              <div class="invoice-actions">
                <button v-if="canManageInvoices && item.status === 'DRAFT'" type="button" @click="transitionInvoice(item, 'review')">
                  <Send :size="14" />{{ t('finance.sendReview') }}
                </button>
                <button v-if="canManageInvoices && item.status === 'UNDER_REVIEW'" type="button" @click="transitionInvoice(item, 'issue')">
                  <CheckCircle2 :size="14" />{{ t('finance.issueInvoice') }}
                </button>
                <button v-if="canManagePayments && ['ISSUED', 'PARTIALLY_PAID'].includes(item.status)" type="button" @click="openPaymentDialog(item)">
                  <WalletCards :size="14" />{{ t('finance.recordPayment') }}
                </button>
                <button v-if="canManageFinance && ['ISSUED', 'PARTIALLY_PAID'].includes(item.status)" type="button" @click="openCollectionDialog(item)">
                  <UserRoundCheck :size="14" />{{ t('finance.collection') }}
                </button>
              </div>
            </article>
          </div>
          <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        </section>
      </div>
    </template>

    <el-dialog v-model="engagementDialog" :title="t('finance.newEngagement')" width="min(700px, 94vw)" append-to-body>
      <div class="dialog-form finance-form">
        <label class="full-field">
          <span>{{ t('finance.matter') }}</span>
          <select v-model="engagementForm.matterId">
            <option value="">{{ t('finance.selectMatter') }}</option>
            <option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option>
          </select>
        </label>
        <label class="full-field"><span>{{ t('finance.title') }}</span><input v-model="engagementForm.title" /></label>
        <label>
          <span>{{ t('finance.feeType') }}</span>
          <select v-model="engagementForm.feeType">
            <option value="HOURLY">{{ t('finance.hourly') }}</option>
            <option value="FIXED">{{ t('finance.fixed') }}</option>
            <option value="RETAINER">{{ t('finance.retainer') }}</option>
            <option value="HYBRID">{{ t('finance.hybrid') }}</option>
          </select>
        </label>
        <label><span>{{ t('finance.rate') }}</span><input v-model.number="engagementForm.rateAmount" type="number" min="0" step="0.01" /></label>
        <label><span>{{ t('finance.cap') }}</span><input v-model.number="engagementForm.capAmount" type="number" min="0" step="0.01" /></label>
        <label><span>{{ t('finance.taxRate') }}</span><input v-model.number="engagementForm.taxRate" type="number" min="0" max="100" step="0.01" /></label>
        <label><span>{{ t('finance.effectiveFrom') }}</span><input v-model="engagementForm.effectiveFrom" type="date" /></label>
        <label><span>{{ t('finance.effectiveTo') }}</span><input v-model="engagementForm.effectiveTo" type="date" /></label>
      </div>
      <template #footer>
        <button class="primary-action" type="button" :disabled="busy || !engagementForm.matterId || !engagementForm.title" @click="createEngagement">
          <Plus :size="16" />{{ t('finance.createEngagement') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="timeDialog" :title="t('finance.logTime')" width="min(640px, 94vw)" append-to-body>
      <div class="dialog-form finance-form">
        <label class="full-field">
          <span>{{ t('finance.matter') }}</span>
          <select v-model="timeForm.matterId">
            <option value="">{{ t('finance.selectMatter') }}</option>
            <option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option>
          </select>
        </label>
        <label><span>{{ t('finance.workDate') }}</span><input v-model="timeForm.workDate" type="date" /></label>
        <label><span>{{ t('finance.minutesLabel') }}</span><input v-model.number="timeForm.minutes" type="number" min="1" max="1440" /></label>
        <label class="full-field"><span>{{ t('finance.description') }}</span><textarea v-model="timeForm.description" rows="4" /></label>
        <label class="check-field full-field"><input v-model="timeForm.billable" type="checkbox" /><span>{{ t('finance.billable') }}</span></label>
      </div>
      <template #footer>
        <button class="primary-action" type="button" :disabled="busy || !timeForm.matterId || !timeForm.description" @click="createTimeEntry">
          <Clock3 :size="16" />{{ t('finance.saveTime') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="invoiceDialog" :title="t('finance.generateInvoice')" width="min(660px, 94vw)" append-to-body>
      <div class="dialog-form finance-form">
        <label class="full-field">
          <span>{{ t('finance.engagement') }}</span>
          <select v-model="invoiceForm.engagementId">
            <option value="">{{ t('finance.selectEngagement') }}</option>
            <option v-for="item in approvedEngagements" :key="item.id" :value="item.id">{{ item.matterNumber }} · {{ item.title }}</option>
          </select>
        </label>
        <label><span>{{ t('finance.dueDate') }}</span><input v-model="invoiceForm.dueDate" type="date" /></label>
        <label><span>{{ t('finance.fixedFeeAmount') }}</span><input v-model.number="invoiceForm.fixedFeeAmount" type="number" min="0" step="0.01" /></label>
        <label class="full-field"><span>{{ t('finance.fixedFeeDescription') }}</span><input v-model="invoiceForm.fixedFeeDescription" /></label>
      </div>
      <template #footer>
        <button class="primary-action" type="button" :disabled="busy || !invoiceForm.engagementId || !invoiceForm.dueDate" @click="draftInvoice">
          <ReceiptText :size="16" />{{ t('finance.createInvoice') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="paymentDialog" :title="t('finance.recordPayment')" width="min(680px, 94vw)" append-to-body>
      <div class="dialog-form finance-form">
        <label class="full-field">
          <span>{{ t('finance.selectInvoice') }}</span>
          <select v-model="paymentForm.invoiceId" @change="syncPaymentAmount">
            <option v-for="item in payableInvoices" :key="item.id" :value="item.id">{{ item.invoiceNumber }} · {{ money(item.outstandingAmount, item.currency) }}</option>
          </select>
        </label>
        <label><span>{{ t('finance.paymentReference') }}</span><input v-model="paymentForm.paymentReference" /></label>
        <label><span>{{ t('finance.receivedOn') }}</span><input v-model="paymentForm.receivedOn" type="date" /></label>
        <label><span>{{ t('finance.payerName') }}</span><input v-model="paymentForm.payerName" /></label>
        <label><span>{{ t('finance.paymentAmount') }}</span><input v-model.number="paymentForm.amount" type="number" min="0.01" :max="selectedPaymentInvoice?.outstandingAmount" step="0.01" /></label>
        <label>
          <span>{{ t('finance.paymentMethod') }}</span>
          <select v-model="paymentForm.method">
            <option value="BANK_TRANSFER">BANK TRANSFER</option>
            <option value="CHEQUE">CHEQUE</option>
            <option value="CARD">CARD</option>
            <option value="CASH">CASH</option>
            <option value="OTHER">OTHER</option>
          </select>
        </label>
        <label><span>{{ t('finance.bankReference') }}</span><input v-model="paymentForm.bankReference" /></label>
      </div>
      <template #footer>
        <button class="primary-action" type="button" :disabled="busy || !paymentForm.invoiceId || !paymentForm.paymentReference || !paymentForm.payerName || paymentForm.amount <= 0" @click="recordPayment">
          <Banknote :size="16" />{{ t('finance.savePayment') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="collectionDialog" :title="t('finance.collection')" width="min(620px, 94vw)" append-to-body>
      <div class="dialog-form finance-form">
        <label>
          <span>{{ t('finance.collectionType') }}</span>
          <select v-model="collectionForm.activityType">
            <option value="CALL">CALL</option>
            <option value="EMAIL">EMAIL</option>
            <option value="LETTER">LETTER</option>
            <option value="MEETING">MEETING</option>
            <option value="PROMISE_TO_PAY">PROMISE TO PAY</option>
            <option value="DISPUTE">DISPUTE</option>
          </select>
        </label>
        <label><span>{{ t('finance.nextAction') }}</span><input v-model="collectionForm.nextActionAt" type="datetime-local" /></label>
        <label class="full-field"><span>{{ t('finance.collectionNotes') }}</span><textarea v-model="collectionForm.notes" rows="5" /></label>
      </div>
      <template #footer>
        <button class="primary-action" type="button" :disabled="busy || !collectionForm.notes" @click="recordCollection">
          <UserRoundCheck :size="16" />{{ t('finance.saveCollection') }}
        </button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.finance-page { --finance-ink: #16342b; --finance-gold: #9b7628; }
.finance-intro {
  margin-bottom: 14px;
  background:
    radial-gradient(circle at 92% 12%, rgba(155,118,40,.14), transparent 34%),
    linear-gradient(128deg, #f7f2e8, #fff 54%, #e7efeb);
}
.finance-header-actions, .operation-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.personal-mode {
  display: flex; align-items: center; gap: 13px; margin-bottom: 14px;
  border-left: 3px solid var(--finance-gold); background: #fbf8f0;
}
.personal-mode > span, .operation-icon, .section-heading > span {
  display: grid; place-items: center; flex: 0 0 auto; color: var(--finance-gold);
}
.personal-mode strong, .operation-bar strong { font-size: 14px; color: var(--finance-ink); }
.personal-mode p, .operation-bar p { margin: 4px 0 0; color: var(--muted); font-size: 10px; line-height: 1.55; }
.metric-grid {
  display: grid; grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 8px; margin-bottom: 14px;
}
.metric-grid article {
  min-width: 0; padding: 16px 14px; border: 1px solid rgba(255,255,255,.08);
  border-radius: var(--radius-md); color: white;
  background: linear-gradient(145deg, #17362d, #10271f);
  box-shadow: 0 10px 22px rgba(20,52,43,.09);
}
.metric-grid article:nth-child(5) { background: linear-gradient(145deg, #7f6325, #5f4818); }
.metric-grid span { display: block; color: #c5d3ce; font-size: 8px; letter-spacing: .03em; }
.metric-grid strong { display: block; margin-top: 8px; font: 700 16px/1.2 "Songti SC", serif; overflow-wrap: anywhere; }
.operation-bar {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  margin-bottom: 14px; padding: 14px 16px;
}
.operation-bar > div:first-child { display: flex; align-items: center; gap: 12px; min-width: 0; }
.operation-icon { width: 38px; height: 38px; border-radius: 12px; background: #f3eddd; }
.finance-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.finance-panel { min-width: 0; padding: 0; overflow: hidden; }
.finance-panel.full-panel, .invoice-panel { grid-column: 1 / -1; }
.section-heading {
  display: flex; align-items: center; gap: 10px; min-height: 62px;
  padding: 13px 16px; border-bottom: 1px solid var(--line);
  background: linear-gradient(180deg, #fff, #fbfaf7);
}
.section-heading > span { width: 32px; height: 32px; border-radius: 10px; background: #f3eddd; }
.section-heading > div { display: flex; align-items: baseline; gap: 8px; }
.section-heading h3 { margin: 0; color: var(--finance-ink); font: 700 15px/1.2 "Songti SC", serif; }
.section-heading small { color: var(--muted); font-size: 9px; }
.record-list article {
  display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 18px;
  padding: 14px 16px; border-bottom: 1px solid var(--line);
}
.record-list article:last-child { border-bottom: 0; }
.record-copy, .record-side { min-width: 0; }
.record-copy strong, .record-copy span, .record-copy small, .record-side strong, .record-side > span { display: block; }
.record-copy strong { color: var(--ink); font-size: 11px; }
.record-copy span { margin-top: 5px; color: var(--ink-soft); font-size: 9px; }
.record-copy small { margin-top: 7px; color: var(--muted); font-size: 8px; line-height: 1.5; }
.record-side { text-align: right; }
.record-side strong { color: var(--finance-ink); font: 700 12px/1.2 "Songti SC", serif; }
.record-side > span:not(.status-pill) { margin-top: 4px; color: var(--muted); font-size: 8px; }
.record-side .status-pill { display: inline-flex; margin-top: 7px; }
.text-action {
  display: block; margin: 8px 0 0 auto; padding: 0; border: 0;
  background: none; color: var(--finance-gold); font-size: 9px; cursor: pointer;
}
.text-action:hover { text-decoration: underline; }
.self-note { display: block; max-width: 150px; margin-top: 7px; color: var(--muted); font-size: 8px; line-height: 1.4; }
.invoice-list > article {
  display: grid; grid-template-columns: minmax(220px, 1fr) minmax(160px, .55fr) auto minmax(180px, auto);
  align-items: center; gap: 14px; padding: 14px 16px; border-bottom: 1px solid var(--line);
}
.invoice-list > article:last-child { border-bottom: 0; }
.invoice-identity { display: flex; align-items: center; gap: 10px; min-width: 0; }
.invoice-identity > span { display: grid; place-items: center; width: 32px; height: 32px; border-radius: 10px; background: #edf3ef; color: var(--finance-ink); }
.invoice-identity strong, .invoice-identity small, .invoice-value strong, .invoice-value span { display: block; }
.invoice-identity strong { font-size: 11px; }
.invoice-identity small, .invoice-value span { margin-top: 4px; color: var(--muted); font-size: 8px; }
.invoice-value { text-align: right; }
.invoice-value strong { color: var(--finance-ink); font: 700 13px/1.2 "Songti SC", serif; }
.invoice-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 6px; }
.invoice-actions button {
  display: inline-flex; align-items: center; gap: 4px; padding: 6px 8px;
  border: 1px solid var(--line); border-radius: 8px; background: #fff;
  color: var(--ink-soft); font-size: 8px; cursor: pointer;
}
.invoice-actions button:hover { border-color: #b49a60; color: var(--finance-ink); background: #fbf7ed; }
.empty-copy, .compact-empty { padding: 24px 16px; color: var(--muted); font-size: 10px; text-align: center; }
.compact-empty { display: grid; justify-items: center; gap: 8px; }
.finance-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px; }
.finance-form label { display: grid; gap: 6px; }
.finance-form label > span { color: var(--ink-soft); font-size: 9px; }
.finance-form .full-field { grid-column: 1 / -1; }
.finance-form input, .finance-form select, .finance-form textarea {
  width: 100%; min-height: 40px; padding: 9px 11px; border: 1px solid var(--line);
  border-radius: 9px; background: #fff; color: var(--ink); font: inherit;
}
.finance-form textarea { resize: vertical; line-height: 1.55; }
.finance-form .check-field { display: flex; grid-template-columns: auto 1fr; align-items: center; }
.finance-form .check-field input { width: 16px; min-height: 16px; }
@media (max-width: 1120px) {
  .metric-grid { grid-template-columns: repeat(3, 1fr); }
  .invoice-list > article { grid-template-columns: minmax(190px, 1fr) auto auto; }
  .invoice-actions { grid-column: 1 / -1; }
}
@media (max-width: 760px) {
  .finance-intro, .operation-bar { align-items: stretch; }
  .finance-header-actions, .operation-actions { width: 100%; }
  .finance-header-actions button, .operation-actions button { flex: 1 1 150px; justify-content: center; }
  .metric-grid, .finance-grid { grid-template-columns: 1fr; }
  .finance-panel.full-panel, .invoice-panel { grid-column: auto; }
  .operation-bar { display: grid; }
  .record-list article { gap: 10px; }
  .invoice-list > article { grid-template-columns: 1fr auto; }
  .invoice-value { text-align: right; }
  .invoice-list .status-pill { justify-self: start; }
  .invoice-actions { grid-column: 1 / -1; justify-content: flex-start; }
  .finance-form { grid-template-columns: 1fr; }
  .finance-form .full-field { grid-column: auto; }
}
</style>

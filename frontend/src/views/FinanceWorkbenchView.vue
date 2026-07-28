<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { BarChart3, BriefcaseBusiness, Clock3, Landmark } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { useI18n } from '@/i18n'
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
  matterNumber: string
  engagementNumber: string
  title: string
  currency: string
  status: string
  feeType: string
  rateAmount: number
}

interface TimeEntry {
  id: string
  matterNumber: string
  professionalName: string
  workDate: string
  minutes: number
  currency: string
  amount: number
  status: string
}

interface Invoice {
  id: string
  matterNumber: string
  invoiceNumber?: string
  currency: string
  status: string
  totalAmount: number
  paidAmount: number
  outstandingAmount: number
  dueDate?: string
}

const { locale, t } = useI18n()
const loading = ref(true)
const report = ref<Report | null>(null)
const engagements = ref<Engagement[]>([])
const timeEntries = ref<TimeEntry[]>([])
const invoices = ref<Invoice[]>([])

function money(value: number, currency = 'CNY') {
  return new Intl.NumberFormat(locale.value, {
    style: 'currency',
    currency,
    maximumFractionDigits: 2,
  }).format(Number(value || 0))
}

async function load() {
  loading.value = true
  try {
    const [reportResult, engagementResult, timeResult, invoiceResult] = await Promise.all([
      http.get<Report>('/finance/reports'),
      http.get<Engagement[]>('/finance/engagements'),
      http.get<{ items: TimeEntry[] }>('/finance/time-entries', { params: { page: 1, size: 30 } }),
      http.get<{ items: Invoice[] }>('/finance/invoices', { params: { page: 1, size: 30 } }),
    ])
    report.value = reportResult.data
    engagements.value = engagementResult.data
    timeEntries.value = timeResult.data.items
    invoices.value = invoiceResult.data.items
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page finance-page">
    <header class="page-intro finance-intro">
      <div>
        <span class="eyebrow">ENGAGEMENT · TIME · BILLING · COLLECTIONS</span>
        <h2>{{ t('page.finance') }}</h2>
        <p>{{ t('p1.financeHeadline') }}</p>
      </div>
      <Landmark :size="34" />
    </header>

    <div v-if="loading" class="panel empty-state">{{ t('shell.connecting') }}</div>
    <template v-else>
      <section v-if="report" class="metric-grid" :aria-label="t('p1.report')">
        <article><span>{{ t('p1.wip') }}</span><strong>{{ money(report.approvedWip) }}</strong></article>
        <article><span>{{ t('p1.unbilled') }}</span><strong>{{ money(report.unbilledTime) }}</strong></article>
        <article><span>{{ t('p1.revenue') }}</span><strong>{{ money(report.issuedRevenue) }}</strong></article>
        <article><span>{{ t('p1.receivables') }}</span><strong>{{ money(report.receivables) }}</strong></article>
        <article><span>{{ t('p1.collected') }}</span><strong>{{ money(report.collectedAmount) }}</strong></article>
        <article><span>{{ t('p1.utilization') }}</span><strong>{{ report.utilizationPercent }}%</strong></article>
      </section>

      <div class="finance-grid">
        <section class="panel">
          <div class="section-heading"><BriefcaseBusiness :size="20" /><h3>{{ t('p1.engagements') }}</h3></div>
          <div v-if="engagements.length" class="record-list">
            <article v-for="item in engagements" :key="item.id">
              <div><strong>{{ item.title }}</strong><span>{{ item.matterNumber }} · {{ item.engagementNumber }}</span></div>
              <div class="amount"><strong>{{ money(item.rateAmount, item.currency) }}</strong><span>{{ formatLegalCode(item.feeType, locale) }} · {{ formatLegalCode(item.status, locale) }}</span></div>
            </article>
          </div>
          <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        </section>

        <section class="panel">
          <div class="section-heading"><Clock3 :size="20" /><h3>{{ t('p1.timeEntries') }}</h3></div>
          <div v-if="timeEntries.length" class="record-list">
            <article v-for="item in timeEntries" :key="item.id">
              <div><strong>{{ item.matterNumber }}</strong><span>{{ item.professionalName }} · {{ item.workDate }}</span></div>
              <div class="amount"><strong>{{ money(item.amount, item.currency) }}</strong><span>{{ item.minutes }} {{ t('p1.minutes') }} · {{ formatLegalCode(item.status, locale) }}</span></div>
            </article>
          </div>
          <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        </section>

        <section class="panel invoice-panel">
          <div class="section-heading"><BarChart3 :size="20" /><h3>{{ t('p1.invoices') }}</h3></div>
          <div v-if="invoices.length" class="record-list">
            <article v-for="item in invoices" :key="item.id">
              <div><strong>{{ item.invoiceNumber || item.matterNumber }}</strong><span>{{ item.matterNumber }} · {{ formatLegalCode(item.status, locale) }}</span></div>
              <div class="amount"><strong>{{ money(item.totalAmount, item.currency) }}</strong><span>{{ t('p1.outstanding') }} · {{ money(item.outstandingAmount, item.currency) }}</span></div>
            </article>
          </div>
          <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        </section>
      </div>
    </template>
  </section>
</template>

<style scoped>
.finance-intro { background: linear-gradient(125deg, #f6f0e4, #fff 52%, #e8edf1); }
.finance-intro > svg { color: #8d641c; }
.metric-grid { display: grid; grid-template-columns: repeat(6, minmax(0, 1fr)); gap: .75rem; margin-bottom: 1rem; }
.metric-grid article { padding: 1rem; border-radius: 14px; background: #172a24; color: #fff; min-width: 0; }
.metric-grid span { display: block; color: #b8c7c1; font-size: .76rem; }
.metric-grid strong { display: block; margin-top: .45rem; font-size: 1.08rem; overflow-wrap: anywhere; }
.finance-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.invoice-panel { grid-column: 1 / -1; }
.section-heading { display: flex; align-items: center; gap: .55rem; margin-bottom: .7rem; }
.section-heading h3 { margin: 0; }
.record-list article { display: flex; justify-content: space-between; gap: 1rem; padding: .8rem 0; border-top: 1px solid var(--border-color); }
.record-list span { display: block; margin-top: .2rem; color: var(--text-muted); font-size: .78rem; }
.amount { text-align: right; }
.empty-copy { color: var(--text-muted); }
@media (max-width: 1100px) { .metric-grid { grid-template-columns: repeat(3, 1fr); } }
@media (max-width: 760px) {
  .metric-grid, .finance-grid { grid-template-columns: 1fr; }
  .invoice-panel { grid-column: auto; }
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  AlertTriangle,
  ArrowRight,
  CheckCircle2,
  Clock3,
  EyeOff,
  FileCheck2,
  RefreshCw,
  Search,
  ShieldCheck,
  UserRoundCheck,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Party } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface ConflictHit {
  partyId: string
  partyName: string
  matterId?: string
  matterNumber: string
  matterTitle: string
  partyRole: string
  side: string
  matterStatus?: string
  restricted: boolean
}

interface ConflictAction {
  id: string
  action: string
  actorName: string
  riskLevel?: string
  decision?: string
  rationale?: string
  mitigationPlan?: string
  occurredAt: string
}

interface ConflictParty {
  partyId: string
  proposedRole: string
}

interface ConflictCheck {
  id: string
  requestNumber: string
  officeId?: string
  officeNameZh?: string
  officeNameEn?: string
  matterTitle: string
  status: string
  riskLevel: string
  decision?: string
  requestedBy: string
  requestedByName: string
  reviewedByName?: string
  decisionRationale?: string
  mitigationPlan?: string
  createdAt: string
  submittedAt?: string
  reviewedAt?: string
  restrictedDetails: boolean
  parties: ConflictParty[]
  hits: ConflictHit[]
  actions: ConflictAction[]
}

interface ConflictPreview {
  riskLevel: string
  hitCount: number
  hits: ConflictHit[]
}

const { locale } = useI18n()
const zh = computed(() => locale.value === 'zh-CN')
const conflictCopyKeys = [
  'kicker', 'title', 'subtitle', 'guide', 'newCheck', 'matterTitle',
  'matterPlaceholder', 'office', 'parties', 'addParty', 'party', 'role',
  'client', 'counterparty', 'affiliate', 'other', 'preview', 'previewing',
  'previewResult', 'previewHint', 'noPreview', 'noPreviewHint', 'submitFormal',
  'saveDraft', 'queue', 'search', 'all', 'empty', 'select', 'hits', 'noHits',
  'restricted', 'evidence', 'conclusion', 'rationale', 'mitigation', 'reviewer',
  'pending', 'review', 'decision', 'risk', 'clear', 'waiver', 'reject', 'low',
  'medium', 'high', 'rationalePlaceholder', 'mitigationPlaceholder', 'confirm',
  'refresh', 'created', 'submitted', 'decided', 'failed', 'required',
  'duplicateParty', 'immutable',
] as const
const copy = computed(() => Object.fromEntries(
  conflictCopyKeys.map((key) => [key, t(`conflictOps.${key}`)]),
) as Record<(typeof conflictCopyKeys)[number], string>)

const parties = ref<Party[]>([])
const checks = ref<ConflictCheck[]>([])
const currentUser = ref<CurrentUser | null>(null)
const preview = ref<ConflictPreview | null>(null)
const selectedId = ref('')
const loading = ref(true)
const checking = ref(false)
const saving = ref(false)
const query = ref('')
const statusFilter = ref('')
const reviewDialog = ref(false)
const form = reactive({
  matterTitle: '',
  officeId: '',
  parties: [{ partyId: '', proposedRole: 'CLIENT' }] as ConflictParty[],
})
const decisionForm = reactive({
  decision: 'CLEAR',
  riskLevel: 'CLEAR',
  rationale: '',
  mitigationPlan: '',
})

const selectedCheck = computed(() =>
  checks.value.find((item) => item.id === selectedId.value) ?? null)
const canCreate = computed(() =>
  currentUser.value?.permissions.includes('CONFLICT_CHECK_CREATE') ?? false)
const canReview = computed(() => Boolean(
  currentUser.value?.permissions.includes('CONFLICT_CHECK_REVIEW')
  && selectedCheck.value
  && ['SUBMITTED', 'REVIEWING'].includes(selectedCheck.value.status)
  && selectedCheck.value.requestedBy !== currentUser.value.userId,
))
const selectedPartyIds = computed(() =>
  form.parties.map((item) => item.partyId).filter(Boolean))
const formReady = computed(() =>
  Boolean(form.matterTitle.trim() && selectedPartyIds.value.length))
const filteredChecks = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return checks.value.filter((item) => {
    const statusMatches = !statusFilter.value || item.status === statusFilter.value
    const textMatches = !needle || [
      item.requestNumber, item.matterTitle, item.requestedByName,
    ].some((value) => value.toLowerCase().includes(needle))
    return statusMatches && textMatches
  })
})
const openCount = computed(() =>
  checks.value.filter((item) => ['DRAFT', 'SUBMITTED', 'REVIEWING'].includes(item.status)).length)
const highRiskCount = computed(() =>
  checks.value.filter((item) => item.riskLevel === 'HIGH').length)

function officeName(check: ConflictCheck) {
  return zh.value ? check.officeNameZh : check.officeNameEn
}

function dateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

function addParty() {
  form.parties.push({ partyId: '', proposedRole: 'COUNTERPARTY' })
}

function removeParty(index: number) {
  if (form.parties.length === 1) return
  form.parties.splice(index, 1)
}

function validateForm() {
  if (!formReady.value) {
    ElMessage.warning(copy.value.required)
    return false
  }
  if (new Set(selectedPartyIds.value).size !== selectedPartyIds.value.length) {
    ElMessage.warning(copy.value.duplicateParty)
    return false
  }
  return true
}

async function load() {
  loading.value = true
  try {
    const [partyResult, checkResult, meResult] = await Promise.all([
      http.get<Party[]>('/parties'),
      http.get<ConflictCheck[]>('/conflict-checks'),
      http.get<CurrentUser>('/me'),
    ])
    parties.value = partyResult.data
    checks.value = checkResult.data
    currentUser.value = meResult.data
    form.officeId ||= meResult.data.primaryOfficeId ?? meResult.data.accessibleOffices[0]?.id ?? ''
    if (!selectedId.value || !checks.value.some((item) => item.id === selectedId.value)) {
      selectedId.value = checks.value[0]?.id ?? ''
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    loading.value = false
  }
}

async function runPreview() {
  if (!validateForm()) return
  checking.value = true
  try {
    preview.value = (await http.post<ConflictPreview>('/conflict-checks/preview', {
      matterTitle: form.matterTitle.trim(),
      partyIds: selectedPartyIds.value,
    })).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    checking.value = false
  }
}

async function createFormal(submit: boolean) {
  if (!validateForm()) return
  saving.value = true
  try {
    const created = (await http.post<ConflictCheck>('/conflict-checks', {
      officeId: form.officeId || null,
      matterTitle: form.matterTitle.trim(),
      parties: form.parties,
    })).data
    const finalItem = submit
      ? (await http.post<ConflictCheck>(`/conflict-checks/${created.id}/submit`)).data
      : created
    await load()
    selectedId.value = finalItem.id
    ElMessage.success(submit ? copy.value.submitted : copy.value.created)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

function openReview() {
  if (!selectedCheck.value) return
  decisionForm.decision = selectedCheck.value.riskLevel === 'HIGH' ? 'REJECT' : 'CLEAR'
  decisionForm.riskLevel = selectedCheck.value.riskLevel || 'CLEAR'
  decisionForm.rationale = ''
  decisionForm.mitigationPlan = ''
  reviewDialog.value = true
}

async function decide() {
  if (!selectedCheck.value) return
  saving.value = true
  try {
    await http.post(`/conflict-checks/${selectedCheck.value.id}/decision`, {
      ...decisionForm,
      mitigationPlan: decisionForm.mitigationPlan || null,
    })
    reviewDialog.value = false
    await load()
    ElMessage.success(copy.value.decided)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page conflict-workbench">
    <header class="conflict-hero">
      <div>
        <span class="eyebrow">{{ copy.kicker }}</span>
        <h2>{{ copy.title }}</h2>
        <p>{{ copy.subtitle }}</p>
      </div>
      <div class="hero-metrics" aria-label="Conflict check summary">
        <span><small>{{ copy.queue }}</small><strong>{{ checks.length }}</strong></span>
        <span><small>{{ formatLegalCode('PENDING', locale) }}</small><strong>{{ openCount }}</strong></span>
        <span><small>{{ copy.high }}</small><strong>{{ highRiskCount }}</strong></span>
      </div>
    </header>

    <div class="process-guide">
      <ShieldCheck :size="20" />
      <span>{{ copy.guide }}</span>
    </div>

    <div class="conflict-grid">
      <article class="panel intake-panel">
        <div class="panel-heading">
          <div><span class="step-number">01</span><h3>{{ copy.newCheck }}</h3></div>
          <span class="permission-mark"><ShieldCheck :size="14" /> {{ copy.immutable }}</span>
        </div>

        <label class="field">
          <span>{{ copy.matterTitle }}</span>
          <input v-model="form.matterTitle" :placeholder="copy.matterPlaceholder" maxlength="300" />
        </label>
        <label class="field">
          <span>{{ copy.office }}</span>
          <select v-model="form.officeId">
            <option v-for="office in currentUser?.accessibleOffices" :key="office.id" :value="office.id">
              {{ zh ? office.nameZh : office.nameEn }}
            </option>
          </select>
        </label>

        <div class="party-section">
          <div class="field-label">
            <span>{{ copy.parties }}</span>
            <button class="text-action" type="button" @click="addParty">+ {{ copy.addParty }}</button>
          </div>
          <div v-for="(partyRow, index) in form.parties" :key="index" class="party-row">
            <select v-model="partyRow.partyId" :aria-label="copy.party">
              <option value="">{{ copy.party }}</option>
              <option v-for="party in parties" :key="party.id" :value="party.id">
                {{ party.displayName }}
              </option>
            </select>
            <select v-model="partyRow.proposedRole" :aria-label="copy.role">
              <option value="CLIENT">{{ copy.client }}</option>
              <option value="COUNTERPARTY">{{ copy.counterparty }}</option>
              <option value="AFFILIATE">{{ copy.affiliate }}</option>
              <option value="OTHER">{{ copy.other }}</option>
            </select>
            <button
              class="remove-row"
              type="button"
              :disabled="form.parties.length === 1"
              :aria-label="copy.other"
              @click="removeParty(index)"
            >×</button>
          </div>
        </div>

        <button class="secondary-action full" :disabled="!formReady || checking" @click="runPreview">
          <RefreshCw v-if="checking" class="spin" :size="17" />
          <Search v-else :size="17" />
          {{ checking ? copy.previewing : copy.preview }}
        </button>

        <div class="preview-card" :class="preview?.riskLevel.toLowerCase()">
          <template v-if="preview">
            <div>
              <span>{{ copy.previewResult }}</span>
              <strong>{{ formatLegalCode(preview.riskLevel, locale) }}</strong>
            </div>
            <strong class="preview-count">{{ preview.hitCount }}</strong>
            <p>{{ copy.previewHint }}</p>
          </template>
          <template v-else>
            <ShieldCheck :size="26" />
            <div><strong>{{ copy.noPreview }}</strong><p>{{ copy.noPreviewHint }}</p></div>
          </template>
        </div>

        <div v-if="canCreate" class="submit-actions">
          <button class="secondary-action" :disabled="saving || !formReady" @click="createFormal(false)">
            <FileCheck2 :size="17" /> {{ copy.saveDraft }}
          </button>
          <button class="primary-action" :disabled="saving || !formReady" @click="createFormal(true)">
            {{ copy.submitFormal }} <ArrowRight :size="17" />
          </button>
        </div>
      </article>

      <article class="panel queue-panel">
        <div class="panel-heading">
          <div><span class="step-number">02</span><h3>{{ copy.queue }}</h3></div>
          <button class="icon-action" :title="copy.refresh" @click="load"><RefreshCw :size="16" /></button>
        </div>
        <div class="queue-filters">
          <label><Search :size="15" /><input v-model="query" :placeholder="copy.search" /></label>
          <select v-model="statusFilter" :aria-label="copy.all">
            <option value="">{{ copy.all }}</option>
            <option v-for="status in ['DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED']" :key="status" :value="status">
              {{ formatLegalCode(status, locale) }}
            </option>
          </select>
        </div>
        <div v-if="loading" class="empty-state">{{ copy.previewing }}</div>
        <div v-else-if="filteredChecks.length" class="check-list">
          <button
            v-for="item in filteredChecks"
            :key="item.id"
            class="check-row"
            :class="{ active: selectedId === item.id }"
            @click="selectedId = item.id"
          >
            <span class="risk-rail" :class="item.riskLevel.toLowerCase()"></span>
            <span class="check-main">
              <span class="check-meta"><code>{{ item.requestNumber }}</code><small>{{ dateTime(item.createdAt) }}</small></span>
              <strong>{{ item.matterTitle }}</strong>
              <small>{{ item.requestedByName }} · {{ officeName(item) || '—' }}</small>
            </span>
            <span class="check-state">
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
              <small>{{ formatLegalCode(item.riskLevel, locale) }}</small>
            </span>
          </button>
        </div>
        <div v-else class="empty-state"><Search :size="26" /><strong>{{ copy.empty }}</strong></div>
      </article>

      <article class="panel evidence-panel">
        <template v-if="selectedCheck">
          <div class="panel-heading evidence-title">
            <div>
              <span class="step-number">03</span>
              <div><h3>{{ selectedCheck.matterTitle }}</h3><code>{{ selectedCheck.requestNumber }}</code></div>
            </div>
            <span class="status-pill">{{ formatLegalCode(selectedCheck.status, locale) }}</span>
          </div>

          <div v-if="selectedCheck.restrictedDetails" class="redaction-notice">
            <EyeOff :size="18" /><span>{{ copy.restricted }}</span>
          </div>

          <section class="evidence-section">
            <div class="section-title">
              <span>{{ copy.hits }}</span>
              <strong>{{ selectedCheck.hits.length }}</strong>
            </div>
            <div v-if="selectedCheck.hits.length" class="hit-list">
              <div v-for="hit in selectedCheck.hits" :key="`${hit.partyId}-${hit.matterId}`" class="hit-card">
                <AlertTriangle :size="17" />
                <div>
                  <strong>{{ hit.partyName }}</strong>
                  <span>{{ hit.matterNumber }} · {{ hit.matterTitle }}</span>
                  <small>{{ formatLegalCode(hit.partyRole, locale) }} / {{ formatLegalCode(hit.side, locale) }}</small>
                </div>
                <EyeOff v-if="hit.restricted" :size="15" />
              </div>
            </div>
            <div v-else class="clean-result"><CheckCircle2 :size="20" /> {{ copy.noHits }}</div>
          </section>

          <section class="evidence-section">
            <div class="section-title"><span>{{ copy.evidence }}</span><strong>{{ selectedCheck.actions.length }}</strong></div>
            <ol class="timeline">
              <li v-for="action in selectedCheck.actions" :key="action.id">
                <span class="timeline-dot"></span>
                <div>
                  <strong>{{ formatLegalCode(action.action, locale) }}</strong>
                  <span>{{ action.actorName }} · {{ dateTime(action.occurredAt) }}</span>
                  <p v-if="action.rationale">{{ action.rationale }}</p>
                </div>
              </li>
            </ol>
          </section>

          <section class="conclusion-card" :class="{ pending: !selectedCheck.decision }">
            <div class="section-title"><span>{{ copy.conclusion }}</span><UserRoundCheck :size="18" /></div>
            <template v-if="selectedCheck.decision">
              <div class="decision-line">
                <strong>{{ formatLegalCode(selectedCheck.decision, locale) }}</strong>
                <span>{{ formatLegalCode(selectedCheck.riskLevel, locale) }}</span>
              </div>
              <dl>
                <dt>{{ copy.reviewer }}</dt><dd>{{ selectedCheck.reviewedByName }}</dd>
                <dt>{{ copy.rationale }}</dt><dd>{{ selectedCheck.decisionRationale }}</dd>
                <template v-if="selectedCheck.mitigationPlan">
                  <dt>{{ copy.mitigation }}</dt><dd>{{ selectedCheck.mitigationPlan }}</dd>
                </template>
              </dl>
            </template>
            <template v-else>
              <div class="pending-state"><Clock3 :size="22" /><span>{{ copy.pending }}</span></div>
              <button v-if="canReview" class="primary-action full" @click="openReview">
                <UserRoundCheck :size="17" /> {{ copy.review }}
              </button>
            </template>
          </section>
        </template>
        <div v-else class="empty-state evidence-empty">
          <ShieldCheck :size="34" /><strong>{{ copy.select }}</strong>
        </div>
      </article>
    </div>

    <ElDialog v-model="reviewDialog" :title="copy.review" width="min(640px, 94vw)">
      <form class="review-form" @submit.prevent="decide">
        <div class="two-fields">
          <label class="field"><span>{{ copy.decision }}</span>
            <select v-model="decisionForm.decision">
              <option value="CLEAR">{{ copy.clear }}</option>
              <option value="WAIVER_REQUIRED">{{ copy.waiver }}</option>
              <option value="REJECT">{{ copy.reject }}</option>
            </select>
          </label>
          <label class="field"><span>{{ copy.risk }}</span>
            <select v-model="decisionForm.riskLevel">
              <option value="CLEAR">{{ copy.low }}</option>
              <option value="MEDIUM">{{ copy.medium }}</option>
              <option value="HIGH">{{ copy.high }}</option>
            </select>
          </label>
        </div>
        <label class="field"><span>{{ copy.rationale }}</span>
          <textarea v-model="decisionForm.rationale" required maxlength="4000" :placeholder="copy.rationalePlaceholder"></textarea>
        </label>
        <label class="field"><span>{{ copy.mitigation }}</span>
          <textarea
            v-model="decisionForm.mitigationPlan"
            :required="decisionForm.decision === 'WAIVER_REQUIRED'"
            maxlength="4000"
            :placeholder="copy.mitigationPlaceholder"
          ></textarea>
        </label>
        <div class="immutable-warning"><ShieldCheck :size="17" /> {{ copy.immutable }}</div>
        <button class="primary-action full" type="submit" :disabled="saving">{{ copy.confirm }}</button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.conflict-workbench { --ink: #17372d; --gold: #a88743; --line: #ddd8cc; }
.conflict-hero { display:flex; align-items:flex-end; justify-content:space-between; gap:24px; padding:4px 0 22px; border-bottom:1px solid var(--line); }
.conflict-hero h2 { margin:8px 0 7px; font-family:var(--font-display); font-size:clamp(28px,3vw,42px); color:var(--ink); }
.conflict-hero p { margin:0; color:#65736e; }
.hero-metrics { display:flex; gap:1px; background:var(--line); border:1px solid var(--line); }
.hero-metrics span { min-width:96px; padding:12px 16px; background:#fbfaf6; }
.hero-metrics small,.hero-metrics strong { display:block; }
.hero-metrics small { color:#56635d; font-size:11px; text-transform:uppercase; }
.hero-metrics strong { margin-top:3px; color:var(--ink); font-size:24px; }
.process-guide { display:flex; align-items:center; gap:10px; margin:18px 0; padding:12px 15px; color:#435f56; background:#eef3ef; border-left:3px solid var(--gold); font-size:13px; }
.conflict-grid { display:grid; grid-template-columns:minmax(280px,.85fr) minmax(330px,1.05fr) minmax(360px,1.2fr); gap:14px; align-items:start; }
.panel { border:1px solid var(--line); border-radius:2px; background:#fffefa; box-shadow:0 8px 24px rgba(26,44,37,.04); }
.intake-panel,.queue-panel,.evidence-panel { padding:18px; min-height:640px; }
.panel-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:18px; }
.panel-heading>div { display:flex; align-items:center; gap:10px; }
.panel-heading h3 { margin:0; color:var(--ink); font-size:17px; }
.intake-panel .panel-heading { align-items:flex-start; flex-direction:column; }
.intake-panel .panel-heading h3 { white-space:nowrap; }
.intake-panel .permission-mark { display:flex; align-items:center; gap:6px; max-width:none; text-align:left; }
.step-number { color:#77571f; font-family:Georgia,serif; font-size:13px; letter-spacing:.08em; }
.permission-mark { max-width:170px; color:#56615c; font-size:10px; line-height:1.35; text-align:right; }
.field { display:grid; gap:7px; margin-bottom:14px; color:#41534d; font-size:12px; font-weight:650; }
.field input,.field select,.field textarea,.party-row select,.queue-filters input,.queue-filters select { width:100%; border:1px solid #d8d5ca; border-radius:2px; background:#fff; color:#223b33; font:inherit; padding:10px 11px; outline:none; }
.field input:focus,.field select:focus,.field textarea:focus,.party-row select:focus,.queue-filters input:focus { border-color:#6b8a7e; box-shadow:0 0 0 3px rgba(43,88,72,.08); }
.field textarea { min-height:96px; resize:vertical; }
.field-label { display:flex; justify-content:space-between; margin:0 0 8px; color:#41534d; font-size:12px; font-weight:650; }
.text-action,.remove-row,.icon-action { border:0; background:transparent; color:#365f50; cursor:pointer; }
.party-row { display:grid; grid-template-columns:1.3fr .9fr 28px; gap:7px; margin-bottom:8px; }
.remove-row { font-size:20px; color:#9a675b; }
.remove-row:disabled { opacity:.25; cursor:not-allowed; }
.full { width:100%; justify-content:center; }
.secondary-action,.primary-action { display:inline-flex; align-items:center; justify-content:center; gap:8px; min-height:40px; border-radius:2px; padding:9px 13px; font-weight:650; cursor:pointer; }
.secondary-action { border:1px solid #b9c7c1; background:#f8faf8; color:#244b3d; }
.primary-action { border:1px solid #183d30; background:#183d30; color:#fff; }
.secondary-action:disabled,.primary-action:disabled { opacity:.45; cursor:not-allowed; }
.preview-card { display:grid; grid-template-columns:1fr auto; gap:4px 12px; align-items:center; margin:12px 0; padding:13px; background:#f5f7f4; border:1px solid #dde4df; color:#476057; }
.preview-card>div span,.preview-card>div strong { display:block; }
.preview-card p { grid-column:1/-1; margin:0; font-size:11px; color:#53615b; }
.preview-count { font-size:28px; color:var(--ink); }
.preview-card.high { border-color:#d8a79c; background:#fff5f1; }
.preview-card.medium { border-color:#d9c38e; background:#fffbef; }
.submit-actions { display:grid; grid-template-columns:.8fr 1.2fr; gap:8px; margin-top:12px; }
.queue-filters { display:grid; grid-template-columns:1fr 120px; gap:8px; margin-bottom:13px; }
.queue-filters label { display:flex; align-items:center; gap:7px; border:1px solid #d8d5ca; padding:0 9px; background:#fff; }
.queue-filters label input { border:0; box-shadow:none; padding-left:0; }
.check-list { display:grid; gap:7px; max-height:555px; overflow:auto; padding-right:3px; }
.check-row { position:relative; display:grid; grid-template-columns:4px 1fr auto; gap:10px; width:100%; padding:12px 10px 12px 0; border:1px solid #e1ded5; background:#fff; color:inherit; text-align:left; cursor:pointer; }
.check-row:hover,.check-row.active { border-color:#779084; background:#f4f8f5; }
.risk-rail { background:#73907f; }
.risk-rail.high { background:#b75945; }.risk-rail.medium { background:#b38a38; }
.check-main,.check-state { display:flex; flex-direction:column; gap:5px; min-width:0; }
.check-main strong { color:#263f36; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.check-main small,.check-state small,.check-meta small { color:#53615b; font-size:11px; }
.check-meta { display:flex; justify-content:space-between; gap:8px; }
.check-meta code,.evidence-title code { color:#8a6a32; font-size:10px; }
.check-state { align-items:flex-end; }
.status-pill { display:inline-flex; padding:4px 7px; border:1px solid #cfd9d3; background:#edf3ef; color:#315646; font-size:10px; white-space:nowrap; }
.evidence-title>div { align-items:flex-start; }.evidence-title h3 { margin-bottom:4px; }
.redaction-notice,.immutable-warning { display:flex; gap:8px; padding:11px; background:#f8f1e5; color:#725a2f; font-size:12px; line-height:1.5; }
.evidence-section { padding:16px 0; border-bottom:1px solid #e7e3d9; }
.section-title { display:flex; justify-content:space-between; align-items:center; margin-bottom:10px; color:#4d5c55; font-size:11px; font-weight:700; letter-spacing:.08em; text-transform:uppercase; }
.section-title strong { color:var(--ink); font-size:15px; }
.hit-list { display:grid; gap:7px; }
.hit-card { display:grid; grid-template-columns:auto 1fr auto; gap:9px; padding:10px; background:#fff7f4; border-left:2px solid #b75945; color:#8e4b3d; }
.hit-card div { display:grid; gap:3px; }.hit-card span,.hit-card small { color:#6d706b; font-size:11px; }
.clean-result { display:flex; gap:8px; align-items:center; padding:12px; color:#37634f; background:#f0f7f2; }
.timeline { list-style:none; margin:0; padding:0 0 0 5px; }
.timeline li { position:relative; display:grid; grid-template-columns:14px 1fr; gap:8px; padding:0 0 15px; }
.timeline li:not(:last-child)::before { content:''; position:absolute; left:5px; top:12px; bottom:0; border-left:1px solid #d3d8d4; }
.timeline-dot { position:relative; z-index:1; width:11px; height:11px; margin-top:3px; border:2px solid #5f8173; border-radius:50%; background:#fff; }
.timeline div { display:grid; gap:3px; }.timeline span,.timeline p { margin:0; color:#506059; font-size:11px; line-height:1.45; }
.conclusion-card { margin-top:16px; padding:14px; border:1px solid #b9cfc4; background:#f2f8f4; }
.conclusion-card.pending { border-style:dashed; background:#fbfaf6; }
.decision-line { display:flex; justify-content:space-between; align-items:center; margin-bottom:12px; }
.decision-line strong { color:#244e3e; font-size:18px; }.decision-line span { color:#8b6730; font-size:12px; }
.conclusion-card dl { display:grid; grid-template-columns:84px 1fr; gap:8px 10px; margin:0; font-size:12px; }
.conclusion-card dt { color:#506059; }.conclusion-card dd { margin:0; color:#2f433c; line-height:1.5; }
.pending-state { display:flex; align-items:center; gap:9px; margin:12px 0; color:#4e5d56; }
.empty-state { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; min-height:180px; color:#86908b; text-align:center; }
.evidence-empty { min-height:550px; }
.review-form { display:grid; gap:12px; }.two-fields { display:grid; grid-template-columns:1fr 1fr; gap:12px; }
.spin { animation:spin 1s linear infinite; } @keyframes spin { to { transform:rotate(360deg); } }
@media (max-width:1280px) { .conflict-grid { grid-template-columns:1fr 1fr; }.evidence-panel { grid-column:1/-1; min-height:auto; }.evidence-empty { min-height:220px; } }
@media (max-width:760px) { .conflict-hero { align-items:flex-start; flex-direction:column; }.hero-metrics { width:100%; }.hero-metrics span { flex:1; min-width:0; padding:10px; }.conflict-grid { grid-template-columns:1fr; }.evidence-panel { grid-column:auto; }.intake-panel,.queue-panel,.evidence-panel { min-height:auto; padding:14px; }.queue-filters,.submit-actions,.two-fields { grid-template-columns:1fr; }.permission-mark { display:none; }.party-row { grid-template-columns:1fr 1fr 24px; } }
</style>

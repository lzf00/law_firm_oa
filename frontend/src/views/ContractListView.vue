<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ArrowRight,
  CheckCircle2,
  Clock3,
  Download,
  Eye,
  FileCheck2,
  FileClock,
  FileSignature,
  Fingerprint,
  History,
  Link2,
  Pencil,
  Plus,
  RefreshCw,
  Search,
  Send,
  ShieldCheck,
  Upload,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter, OrganizationUser } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Contract {
  id: string
  contractNumber: string
  title: string
  status: string
  clientId?: string
  clientName?: string
  responsibleUserId: string
  responsibleName: string
  effectiveDate?: string
  expiryDate?: string
  amount?: number
  currency: string
  matterCount: number
  matterIds: string[]
  currentVersionNumber?: number
  currentVersionStatus?: string
  signatureStatus?: string
  signedAt?: string
}

interface ContractVersion {
  id: string
  versionNumber: number
  status: string
  summary: string
  primaryDocumentId: string
  primaryDocumentVersionId: string
  primaryFilename: string
  primarySha256: string
  signedDocumentId?: string
  signedDocumentVersionId?: string
  signedFilename?: string
  signedSha256?: string
  signatureStatus: string
  createdByName: string
  createdAt: string
  finalizedByName?: string
  finalizedAt?: string
  signedByName?: string
  signedAt?: string
}

interface ContractEvent {
  id: string
  action: string
  actorName: string
  fromContractStatus?: string
  toContractStatus?: string
  contractVersionId?: string
  documentVersionId?: string
  comment?: string
  occurredAt: string
}

interface ContractDetail {
  contract: Contract
  versions: ContractVersion[]
  lifecycle: ContractEvent[]
}

interface Client {
  id: string
  displayName: string
  clientNumber: string
}

interface UploadTicket {
  uploadId: string
  uploadUrl: string
  requiredContentType: string
}

interface UploadedDocument {
  id: string
  currentVersionId: string
  ingestionStatus: string
}

const { locale } = useI18n()
const zh = computed(() => locale.value === 'zh-CN')
const contractCopyKeys = [
  'kicker', 'title', 'subtitle', 'guide', 'add', 'portfolio', 'search', 'all',
  'loading', 'empty', 'select', 'owner', 'client', 'value', 'matters', 'term',
  'noDate', 'evidence', 'versionLedger', 'timeline', 'reviewCopy',
  'replaceReviewCopy', 'uploadSigned', 'submit', 'finalize', 'edit', 'preview',
  'download', 'version', 'createdBy', 'finalizedBy', 'signedBy', 'hash',
  'noVersions', 'noEvents', 'integrity', 'contractNo', 'currency',
  'contractTitle', 'selectClient', 'responsible', 'effective', 'expiry', 'amount',
  'linkedMatters', 'save', 'saving', 'reviewTitle', 'reviewSummary',
  'reviewSummaryPlaceholder', 'chooseFile', 'signedTitle', 'signedComment',
  'signedCommentPlaceholder', 'chooseSigned', 'uploading', 'confirmFinalize',
  'confirmSigned', 'close', 'success', 'failed', 'required', 'dateInvalid',
  'fileRequired', 'signedRequired', 'uploadUnavailable', 'reviewRequired',
  'refresh', 'draft', 'reviewing', 'approved', 'signed',
] as const
const copy = computed(() => Object.fromEntries(
  contractCopyKeys.map((key) => [key, t(`contractOps.${key}`)]),
) as Record<(typeof contractCopyKeys)[number], string>)

const contracts = ref<Contract[]>([])
const detail = ref<ContractDetail | null>(null)
const clients = ref<Client[]>([])
const matters = ref<Matter[]>([])
const users = ref<OrganizationUser[]>([])
const currentUser = ref<CurrentUser | null>(null)
const selectedId = ref('')
const query = ref('')
const statusFilter = ref('')
const loading = ref(true)
const detailLoading = ref(false)
const saving = ref(false)
const actionId = ref('')
const contractDialog = ref(false)
const reviewDialog = ref(false)
const signedDialog = ref(false)
const editingId = ref('')
const reviewFile = ref<File>()
const signedFile = ref<File>()
const reviewInput = ref<HTMLInputElement>()
const signedInput = ref<HTMLInputElement>()
const selectedFinalVersion = ref<ContractVersion | null>(null)
const form = reactive({
  contractNumber: '',
  title: '',
  clientId: '',
  responsibleUserId: '',
  effectiveDate: '',
  expiryDate: '',
  amount: undefined as number | undefined,
  currency: 'CNY',
  matterIds: [] as string[],
})
const reviewForm = reactive({ summary: '' })
const signedForm = reactive({ comment: '' })

const can = (permission: string) =>
  currentUser.value?.permissions.includes(permission) ?? false
const selectedContract = computed(() =>
  detail.value?.contract ?? contracts.value.find((item) => item.id === selectedId.value) ?? null)
const filteredContracts = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return contracts.value.filter((item) => {
    const statusMatches = !statusFilter.value || item.status === statusFilter.value
    const textMatches = !needle || [
      item.contractNumber, item.title, item.clientName ?? '', item.responsibleName,
    ].some((value) => value.toLowerCase().includes(needle))
    return statusMatches && textMatches
  })
})
const counts = computed(() => ({
  draft: contracts.value.filter((item) => ['DRAFT', 'REJECTED'].includes(item.status)).length,
  reviewing: contracts.value.filter((item) => item.status === 'REVIEWING').length,
  approved: contracts.value.filter((item) => item.status === 'APPROVED').length,
  signed: contracts.value.filter((item) => item.status === 'SIGNED').length,
}))
const canEditSelected = computed(() =>
  Boolean(selectedContract.value && can('CONTRACT_MANAGE')
    && ['DRAFT', 'REJECTED'].includes(selectedContract.value.status)))
const canSubmit = computed(() =>
  Boolean(selectedContract.value && can('CONTRACT_MANAGE')
    && ['DRAFT', 'REJECTED'].includes(selectedContract.value.status)
    && detail.value?.versions.length))
const canUploadReview = computed(() =>
  Boolean(selectedContract.value && can('CONTRACT_MANAGE')
    && ['DRAFT', 'REJECTED'].includes(selectedContract.value.status)))
const finalizableVersions = computed(() =>
  detail.value?.versions.filter((item) => ['DRAFT', 'REVIEWED'].includes(item.status)) ?? [])
const finalVersion = computed(() =>
  detail.value?.versions.find((item) => item.status === 'FINAL') ?? null)

function money(contract: Contract) {
  if (contract.amount == null) return '—'
  return new Intl.NumberFormat(locale.value, {
    style: 'currency', currency: contract.currency, maximumFractionDigits: 2,
  }).format(contract.amount)
}

function date(value?: string) {
  if (!value) return copy.value.noDate
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric', month: 'short', day: 'numeric',
  }).format(new Date(value))
}

function dateTime(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

async function load(selectFirst = true) {
  loading.value = true
  try {
    const [contractResult, clientResult, matterResult, userResult, meResult] = await Promise.all([
      http.get<Contract[]>('/contracts'),
      http.get<Client[]>('/clients'),
      http.get<Matter[]>('/matters'),
      http.get<OrganizationUser[]>('/organization/users'),
      http.get<CurrentUser>('/me'),
    ])
    contracts.value = contractResult.data
    clients.value = clientResult.data
    matters.value = matterResult.data
    users.value = userResult.data
    currentUser.value = meResult.data
    if (selectFirst && (!selectedId.value || !contracts.value.some((item) => item.id === selectedId.value))) {
      selectedId.value = contracts.value[0]?.id ?? ''
    }
    if (selectedId.value) await loadDetail(selectedId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    loading.value = false
  }
}

async function loadDetail(id: string) {
  selectedId.value = id
  detailLoading.value = true
  try {
    detail.value = (await http.get<ContractDetail>(`/contracts/${id}`)).data
  } catch (error) {
    detail.value = null
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    detailLoading.value = false
  }
}

function openForm(contract?: Contract) {
  editingId.value = contract?.id ?? ''
  Object.assign(form, {
    contractNumber: contract?.contractNumber ?? '',
    title: contract?.title ?? '',
    clientId: contract?.clientId ?? '',
    responsibleUserId: contract?.responsibleUserId ?? currentUser.value?.userId ?? users.value[0]?.id ?? '',
    effectiveDate: contract?.effectiveDate ?? '',
    expiryDate: contract?.expiryDate ?? '',
    amount: contract?.amount,
    currency: contract?.currency ?? currentUser.value?.defaultCurrency ?? 'CNY',
    matterIds: [...(contract?.matterIds ?? [])],
  })
  contractDialog.value = true
}

async function save() {
  if (!form.contractNumber.trim() || !form.title.trim() || !form.responsibleUserId) {
    ElMessage.warning(copy.value.required)
    return
  }
  if (form.effectiveDate && form.expiryDate && form.expiryDate < form.effectiveDate) {
    ElMessage.warning(copy.value.dateInvalid)
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      clientId: form.clientId || null,
      effectiveDate: form.effectiveDate || null,
      expiryDate: form.expiryDate || null,
      amount: form.amount ?? null,
      currency: form.currency.toUpperCase(),
    }
    const saved = editingId.value
      ? (await http.put<Contract>(`/contracts/${editingId.value}`, payload)).data
      : (await http.post<Contract>('/contracts', payload)).data
    selectedId.value = saved.id
    contractDialog.value = false
    await load(false)
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

async function sha256(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('')
}

async function uploadContractFile(file: File, documentType: string) {
  if (!selectedContract.value) throw new Error(copy.value.failed)
  const hash = await sha256(file)
  const ticket = (await http.post<UploadTicket>('/documents/uploads', {
    contractId: selectedContract.value.id,
    logicalName: `${selectedContract.value.contractNumber} · ${file.name.replace(/\.[^.]+$/, '')}`,
    documentType,
    originalFilename: file.name,
    contentType: file.type || 'application/pdf',
    sizeBytes: file.size,
    sha256: hash,
  })).data
  const uploaded = await fetch(ticket.uploadUrl, {
    method: 'PUT',
    headers: { 'Content-Type': ticket.requiredContentType },
    body: file,
  })
  if (!uploaded.ok) throw new Error(copy.value.failed)
  const completed = (await http.post<UploadedDocument>(
    `/documents/uploads/${ticket.uploadId}/complete`,
  )).data
  if (completed.ingestionStatus !== 'AVAILABLE' || !completed.currentVersionId) {
    throw new Error(copy.value.uploadUnavailable)
  }
  return completed.currentVersionId
}

function openReviewUpload() {
  reviewForm.summary = ''
  reviewFile.value = undefined
  if (reviewInput.value) reviewInput.value.value = ''
  reviewDialog.value = true
}

async function registerReviewCopy() {
  if (!selectedContract.value || !reviewFile.value || !reviewForm.summary.trim()) {
    ElMessage.warning(copy.value.fileRequired)
    return
  }
  saving.value = true
  try {
    const documentVersionId = await uploadContractFile(reviewFile.value, 'CONTRACT_DRAFT')
    detail.value = (await http.post<ContractDetail>(
      `/contracts/${selectedContract.value.id}/versions`,
      { documentVersionId, summary: reviewForm.summary.trim() },
    )).data
    reviewDialog.value = false
    await load(false)
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

async function submitReview() {
  if (!selectedContract.value) return
  if (!detail.value?.versions.length) {
    ElMessage.warning(copy.value.reviewRequired)
    return
  }
  actionId.value = 'submit'
  try {
    await http.post('/workflows', {
      businessType: 'CONTRACT',
      businessId: selectedContract.value.id,
      variables: {},
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    await load(false)
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    actionId.value = ''
  }
}

async function finalizeVersion(version: ContractVersion) {
  if (!selectedContract.value || !window.confirm(copy.value.confirmFinalize)) return
  actionId.value = version.id
  try {
    detail.value = (await http.post<ContractDetail>(
      `/contracts/${selectedContract.value.id}/versions/${version.id}/finalize`,
      { comment: copy.value.confirmFinalize },
    )).data
    await load(false)
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    actionId.value = ''
  }
}

function openSignedUpload(version: ContractVersion) {
  selectedFinalVersion.value = version
  signedForm.comment = ''
  signedFile.value = undefined
  if (signedInput.value) signedInput.value.value = ''
  signedDialog.value = true
}

async function archiveSignedCopy() {
  if (!selectedContract.value || !selectedFinalVersion.value || !signedFile.value) {
    ElMessage.warning(copy.value.signedRequired)
    return
  }
  saving.value = true
  try {
    const signedDocumentVersionId = await uploadContractFile(signedFile.value, 'SIGNED_CONTRACT')
    detail.value = (await http.post<ContractDetail>(
      `/contracts/${selectedContract.value.id}/versions/${selectedFinalVersion.value.id}/signed-file`,
      { signedDocumentVersionId, comment: signedForm.comment || null },
    )).data
    signedDialog.value = false
    await load(false)
    ElMessage.success(copy.value.success)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  } finally {
    saving.value = false
  }
}

async function openFile(
  documentId: string,
  documentVersionId: string,
  action: 'preview' | 'download',
) {
  try {
    const ticket = (await http.post<{ downloadUrl: string }>(
      `/documents/${documentId}/versions/${documentVersionId}/${action}-url`,
    )).data
    window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : copy.value.failed)
  }
}

onMounted(() => load())
</script>

<template>
  <section class="module-page contract-workbench">
    <header class="contract-hero">
      <div>
        <span class="eyebrow">{{ copy.kicker }}</span>
        <h2>{{ copy.title }}</h2>
        <p>{{ copy.subtitle }}</p>
      </div>
      <button v-if="can('CONTRACT_CREATE')" class="primary-action" @click="openForm()">
        <Plus :size="17" /> {{ copy.add }}
      </button>
    </header>

    <div class="workflow-guide">
      <ShieldCheck :size="19" /><span>{{ copy.guide }}</span>
      <div class="guide-steps">
        <span>01 {{ copy.add }}</span><ArrowRight :size="13" />
        <span>02 {{ copy.reviewCopy }}</span><ArrowRight :size="13" />
        <span>03 {{ copy.submit }}</span><ArrowRight :size="13" />
        <span>04 {{ copy.uploadSigned }}</span>
      </div>
    </div>

    <div class="contract-metrics">
      <span><small>{{ copy.draft }}</small><strong>{{ counts.draft }}</strong></span>
      <span><small>{{ copy.reviewing }}</small><strong>{{ counts.reviewing }}</strong></span>
      <span><small>{{ copy.approved }}</small><strong>{{ counts.approved }}</strong></span>
      <span><small>{{ copy.signed }}</small><strong>{{ counts.signed }}</strong></span>
    </div>

    <div class="contract-layout">
      <aside class="panel register-panel">
        <div class="panel-heading">
          <div><FileSignature :size="18" /><h3>{{ copy.portfolio }}</h3></div>
          <button class="icon-action" :title="copy.refresh" @click="load(false)"><RefreshCw :size="16" /></button>
        </div>
        <div class="register-filters">
          <label><Search :size="15" /><input v-model="query" :placeholder="copy.search" /></label>
          <select v-model="statusFilter" :aria-label="copy.all">
            <option value="">{{ copy.all }}</option>
            <option v-for="status in ['DRAFT', 'REJECTED', 'REVIEWING', 'APPROVED', 'SIGNED']" :key="status" :value="status">
              {{ formatLegalCode(status, locale) }}
            </option>
          </select>
        </div>
        <div v-if="loading" class="empty-state">{{ copy.loading }}</div>
        <div v-else-if="filteredContracts.length" class="contract-list">
          <button
            v-for="contract in filteredContracts"
            :key="contract.id"
            class="contract-row"
            :class="{ active: selectedId === contract.id }"
            @click="loadDetail(contract.id)"
          >
            <div class="row-top">
              <code>{{ contract.contractNumber }}</code>
              <span class="status-pill">{{ formatLegalCode(contract.status, locale) }}</span>
            </div>
            <strong>{{ contract.title }}</strong>
            <span>{{ contract.clientName || '—' }} · {{ contract.responsibleName }}</span>
            <div class="row-bottom">
              <small>{{ money(contract) }}</small>
              <small v-if="contract.currentVersionNumber">V{{ contract.currentVersionNumber }} · {{ formatLegalCode(contract.currentVersionStatus || '', locale) }}</small>
              <small v-else>{{ copy.noVersions }}</small>
            </div>
          </button>
        </div>
        <div v-else class="empty-state"><FileSignature :size="30" /><strong>{{ copy.empty }}</strong></div>
      </aside>

      <main class="panel contract-detail">
        <div v-if="detailLoading" class="empty-state">{{ copy.loading }}</div>
        <template v-else-if="detail && selectedContract">
          <header class="detail-header">
            <div>
              <span class="eyebrow">{{ selectedContract.contractNumber }}</span>
              <h3>{{ selectedContract.title }}</h3>
              <div class="detail-tags">
                <span class="status-pill">{{ formatLegalCode(selectedContract.status, locale) }}</span>
                <span v-if="selectedContract.signatureStatus" class="signature-pill">
                  <FileCheck2 :size="13" /> {{ formatLegalCode(selectedContract.signatureStatus, locale) }}
                </span>
              </div>
            </div>
            <div class="detail-actions">
              <button v-if="canEditSelected" class="secondary-action" @click="openForm(selectedContract)">
                <Pencil :size="15" /> {{ copy.edit }}
              </button>
              <button v-if="canUploadReview" class="secondary-action" @click="openReviewUpload">
                <Upload :size="15" /> {{ detail.versions.length ? copy.replaceReviewCopy : copy.reviewCopy }}
              </button>
              <button v-if="canSubmit" class="primary-action" :disabled="actionId === 'submit'" @click="submitReview">
                <Send :size="15" /> {{ copy.submit }}
              </button>
            </div>
          </header>

          <dl class="contract-facts">
            <div><dt>{{ copy.client }}</dt><dd>{{ selectedContract.clientName || '—' }}</dd></div>
            <div><dt>{{ copy.owner }}</dt><dd>{{ selectedContract.responsibleName }}</dd></div>
            <div><dt>{{ copy.value }}</dt><dd>{{ money(selectedContract) }}</dd></div>
            <div><dt>{{ copy.term }}</dt><dd>{{ date(selectedContract.effectiveDate) }} → {{ date(selectedContract.expiryDate) }}</dd></div>
            <div><dt>{{ copy.matters }}</dt><dd>{{ selectedContract.matterCount }}</dd></div>
          </dl>

          <div class="integrity-note"><Fingerprint :size="18" /><span>{{ copy.integrity }}</span></div>

          <section class="detail-section">
            <div class="section-heading">
              <div><FileClock :size="18" /><h4>{{ copy.versionLedger }}</h4></div>
              <strong>{{ detail.versions.length }}</strong>
            </div>
            <div v-if="detail.versions.length" class="version-list">
              <article v-for="version in detail.versions" :key="version.id" class="version-card">
                <div class="version-head">
                  <div class="version-index">V{{ version.versionNumber }}</div>
                  <div>
                    <strong>{{ version.primaryFilename }}</strong>
                    <span>{{ version.summary }}</span>
                  </div>
                  <div class="version-status">
                    <span class="status-pill">{{ formatLegalCode(version.status, locale) }}</span>
                    <small>{{ formatLegalCode(version.signatureStatus, locale) }}</small>
                  </div>
                </div>
                <div class="file-proof">
                  <Fingerprint :size="14" />
                  <code>{{ copy.hash }} · {{ version.primarySha256 }}</code>
                </div>
                <div class="version-meta">
                  <span>{{ copy.createdBy }} · {{ version.createdByName }} · {{ dateTime(version.createdAt) }}</span>
                  <span v-if="version.finalizedAt">{{ copy.finalizedBy }} · {{ version.finalizedByName }} · {{ dateTime(version.finalizedAt) }}</span>
                </div>
                <div class="version-actions">
                  <button class="text-button" @click="openFile(version.primaryDocumentId, version.primaryDocumentVersionId, 'preview')"><Eye :size="14" /> {{ copy.preview }}</button>
                  <button class="text-button" @click="openFile(version.primaryDocumentId, version.primaryDocumentVersionId, 'download')"><Download :size="14" /> {{ copy.download }}</button>
                  <button
                    v-if="selectedContract.status === 'APPROVED' && can('CONTRACT_FINALIZE') && finalizableVersions.some((item) => item.id === version.id)"
                    class="text-button accent"
                    :disabled="actionId === version.id"
                    @click="finalizeVersion(version)"
                  ><CheckCircle2 :size="14" /> {{ copy.finalize }}</button>
                </div>

                <div v-if="version.signedDocumentVersionId" class="signed-proof">
                  <FileSignature :size="18" />
                  <div>
                    <strong>{{ version.signedFilename }}</strong>
                    <code>{{ version.signedSha256 }}</code>
                    <span>{{ copy.signedBy }} · {{ version.signedByName }} · {{ dateTime(version.signedAt) }}</span>
                  </div>
                  <div>
                    <button class="text-button" @click="openFile(version.signedDocumentId!, version.signedDocumentVersionId!, 'preview')"><Eye :size="14" /> {{ copy.preview }}</button>
                    <button class="text-button" @click="openFile(version.signedDocumentId!, version.signedDocumentVersionId!, 'download')"><Download :size="14" /> {{ copy.download }}</button>
                  </div>
                </div>
              </article>
            </div>
            <div v-else class="version-empty">
              <Upload :size="26" /><div><strong>{{ copy.noVersions }}</strong><span>{{ copy.guide }}</span></div>
              <button v-if="canUploadReview" class="primary-action" @click="openReviewUpload">{{ copy.reviewCopy }}</button>
            </div>
          </section>

          <button
            v-if="selectedContract.status === 'APPROVED' && finalVersion && can('CONTRACT_SIGN_ARCHIVE')"
            class="signed-cta"
            @click="openSignedUpload(finalVersion)"
          >
            <FileSignature :size="22" />
            <span><strong>{{ copy.uploadSigned }}</strong><small>{{ copy.confirmSigned }}</small></span>
            <ArrowRight :size="18" />
          </button>

          <section class="detail-section">
            <div class="section-heading">
              <div><History :size="18" /><h4>{{ copy.timeline }}</h4></div>
              <strong>{{ detail.lifecycle.length }}</strong>
            </div>
            <ol v-if="detail.lifecycle.length" class="event-timeline">
              <li v-for="event in detail.lifecycle" :key="event.id">
                <span class="event-dot"></span>
                <div>
                  <strong>{{ formatLegalCode(event.action, locale) }}</strong>
                  <span>{{ event.actorName }} · {{ dateTime(event.occurredAt) }}</span>
                  <small v-if="event.comment">{{ event.comment }}</small>
                </div>
              </li>
            </ol>
            <div v-else class="event-empty"><Clock3 :size="18" /> {{ copy.noEvents }}</div>
          </section>
        </template>
        <div v-else class="empty-state detail-empty"><Link2 :size="34" /><strong>{{ copy.select }}</strong></div>
      </main>
    </div>

    <ElDialog v-model="contractDialog" :title="editingId ? copy.edit : copy.add" width="min(760px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label><span>{{ copy.contractNo }}</span><input v-model="form.contractNumber" required maxlength="80" /></label>
        <label><span>{{ copy.currency }}</span><input v-model="form.currency" required maxlength="3" /></label>
        <label class="full-field"><span>{{ copy.contractTitle }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ copy.client }}</span><select v-model="form.clientId"><option value="">{{ copy.selectClient }}</option><option v-for="clientItem in clients" :key="clientItem.id" :value="clientItem.id">{{ clientItem.clientNumber }} · {{ clientItem.displayName }}</option></select></label>
        <label><span>{{ copy.responsible }}</span><select v-model="form.responsibleUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ copy.effective }}</span><input v-model="form.effectiveDate" type="date" /></label>
        <label><span>{{ copy.expiry }}</span><input v-model="form.expiryDate" type="date" /></label>
        <label><span>{{ copy.amount }}</span><input v-model.number="form.amount" type="number" min="0" step="0.01" /></label>
        <label class="full-field"><span>{{ copy.linkedMatters }}</span><select v-model="form.matterIds" multiple size="4"><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? copy.saving : copy.save }}</button>
      </form>
    </ElDialog>

    <ElDialog v-model="reviewDialog" :title="copy.reviewTitle" width="min(600px, 94vw)">
      <form class="upload-form" @submit.prevent="registerReviewCopy">
        <label><span>{{ copy.reviewSummary }}</span><textarea v-model="reviewForm.summary" required maxlength="500" :placeholder="copy.reviewSummaryPlaceholder"></textarea></label>
        <label class="file-picker">
          <Upload :size="22" />
          <span>{{ reviewFile?.name || copy.chooseFile }}</span>
          <input ref="reviewInput" type="file" accept=".pdf,.doc,.docx" @change="reviewFile = ($event.target as HTMLInputElement).files?.[0]" />
        </label>
        <div class="integrity-note"><ShieldCheck :size="17" />{{ copy.integrity }}</div>
        <button class="primary-action full" type="submit" :disabled="saving">{{ saving ? copy.uploading : copy.reviewCopy }}</button>
      </form>
    </ElDialog>

    <ElDialog v-model="signedDialog" :title="copy.signedTitle" width="min(600px, 94vw)">
      <form class="upload-form" @submit.prevent="archiveSignedCopy">
        <div class="final-reference">
          <FileCheck2 :size="19" />
          <span>V{{ selectedFinalVersion?.versionNumber }} · {{ selectedFinalVersion?.primaryFilename }}</span>
        </div>
        <label><span>{{ copy.signedComment }}</span><textarea v-model="signedForm.comment" maxlength="1000" :placeholder="copy.signedCommentPlaceholder"></textarea></label>
        <label class="file-picker">
          <FileSignature :size="22" />
          <span>{{ signedFile?.name || copy.chooseSigned }}</span>
          <input ref="signedInput" type="file" accept=".pdf" @change="signedFile = ($event.target as HTMLInputElement).files?.[0]" />
        </label>
        <div class="integrity-note"><ShieldCheck :size="17" />{{ copy.confirmSigned }}</div>
        <button class="primary-action full" type="submit" :disabled="saving">{{ saving ? copy.uploading : copy.uploadSigned }}</button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.contract-workbench { --ink:#17372d; --gold:#a88743; --line:#ded9ce; }
.contract-hero { display:flex; justify-content:space-between; align-items:flex-end; gap:24px; padding:4px 0 20px; border-bottom:1px solid var(--line); }
.contract-hero h2 { margin:8px 0 6px; font-family:var(--font-display); font-size:clamp(28px,3vw,42px); color:var(--ink); }
.contract-hero p { margin:0; color:#65736e; }
.workflow-guide { display:flex; align-items:center; gap:10px; margin:16px 0 10px; padding:12px 14px; border-left:3px solid var(--gold); background:#f0f4f1; color:#486159; font-size:12px; }
.guide-steps { display:flex; align-items:center; gap:7px; margin-left:auto; color:#505b51; font-size:10px; letter-spacing:.02em; }
.contract-metrics { display:grid; grid-template-columns:repeat(4,1fr); gap:1px; margin-bottom:14px; border:1px solid var(--line); background:var(--line); }
.contract-metrics span { display:flex; align-items:center; justify-content:space-between; padding:11px 14px; background:#fffefa; }
.contract-metrics small { color:#56635d; }.contract-metrics strong { color:var(--ink); font-size:20px; }
.contract-layout { display:grid; grid-template-columns:minmax(330px,.9fr) minmax(0,1.65fr); gap:14px; align-items:start; min-width:0; }
.panel { border:1px solid var(--line); border-radius:2px; background:#fffefa; box-shadow:0 8px 24px rgba(25,45,37,.04); }
.register-panel,.contract-detail { min-width:0; max-width:100%; min-height:680px; padding:18px; }
.panel-heading,.section-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:16px; }
.panel-heading>div,.section-heading>div { display:flex; align-items:center; gap:9px; color:var(--ink); }
.panel-heading h3,.section-heading h4 { margin:0; }
.section-heading strong { color:#8a6a32; }
.icon-action { display:grid; place-items:center; border:0; background:transparent; color:#426254; cursor:pointer; }
.register-filters { display:grid; grid-template-columns:1fr 110px; gap:8px; margin-bottom:12px; }
.register-filters label { display:flex; align-items:center; gap:7px; padding:0 9px; border:1px solid #d9d5ca; background:#fff; }
.register-filters input,.register-filters select { width:100%; border:1px solid #d9d5ca; background:#fff; padding:9px; color:#2c443b; outline:none; }
.register-filters label input { border:0; padding-left:0; }
.contract-list { display:grid; gap:7px; min-width:0; max-height:600px; overflow:auto; padding-right:3px; }
.contract-row { display:grid; gap:7px; width:100%; min-width:0; padding:13px; border:1px solid #e0ddd3; border-left:3px solid transparent; background:#fff; color:inherit; text-align:left; cursor:pointer; }
.contract-row:hover,.contract-row.active { border-color:#7d978b; border-left-color:#2e604d; background:#f3f7f4; }
.row-top,.row-bottom { display:flex; align-items:center; justify-content:space-between; gap:10px; }
.row-top code { color:#8d6b31; font-size:10px; }.contract-row>strong { color:#253f36; }.contract-row>span,.row-bottom small { color:#52615a; font-size:11px; }
.row-bottom small:last-child { max-width:58%; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.status-pill,.signature-pill { display:inline-flex; align-items:center; gap:4px; padding:4px 7px; border:1px solid #cdd8d2; background:#edf3ef; color:#214a3b; font-size:10px; white-space:nowrap; }
.signature-pill { border-color:#d7c793; background:#fbf6e8; color:#79602e; }
.detail-header { display:flex; align-items:flex-start; justify-content:space-between; gap:20px; padding-bottom:18px; border-bottom:1px solid #e6e2d8; }
.detail-header h3 { margin:7px 0 8px; color:var(--ink); font-family:var(--font-display); font-size:26px; }
.detail-tags,.detail-actions { display:flex; flex-wrap:wrap; gap:7px; }
.secondary-action,.primary-action { display:inline-flex; align-items:center; justify-content:center; gap:7px; min-height:38px; padding:8px 12px; border-radius:2px; font-weight:650; cursor:pointer; }
.secondary-action { border:1px solid #becbc5; background:#f8faf8; color:#2d5747; }
.primary-action { border:1px solid #183d30; background:#183d30; color:#fff; }
.secondary-action:disabled,.primary-action:disabled { opacity:.45; cursor:not-allowed; }
.full { width:100%; }
.contract-facts { display:grid; grid-template-columns:1.2fr 1fr 1fr 1.5fr .7fr; margin:14px 0; border:1px solid #e3dfd5; background:#f9f8f3; }
.contract-facts div { padding:11px 12px; border-right:1px solid #e3dfd5; }.contract-facts div:last-child { border:0; }
.contract-facts dt { color:#4f5e57; font-size:10px; }.contract-facts dd { margin:5px 0 0; color:#2e463d; font-size:12px; font-weight:650; }
.integrity-note { display:flex; align-items:center; gap:8px; padding:10px 12px; background:#f2f5f2; color:#5a6d65; font-size:11px; line-height:1.45; }
.detail-section { padding-top:20px; }
.version-list { display:grid; gap:10px; }
.version-card { padding:13px; border:1px solid #dedbd1; background:#fff; }
.version-head { display:grid; grid-template-columns:44px 1fr auto; gap:11px; align-items:start; }
.version-index { display:grid; place-items:center; width:40px; height:40px; border:1px solid #bca976; color:#795e2c; font-family:Georgia,serif; font-weight:700; }
.version-head>div:nth-child(2) { display:grid; gap:4px; }.version-head strong { color:#294138; }.version-head span { color:#4f5f58; font-size:11px; line-height:1.45; }
.version-status { display:grid; justify-items:end; gap:5px; }.version-status small { color:#8a713f; font-size:10px; }
.file-proof { display:flex; align-items:center; gap:7px; margin:10px 0 7px; padding:7px 9px; background:#f5f6f3; color:#66736d; overflow:hidden; }
.file-proof code { overflow:hidden; text-overflow:ellipsis; white-space:nowrap; font-size:9px; }
.version-meta { display:flex; flex-wrap:wrap; gap:8px 20px; color:#4e5e57; font-size:10px; }
.version-actions { display:flex; gap:12px; margin-top:10px; padding-top:9px; border-top:1px solid #ebe8df; }
.text-button { display:inline-flex; align-items:center; gap:5px; border:0; background:transparent; color:#49675c; font-size:11px; cursor:pointer; }.text-button.accent { color:#8a6524; font-weight:700; }
.signed-proof { display:grid; grid-template-columns:auto 1fr auto; gap:10px; align-items:center; margin-top:11px; padding:11px; border-left:3px solid #aa8b46; background:#fbf7ed; color:#83672e; }
.signed-proof>div { display:grid; gap:3px; min-width:0; }.signed-proof span,.signed-proof code { color:#6f766f; font-size:9px; overflow:hidden; text-overflow:ellipsis; }
.version-empty { display:grid; grid-template-columns:auto 1fr auto; align-items:center; gap:12px; padding:20px; border:1px dashed #c9ccc5; color:#6f7a74; }
.version-empty div { display:grid; gap:4px; }.version-empty span { font-size:11px; }
.signed-cta { display:grid; grid-template-columns:auto 1fr auto; gap:12px; align-items:center; width:100%; margin-top:18px; padding:14px; border:1px solid #bba66c; background:#fbf7e9; color:#715a2c; text-align:left; cursor:pointer; }
.signed-cta span { display:grid; gap:4px; }.signed-cta small { color:#7c7a6d; }
.event-timeline { list-style:none; margin:0; padding:0 0 0 4px; }
.event-timeline li { position:relative; display:grid; grid-template-columns:14px 1fr; gap:9px; padding-bottom:14px; }
.event-timeline li:not(:last-child)::before { content:''; position:absolute; left:5px; top:12px; bottom:0; border-left:1px solid #d6dad6; }
.event-dot { position:relative; z-index:1; width:11px; height:11px; margin-top:3px; border:2px solid #628174; border-radius:50%; background:#fff; }
.event-timeline div { display:grid; gap:3px; }.event-timeline strong { color:#334d43; font-size:12px; }.event-timeline span,.event-timeline small { color:#4e5e57; font-size:10px; }
.event-empty { display:flex; align-items:center; gap:8px; color:#818984; font-size:12px; }
.empty-state { display:flex; flex-direction:column; align-items:center; justify-content:center; gap:8px; min-height:180px; color:#87908b; text-align:center; }.detail-empty { min-height:620px; }
.upload-form { display:grid; gap:14px; }.upload-form>label:not(.file-picker) { display:grid; gap:7px; color:#41534d; font-size:12px; font-weight:650; }.upload-form textarea { min-height:100px; padding:10px; border:1px solid #d8d5ca; resize:vertical; }
.file-picker { position:relative; display:flex; align-items:center; gap:10px; padding:18px; border:1px dashed #93a69d; background:#f6f9f7; color:#38594c; cursor:pointer; }.file-picker input { position:absolute; inset:0; opacity:0; cursor:pointer; }
.final-reference { display:flex; align-items:center; gap:8px; padding:11px; background:#f4f5f1; color:#4b6258; font-size:12px; }
@media (max-width:1100px) { .contract-layout { grid-template-columns:1fr; }.register-panel { min-height:auto; }.contract-list { max-height:360px; }.contract-facts { grid-template-columns:repeat(3,1fr); }.contract-facts div { border-bottom:1px solid #e3dfd5; } }
@media (max-width:760px) { .contract-hero { align-items:flex-start; flex-direction:column; }.workflow-guide { align-items:flex-start; }.guide-steps { display:none; }.contract-metrics { grid-template-columns:1fr 1fr; }.contract-layout { grid-template-columns:minmax(0,1fr); }.register-panel,.contract-detail { min-height:auto; padding:14px; overflow:hidden; }.register-filters { grid-template-columns:minmax(0,1fr); }.detail-header { flex-direction:column; }.contract-facts { grid-template-columns:1fr 1fr; }.version-head { grid-template-columns:40px minmax(0,1fr); }.version-status { grid-column:2; justify-items:start; }.version-empty { grid-template-columns:1fr; }.signed-proof { grid-template-columns:auto minmax(0,1fr); }.signed-proof>div:last-child { grid-column:2; }.detail-actions { width:100%; }.detail-actions button { flex:1; } }
</style>

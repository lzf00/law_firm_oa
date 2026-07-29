<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Download,
  Eye,
  FileCheck2,
  FileClock,
  FileLock2,
  FileText,
  FileUp,
  FolderArchive,
  History,
  KeyRound,
  Link2,
  Plus,
  RefreshCw,
  ShieldCheck,
  Trash2,
  UsersRound,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter, OrganizationUser } from '@/api/types'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface DocumentItem {
  id: string
  matterId?: string
  contractId?: string
  logicalName: string
  documentType: string
  confidentialityLevel: string
  currentVersionId: string
  versionNumber: number
  versionStatus: string
  signatureStatus: string
  originalFilename: string
  sizeBytes: number
  createdAt: string
  ingestionStatus: string
  scanFailureReason?: string
  scanCompletedAt?: string
}

interface DocumentVersion {
  id: string
  versionNumber: number
  filename: string
  contentType: string
  detectedContentType?: string
  sizeBytes: number
  sha256: string
  status: string
  signatureStatus: string
  ingestionStatus: string
  current: boolean
  createdBy: string
  createdByName: string
  createdAt: string
  scanCompletedAt?: string
}

interface DocumentGrant {
  id: string
  userId: string
  username: string
  displayName: string
  permission: string
  grantedByName: string
  expiresAt?: string
  createdAt: string
  active: boolean
}

interface ContractSummary {
  id: string
  contractNumber: string
  title: string
}

interface DocumentEvidence {
  documentId: string
  matterId?: string
  matterNumber?: string
  matterTitle?: string
  contractId?: string
  contractNumber?: string
  contractTitle?: string
  contractVersions: Array<{
    contractVersionId: string
    versionNumber: number
    versionStatus: string
    signatureStatus: string
    primaryFile: boolean
    signedFile: boolean
  }>
  archives: Array<{
    archiveId: string
    archiveNumber: string
    archiveTitle: string
    archiveStatus: string
    pinnedDocumentVersionId: string
    sequenceNumber: number
  }>
}

const { locale } = useI18n()
const route = useRoute()
const router = useRouter()
const matters = ref<Matter[]>([])
const contracts = ref<ContractSummary[]>([])
const currentUser = ref<CurrentUser | null>(null)
const organizationUsers = ref<OrganizationUser[]>([])
const selectedMatter = ref('')
const selectedContract = ref('')
const scope = ref<'matter' | 'contract'>('matter')
const documents = ref<DocumentItem[]>([])
const selectedDocumentId = ref('')
const versions = ref<DocumentVersion[]>([])
const grants = ref<DocumentGrant[]>([])
const evidence = ref<DocumentEvidence | null>(null)
const loading = ref(false)
const versionLoading = ref(false)
const uploading = ref(false)
const uploadMode = ref<'new' | 'version'>('new')
const fileInput = ref<HTMLInputElement | null>(null)
const grantDialog = ref(false)
const grantSaving = ref(false)
const grantForm = ref({
  userId: '',
  permission: 'PREVIEW',
  expiresAt: '',
})

const selectedMatterItem = computed(() =>
  matters.value.find((item) => item.id === selectedMatter.value))
const selectedMatterName = computed(() =>
  selectedMatterItem.value?.title ?? t('copy.0136'))
const selectedContractItem = computed(() =>
  contracts.value.find((item) => item.id === selectedContract.value))
const selectedContextName = computed(() => scope.value === 'contract'
  ? selectedContractItem.value?.title ?? t('documents.noContract')
  : selectedMatterName.value)
const selectedDocument = computed(() =>
  documents.value.find((item) => item.id === selectedDocumentId.value) ?? null)
const currentVersion = computed(() =>
  versions.value.find((item) => item.current) ?? versions.value[0] ?? null)
const availableCount = computed(() =>
  documents.value.filter((item) => item.ingestionStatus === 'AVAILABLE').length)
const canManageSelected = computed(() => Boolean(
  selectedDocument.value
  && currentUser.value
  && (
    currentUser.value.permissions.includes('DOCUMENT_GOVERNANCE_MANAGE')
    || selectedMatterItem.value?.responsibleUserId === currentUser.value.userId
  ),
))

onMounted(async () => {
  try {
    const [matterResult, contractResult, meResult, userResult] = await Promise.all([
      http.get<Matter[]>('/matters'),
      http.get<ContractSummary[]>('/contracts'),
      http.get<CurrentUser>('/me'),
      http.get<OrganizationUser[]>('/organization/users'),
    ])
    matters.value = matterResult.data
    contracts.value = contractResult.data
    currentUser.value = meResult.data
    organizationUsers.value = userResult.data.filter((item) => item.status === 'ACTIVE')
    const requestedContract = String(route.query.contractId ?? '')
    const requestedMatter = String(route.query.matterId ?? '')
    scope.value = requestedContract && contracts.value.some((item) => item.id === requestedContract)
      ? 'contract'
      : 'matter'
    selectedContract.value = scope.value === 'contract'
      ? requestedContract
      : contracts.value[0]?.id ?? ''
    selectedMatter.value = matters.value.some((item) => item.id === requestedMatter)
      ? requestedMatter
      : matters.value[0]?.id ?? ''
    await loadDocuments()
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.loadFailed'))
  }
})

watch(selectedMatter, () => {
  if (scope.value === 'matter') loadDocuments()
})
watch(selectedContract, () => {
  if (scope.value === 'contract') loadDocuments()
})
watch(scope, loadDocuments)
watch(selectedDocumentId, async (documentId) => {
  versions.value = []
  grants.value = []
  evidence.value = null
  if (!documentId) return
  await Promise.all([loadVersions(documentId), loadEvidence(documentId)])
})

async function loadDocuments() {
  const contextId = scope.value === 'matter' ? selectedMatter.value : selectedContract.value
  if (!contextId) {
    documents.value = []
    selectedDocumentId.value = ''
    return
  }
  loading.value = true
  try {
    documents.value = (await http.get<DocumentItem[]>('/documents', {
      params: scope.value === 'matter'
        ? { matterId: contextId }
        : { contractId: contextId },
    })).data
    const requestedDocument = String(route.query.documentId ?? '')
    if (!documents.value.some((item) => item.id === selectedDocumentId.value)) {
      selectedDocumentId.value = documents.value.some((item) => item.id === requestedDocument)
        ? requestedDocument
        : documents.value[0]?.id ?? ''
    }
  } catch (error) {
    documents.value = []
    selectedDocumentId.value = ''
    ElMessage.error(error instanceof Error ? error.message : t('documents.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function loadEvidence(documentId = selectedDocumentId.value) {
  if (!documentId) return
  try {
    const result = (await http.get<DocumentEvidence>(
      `/documents/${documentId}/evidence`,
    )).data
    evidence.value = result && !Array.isArray(result)
      ? {
          ...result,
          contractVersions: result.contractVersions ?? [],
          archives: result.archives ?? [],
        }
      : null
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.evidenceLoadFailed'))
  }
}

async function loadVersions(documentId = selectedDocumentId.value) {
  if (!documentId) return
  versionLoading.value = true
  try {
    versions.value = (await http.get<DocumentVersion[]>(
      `/documents/${documentId}/versions`,
    )).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.versionLoadFailed'))
  } finally {
    versionLoading.value = false
  }
}

function humanSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

async function sha256(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)]
    .map((value) => value.toString(16).padStart(2, '0'))
    .join('')
}

function requestUpload(mode: 'new' | 'version') {
  const contextId = scope.value === 'matter' ? selectedMatter.value : selectedContract.value
  if (!contextId || (mode === 'version' && !selectedDocument.value)) return
  uploadMode.value = mode
  fileInput.value?.click()
}

async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  const contextId = scope.value === 'matter' ? selectedMatter.value : selectedContract.value
  if (!file || !contextId) return
  const target = uploadMode.value === 'version' ? selectedDocument.value : null
  uploading.value = true
  try {
    const hash = await sha256(file)
    const ticket = (await http.post('/documents/uploads', {
      documentId: target?.id,
      matterId: scope.value === 'matter' ? contextId : undefined,
      contractId: scope.value === 'contract' ? contextId : undefined,
      logicalName: target?.logicalName ?? file.name.replace(/\.[^.]+$/, ''),
      documentType: target?.documentType ?? 'CASE_FILE',
      originalFilename: file.name,
      contentType: file.type || 'application/pdf',
      sizeBytes: file.size,
      sha256: hash,
    })).data
    const uploadResponse = await fetch(ticket.uploadUrl, {
      method: 'PUT',
      headers: { 'Content-Type': ticket.requiredContentType },
      body: file,
    })
    if (!uploadResponse.ok) throw new Error(t('copy.0138'))
    const completed = (await http.post<DocumentItem>(
      `/documents/uploads/${ticket.uploadId}/complete`,
    )).data
    await loadDocuments()
    selectedDocumentId.value = completed.id
    await loadVersions(completed.id)
    ElMessage.success(uploadMode.value === 'version'
      ? t('documents.versionUploaded')
      : t('copy.0139'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0140'))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

function openContract() {
  const contractId = evidence.value?.contractId
  if (contractId) router.push({ path: '/contracts', query: { contractId } })
}

function openArchive(archiveId: string) {
  router.push({ path: '/archives', query: { archiveId } })
}

async function openVersion(version: DocumentVersion, action: 'preview' | 'download') {
  if (!selectedDocument.value || version.ingestionStatus !== 'AVAILABLE') {
    ElMessage.warning(t('copy.0141'))
    return
  }
  try {
    const ticket = (await http.post(
      `/documents/${selectedDocument.value.id}/versions/${version.id}/${action}-url`,
    )).data
    window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0142'))
  }
}

async function openGrantDialog() {
  if (!selectedDocument.value) return
  try {
    grants.value = (await http.get<DocumentGrant[]>(
      `/documents/${selectedDocument.value.id}/grants`,
    )).data
    grantForm.value = { userId: '', permission: 'PREVIEW', expiresAt: '' }
    grantDialog.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.grantLoadFailed'))
  }
}

async function saveGrant() {
  if (!selectedDocument.value || !grantForm.value.userId) {
    ElMessage.warning(t('documents.selectGrantee'))
    return
  }
  grantSaving.value = true
  try {
    await http.post(`/documents/${selectedDocument.value.id}/grants`, {
      userId: grantForm.value.userId,
      permission: grantForm.value.permission,
      expiresAt: grantForm.value.expiresAt
        ? new Date(grantForm.value.expiresAt).toISOString()
        : null,
    })
    grants.value = (await http.get<DocumentGrant[]>(
      `/documents/${selectedDocument.value.id}/grants`,
    )).data
    grantForm.value = { userId: '', permission: 'PREVIEW', expiresAt: '' }
    ElMessage.success(t('documents.grantSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.grantSaveFailed'))
  } finally {
    grantSaving.value = false
  }
}

async function revokeGrant(grant: DocumentGrant) {
  if (!selectedDocument.value) return
  try {
    await http.delete(`/documents/${selectedDocument.value.id}/grants/${grant.id}`)
    grants.value = grants.value.filter((item) => item.id !== grant.id)
    ElMessage.success(t('documents.grantRevoked'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('documents.grantRevokeFailed'))
  }
}
</script>

<template>
  <section class="module-page document-page">
    <header class="page-intro document-intro">
      <div>
        <span class="eyebrow">{{ t('documents.kicker') }}</span>
        <h2>{{ t('headline.documents') }}</h2>
        <p data-allow-business-data>{{ tp('documents.currentContext', { context: selectedContextName }) }}</p>
      </div>
      <div class="page-actions">
        <button class="secondary-action" type="button" :disabled="loading" @click="loadDocuments()">
          <RefreshCw :size="16" />{{ t('documents.refresh') }}
        </button>
        <button class="primary-action" type="button" :disabled="!(scope === 'matter' ? selectedMatter : selectedContract) || uploading" @click="requestUpload('new')">
          <FileUp :size="17" />{{ uploading ? t('copy.0143') : t('copy.0144') }}
        </button>
        <input
          ref="fileInput"
          class="sr-only"
          type="file"
          :aria-label="t('copy.0144')"
          @change="upload"
        />
      </div>
    </header>

    <section class="context-bar">
      <label class="scope-switch">
        <span>{{ t('documents.scope') }}</span>
        <select v-model="scope">
          <option value="matter">{{ t('documents.matterFiles') }}</option>
          <option value="contract">{{ t('documents.contractFiles') }}</option>
        </select>
      </label>
      <label v-if="scope === 'matter'">
        <span>{{ t('documents.matter') }}</span>
        <select v-model="selectedMatter">
          <option v-for="matter in matters" :key="matter.id" :value="matter.id">
            {{ matter.matterNumber }} · {{ matter.title }}
          </option>
        </select>
      </label>
      <label v-else>
        <span>{{ t('documents.contract') }}</span>
        <select v-model="selectedContract">
          <option v-for="item in contracts" :key="item.id" :value="item.id">
            {{ item.contractNumber }} · {{ item.title }}
          </option>
        </select>
      </label>
      <div class="context-metric">
        <FileCheck2 :size="18" />
        <span>{{ t('documents.safeFiles') }}</span>
        <strong>{{ availableCount }}/{{ documents.length }}</strong>
      </div>
      <div class="context-metric">
        <ShieldCheck :size="18" />
        <span>{{ t('documents.security') }}</span>
        <strong>{{ t('copy.0147') }}</strong>
      </div>
    </section>

    <section class="document-workspace">
      <aside class="document-index panel">
        <header>
          <div>
            <span class="eyebrow">{{ t('documents.caseFiles') }}</span>
            <h3>{{ tp('documents.fileCount', { count: documents.length }) }}</h3>
          </div>
          <Plus :size="18" />
        </header>
        <div v-if="loading" class="empty-state compact-empty">
          <FileClock class="spin" :size="28" /><strong>{{ t('governance.loading') }}</strong>
        </div>
        <div v-else-if="documents.length" class="document-list">
          <button
            v-for="item in documents"
            :key="item.id"
            type="button"
            :class="{ active: selectedDocumentId === item.id }"
            @click="selectedDocumentId = item.id"
          >
            <span class="file-symbol"><FileText :size="18" /></span>
            <span class="file-copy">
              <strong>{{ item.logicalName }}</strong>
              <small>{{ item.originalFilename }}</small>
              <em>
                V{{ item.versionNumber }} · {{ formatLegalCode(item.ingestionStatus, locale) }}
              </em>
            </span>
          </button>
        </div>
        <div v-else class="empty-state compact-empty">
          <FileLock2 :size="30" />
          <strong>{{ t('copy.0157') }}</strong>
          <span>{{ t('copy.0158') }}</span>
        </div>
      </aside>

      <article v-if="selectedDocument" class="document-detail panel">
        <header class="detail-heading">
          <span class="detail-icon"><FileText :size="23" /></span>
          <div>
            <span class="eyebrow">{{ formatLegalCode(selectedDocument.documentType, locale) }}</span>
            <h3>{{ selectedDocument.logicalName }}</h3>
            <p>{{ selectedDocument.originalFilename }}</p>
          </div>
          <span class="status-pill">
            {{ formatLegalCode(selectedDocument.ingestionStatus, locale) }}
          </span>
        </header>

        <div class="document-facts">
          <span><small>{{ t('documents.currentVersion') }}</small><strong>V{{ selectedDocument.versionNumber }}</strong></span>
          <span><small>{{ t('copy.0153') }}</small><strong>{{ formatLegalCode(selectedDocument.confidentialityLevel, locale) }}</strong></span>
          <span><small>{{ t('copy.0154') }}</small><strong>{{ humanSize(selectedDocument.sizeBytes) }}</strong></span>
          <span><small>{{ t('documents.updatedAt') }}</small><strong>{{ formatDate(selectedDocument.createdAt) }}</strong></span>
        </div>

        <div class="detail-actions">
          <button
            class="primary-action"
            type="button"
            :disabled="selectedDocument.ingestionStatus !== 'AVAILABLE'"
            @click="currentVersion && openVersion(currentVersion, 'preview')"
          >
            <Eye :size="16" />{{ t('documents.preview') }}
          </button>
          <button
            class="secondary-action"
            type="button"
            :disabled="selectedDocument.ingestionStatus !== 'AVAILABLE'"
            @click="currentVersion && openVersion(currentVersion, 'download')"
          >
            <Download :size="16" />{{ t('documents.download') }}
          </button>
          <button class="secondary-action" type="button" :disabled="uploading" @click="requestUpload('version')">
            <FileUp :size="16" />{{ t('documents.uploadVersion') }}
          </button>
          <button v-if="canManageSelected" class="secondary-action" type="button" @click="openGrantDialog">
            <UsersRound :size="16" />{{ t('documents.access') }}
          </button>
        </div>

        <section v-if="evidence" class="evidence-section">
          <header>
            <div><Link2 :size="18" /><strong>{{ t('documents.evidenceChain') }}</strong></div>
            <span>{{ t('documents.evidenceHint') }}</span>
          </header>
          <div class="evidence-links">
            <button v-if="evidence.contractId" type="button" @click="openContract">
              <FileCheck2 :size="18" />
              <span><strong>{{ evidence.contractNumber }} · {{ evidence.contractTitle }}</strong><small>{{ evidence.contractVersions.length }} {{ t('documents.contractVersions') }}</small></span>
              <Eye :size="15" />
            </button>
            <button
              v-for="item in evidence.archives"
              :key="`${item.archiveId}-${item.pinnedDocumentVersionId}`"
              type="button"
              @click="openArchive(item.archiveId)"
            >
              <FolderArchive :size="18" />
              <span><strong>{{ item.archiveNumber }} · {{ item.archiveTitle }}</strong><small>{{ t('documents.archiveSequence') }} {{ item.sequenceNumber }} · {{ formatLegalCode(item.archiveStatus, locale) }}</small></span>
              <Eye :size="15" />
            </button>
            <span v-if="!evidence.contractId && !evidence.archives.length" class="no-evidence">
              <ShieldCheck :size="17" />{{ t('documents.notLinked') }}
            </span>
          </div>
        </section>

        <section class="version-section">
          <header>
            <div><History :size="18" /><strong>{{ t('documents.versionHistory') }}</strong></div>
            <span>{{ tp('documents.versionCount', { count: versions.length }) }}</span>
          </header>
          <div v-if="versionLoading" class="empty-state compact-empty">
            <FileClock class="spin" :size="25" /><strong>{{ t('governance.loading') }}</strong>
          </div>
          <div v-else class="version-list">
            <article v-for="version in versions" :key="version.id">
              <span class="version-marker">V{{ version.versionNumber }}</span>
              <div>
                <strong>
                  {{ version.filename }}
                  <em v-if="version.current">{{ t('documents.current') }}</em>
                </strong>
                <small>
                  {{ version.createdByName }} · {{ formatDate(version.createdAt) }} ·
                  {{ humanSize(version.sizeBytes) }}
                </small>
                <span>
                  {{ formatLegalCode(version.ingestionStatus, locale) }} ·
                  {{ formatLegalCode(version.status, locale) }} ·
                  SHA-256 {{ version.sha256.slice(0, 10) }}…
                </span>
              </div>
              <div class="version-actions">
                <button
                  type="button"
                  :disabled="version.ingestionStatus !== 'AVAILABLE'"
                  :aria-label="t('documents.preview')"
                  @click="openVersion(version, 'preview')"
                ><Eye :size="15" /></button>
                <button
                  type="button"
                  :disabled="version.ingestionStatus !== 'AVAILABLE'"
                  :aria-label="t('documents.download')"
                  @click="openVersion(version, 'download')"
                ><Download :size="15" /></button>
              </div>
            </article>
          </div>
        </section>
      </article>

      <div v-else class="document-detail panel empty-state">
        <FileLock2 :size="34" />
        <strong>{{ t('documents.selectFile') }}</strong>
        <span>{{ t('documents.selectFileHint') }}</span>
      </div>
    </section>

    <el-dialog
      v-model="grantDialog"
      :title="t('documents.accessTitle')"
      width="min(760px, 94vw)"
      append-to-body
    >
      <div class="grant-dialog">
        <aside>
          <KeyRound :size="22" />
          <div>
            <strong>{{ selectedDocument?.logicalName }}</strong>
            <span>{{ t('documents.accessHint') }}</span>
          </div>
        </aside>
        <div class="grant-form">
          <label>
            <span>{{ t('documents.grantee') }}</span>
            <select v-model="grantForm.userId">
              <option value="">{{ t('documents.selectGrantee') }}</option>
              <option v-for="user in organizationUsers" :key="user.id" :value="user.id">
                {{ user.displayName }} · {{ user.username }}
              </option>
            </select>
          </label>
          <label>
            <span>{{ t('documents.permission') }}</span>
            <select v-model="grantForm.permission">
              <option value="PREVIEW">{{ t('documents.permissionPreview') }}</option>
              <option value="DOWNLOAD">{{ t('documents.permissionDownload') }}</option>
              <option value="EDIT">{{ t('documents.permissionEdit') }}</option>
              <option value="SHARE">{{ t('documents.permissionShare') }}</option>
            </select>
          </label>
          <label>
            <span>{{ t('documents.expiry') }}</span>
            <input v-model="grantForm.expiresAt" type="datetime-local" />
          </label>
          <button class="primary-action" type="button" :disabled="grantSaving" @click="saveGrant">
            <Plus :size="16" />{{ t('documents.addGrant') }}
          </button>
        </div>
        <div v-if="grants.length" class="grant-list">
          <article v-for="grant in grants" :key="grant.id">
            <span><UsersRound :size="17" /></span>
            <div>
              <strong>{{ grant.displayName }}</strong>
              <small>{{ grant.username }} · {{ formatLegalCode(grant.permission, locale) }}</small>
            </div>
            <em :class="{ expired: !grant.active }">
              {{ grant.expiresAt ? formatDate(grant.expiresAt) : t('documents.noExpiry') }}
            </em>
            <button type="button" :aria-label="t('documents.revoke')" @click="revokeGrant(grant)">
              <Trash2 :size="15" />
            </button>
          </article>
        </div>
        <div v-else class="empty-state compact-empty">
          <UsersRound :size="28" />
          <strong>{{ t('documents.noGrants') }}</strong>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.document-intro { margin-bottom: 14px; }
.page-actions { display: flex; gap: 8px; }
.context-bar {
  display: grid;
  grid-template-columns: 160px minmax(280px, 1fr) auto auto;
  gap: 10px;
  margin-bottom: 14px;
}
.context-bar > label, .context-metric {
  min-height: 66px;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 11px 14px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: white;
  box-shadow: var(--shadow-sm);
}
.context-bar > label { display: grid; grid-template-columns: auto 1fr; }
.context-bar label span, .context-metric span { color: var(--muted); font-size: 10px; }
.context-bar select {
  min-width: 0;
  height: 36px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 0 10px;
  background: var(--surface-subtle);
  color: var(--ink);
}
.context-metric { min-width: 178px; }
.context-metric svg { color: var(--forest-2); }
.context-metric strong { margin-left: auto; color: var(--forest-2); font-size: 12px; }
.document-workspace {
  display: grid;
  grid-template-columns: minmax(270px, .34fr) minmax(0, 1fr);
  gap: 14px;
  align-items: start;
}
.document-index, .document-detail { overflow: hidden; }
.document-index > header {
  min-height: 75px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 16px;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(120deg, #fff, #f4f7f4);
}
.document-index h3 { margin: 3px 0 0; font: 700 16px "Songti SC", serif; }
.document-list { max-height: 620px; overflow-y: auto; }
.document-list button {
  width: 100%;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 11px;
  padding: 13px 15px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: white;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.document-list button:hover { background: #f8faf8; }
.document-list button.active { background: var(--forest-3); box-shadow: inset 3px 0 var(--forest-2); }
.file-symbol, .detail-icon {
  display: grid;
  place-items: center;
  color: var(--forest-2);
  background: white;
  border: 1px solid #dbe4df;
}
.file-symbol { width: 36px; height: 36px; border-radius: 9px; }
.file-copy { min-width: 0; }
.file-copy strong, .file-copy small, .file-copy em { display: block; }
.file-copy strong, .file-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.file-copy strong { font-size: 12px; }
.file-copy small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.file-copy em { margin-top: 7px; color: var(--forest-2); font-size: 9px; font-style: normal; font-weight: 650; }
.detail-heading {
  min-height: 104px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 14px;
  align-items: center;
  padding: 19px 20px;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(110deg, #fff, #f5f8f6);
}
.detail-icon { width: 48px; height: 48px; border-radius: 12px; }
.detail-heading h3 { margin: 3px 0 0; font: 700 20px "Songti SC", serif; }
.detail-heading p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.document-facts {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  border-bottom: 1px solid var(--line);
}
.document-facts span { padding: 14px 16px; border-right: 1px solid var(--line); }
.document-facts span:last-child { border-right: 0; }
.document-facts small, .document-facts strong { display: block; }
.document-facts small { color: var(--muted); font-size: 9px; }
.document-facts strong { margin-top: 5px; font-size: 11px; overflow-wrap: anywhere; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; padding: 14px 18px; border-bottom: 1px solid var(--line); }
.evidence-section { border-bottom: 1px solid var(--line); }
.evidence-section > header { display:flex; align-items:center; justify-content:space-between; gap:12px; padding:12px 18px; background:#f0f5f2; }
.evidence-section > header div { display:flex; align-items:center; gap:8px; color:var(--forest-2); }
.evidence-section > header span { color:var(--muted); font-size:9px; }
.evidence-links { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:8px; padding:12px 18px; }
.evidence-links button { display:grid; grid-template-columns:auto minmax(0,1fr) auto; gap:10px; align-items:center; padding:11px 12px; border:1px solid var(--line); border-radius:9px; background:#fff; color:var(--forest-2); text-align:left; cursor:pointer; }
.evidence-links button:hover { border-color:#91a99e; background:#f7faf8; }
.evidence-links strong,.evidence-links small { display:block; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
.evidence-links strong { font-size:10px; }.evidence-links small { margin-top:4px; color:var(--muted); font-size:9px; }
.no-evidence { display:flex; align-items:center; gap:8px; grid-column:1/-1; color:var(--muted); font-size:10px; }
.version-section > header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 14px 18px;
  background: var(--surface-subtle);
  border-bottom: 1px solid var(--line);
}
.version-section > header div { display: flex; align-items: center; gap: 8px; }
.version-section > header span { color: var(--muted); font-size: 9px; }
.version-list article {
  min-height: 76px;
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: 12px;
  align-items: center;
  padding: 13px 18px;
  border-bottom: 1px solid var(--line);
}
.version-list article:last-child { border-bottom: 0; }
.version-marker {
  min-width: 38px;
  padding: 5px 7px;
  border-radius: 7px;
  background: var(--forest-3);
  color: var(--forest-2);
  font-size: 10px;
  font-weight: 750;
  text-align: center;
}
.version-list strong, .version-list small, .version-list div > span { display: block; }
.version-list strong { font-size: 11px; }
.version-list strong em {
  margin-left: 6px;
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--brass-soft);
  color: var(--brass);
  font-size: 8px;
  font-style: normal;
}
.version-list small, .version-list div > span { margin-top: 4px; color: var(--muted); font-size: 9px; }
.version-actions { display: flex; gap: 6px; }
.version-actions button, .grant-list button {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border: 1px solid var(--line);
  border-radius: 8px;
  background: white;
  color: var(--forest-2);
  cursor: pointer;
}
.version-actions button:disabled { cursor: not-allowed; opacity: .4; }
.compact-empty { min-height: 180px; }
.grant-dialog { display: grid; gap: 14px; }
.grant-dialog > aside {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 12px;
  padding: 13px 14px;
  border-radius: 10px;
  background: var(--forest-3);
  color: var(--forest-2);
}
.grant-dialog > aside strong, .grant-dialog > aside span { display: block; }
.grant-dialog > aside span { margin-top: 3px; font-size: 10px; }
.grant-form {
  display: grid;
  grid-template-columns: 1.25fr .8fr 1fr auto;
  gap: 9px;
  align-items: end;
}
.grant-form label { display: grid; gap: 5px; }
.grant-form label span { color: var(--muted); font-size: 9px; font-weight: 650; }
.grant-form select, .grant-form input {
  width: 100%;
  height: 38px;
  border: 1px solid var(--line);
  border-radius: 8px;
  padding: 0 9px;
  background: white;
  color: var(--ink);
}
.grant-list { border: 1px solid var(--line); border-radius: 10px; overflow: hidden; }
.grant-list article {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto auto;
  gap: 10px;
  align-items: center;
  padding: 11px 13px;
  border-bottom: 1px solid var(--line);
}
.grant-list article:last-child { border-bottom: 0; }
.grant-list strong, .grant-list small { display: block; }
.grant-list strong { font-size: 11px; }
.grant-list small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.grant-list em { color: var(--forest-2); font-size: 9px; font-style: normal; }
.grant-list em.expired { color: var(--oxblood); }
.grant-list button { color: var(--oxblood); }

@media (max-width: 1000px) {
  .context-bar { grid-template-columns: 1fr 1fr; }
  .context-bar > label { grid-column: 1 / -1; }
  .document-workspace { grid-template-columns: 1fr; }
  .document-list { max-height: 320px; }
}
@media (max-width: 680px) {
  .document-intro, .page-actions { align-items: stretch; }
  .page-actions { width: 100%; }
  .page-actions button { flex: 1; }
  .context-bar { grid-template-columns: 1fr; }
  .context-bar > label { grid-column: auto; }
  .context-metric { min-width: 0; }
  .detail-heading { grid-template-columns: auto 1fr; }
  .detail-heading .status-pill { grid-column: 2; width: max-content; }
  .document-facts { grid-template-columns: repeat(2, 1fr); }
  .document-facts span:nth-child(2) { border-right: 0; }
  .document-facts span:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .detail-actions button { flex: 1 1 calc(50% - 4px); justify-content: center; }
  .evidence-section > header { align-items:flex-start; flex-direction:column; }
  .evidence-links { grid-template-columns:1fr; }
  .version-list article { grid-template-columns: auto 1fr; }
  .version-actions { grid-column: 2; }
  .grant-form { grid-template-columns: 1fr; }
  .grant-list article { grid-template-columns: auto 1fr auto; }
  .grant-list em { grid-column: 2; }
}
</style>

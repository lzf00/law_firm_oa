<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Archive,
  CheckCircle2,
  Clock3,
  ExternalLink,
  FilePlus2,
  FileText,
  FolderArchive,
  History,
  LockKeyhole,
  Plus,
  Search,
  ShieldCheck,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface ArchiveItem {
  documentId: string
  documentVersionId?: string
  logicalName: string
  documentType: string
  sequenceNumber: number
  versionNumber?: number
  filename?: string
  sha256?: string
  versionStatus?: string
  signatureStatus?: string
  contractId?: string
  contractNumber?: string
}

interface ArchiveLifecycleEvent {
  id: string
  action: string
  actorName: string
  comment?: string
  occurredAt: string
}

interface ArchiveVolume {
  id: string
  archiveNumber: string
  title: string
  matterId: string
  matterNumber?: string
  matterTitle?: string
  retentionPolicyCode: string
  status: string
  archivedAt?: string
  archivedByName?: string
  createdByName: string
  createdAt: string
  itemCount: number
  items: ArchiveItem[]
  lifecycle: ArchiveLifecycleEvent[]
}

interface ArchiveCandidate {
  documentId: string
  logicalName: string
  documentType: string
  versionNumber: number
  filename: string
  sha256: string
  signatureStatus: string
  sourceType: string
  contractId?: string
  contractNumber?: string
}

const archives = ref<ArchiveVolume[]>([])
const matters = ref<Matter[]>([])
const currentUser = ref<CurrentUser | null>(null)
const selectedId = ref('')
const candidates = ref<ArchiveCandidate[]>([])
const selectedDocumentId = ref('')
const query = ref('')
const statusFilter = ref('')
const loading = ref(true)
const saving = ref(false)
const adding = ref(false)
const closing = ref(false)
const createVisible = ref(false)
const closeVisible = ref(false)
const closeComment = ref('')
const route = useRoute()
const router = useRouter()
const { locale } = useI18n()
const form = reactive({
  archiveNumber: '',
  title: '',
  retentionPolicyCode: 'LITIGATION_10Y',
  matterId: '',
})

const can = (permission: string) =>
  currentUser.value?.permissions.includes(permission) ?? false
const selectedArchive = computed(() =>
  archives.value.find((item) => item.id === selectedId.value) ?? null)
const filteredArchives = computed(() => {
  const needle = query.value.trim().toLowerCase()
  return archives.value.filter((item) => {
    const matchesStatus = !statusFilter.value || item.status === statusFilter.value
    const matchesText = !needle || [
      item.archiveNumber, item.title, item.matterNumber ?? '', item.matterTitle ?? '',
    ].some((value) => value.toLowerCase().includes(needle))
    return matchesStatus && matchesText
  })
})
const counts = computed(() => ({
  total: archives.value.length,
  open: archives.value.filter((item) => item.status === 'OPEN').length,
  archived: archives.value.filter((item) => item.status === 'ARCHIVED').length,
  files: archives.value.reduce((sum, item) => sum + item.itemCount, 0),
}))

function formatDate(value?: string) {
  if (!value) return '—'
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(value))
}

async function load() {
  loading.value = true
  try {
    const [archiveResult, matterResult, meResult] = await Promise.all([
      http.get<ArchiveVolume[]>('/archives'),
      http.get<Matter[]>('/matters'),
      http.get<CurrentUser>('/me'),
    ])
    archives.value = archiveResult.data
    matters.value = matterResult.data
    currentUser.value = meResult.data
    if (!form.matterId) form.matterId = matters.value[0]?.id ?? ''
    const requested = String(route.query.archiveId ?? '')
    selectedId.value = archives.value.some((item) => item.id === requested)
      ? requested
      : archives.value[0]?.id ?? ''
    if (selectedId.value) await loadCandidates(selectedId.value)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('archiveOps.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function selectArchive(item: ArchiveVolume) {
  selectedId.value = item.id
  selectedDocumentId.value = ''
  await router.replace({ query: { ...route.query, archiveId: item.id } })
  await loadCandidates(item.id)
}

async function loadCandidates(archiveId = selectedId.value) {
  candidates.value = []
  if (!archiveId) return
  try {
    candidates.value = (await http.get<ArchiveCandidate[]>(
      `/archives/${archiveId}/candidates`,
    )).data
  } catch (error) {
    if (selectedArchive.value?.status === 'OPEN') {
      ElMessage.error(error instanceof Error ? error.message : t('archiveOps.candidateFailed'))
    }
  }
}

async function createArchive() {
  if (!form.archiveNumber.trim() || !form.title.trim() || !form.matterId) {
    ElMessage.warning(t('archiveOps.required'))
    return
  }
  saving.value = true
  try {
    const created = (await http.post<ArchiveVolume>('/archives', form)).data
    archives.value.unshift(created)
    createVisible.value = false
    form.archiveNumber = ''
    form.title = ''
    await selectArchive(created)
    ElMessage.success(t('archiveOps.created'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('archiveOps.createFailed'))
  } finally {
    saving.value = false
  }
}

async function addItem() {
  if (!selectedArchive.value || !selectedDocumentId.value) {
    ElMessage.warning(t('archiveOps.selectDocument'))
    return
  }
  adding.value = true
  try {
    const updated = (await http.post<ArchiveVolume>(
      `/archives/${selectedArchive.value.id}/items`,
      { documentId: selectedDocumentId.value },
    )).data
    const index = archives.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) archives.value[index] = updated
    selectedDocumentId.value = ''
    await loadCandidates(updated.id)
    ElMessage.success(t('archiveOps.added'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('archiveOps.addFailed'))
  } finally {
    adding.value = false
  }
}

async function closeArchive() {
  if (!selectedArchive.value) return
  closing.value = true
  try {
    const updated = (await http.post<ArchiveVolume>(
      `/archives/${selectedArchive.value.id}/close`,
      { comment: closeComment.value.trim() || null },
    )).data
    const index = archives.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) archives.value[index] = updated
    closeVisible.value = false
    closeComment.value = ''
    candidates.value = []
    ElMessage.success(t('archiveOps.closed'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('archiveOps.closeFailed'))
  } finally {
    closing.value = false
  }
}

function openDocument(item: ArchiveItem) {
  router.push({
    path: '/documents',
    query: { matterId: selectedArchive.value?.matterId, documentId: item.documentId },
  })
}

function openContract(item: ArchiveItem | ArchiveCandidate) {
  if (item.contractId) {
    router.push({ path: '/contracts', query: { contractId: item.contractId } })
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page archive-page">
    <header class="page-intro archive-hero">
      <div>
        <span class="eyebrow">{{ t('archiveOps.kicker') }}</span>
        <h2>{{ t('headline.archives') }}</h2>
        <p>{{ t('archiveOps.subtitle') }}</p>
      </div>
      <button v-if="can('ARCHIVE_CREATE')" class="primary-action" type="button" @click="createVisible = true">
        <Plus :size="17" />{{ t('archiveOps.new') }}
      </button>
    </header>

    <section class="archive-metrics" aria-label="Archive summary">
      <article><FolderArchive :size="19" /><span>{{ t('archiveOps.total') }}</span><strong>{{ counts.total }}</strong></article>
      <article><Clock3 :size="19" /><span>{{ t('archiveOps.open') }}</span><strong>{{ counts.open }}</strong></article>
      <article><LockKeyhole :size="19" /><span>{{ t('archiveOps.closed') }}</span><strong>{{ counts.archived }}</strong></article>
      <article><FileText :size="19" /><span>{{ t('archiveOps.files') }}</span><strong>{{ counts.files }}</strong></article>
    </section>

    <section class="archive-workspace">
      <aside class="archive-index panel">
        <header>
          <div>
            <span class="eyebrow">{{ t('archiveOps.register') }}</span>
            <h3>{{ t('archiveOps.caseArchives') }}</h3>
          </div>
          <Archive :size="21" />
        </header>
        <div class="archive-filters">
          <label><Search :size="15" /><input v-model="query" :placeholder="t('archiveOps.search')" /></label>
          <select v-model="statusFilter" :aria-label="t('archiveOps.statusFilter')">
            <option value="">{{ t('archiveOps.allStatus') }}</option>
            <option value="OPEN">{{ t('archiveOps.open') }}</option>
            <option value="ARCHIVED">{{ t('archiveOps.closed') }}</option>
          </select>
        </div>
        <div v-if="loading" class="empty-state compact-empty"><Clock3 class="spin" :size="28" /><strong>{{ t('archiveOps.loading') }}</strong></div>
        <div v-else-if="filteredArchives.length" class="archive-list">
          <button
            v-for="item in filteredArchives"
            :key="item.id"
            type="button"
            :class="{ active: selectedId === item.id }"
            @click="selectArchive(item)"
          >
            <span class="folder-mark"><FolderArchive :size="19" /></span>
            <span>
              <strong>{{ item.archiveNumber }}</strong>
              <small>{{ item.title }}</small>
              <em>{{ item.matterNumber }} · {{ item.itemCount }} {{ t('archiveOps.filesUnit') }}</em>
            </span>
            <i :class="item.status.toLowerCase()">{{ formatLegalCode(item.status, locale) }}</i>
          </button>
        </div>
        <div v-else class="empty-state compact-empty">
          <Archive :size="32" /><strong>{{ t('archiveOps.empty') }}</strong><span>{{ t('archiveOps.emptyHint') }}</span>
        </div>
      </aside>

      <article v-if="selectedArchive" class="archive-detail panel">
        <header class="detail-heading">
          <span class="detail-icon"><FolderArchive :size="25" /></span>
          <div>
            <span class="eyebrow">{{ selectedArchive.archiveNumber }}</span>
            <h3>{{ selectedArchive.title }}</h3>
            <p>{{ selectedArchive.matterNumber }} · {{ selectedArchive.matterTitle }}</p>
          </div>
          <span class="status-pill">{{ formatLegalCode(selectedArchive.status, locale) }}</span>
        </header>

        <div class="archive-facts">
          <span><small>{{ t('archiveOps.retention') }}</small><strong>{{ formatLegalCode(selectedArchive.retentionPolicyCode, locale) }}</strong></span>
          <span><small>{{ t('archiveOps.createdBy') }}</small><strong>{{ selectedArchive.createdByName }}</strong></span>
          <span><small>{{ t('archiveOps.createdAt') }}</small><strong>{{ formatDate(selectedArchive.createdAt) }}</strong></span>
          <span><small>{{ t('archiveOps.integrity') }}</small><strong><ShieldCheck :size="14" />{{ t('archiveOps.versionPinned') }}</strong></span>
        </div>

        <section v-if="selectedArchive.status === 'OPEN'" class="filing-bar">
          <div>
            <strong>{{ t('archiveOps.addTitle') }}</strong>
            <span>{{ t('archiveOps.addHint') }}</span>
          </div>
          <select v-model="selectedDocumentId" :aria-label="t('archiveOps.selectDocument')" :disabled="!can('ARCHIVE_MANAGE')">
            <option value="">{{ t('archiveOps.selectDocument') }}</option>
            <option v-for="item in candidates" :key="item.documentId" :value="item.documentId">
              {{ item.logicalName }} · V{{ item.versionNumber }}{{ item.contractNumber ? ` · ${item.contractNumber}` : '' }}
            </option>
          </select>
          <button class="primary-action" type="button" :disabled="adding || !selectedDocumentId || !can('ARCHIVE_MANAGE')" @click="addItem">
            <FilePlus2 :size="16" />{{ t('archiveOps.add') }}
          </button>
        </section>

        <section class="ledger-section">
          <header>
            <div><FileText :size="18" /><strong>{{ t('archiveOps.catalog') }}</strong></div>
            <span>{{ selectedArchive.itemCount }} {{ t('archiveOps.filesUnit') }}</span>
          </header>
          <div v-if="selectedArchive.items.length" class="archive-items">
            <article v-for="item in selectedArchive.items" :key="`${item.documentId}-${item.sequenceNumber}`">
              <span class="sequence">{{ String(item.sequenceNumber).padStart(3, '0') }}</span>
              <div>
                <strong>{{ item.logicalName }}</strong>
                <small>{{ item.filename }} · {{ formatLegalCode(item.documentType, locale) }}</small>
                <em>
                  V{{ item.versionNumber }} · SHA-256 {{ item.sha256?.slice(0, 12) }}…
                  <template v-if="item.contractNumber"> · {{ item.contractNumber }}</template>
                </em>
              </div>
              <div class="item-actions">
                <button type="button" :title="t('archiveOps.openDocument')" @click="openDocument(item)"><ExternalLink :size="15" /></button>
                <button v-if="item.contractId" type="button" :title="t('archiveOps.openContract')" @click="openContract(item)"><FileText :size="15" /></button>
              </div>
            </article>
          </div>
          <div v-else class="empty-state compact-empty"><FolderArchive :size="28" /><strong>{{ t('archiveOps.noItems') }}</strong><span>{{ t('archiveOps.noItemsHint') }}</span></div>
        </section>

        <section class="lifecycle-section">
          <header><div><History :size="18" /><strong>{{ t('archiveOps.lifecycle') }}</strong></div></header>
          <ol>
            <li v-for="event in selectedArchive.lifecycle" :key="event.id">
              <span><CheckCircle2 :size="14" /></span>
              <div><strong>{{ formatLegalCode(event.action, locale) }}</strong><small>{{ event.actorName }} · {{ formatDate(event.occurredAt) }}</small><p v-if="event.comment">{{ event.comment }}</p></div>
            </li>
          </ol>
        </section>

        <footer v-if="selectedArchive.status === 'OPEN' && can('ARCHIVE_CLOSE')" class="close-bar">
          <div><LockKeyhole :size="19" /><span><strong>{{ t('archiveOps.closeTitle') }}</strong><small>{{ t('archiveOps.closeHint') }}</small></span></div>
          <button class="secondary-action danger-action" type="button" :disabled="!selectedArchive.itemCount" @click="closeVisible = true">{{ t('archiveOps.close') }}</button>
        </footer>
      </article>

      <div v-else class="archive-detail panel empty-state">
        <FolderArchive :size="34" /><strong>{{ t('archiveOps.selectArchive') }}</strong><span>{{ t('archiveOps.selectArchiveHint') }}</span>
      </div>
    </section>

    <el-dialog v-model="createVisible" :title="t('archiveOps.createTitle')" width="min(540px, 94vw)" append-to-body>
      <div class="dialog-form">
        <label><span>{{ t('archiveOps.matter') }}</span><select v-model="form.matterId"><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <label><span>{{ t('archiveOps.number') }}</span><input v-model="form.archiveNumber" :placeholder="t('archiveOps.numberPlaceholder')" /></label>
        <label><span>{{ t('archiveOps.title') }}</span><input v-model="form.title" :placeholder="t('archiveOps.titlePlaceholder')" /></label>
        <label><span>{{ t('archiveOps.retention') }}</span><select v-model="form.retentionPolicyCode"><option value="LITIGATION_10Y">{{ t('archiveOps.retention10') }}</option><option value="PERMANENT">{{ t('archiveOps.permanent') }}</option><option value="GENERAL_5Y">{{ t('archiveOps.retention5') }}</option></select></label>
      </div>
      <template #footer><button class="secondary-action" type="button" @click="createVisible = false">{{ t('archiveOps.cancel') }}</button><button class="primary-action" type="button" :disabled="saving" @click="createArchive">{{ saving ? t('archiveOps.saving') : t('archiveOps.create') }}</button></template>
    </el-dialog>

    <el-dialog v-model="closeVisible" :title="t('archiveOps.confirmClose')" width="min(500px, 94vw)" append-to-body>
      <div class="close-dialog"><LockKeyhole :size="27" /><p>{{ t('archiveOps.closeWarning') }}</p><label><span>{{ t('archiveOps.closeComment') }}</span><textarea v-model="closeComment" :placeholder="t('archiveOps.closeCommentHint')" maxlength="500" /></label></div>
      <template #footer><button class="secondary-action" type="button" @click="closeVisible = false">{{ t('archiveOps.cancel') }}</button><button class="primary-action" type="button" :disabled="closing" @click="closeArchive">{{ closing ? t('archiveOps.closing') : t('archiveOps.confirm') }}</button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.archive-hero{margin-bottom:14px}.archive-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-bottom:14px}.archive-metrics article{display:grid;grid-template-columns:auto 1fr auto;align-items:center;gap:10px;min-height:66px;padding:12px 14px;border:1px solid var(--line);border-radius:var(--radius-md);background:#fff;box-shadow:var(--shadow-sm)}.archive-metrics svg{color:var(--forest-2)}.archive-metrics span{color:var(--muted);font-size:10px}.archive-metrics strong{font:700 20px "Songti SC",serif;color:var(--forest-2)}.archive-workspace{display:grid;grid-template-columns:minmax(290px,.34fr) minmax(0,1fr);gap:14px;align-items:start}.archive-index,.archive-detail{overflow:hidden}.archive-index>header{display:flex;align-items:center;justify-content:space-between;min-height:74px;padding:14px 16px;border-bottom:1px solid var(--line);background:linear-gradient(120deg,#fff,#f4f7f4)}.archive-index h3{margin:3px 0 0;font:700 16px "Songti SC",serif}.archive-filters{display:grid;grid-template-columns:1fr 105px;gap:7px;padding:10px;border-bottom:1px solid var(--line)}.archive-filters label{display:flex;align-items:center;gap:6px;padding:0 9px;border:1px solid var(--line);border-radius:8px}.archive-filters input,.archive-filters select{width:100%;height:35px;border:0;background:#fff;color:var(--ink);outline:0}.archive-filters select{border:1px solid var(--line);border-radius:8px;padding:0 7px}.archive-list{max-height:690px;overflow:auto}.archive-list button{width:100%;display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:10px;align-items:center;padding:13px;border:0;border-bottom:1px solid var(--line);background:#fff;color:var(--ink);text-align:left;cursor:pointer}.archive-list button:hover{background:#f8faf8}.archive-list button.active{background:var(--forest-3);box-shadow:inset 3px 0 var(--forest-2)}.folder-mark,.detail-icon{display:grid;place-items:center;border:1px solid #dbe4df;border-radius:10px;background:#fff;color:var(--forest-2)}.folder-mark{width:38px;height:38px}.archive-list strong,.archive-list small,.archive-list em{display:block}.archive-list strong{font-size:11px}.archive-list small{margin-top:3px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:var(--muted);font-size:10px}.archive-list em{margin-top:6px;color:var(--forest-2);font-size:9px;font-style:normal}.archive-list i{padding:4px 7px;border-radius:999px;background:#eef3f0;color:#466457;font-size:8px;font-style:normal}.archive-list i.archived{background:#eeeae1;color:#6d665b}.detail-heading{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:14px;align-items:center;min-height:108px;padding:19px 20px;border-bottom:1px solid var(--line);background:linear-gradient(110deg,#fff,#f4f8f6)}.detail-icon{width:50px;height:50px}.detail-heading h3{margin:3px 0 0;font:700 21px "Songti SC",serif}.detail-heading p{margin:5px 0 0;color:var(--muted);font-size:10px}.archive-facts{display:grid;grid-template-columns:repeat(4,1fr);border-bottom:1px solid var(--line)}.archive-facts>span{padding:14px 16px;border-right:1px solid var(--line)}.archive-facts>span:last-child{border-right:0}.archive-facts small,.archive-facts strong{display:block}.archive-facts small{color:var(--muted);font-size:9px}.archive-facts strong{display:flex;align-items:center;gap:5px;margin-top:5px;font-size:10px}.filing-bar{display:grid;grid-template-columns:minmax(170px,.7fr) minmax(220px,1.3fr) auto;gap:12px;align-items:center;padding:15px 18px;border-bottom:1px solid var(--line);background:#fbfcfa}.filing-bar strong,.filing-bar span{display:block}.filing-bar strong{font-size:11px}.filing-bar span{margin-top:3px;color:var(--muted);font-size:9px}.filing-bar select{min-width:0;height:40px;padding:0 10px;border:1px solid var(--line);border-radius:8px;background:#fff}.ledger-section>header,.lifecycle-section>header{display:flex;justify-content:space-between;align-items:center;padding:14px 18px;border-bottom:1px solid var(--line);background:var(--surface-subtle)}.ledger-section>header div,.lifecycle-section>header div{display:flex;align-items:center;gap:8px}.ledger-section>header span{color:var(--muted);font-size:9px}.archive-items article{display:grid;grid-template-columns:auto minmax(0,1fr) auto;gap:12px;align-items:center;min-height:76px;padding:13px 18px;border-bottom:1px solid var(--line)}.sequence{min-width:40px;padding:6px;border-radius:7px;background:var(--forest-3);color:var(--forest-2);font:700 10px monospace;text-align:center}.archive-items strong,.archive-items small,.archive-items em{display:block}.archive-items strong{font-size:11px}.archive-items small{margin-top:4px;color:var(--muted);font-size:9px}.archive-items em{margin-top:5px;color:var(--forest-2);font-size:9px;font-style:normal}.item-actions{display:flex;gap:6px}.item-actions button{display:grid;place-items:center;width:32px;height:32px;border:1px solid var(--line);border-radius:8px;background:#fff;color:var(--forest-2);cursor:pointer}.lifecycle-section ol{margin:0;padding:14px 18px;list-style:none}.lifecycle-section li{display:grid;grid-template-columns:auto 1fr;gap:10px;position:relative;padding:0 0 15px}.lifecycle-section li:last-child{padding-bottom:0}.lifecycle-section li>span{color:var(--forest-2)}.lifecycle-section strong,.lifecycle-section small{display:block}.lifecycle-section strong{font-size:10px}.lifecycle-section small,.lifecycle-section p{margin:3px 0 0;color:var(--muted);font-size:9px}.close-bar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:15px 18px;border-top:1px solid var(--line);background:#f5f3ee}.close-bar>div{display:flex;align-items:center;gap:10px;color:var(--forest-2)}.close-bar strong,.close-bar small{display:block}.close-bar strong{font-size:11px}.close-bar small{margin-top:3px;color:var(--muted);font-size:9px}.danger-action{color:var(--oxblood)}.compact-empty{min-height:170px}.close-dialog{display:grid;justify-items:center;gap:12px;color:var(--forest-2);text-align:center}.close-dialog p{margin:0;color:var(--muted);font-size:11px;line-height:1.7}.close-dialog label{display:grid;gap:6px;width:100%;text-align:left}.close-dialog label span{font-size:10px;font-weight:650}.close-dialog textarea{min-height:90px;padding:10px;border:1px solid var(--line);border-radius:8px;resize:vertical}
@media(max-width:1100px){.archive-workspace{grid-template-columns:1fr}.archive-list{max-height:360px}}
@media(max-width:760px){.archive-metrics{grid-template-columns:1fr 1fr}.archive-workspace{grid-template-columns:minmax(0,1fr)}.archive-facts{grid-template-columns:1fr 1fr}.archive-facts>span:nth-child(2){border-right:0}.filing-bar{grid-template-columns:1fr}.detail-heading{grid-template-columns:auto minmax(0,1fr)}.detail-heading>.status-pill{grid-column:2}.close-bar{align-items:flex-start;flex-direction:column}.close-bar button{width:100%}}
</style>

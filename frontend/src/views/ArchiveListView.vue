<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Archive, FilePlus2, FolderArchive, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface ArchiveItem {
  documentId: string
  logicalName: string
  documentType: string
  sequenceNumber: number
}

interface ArchiveVolume {
  id: string
  archiveNumber: string
  title: string
  matterId?: string
  retentionPolicyCode: string
  status: string
  archivedAt?: string
  createdByName: string
  createdAt: string
  itemCount: number
  items: ArchiveItem[]
}

interface DocumentItem {
  id: string
  logicalName: string
  documentType: string
}

const archives = ref<ArchiveVolume[]>([])
const matters = ref<Matter[]>([])
const dialogVisible = ref(false)
const manageVisible = ref(false)
const saving = ref(false)
const adding = ref(false)
const selectedArchive = ref<ArchiveVolume | null>(null)
const availableDocuments = ref<DocumentItem[]>([])
const selectedDocumentId = ref('')
const { locale } = useI18n()
const form = reactive({
  archiveNumber: '',
  title: '',
  retentionPolicyCode: 'LITIGATION_10Y',
  matterId: '',
})

const matterById = computed(() => new Map(matters.value.map((item) => [item.id, item])))
const unfiledDocuments = computed(() => {
  const filed = new Set(selectedArchive.value?.items.map((item) => item.documentId) ?? [])
  return availableDocuments.value.filter((item) => !filed.has(item.id))
})

async function load() {
  try {
    const [archiveResult, matterResult] = await Promise.all([
      http.get<ArchiveVolume[]>('/archives'),
      http.get<Matter[]>('/matters'),
    ])
    archives.value = archiveResult.data
    matters.value = matterResult.data
    if (!form.matterId) form.matterId = matters.value[0]?.id ?? ''
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0055'))
  }
}

async function createArchive() {
  if (!form.archiveNumber.trim() || !form.title.trim() || !form.matterId) {
    ElMessage.warning(t('copy.0056'))
    return
  }
  saving.value = true
  try {
    const created = (await http.post<ArchiveVolume>('/archives', form)).data
    archives.value.unshift(created)
    dialogVisible.value = false
    form.archiveNumber = ''
    form.title = ''
    ElMessage.success(t('copy.0057'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0058'))
  } finally {
    saving.value = false
  }
}

async function openArchive(item: ArchiveVolume) {
  selectedArchive.value = item
  selectedDocumentId.value = ''
  availableDocuments.value = []
  if (item.matterId) {
    try {
      availableDocuments.value = (await http.get<DocumentItem[]>('/documents', {
        params: { matterId: item.matterId },
      })).data
    } catch (error) {
      ElMessage.error(error instanceof Error ? error.message : t('copy.0059'))
    }
  }
  manageVisible.value = true
}

async function addItem() {
  if (!selectedArchive.value || !selectedDocumentId.value) {
    ElMessage.warning(t('copy.0060'))
    return
  }
  adding.value = true
  try {
    const updated = (await http.post<ArchiveVolume>(
      `/archives/${selectedArchive.value.id}/items`,
      { documentId: selectedDocumentId.value },
    )).data
    selectedArchive.value = updated
    const index = archives.value.findIndex((item) => item.id === updated.id)
    if (index >= 0) archives.value[index] = updated
    selectedDocumentId.value = ''
    ElMessage.success(t('copy.0061'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0062'))
  } finally {
    adding.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">ELECTRONIC ARCHIVE</span>
        <h2>{{ t('headline.archives') }}</h2>
        <p>{{ t('copy.0063') }}</p>
      </div>
      <button class="primary-action" @click="dialogVisible = true"><Plus :size="17" /> {{ t('copy.0064') }}</button>
    </div>

    <div v-if="archives.length" class="archive-grid">
      <article
        v-for="item in archives"
        :key="item.id"
        class="archive-card interactive-archive"
        tabindex="0"
        @click="openArchive(item)"
        @keydown.enter="openArchive(item)"
      >
        <div class="archive-tab"><FolderArchive :size="21" /></div>
        <span class="mono">{{ item.archiveNumber }}</span>
        <h3>{{ item.title }}</h3>
        <p>
          {{ matterById.get(item.matterId || '')?.matterNumber || t('copy.0065') }}
          · {{ item.retentionPolicyCode }} · {{ item.createdByName }}
        </p>
        <div class="archive-meta">
          <span>{{ item.itemCount }} {{ t('copy.0066') }}</span>
          <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
        </div>
      </article>
    </div>
    <div v-else class="panel empty-state">
      <Archive :size="34" />
      <strong>{{ t('copy.0067') }}</strong>
      <span>{{ t('copy.0068') }}</span>
    </div>

    <el-dialog
      v-model="dialogVisible"
      :title="t('copy.0069')"
      width="min(520px, 92vw)"
    >
      <div class="dialog-form">
        <label>
          <span>{{ t('copy.0070') }}</span>
          <select v-model="form.matterId" data-testid="archive-matter">
            <option v-for="matter in matters" :key="matter.id" :value="matter.id">
              {{ matter.matterNumber }} · {{ matter.title }}
            </option>
          </select>
        </label>
        <label><span>{{ t('copy.0071') }}</span><input v-model="form.archiveNumber" :placeholder="t('copy.0072')" /></label>
        <label><span>{{ t('copy.0073') }}</span><input v-model="form.title" :placeholder="t('copy.0074')" /></label>
        <label>
          <span>{{ t('copy.0075') }}</span>
          <select v-model="form.retentionPolicyCode">
            <option value="LITIGATION_10Y">{{ t('copy.0076') }}</option>
            <option value="PERMANENT">{{ t('copy.0077') }}</option>
            <option value="GENERAL_5Y">{{ t('copy.0078') }}</option>
          </select>
        </label>
      </div>
      <template #footer>
        <button class="secondary-action" @click="dialogVisible = false">{{ t('copy.0079') }}</button>
        <button class="primary-action" :disabled="saving" @click="createArchive">
          {{ saving ? t('copy.0080') : t('copy.0081') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="manageVisible"
      :title="selectedArchive ? `${selectedArchive.archiveNumber} · ${selectedArchive.title}` : ''"
      width="min(680px, 94vw)"
    >
      <div v-if="selectedArchive" class="archive-manager">
        <div class="archive-manager-meta">
          <span>{{ matterById.get(selectedArchive.matterId || '')?.title || t('copy.0065') }}</span>
          <strong>{{ formatLegalCode(selectedArchive.retentionPolicyCode, locale) }} · {{ formatLegalCode(selectedArchive.status, locale) }}</strong>
        </div>
        <div class="add-document">
          <select v-model="selectedDocumentId" :disabled="selectedArchive.status !== 'OPEN'">
            <option value="">{{ t('copy.0082') }}</option>
            <option v-for="document in unfiledDocuments" :key="document.id" :value="document.id">
              {{ document.logicalName }} · {{ formatLegalCode(document.documentType, locale) }}
            </option>
          </select>
          <button class="primary-action" :disabled="adding || !selectedDocumentId" @click="addItem">
            <FilePlus2 :size="16" /> {{ t('copy.0083') }}
          </button>
        </div>
        <ol v-if="selectedArchive.items.length" class="archive-item-list">
          <li v-for="item in selectedArchive.items" :key="item.documentId">
            <span class="mono">{{ String(item.sequenceNumber).padStart(3, '0') }}</span>
            <div><strong>{{ item.logicalName }}</strong><small>{{ formatLegalCode(item.documentType, locale) }}</small></div>
          </li>
        </ol>
        <div v-else class="empty-state compact-empty">
          <FolderArchive :size="28" />
          <span>{{ t('copy.0084') }}</span>
        </div>
      </div>
    </el-dialog>
  </section>
</template>

<style scoped>
.interactive-archive { cursor: pointer; transition: transform .16s ease, box-shadow .16s ease; }
.interactive-archive:hover, .interactive-archive:focus-visible { transform: translateY(-2px); box-shadow: 0 10px 24px rgba(20,35,30,.08); outline: 0; }
.archive-manager-meta { display: flex; justify-content: space-between; gap: 16px; padding: 13px 15px; background: #eeeae1; color: var(--muted); font-size: 10px; }
.archive-manager-meta strong { color: var(--ink); }
.add-document { display: grid; grid-template-columns: 1fr auto; gap: 10px; margin: 18px 0; }
.add-document select { min-width: 0; height: 43px; padding: 0 11px; border: 1px solid var(--line); background: white; color: var(--ink); }
.archive-item-list { margin: 0; padding: 0; list-style: none; border: 1px solid var(--line); }
.archive-item-list li { min-height: 62px; display: grid; grid-template-columns: 50px 1fr; align-items: center; gap: 12px; padding: 11px 15px; border-bottom: 1px solid var(--line); }
.archive-item-list li:last-child { border-bottom: 0; }
.archive-item-list strong, .archive-item-list small { display: block; }
.archive-item-list strong { font-size: 12px; }
.archive-item-list small { margin-top: 5px; color: var(--muted); font-size: 9px; }
.compact-empty { min-height: 120px; border: 1px solid var(--line); }
@media (max-width: 560px) {
  .archive-manager-meta { flex-direction: column; }
  .add-document { grid-template-columns: 1fr; }
}
</style>

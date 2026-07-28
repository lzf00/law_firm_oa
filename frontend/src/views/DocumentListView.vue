<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, FileLock2, FileUp, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
interface DocumentItem {
  id: string
  matterId?: string
  logicalName: string
  documentType: string
  confidentialityLevel: string
  currentVersionId: string
  versionNumber: number
  versionStatus: string
  originalFilename: string
  sizeBytes: number
  createdAt: string
  ingestionStatus: string
  scanFailureReason?: string
  scanCompletedAt?: string
}

const matters = ref<Matter[]>([])
const selectedMatter = ref('')
const documents = ref<DocumentItem[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const selectedMatterName = computed(() =>
  matters.value.find((item) => item.id === selectedMatter.value)?.title ?? t('copy.0136'),
)

onMounted(async () => {
  matters.value = (await http.get<Matter[]>('/matters')).data
  selectedMatter.value = matters.value[0]?.id ?? ''
})

watch(selectedMatter, async (matterId) => {
  if (!matterId) {
    documents.value = []
    return
  }
  try {
    documents.value = (await http.get<DocumentItem[]>('/documents', { params: { matterId } })).data
  } catch (error) {
    documents.value = []
    ElMessage.error(error instanceof Error ? error.message : t('copy.0137'))
  }
})

function humanSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

async function sha256(file: File) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer())
  return [...new Uint8Array(digest)].map((value) => value.toString(16).padStart(2, '0')).join('')
}

async function upload(event: Event) {
  const file = (event.target as HTMLInputElement).files?.[0]
  if (!file || !selectedMatter.value) return
  uploading.value = true
  try {
    const hash = await sha256(file)
    const ticket = (await http.post('/documents/uploads', {
      matterId: selectedMatter.value,
      logicalName: file.name.replace(/\.[^.]+$/, ''),
      documentType: 'CASE_FILE',
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
    const completed = (await http.post<DocumentItem>(`/documents/uploads/${ticket.uploadId}/complete`)).data
    documents.value.unshift(completed)
    ElMessage.success(t('copy.0139'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0140'))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function download(item: DocumentItem) {
  if (item.ingestionStatus !== 'AVAILABLE') {
    ElMessage.warning(t('copy.0141'))
    return
  }
  try {
    const ticket = (await http.post(`/documents/${item.id}/versions/${item.currentVersionId}/download-url`)).data
    window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0142'))
  }
}
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">PRIVATE DOCUMENT VAULT</span>
        <h2>{{ t('headline.documents') }}</h2>
        <p data-allow-business-data>{{ tp('copy.dynamic.currentMatter', { matter: selectedMatterName }) }}</p>
      </div>
      <label class="primary-action upload-button" :class="{ disabled: !selectedMatter || uploading }">
        <FileUp :size="17" /> {{ uploading ? t('copy.0143') : t('copy.0144') }}
        <input ref="fileInput" type="file" :disabled="!selectedMatter || uploading" @change="upload" />
      </label>
    </div>

    <div class="document-context">
      <label>
        <span>{{ t('copy.0145') }}</span>
        <select v-model="selectedMatter">
          <option v-for="matter in matters" :key="matter.id" :value="matter.id">
            {{ matter.matterNumber }} · {{ matter.title }}
          </option>
        </select>
      </label>
      <div><ShieldCheck :size="18" /><span>{{ t('copy.0146') }}</span><strong>{{ t('copy.0147') }}</strong></div>
      <div><FileLock2 :size="18" /><span>{{ t('copy.0148') }}</span><strong>{{ t('copy.0149') }}</strong></div>
    </div>

    <div class="panel table-panel" tabindex="0">
      <table v-if="documents.length">
        <thead><tr><th>{{ t('copy.0150') }}</th><th>{{ t('copy.0151') }}</th><th>{{ t('copy.0152') }}</th><th>{{ t('copy.0153') }}</th><th>{{ t('copy.0154') }}</th><th>{{ t('copy.0155') }}</th><th></th></tr></thead>
        <tbody>
          <tr v-for="item in documents" :key="item.id">
            <td><strong>{{ item.logicalName }}</strong><small>{{ item.originalFilename }}</small></td>
            <td>{{ formatLegalCode(item.documentType, locale) }}</td>
            <td>
              <span class="status-pill">V{{ item.versionNumber }} · {{ formatLegalCode(item.ingestionStatus, locale) }}</span>
              <small v-if="item.scanFailureReason">{{ item.scanFailureReason }}</small>
            </td>
            <td>{{ formatLegalCode(item.confidentialityLevel, locale) }}</td>
            <td>{{ humanSize(item.sizeBytes) }}</td>
            <td>{{ new Date(item.createdAt).toLocaleString(locale) }}</td>
            <td><button class="table-action" :disabled="item.ingestionStatus !== 'AVAILABLE'" :aria-label="t('copy.0156')" @click="download(item)"><Download :size="16" /></button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        <FileLock2 :size="34" />
        <strong>{{ t('copy.0157') }}</strong>
        <span>{{ t('copy.0158') }}</span>
      </div>
    </div>
  </section>
</template>

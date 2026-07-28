<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, FileLock2, FileUp, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
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
}

const matters = ref<Matter[]>([])
const selectedMatter = ref('')
const documents = ref<DocumentItem[]>([])
const uploading = ref(false)
const fileInput = ref<HTMLInputElement | null>(null)

const selectedMatterName = computed(() =>
  matters.value.find((item) => item.id === selectedMatter.value)?.title ?? text('请选择案件', 'Select a matter'),
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
    ElMessage.error(error instanceof Error ? error.message : text('文档加载失败', 'Failed to load documents'))
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
    if (!uploadResponse.ok) throw new Error(text('对象存储上传失败', 'Object storage upload failed'))
    const completed = (await http.post<DocumentItem>(`/documents/uploads/${ticket.uploadId}/complete`)).data
    documents.value.unshift(completed)
    ElMessage.success(text('文件已安全入库并创建第 1 个版本', 'File secured and version 1 created'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('上传失败', 'Upload failed'))
  } finally {
    uploading.value = false
    if (fileInput.value) fileInput.value.value = ''
  }
}

async function download(item: DocumentItem) {
  try {
    const ticket = (await http.post(`/documents/${item.id}/versions/${item.currentVersionId}/download-url`)).data
    window.open(ticket.downloadUrl, '_blank', 'noopener,noreferrer')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('无法取得下载授权', 'Could not obtain download authorization'))
  }
}
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">PRIVATE DOCUMENT VAULT</span>
        <h2>{{ t('headline.documents') }}</h2>
        <p data-allow-business-data>{{ text(`当前案件：${selectedMatterName}。上传不经过应用服务器，下载链接 5 分钟失效。`, `Current matter: ${selectedMatterName}. Uploads bypass the application server and download links expire after five minutes.`) }}</p>
      </div>
      <label class="primary-action upload-button" :class="{ disabled: !selectedMatter || uploading }">
        <FileUp :size="17" /> {{ uploading ? text('正在校验并上传…', 'Validating and uploading…') : text('上传案件文件', 'Upload matter file') }}
        <input ref="fileInput" type="file" :disabled="!selectedMatter || uploading" @change="upload" />
      </label>
    </div>

    <div class="document-context">
      <label>
        <span>{{ text('文件归属案件', 'Matter') }}</span>
        <select v-model="selectedMatter">
          <option v-for="matter in matters" :key="matter.id" :value="matter.id">
            {{ matter.matterNumber }} · {{ matter.title }}
          </option>
        </select>
      </label>
      <div><ShieldCheck :size="18" /><span>{{ text('私有桶', 'Private bucket') }}</span><strong>{{ text('服务端授权', 'Server authorized') }}</strong></div>
      <div><FileLock2 :size="18" /><span>{{ text('下载审计', 'Download audit') }}</span><strong>{{ text('已开启', 'Enabled') }}</strong></div>
    </div>

    <div class="panel table-panel">
      <table v-if="documents.length">
        <thead><tr><th>{{ text('文档', 'Document') }}</th><th>{{ text('类型', 'Type') }}</th><th>{{ text('版本', 'Version') }}</th><th>{{ text('密级', 'Confidentiality') }}</th><th>{{ text('大小', 'Size') }}</th><th>{{ text('入库时间', 'Added') }}</th><th></th></tr></thead>
        <tbody>
          <tr v-for="item in documents" :key="item.id">
            <td><strong>{{ item.logicalName }}</strong><small>{{ item.originalFilename }}</small></td>
            <td>{{ formatLegalCode(item.documentType, locale) }}</td>
            <td><span class="status-pill">V{{ item.versionNumber }} · {{ formatLegalCode(item.versionStatus, locale) }}</span></td>
            <td>{{ formatLegalCode(item.confidentialityLevel, locale) }}</td>
            <td>{{ humanSize(item.sizeBytes) }}</td>
            <td>{{ new Date(item.createdAt).toLocaleString(locale) }}</td>
            <td><button class="table-action" :aria-label="text('下载', 'Download')" @click="download(item)"><Download :size="16" /></button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        <FileLock2 :size="34" />
        <strong>{{ text('该案件尚无文件', 'No files in this matter') }}</strong>
        <span>{{ text('支持 PDF、Word、Excel、图片与 ZIP，单文件最大 200MB。', 'Supports PDF, Word, Excel, images and ZIP files up to 200 MB.') }}</span>
      </div>
    </div>
  </section>
</template>

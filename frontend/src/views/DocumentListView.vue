<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Download, FileLock2, FileUp, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter } from '@/api/types'
import { translate as t } from '@/i18n'

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
  matters.value.find((item) => item.id === selectedMatter.value)?.title ?? '请选择案件',
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
    ElMessage.error(error instanceof Error ? error.message : '文档加载失败')
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
    if (!uploadResponse.ok) throw new Error('对象存储上传失败')
    const completed = (await http.post<DocumentItem>(`/documents/uploads/${ticket.uploadId}/complete`)).data
    documents.value.unshift(completed)
    ElMessage.success('文件已安全入库并创建第 1 个版本')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '上传失败')
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
    ElMessage.error(error instanceof Error ? error.message : '无法取得下载授权')
  }
}
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">PRIVATE DOCUMENT VAULT</span>
        <h2>{{ t('headline.documents') }}</h2>
        <p>当前案件：{{ selectedMatterName }}。上传不经过应用服务器，下载链接 5 分钟失效。</p>
      </div>
      <label class="primary-action upload-button" :class="{ disabled: !selectedMatter || uploading }">
        <FileUp :size="17" /> {{ uploading ? '正在校验并上传…' : '上传案件文件' }}
        <input ref="fileInput" type="file" :disabled="!selectedMatter || uploading" @change="upload" />
      </label>
    </div>

    <div class="document-context">
      <label>
        <span>文件归属案件</span>
        <select v-model="selectedMatter">
          <option v-for="matter in matters" :key="matter.id" :value="matter.id">
            {{ matter.matterNumber }} · {{ matter.title }}
          </option>
        </select>
      </label>
      <div><ShieldCheck :size="18" /><span>私有桶</span><strong>服务端授权</strong></div>
      <div><FileLock2 :size="18" /><span>下载审计</span><strong>已开启</strong></div>
    </div>

    <div class="panel table-panel">
      <table v-if="documents.length">
        <thead><tr><th>文档</th><th>类型</th><th>版本</th><th>密级</th><th>大小</th><th>入库时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="item in documents" :key="item.id">
            <td><strong>{{ item.logicalName }}</strong><small>{{ item.originalFilename }}</small></td>
            <td>{{ item.documentType }}</td>
            <td><span class="status-pill">V{{ item.versionNumber }} · {{ item.versionStatus }}</span></td>
            <td>{{ item.confidentialityLevel }}</td>
            <td>{{ humanSize(item.sizeBytes) }}</td>
            <td>{{ new Date(item.createdAt).toLocaleString('zh-CN') }}</td>
            <td><button class="table-action" aria-label="下载" @click="download(item)"><Download :size="16" /></button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        <FileLock2 :size="34" />
        <strong>该案件尚无文件</strong>
        <span>支持 PDF、Word、Excel、图片与 ZIP，单文件最大 200MB。</span>
      </div>
    </div>
  </section>
</template>

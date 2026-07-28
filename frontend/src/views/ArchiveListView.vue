<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Archive, FolderArchive, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

interface ArchiveVolume {
  id: string
  archiveNumber: string
  title: string
  retentionPolicyCode: string
  status: string
  archivedAt?: string
  createdByName: string
  createdAt: string
  itemCount: number
}

const archives = ref<ArchiveVolume[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  archiveNumber: '',
  title: '',
  retentionPolicyCode: 'LITIGATION_10Y',
})

async function load() {
  try {
    archives.value = (await http.get<ArchiveVolume[]>('/archives')).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '卷宗加载失败')
  }
}

async function createArchive() {
  if (!form.archiveNumber.trim() || !form.title.trim()) {
    ElMessage.warning('请填写卷宗编号与名称')
    return
  }
  saving.value = true
  try {
    const created = (await http.post<ArchiveVolume>('/archives', form)).data
    archives.value.unshift(created)
    dialogVisible.value = false
    form.archiveNumber = ''
    form.title = ''
    ElMessage.success('电子卷宗已建立')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '创建失败')
  } finally {
    saving.value = false
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
        <p>卷内文件引用不可变版本；保管期限、冻结与调阅都独立留痕。</p>
      </div>
      <button class="primary-action" @click="dialogVisible = true"><Plus :size="17" /> 新建卷宗</button>
    </div>

    <div v-if="archives.length" class="archive-grid">
      <article v-for="item in archives" :key="item.id" class="archive-card">
        <div class="archive-tab"><FolderArchive :size="21" /></div>
        <span class="mono">{{ item.archiveNumber }}</span>
        <h3>{{ item.title }}</h3>
        <p>{{ item.retentionPolicyCode }} · 建卷人 {{ item.createdByName }}</p>
        <div class="archive-meta">
          <span>{{ item.itemCount }} 份文件</span>
          <span class="status-pill">{{ item.status }}</span>
        </div>
      </article>
    </div>
    <div v-else class="panel empty-state">
      <Archive :size="34" />
      <strong>尚未建立电子卷宗</strong>
      <span>案件结项前即可预建目录，归档后转为只读。</span>
    </div>

    <el-dialog v-model="dialogVisible" title="新建电子卷宗" width="480px">
      <div class="dialog-form">
        <label><span>卷宗编号</span><input v-model="form.archiveNumber" placeholder="如 AJ-2026-008" /></label>
        <label><span>卷宗名称</span><input v-model="form.title" placeholder="案件或专项名称" /></label>
        <label>
          <span>保管策略</span>
          <select v-model="form.retentionPolicyCode">
            <option value="LITIGATION_10Y">诉讼卷宗 · 10年</option>
            <option value="PERMANENT">永久保管</option>
            <option value="GENERAL_5Y">一般业务 · 5年</option>
          </select>
        </label>
      </div>
      <template #footer>
        <button class="secondary-action" @click="dialogVisible = false">取消</button>
        <button class="primary-action" :disabled="saving" @click="createArchive">
          {{ saving ? '正在建立…' : '确认建卷' }}
        </button>
      </template>
    </el-dialog>
  </section>
</template>

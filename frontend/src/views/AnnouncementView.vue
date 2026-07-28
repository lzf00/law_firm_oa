<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCheck, Megaphone, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'
import { manageableOffices } from '@/officeScope'
import { translate as t } from '@/i18n'

interface Announcement {
  id: string; title: string; summary?: string; content: string; category: string
  priority: string; status: string; publisherName: string; publishedAt?: string
  readCount: number; read: boolean; officeId?: string; officeNameZh?: string; officeNameEn?: string
}

const items = ref<Announcement[]>([])
const currentUser = ref<CurrentUser | null>(null)
const dialog = ref(false)
const busy = ref(false)
const form = reactive({ title: '', summary: '', content: '', priority: 'NORMAL', officeId: '' })
const publishableOffices = computed(() => currentUser.value
  ? manageableOffices(currentUser.value)
  : [])
const unread = computed(() => items.value.filter((item) => item.status === 'PUBLISHED' && !item.read).length)

async function load() {
  const [announcementResult, meResult] = await Promise.all([
    http.get<Announcement[]>('/announcements'),
    http.get<CurrentUser>('/me'),
  ])
  items.value = announcementResult.data
  currentUser.value = meResult.data
  if (!currentUser.value.globalOfficeAccess && !form.officeId) {
    form.officeId = publishableOffices.value[0]?.id ?? ''
  }
}

async function create() {
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('请填写公告标题和正文')
  busy.value = true
  try {
    const created = (await http.post<Announcement>('/announcements', {
      ...form,
      officeId: form.officeId || null,
      category: 'NOTICE',
      audienceType: 'ALL',
    })).data
    await http.post(`/announcements/${created.id}/publish`)
    dialog.value = false
    Object.assign(form, {
      title: '',
      summary: '',
      content: '',
      priority: 'NORMAL',
      officeId: currentUser.value?.globalOfficeAccess
        ? ''
        : publishableOffices.value[0]?.id ?? '',
    })
    await load()
    ElMessage.success('公告已发布，并进入站内通知队列')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '公告发布失败')
  } finally {
    busy.value = false
  }
}

async function markRead(item: Announcement) {
  if (item.read || item.status !== 'PUBLISHED') return
  await http.post(`/announcements/${item.id}/read`)
  await load()
}

onMounted(() => load().catch(() => ElMessage.error('公告加载失败')))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">FIRM BULLETIN · {{ unread }} UNREAD</span>
        <h2>{{ t('headline.announcements') }}</h2>
        <p>支持草稿、发布、优先级、范围控制与已读回执，发布后自动生成站内通知。</p>
      </div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> 发布公告</button>
    </div>

    <div class="announcement-grid">
      <article
        v-for="item in items"
        :key="item.id"
        class="announcement-card"
        :class="{ unread: !item.read && item.status === 'PUBLISHED' }"
        @click="markRead(item)"
      >
        <div class="announcement-top">
          <span class="status-pill">{{ item.priority }}</span>
          <span>{{ item.officeNameZh || '全所' }} · {{ item.status }}</span>
        </div>
        <Megaphone :size="23" />
        <h3>{{ item.title }}</h3>
        <p>{{ item.summary || item.content }}</p>
        <div class="announcement-foot">
          <span>{{ item.publisherName }} · {{ item.publishedAt ? new Date(item.publishedAt).toLocaleDateString('zh-CN') : '草稿' }}</span>
          <span><CheckCheck :size="14" /> {{ item.readCount }} 已读</span>
        </div>
      </article>
      <div v-if="!items.length" class="panel empty-state">暂无公告</div>
    </div>

    <el-dialog v-model="dialog" title="发布公告" width="560px">
      <div class="dialog-form">
        <label><span>标题</span><input v-model="form.title" placeholder="公告标题" /></label>
        <label><span>摘要</span><input v-model="form.summary" placeholder="一句话说明重点" /></label>
        <label><span>优先级</span><select v-model="form.priority"><option value="NORMAL">普通</option><option value="IMPORTANT">重要</option><option value="URGENT">紧急</option></select></label>
        <label>
          <span>发布范围</span>
          <select v-model="form.officeId">
            <option v-if="currentUser?.globalOfficeAccess" value="">全所</option>
            <option
              v-for="office in publishableOffices"
              :key="office.id"
              :value="office.id"
            >
              {{ office.nameZh }}
            </option>
          </select>
        </label>
        <label><span>正文</span><textarea v-model="form.content" rows="7" placeholder="填写公告正文"></textarea></label>
      </div>
      <template #footer><button class="primary-action" :disabled="busy" @click="create"><Send :size="16" /> {{ busy ? '发布中' : '确认发布' }}</button></template>
    </el-dialog>
  </section>
</template>

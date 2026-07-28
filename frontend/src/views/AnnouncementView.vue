<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { CheckCheck, Megaphone, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'
import { manageableOffices } from '@/officeScope'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
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
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning(text('请填写公告标题和正文', 'Enter an announcement title and body'))
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
    ElMessage.success(text('公告已发布，并进入站内通知队列', 'Announcement published and queued for in-app delivery'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : text('公告发布失败', 'Failed to publish announcement'))
  } finally {
    busy.value = false
  }
}

async function markRead(item: Announcement) {
  if (item.read || item.status !== 'PUBLISHED') return
  await http.post(`/announcements/${item.id}/read`)
  await load()
}

onMounted(() => load().catch(() => ElMessage.error(text('公告加载失败', 'Failed to load announcements'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">FIRM BULLETIN · {{ unread }} UNREAD</span>
        <h2>{{ t('headline.announcements') }}</h2>
        <p>{{ text('支持草稿、发布、优先级、范围控制与已读回执，发布后自动生成站内通知。', 'Draft, publish, prioritize and scope announcements with read receipts and automatic in-app delivery.') }}</p>
      </div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ text('发布公告', 'New announcement') }}</button>
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
          <span class="status-pill">{{ formatLegalCode(item.priority, locale) }}</span>
          <span>{{ (locale === 'en-US' ? item.officeNameEn : item.officeNameZh) || text('全所', 'Firm-wide') }} · {{ formatLegalCode(item.status, locale) }}</span>
        </div>
        <Megaphone :size="23" />
        <h3>{{ item.title }}</h3>
        <p>{{ item.summary || item.content }}</p>
        <div class="announcement-foot">
          <span>{{ item.publisherName }} · {{ item.publishedAt ? new Date(item.publishedAt).toLocaleDateString(locale) : formatLegalCode('DRAFT', locale) }}</span>
          <span><CheckCheck :size="14" /> {{ item.readCount }} {{ text('已读', 'read') }}</span>
        </div>
      </article>
      <div v-if="!items.length" class="panel empty-state">{{ text('暂无公告', 'No announcements') }}</div>
    </div>

    <el-dialog v-model="dialog" :title="text('发布公告', 'Publish announcement')" width="560px">
      <div class="dialog-form">
        <label><span>{{ text('标题', 'Title') }}</span><input v-model="form.title" :placeholder="text('公告标题', 'Announcement title')" /></label>
        <label><span>{{ text('摘要', 'Summary') }}</span><input v-model="form.summary" :placeholder="text('一句话说明重点', 'Summarize the key point')" /></label>
        <label><span>{{ text('优先级', 'Priority') }}</span><select v-model="form.priority"><option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option><option value="IMPORTANT">{{ formatLegalCode('IMPORTANT', locale) }}</option><option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option></select></label>
        <label>
          <span>{{ text('发布范围', 'Audience') }}</span>
          <select v-model="form.officeId">
            <option v-if="currentUser?.globalOfficeAccess" value="">{{ text('全所', 'Firm-wide') }}</option>
            <option
              v-for="office in publishableOffices"
              :key="office.id"
              :value="office.id"
            >
              {{ locale === 'en-US' ? office.nameEn : office.nameZh }}
            </option>
          </select>
        </label>
        <label><span>{{ text('正文', 'Body') }}</span><textarea v-model="form.content" rows="7" :placeholder="text('填写公告正文', 'Enter announcement body')"></textarea></label>
      </div>
      <template #footer><button class="primary-action" :disabled="busy" @click="create"><Send :size="16" /> {{ busy ? text('发布中', 'Publishing…') : text('确认发布', 'Publish') }}</button></template>
    </el-dialog>
  </section>
</template>

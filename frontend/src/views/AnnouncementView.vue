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
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning(t('copy.0001'))
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
    ElMessage.success(t('copy.0002'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0003'))
  } finally {
    busy.value = false
  }
}

async function markRead(item: Announcement) {
  if (item.read || item.status !== 'PUBLISHED') return
  await http.post(`/announcements/${item.id}/read`)
  await load()
}

onMounted(() => load().catch(() => ElMessage.error(t('copy.0004'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">FIRM BULLETIN · {{ unread }} UNREAD</span>
        <h2>{{ t('headline.announcements') }}</h2>
        <p>{{ t('copy.0005') }}</p>
      </div>
      <button class="primary-action" @click="dialog = true"><Plus :size="17" /> {{ t('copy.0006') }}</button>
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
          <span>{{ (locale === 'en-US' ? item.officeNameEn : item.officeNameZh) || t('copy.0007') }} · {{ formatLegalCode(item.status, locale) }}</span>
        </div>
        <Megaphone :size="23" />
        <h3>{{ item.title }}</h3>
        <p>{{ item.summary || item.content }}</p>
        <div class="announcement-foot">
          <span>{{ item.publisherName }} · {{ item.publishedAt ? new Date(item.publishedAt).toLocaleDateString(locale) : formatLegalCode('DRAFT', locale) }}</span>
          <span><CheckCheck :size="14" /> {{ item.readCount }} {{ t('copy.0008') }}</span>
        </div>
      </article>
      <div v-if="!items.length" class="panel empty-state">{{ t('copy.0009') }}</div>
    </div>

    <el-dialog v-model="dialog" :title="t('copy.0010')" width="560px">
      <div class="dialog-form">
        <label><span>{{ t('copy.0011') }}</span><input v-model="form.title" :placeholder="t('copy.0012')" /></label>
        <label><span>{{ t('copy.0013') }}</span><input v-model="form.summary" :placeholder="t('copy.0014')" /></label>
        <label><span>{{ t('copy.0015') }}</span><select v-model="form.priority"><option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option><option value="IMPORTANT">{{ formatLegalCode('IMPORTANT', locale) }}</option><option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option></select></label>
        <label>
          <span>{{ t('copy.0016') }}</span>
          <select v-model="form.officeId">
            <option v-if="currentUser?.globalOfficeAccess" value="">{{ t('copy.0007') }}</option>
            <option
              v-for="office in publishableOffices"
              :key="office.id"
              :value="office.id"
            >
              {{ locale === 'en-US' ? office.nameEn : office.nameZh }}
            </option>
          </select>
        </label>
        <label><span>{{ t('copy.0017') }}</span><textarea v-model="form.content" rows="7" :placeholder="t('copy.0018')"></textarea></label>
      </div>
      <template #footer><button class="primary-action" :disabled="busy" @click="create"><Send :size="16" /> {{ busy ? t('copy.0019') : t('copy.0020') }}</button></template>
    </el-dialog>
  </section>
</template>

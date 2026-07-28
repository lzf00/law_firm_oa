<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { Building2, CalendarPlus, Users } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface Room {
  id: string; name: string; location: string; capacity: number; facilities: string; status: string
  officeId?: string; officeNameZh?: string; officeNameEn?: string
}
interface Booking {
  id: string; roomName: string; roomLocation: string; title: string; organizerName: string
  startAt: string; endAt: string; attendeeCount: number; status: string
  officeId?: string; officeNameZh?: string; officeNameEn?: string
}
const rooms = ref<Room[]>([])
const bookings = ref<Booking[]>([])
const dialog = ref(false)
const form = reactive({ roomId: '', title: '', startAt: '', endAt: '', attendeeCount: 2, notes: '' })
async function load() {
  const [roomResult, bookingResult] = await Promise.all([
    http.get<Room[]>('/meetings/rooms'), http.get<Booking[]>('/meetings/bookings'),
  ])
  rooms.value = roomResult.data
  bookings.value = bookingResult.data
  if (!form.roomId) form.roomId = rooms.value[0]?.id ?? ''
}
async function create() {
  try {
    await http.post('/meetings/bookings', {
      ...form, startAt: new Date(form.startAt).toISOString(), endAt: new Date(form.endAt).toISOString(),
    })
    dialog.value = false
    await load()
    ElMessage.success(t('copy.0267'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : t('copy.0268')) }
}
async function cancel(item: Booking) {
  await http.post(`/meetings/bookings/${item.id}/cancel`)
  await load()
  ElMessage.success(t('copy.0269'))
}
onMounted(() => load().catch(() => ElMessage.error(t('copy.0270'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">MEETING ROOMS</span><h2>{{ t('headline.meetings') }}</h2><p>{{ t('copy.0271') }}</p></div>
      <button class="primary-action" @click="dialog = true"><CalendarPlus :size="17" /> {{ t('copy.0272') }}</button>
    </div>
    <div class="room-strip">
      <article v-for="room in rooms" :key="room.id" class="room-card">
        <Building2 :size="23" /><div><strong>{{ room.name }}</strong><span>{{ locale === 'en-US' ? room.officeNameEn : room.officeNameZh }} · {{ room.location }} · {{ room.facilities }}</span></div>
        <span><Users :size="14" /> {{ room.capacity }} {{ t('copy.0273') }}</span>
      </article>
    </div>
    <div class="panel meeting-list">
      <div class="panel-heading"><div><span class="eyebrow">UPCOMING</span><h3>{{ t('copy.0274') }}</h3></div><span class="status-pill">{{ bookings.filter((item) => item.status === 'CONFIRMED').length }} {{ t('copy.0275') }}</span></div>
      <article v-for="item in bookings" :key="item.id" class="meeting-row">
        <time><strong>{{ new Date(item.startAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) }}</strong><span>{{ new Date(item.startAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span></time>
        <div><strong>{{ item.title }}</strong><span>{{ locale === 'en-US' ? item.officeNameEn : item.officeNameZh }} · {{ item.roomName }} · {{ item.roomLocation }} · {{ item.organizerName }}</span></div>
        <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
        <button v-if="item.status === 'CONFIRMED'" class="secondary-action compact-action" @click="cancel(item)">{{ t('copy.0079') }}</button>
      </article>
      <div v-if="!bookings.length" class="empty-state">{{ t('copy.0276') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="t('copy.0277')" width="540px">
      <div class="dialog-form two-column-form">
        <label class="full-field"><span>{{ t('copy.0278') }}</span><input v-model="form.title" :placeholder="t('copy.0279')" /></label>
        <label class="full-field"><span>{{ t('copy.0280') }}</span><select v-model="form.roomId"><option v-for="room in rooms" :key="room.id" :value="room.id">{{ room.name }} ({{ room.capacity }} {{ t('copy.0273') }})</option></select></label>
        <label><span>{{ t('copy.0196') }}</span><input v-model="form.startAt" type="datetime-local" /></label>
        <label><span>{{ t('copy.0197') }}</span><input v-model="form.endAt" type="datetime-local" /></label>
        <label><span>{{ t('copy.0281') }}</span><input v-model.number="form.attendeeCount" type="number" min="1" /></label>
        <label><span>{{ t('copy.0282') }}</span><input v-model="form.notes" :placeholder="t('copy.0201')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="create">{{ t('copy.0283') }}</button></template>
    </el-dialog>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Building2, CalendarPlus, CheckCircle2, Clock3, Users } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'
import { canManageOffice } from '@/officeScope'

const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
interface Room {
  id: string; name: string; location: string; capacity: number; facilities: string; status: string
  officeId?: string; officeNameZh?: string; officeNameEn?: string
}
interface Booking {
  id: string; roomName: string; roomLocation: string; title: string; organizerName: string
  organizerUserId: string
  startAt: string; endAt: string; attendeeCount: number; status: string
  officeId?: string; officeNameZh?: string; officeNameEn?: string
}
const rooms = ref<Room[]>([])
const bookings = ref<Booking[]>([])
const currentUser = ref<CurrentUser | null>(null)
const dialog = ref(false)
const busy = ref(false)
const form = reactive({ roomId: '', title: '', startAt: '', endAt: '', attendeeCount: 2, notes: '' })
const selectedRoom = computed(() => rooms.value.find((room) => room.id === form.roomId))
const confirmedBookings = computed(() => bookings.value.filter((item) => item.status === 'CONFIRMED'))
const canBook = computed(() => Boolean(
  form.roomId && form.title.trim() && form.startAt && form.endAt
  && new Date(form.endAt).getTime() > new Date(form.startAt).getTime()
  && form.attendeeCount > 0
  && form.attendeeCount <= (selectedRoom.value?.capacity ?? 0),
))
async function load() {
  const [roomResult, bookingResult, meResult] = await Promise.all([
    http.get<Room[]>('/meetings/rooms'),
    http.get<Booking[]>('/meetings/bookings'),
    http.get<CurrentUser>('/me'),
  ])
  rooms.value = roomResult.data
  bookings.value = bookingResult.data
  currentUser.value = meResult.data
  if (!form.roomId) form.roomId = rooms.value[0]?.id ?? ''
}
async function create() {
  if (!canBook.value || busy.value) return
  busy.value = true
  try {
    await http.post('/meetings/bookings', {
      ...form, startAt: new Date(form.startAt).toISOString(), endAt: new Date(form.endAt).toISOString(),
    })
    dialog.value = false
    await load()
    ElMessage.success(t('copy.0267'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0268'))
  } finally {
    busy.value = false
  }
}
function canCancel(item: Booking) {
  if (item.status !== 'CONFIRMED' || !currentUser.value) return false
  return item.organizerUserId === currentUser.value.userId
    || (
      currentUser.value.permissions.includes('MEETING_ROOM_MANAGE')
      && Boolean(item.officeId)
      && canManageOffice(currentUser.value, item.officeId!)
    )
}
async function cancel(item: Booking) {
  try {
    await http.post(`/meetings/bookings/${item.id}/cancel`)
    await load()
    ElMessage.success(t('copy.0269'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0268'))
  }
}
onMounted(() => load().catch(() => ElMessage.error(t('copy.0270'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">MEETING ROOMS</span><h2>{{ t('headline.meetings') }}</h2><p>{{ t('copy.0271') }}</p></div>
      <button class="primary-action" @click="dialog = true"><CalendarPlus :size="17" /> {{ t('copy.0272') }}</button>
    </div>
    <section class="meeting-summary" :aria-label="t('meeting.summary')">
      <article><Building2 :size="18" /><span><small>{{ t('meeting.availableRooms') }}</small><strong>{{ rooms.filter((room) => room.status === 'ACTIVE').length }}</strong></span></article>
      <article><Clock3 :size="18" /><span><small>{{ t('meeting.upcomingBookings') }}</small><strong>{{ confirmedBookings.length }}</strong></span></article>
      <article><CheckCircle2 :size="18" /><span><small>{{ t('meeting.conflictProtection') }}</small><strong>{{ t('meeting.enabled') }}</strong></span></article>
    </section>
    <div class="room-strip">
      <article v-for="room in rooms" :key="room.id" class="room-card">
        <Building2 :size="23" /><div><strong>{{ room.name }}</strong><span>{{ locale === 'en-US' ? room.officeNameEn : room.officeNameZh }} · {{ room.location }} · {{ room.facilities }}</span></div>
        <span><Users :size="14" /> {{ room.capacity }} {{ t('copy.0273') }}</span>
      </article>
    </div>
    <div class="panel meeting-list">
      <div class="panel-heading"><div><span class="eyebrow">UPCOMING</span><h3>{{ t('copy.0274') }}</h3></div><span class="status-pill">{{ confirmedBookings.length }} {{ t('copy.0275') }}</span></div>
      <article v-for="item in bookings" :key="item.id" class="meeting-row">
        <time><strong>{{ new Date(item.startAt).toLocaleDateString(locale, { month: '2-digit', day: '2-digit' }) }}</strong><span>{{ new Date(item.startAt).toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit' }) }}</span></time>
        <div><strong>{{ item.title }}</strong><span>{{ locale === 'en-US' ? item.officeNameEn : item.officeNameZh }} · {{ item.roomName }} · {{ item.roomLocation }} · {{ item.organizerName }}</span></div>
        <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
        <button v-if="canCancel(item)" class="secondary-action compact-action" @click="cancel(item)">{{ t('copy.0079') }}</button>
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
      <template #footer>
        <span v-if="selectedRoom" class="booking-capacity">{{ t('meeting.capacityHint').replace('{count}', String(selectedRoom.capacity)) }}</span>
        <button class="primary-action" :disabled="!canBook || busy" @click="create">{{ busy ? t('common.saving') : t('copy.0283') }}</button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.meeting-summary {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; margin-bottom: 14px;
}
.meeting-summary article {
  min-height: 72px; display: flex; align-items: center; gap: 11px;
  padding: 13px 15px; border: 1px solid var(--line); border-radius: 11px;
  background: rgba(255,255,255,.72); box-shadow: var(--shadow-sm); color: var(--forest-2);
}
.meeting-summary span, .meeting-summary small, .meeting-summary strong { display: block; }
.meeting-summary small { color: var(--muted); font-size: 8px; }
.meeting-summary strong { margin-top: 3px; color: var(--ink); font-size: 13px; }
.room-card { transition: transform .18s ease, box-shadow .18s ease; }
.room-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
.booking-capacity { margin-right: auto; color: var(--muted); font-size: 9px; }
:global(.el-dialog__footer) { display: flex; align-items: center; }
@media (max-width: 700px) {
  .meeting-summary { grid-template-columns: 1fr; }
}
</style>

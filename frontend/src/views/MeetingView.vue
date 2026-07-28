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
    ElMessage.success(text('会议室预约成功', 'Meeting room booked'))
  } catch (error) { ElMessage.error(error instanceof Error ? error.message : text('预约失败', 'Booking failed')) }
}
async function cancel(item: Booking) {
  await http.post(`/meetings/bookings/${item.id}/cancel`)
  await load()
  ElMessage.success(text('预约已取消，时段重新开放', 'Booking cancelled and the time slot released'))
}
onMounted(() => load().catch(() => ElMessage.error(text('会议室数据加载失败', 'Failed to load meeting rooms'))))
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">MEETING ROOMS</span><h2>{{ t('headline.meetings') }}</h2><p>{{ text('容量、设施、地点和未来预约统一展示；数据库排他约束保证同一房间同一时段只能有一场会议。', 'Capacity, facilities, location and future bookings are shown together; a database exclusion constraint prevents overlapping bookings.') }}</p></div>
      <button class="primary-action" @click="dialog = true"><CalendarPlus :size="17" /> {{ text('预约会议室', 'Book a room') }}</button>
    </div>
    <div class="room-strip">
      <article v-for="room in rooms" :key="room.id" class="room-card">
        <Building2 :size="23" /><div><strong>{{ room.name }}</strong><span>{{ locale === 'en-US' ? room.officeNameEn : room.officeNameZh }} · {{ room.location }} · {{ room.facilities }}</span></div>
        <span><Users :size="14" /> {{ room.capacity }} {{ text('人', 'people') }}</span>
      </article>
    </div>
    <div class="panel meeting-list">
      <div class="panel-heading"><div><span class="eyebrow">UPCOMING</span><h3>{{ text('未来预约', 'Upcoming bookings') }}</h3></div><span class="status-pill">{{ bookings.filter((item) => item.status === 'CONFIRMED').length }} {{ text('场', 'bookings') }}</span></div>
      <article v-for="item in bookings" :key="item.id" class="meeting-row">
        <time><strong>{{ new Date(item.startAt).toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }) }}</strong><span>{{ new Date(item.startAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) }}</span></time>
        <div><strong>{{ item.title }}</strong><span>{{ locale === 'en-US' ? item.officeNameEn : item.officeNameZh }} · {{ item.roomName }} · {{ item.roomLocation }} · {{ item.organizerName }}</span></div>
        <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
        <button v-if="item.status === 'CONFIRMED'" class="secondary-action compact-action" @click="cancel(item)">{{ text('取消', 'Cancel') }}</button>
      </article>
      <div v-if="!bookings.length" class="empty-state">{{ text('未来 90 天暂无预约', 'No bookings in the next 90 days') }}</div>
    </div>
    <el-dialog v-model="dialog" :title="text('预约会议室', 'Book meeting room')" width="540px">
      <div class="dialog-form two-column-form">
        <label class="full-field"><span>{{ text('会议主题', 'Meeting title') }}</span><input v-model="form.title" :placeholder="text('会议名称', 'Meeting name')" /></label>
        <label class="full-field"><span>{{ text('会议室', 'Room') }}</span><select v-model="form.roomId"><option v-for="room in rooms" :key="room.id" :value="room.id">{{ room.name }} ({{ room.capacity }} {{ text('人', 'people') }})</option></select></label>
        <label><span>{{ text('开始时间', 'Start') }}</span><input v-model="form.startAt" type="datetime-local" /></label>
        <label><span>{{ text('结束时间', 'End') }}</span><input v-model="form.endAt" type="datetime-local" /></label>
        <label><span>{{ text('参会人数', 'Attendees') }}</span><input v-model.number="form.attendeeCount" type="number" min="1" /></label>
        <label><span>{{ text('备注', 'Notes') }}</span><input v-model="form.notes" :placeholder="text('选填', 'Optional')" /></label>
      </div>
      <template #footer><button class="primary-action" @click="create">{{ text('确认预约', 'Confirm booking') }}</button></template>
    </el-dialog>
  </section>
</template>

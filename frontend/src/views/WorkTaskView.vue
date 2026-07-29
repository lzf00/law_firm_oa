<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowRight,
  CalendarClock,
  Check,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  Clock3,
  FileText,
  ListChecks,
  MessageSquareText,
  PencilLine,
  Plus,
  RotateCcw,
  Search,
  Send,
  UserRoundCheck,
  UsersRound,
  X,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Matter, OrganizationUser } from '@/api/types'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Task {
  id: string
  title: string
  description?: string
  status: 'TODO' | 'IN_PROGRESS' | 'DONE' | 'CANCELLED'
  priority: 'LOW' | 'NORMAL' | 'HIGH' | 'URGENT'
  ownerUserId: string
  ownerName: string
  assignerName: string
  dueAt?: string
  completedAt?: string
  createdAt: string
  commentCount: number
  officeId?: string
  officeNameZh?: string
  officeNameEn?: string
  relatedBusinessType?: string
  relatedBusinessId?: string
  relatedBusinessLabel?: string
  version: number
  participantCount: number
  eventCount: number
  completedBy?: string
  completedByName?: string
  completionNote?: string
  cancelledAt?: string
  cancelledBy?: string
  cancelledByName?: string
  cancellationReason?: string
}

interface TaskParticipant {
  userId: string
  displayName: string
  participantRole: string
  joinedAt: string
}

interface TaskComment {
  id: string
  authorName: string
  content: string
  createdAt: string
}

interface TaskEvent {
  id: string
  action: string
  actorUserId: string
  actorName: string
  fromStatus?: string
  toStatus?: string
  note?: string
  occurredAt: string
}

interface TaskDetail {
  task: Task
  participants: TaskParticipant[]
  comments: TaskComment[]
  events: TaskEvent[]
}

const { locale, t } = useI18n()
const route = useRoute()
const router = useRouter()
const tasks = ref<Task[]>([])
const users = ref<OrganizationUser[]>([])
const matters = ref<Matter[]>([])
const currentUser = ref<CurrentUser | null>(null)
const selectedId = ref('')
const detail = ref<TaskDetail | null>(null)
const loading = ref(true)
const detailLoading = ref(false)
const saving = ref(false)
const editorVisible = ref(false)
const actionVisible = ref(false)
const actionStatus = ref<Task['status']>('IN_PROGRESS')
const actionNote = ref('')
const comment = ref('')
const search = ref('')
const statusFilter = ref<'ACTIVE' | 'ALL' | Task['status']>('ACTIVE')
const priorityFilter = ref<'ALL' | Task['priority']>('ALL')
const ownershipFilter = ref<'ALL' | 'MINE' | 'ASSIGNED'>('ALL')

const form = reactive({
  id: '',
  expectedVersion: 0,
  title: '',
  description: '',
  ownerUserId: '',
  participantUserIds: [] as string[],
  dueAt: '',
  priority: 'NORMAL' as Task['priority'],
  relatedBusinessId: '',
})

const canCreate = computed(() => currentUser.value?.permissions.includes('TASK_CREATE') ?? false)
const canManage = computed(() => currentUser.value?.permissions.includes('TASK_MANAGE') ?? false)
const now = () => Date.now()
const isOverdue = (task: Task) => Boolean(
  task.dueAt
  && !['DONE', 'CANCELLED'].includes(task.status)
  && new Date(task.dueAt).getTime() < now(),
)
const isDueSoon = (task: Task) => Boolean(
  task.dueAt
  && !['DONE', 'CANCELLED'].includes(task.status)
  && new Date(task.dueAt).getTime() >= now()
  && new Date(task.dueAt).getTime() <= now() + 7 * 86_400_000,
)
const stats = computed(() => ({
  active: tasks.value.filter((task) => ['TODO', 'IN_PROGRESS'].includes(task.status)).length,
  mine: tasks.value.filter((task) => task.ownerUserId === currentUser.value?.userId
    && ['TODO', 'IN_PROGRESS'].includes(task.status)).length,
  dueSoon: tasks.value.filter(isDueSoon).length,
  overdue: tasks.value.filter(isOverdue).length,
}))
const filtered = computed(() => tasks.value.filter((task) => {
  const needle = search.value.trim().toLocaleLowerCase(locale.value)
  if (needle && ![
    task.title,
    task.description,
    task.ownerName,
    task.assignerName,
    task.relatedBusinessLabel,
  ].some((value) => value?.toLocaleLowerCase(locale.value).includes(needle))) return false
  if (statusFilter.value === 'ACTIVE' && !['TODO', 'IN_PROGRESS'].includes(task.status)) return false
  if (!['ACTIVE', 'ALL'].includes(statusFilter.value) && task.status !== statusFilter.value) return false
  if (priorityFilter.value !== 'ALL' && task.priority !== priorityFilter.value) return false
  if (ownershipFilter.value === 'MINE' && task.ownerUserId !== currentUser.value?.userId) return false
  if (ownershipFilter.value === 'ASSIGNED' && task.assignerName !== currentUser.value?.displayName) return false
  return true
}))
const selectedTask = computed(() => tasks.value.find((task) => task.id === selectedId.value))
const canEditSelected = computed(() => {
  const task = selectedTask.value
  return Boolean(task
    && !['DONE', 'CANCELLED'].includes(task.status)
    && (canManage.value || task.assignerName === currentUser.value?.displayName))
})
const selectedOffice = computed(() => {
  const task = selectedTask.value
  if (!task) return ''
  return locale.value === 'en-US' ? task.officeNameEn : task.officeNameZh
})

async function load() {
  loading.value = true
  try {
    const [taskResult, userResult, matterResult, meResult] = await Promise.all([
      http.get<Task[]>('/work-tasks'),
      http.get<OrganizationUser[]>('/organization/users'),
      http.get<Matter[]>('/matters'),
      http.get<CurrentUser>('/me'),
    ])
    tasks.value = taskResult.data
    users.value = userResult.data.filter((user) => user.status === 'ACTIVE')
    matters.value = matterResult.data.filter((matter) => matter.status !== 'ARCHIVED')
    currentUser.value = meResult.data
    const requested = typeof route.query.id === 'string' ? route.query.id : ''
    const next = tasks.value.some((task) => task.id === requested)
      ? requested
      : tasks.value.find((task) => ['TODO', 'IN_PROGRESS'].includes(task.status))?.id
        ?? tasks.value[0]?.id
        ?? ''
    if (next) await selectTask(next, false)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('wt.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function selectTask(id: string, updateUrl = true) {
  selectedId.value = id
  detailLoading.value = true
  if (updateUrl) {
    await router.replace({ query: { ...route.query, id } })
  }
  try {
    detail.value = (await http.get<TaskDetail>(`/work-tasks/${id}`)).data
  } catch (error) {
    detail.value = null
    ElMessage.error(error instanceof Error ? error.message : t('wt.detailFailed'))
  } finally {
    detailLoading.value = false
  }
}

function resetForm() {
  Object.assign(form, {
    id: '',
    expectedVersion: 0,
    title: '',
    description: '',
    ownerUserId: currentUser.value?.userId ?? users.value[0]?.id ?? '',
    participantUserIds: [],
    dueAt: '',
    priority: 'NORMAL',
    relatedBusinessId: '',
  })
}

function openCreate() {
  resetForm()
  editorVisible.value = true
}

function openEdit() {
  if (!detail.value) return
  const task = detail.value.task
  Object.assign(form, {
    id: task.id,
    expectedVersion: task.version,
    title: task.title,
    description: task.description ?? '',
    ownerUserId: task.ownerUserId,
    participantUserIds: detail.value.participants.map((item) => item.userId),
    dueAt: task.dueAt ? toLocalInput(task.dueAt) : '',
    priority: task.priority,
    relatedBusinessId: task.relatedBusinessType === 'MATTER'
      ? task.relatedBusinessId ?? ''
      : '',
  })
  editorVisible.value = true
}

async function saveTask() {
  if (!form.title.trim() || !form.ownerUserId) {
    ElMessage.warning(t('wt.required'))
    return
  }
  saving.value = true
  const payload = {
    title: form.title.trim(),
    description: form.description.trim() || null,
    ownerUserId: form.ownerUserId,
    participantUserIds: form.participantUserIds.filter((id) => id !== form.ownerUserId),
    dueAt: form.dueAt ? new Date(form.dueAt).toISOString() : null,
    priority: form.priority,
    relatedBusinessType: form.relatedBusinessId ? 'MATTER' : null,
    relatedBusinessId: form.relatedBusinessId || null,
  }
  try {
    if (form.id) {
      await http.put(`/work-tasks/${form.id}`, {
        ...payload,
        expectedVersion: form.expectedVersion,
      })
    } else {
      const created = (await http.post<Task>('/work-tasks', payload)).data
      selectedId.value = created.id
    }
    editorVisible.value = false
    await load()
    ElMessage.success(form.id ? t('wt.updated') : t('wt.assigned'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('wt.saveFailed'))
  } finally {
    saving.value = false
  }
}

function openAction(status: Task['status']) {
  actionStatus.value = status
  actionNote.value = ''
  actionVisible.value = true
}

async function submitAction() {
  const task = detail.value?.task
  if (!task) return
  const noteRequired = actionStatus.value === 'CANCELLED'
    || task.status === 'DONE'
    || task.status === 'CANCELLED'
  if (noteRequired && !actionNote.value.trim()) {
    ElMessage.warning(t('wt.reasonRequired'))
    return
  }
  saving.value = true
  try {
    await http.patch(`/work-tasks/${task.id}/status`, {
      status: actionStatus.value,
      expectedVersion: task.version,
      note: actionNote.value.trim() || null,
    })
    actionVisible.value = false
    await load()
    ElMessage.success(t('wt.statusUpdated'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('wt.statusFailed'))
  } finally {
    saving.value = false
  }
}

async function addComment() {
  const task = detail.value?.task
  if (!task || !comment.value.trim()) return
  saving.value = true
  try {
    await http.post(`/work-tasks/${task.id}/comments`, { content: comment.value.trim() })
    comment.value = ''
    await selectTask(task.id, false)
    const index = tasks.value.findIndex((item) => item.id === task.id)
    const listItem = tasks.value[index]
    if (listItem) listItem.commentCount += 1
    ElMessage.success(t('wt.commentPosted'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('wt.commentFailed'))
  } finally {
    saving.value = false
  }
}

function taskDate(task: Task) {
  if (!task.dueAt) return t('wt.noDue')
  return new Intl.DateTimeFormat(locale.value, {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(task.dueAt))
}

function fullDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function toLocalInput(value: string) {
  const date = new Date(value)
  const offset = date.getTimezoneOffset() * 60_000
  return new Date(date.getTime() - offset).toISOString().slice(0, 16)
}

function eventLabel(action: string) {
  const labels: Record<string, string> = {
    CREATED: 'wt.event.created',
    UPDATED: 'wt.event.updated',
    STATUS_CHANGED: 'wt.event.status',
    STATUS_CONFIRMED: 'wt.event.confirmed',
    COMPLETED: 'wt.event.completed',
    CANCELLED: 'wt.event.cancelled',
    REOPENED: 'wt.event.reopened',
    COMMENTED: 'wt.event.commented',
  }
  const label = labels[action]
  return label ? t(label) : formatLegalCode(action, locale.value)
}

watch(() => route.query.id, async (value) => {
  if (typeof value === 'string' && value !== selectedId.value
      && tasks.value.some((task) => task.id === value)) {
    await selectTask(value, false)
  }
})

onMounted(load)
</script>

<template>
  <section class="task-workbench">
    <header class="task-hero">
      <div>
        <span class="eyebrow">LEGAL TEAM DELIVERY</span>
        <h2>{{ t('wt.heroTitle') }}</h2>
        <p>{{ t('wt.heroDescription') }}</p>
      </div>
      <button v-if="canCreate" class="primary-action" @click="openCreate">
        <Plus :size="17" /> {{ t('wt.new') }}
      </button>
    </header>

    <section class="task-metrics" :aria-label="t('wt.overview')">
      <article><ListChecks :size="19" /><span>{{ t('wt.active') }}</span><strong>{{ stats.active }}</strong></article>
      <article><UserRoundCheck :size="19" /><span>{{ t('wt.owned') }}</span><strong>{{ stats.mine }}</strong></article>
      <article><CalendarClock :size="19" /><span>{{ t('wt.dueSeven') }}</span><strong>{{ stats.dueSoon }}</strong></article>
      <article :class="{ danger: stats.overdue }"><CircleAlert :size="19" /><span>{{ t('wt.overdue') }}</span><strong>{{ stats.overdue }}</strong></article>
    </section>

    <section class="task-toolbar">
      <label class="task-search">
        <Search :size="16" />
        <input v-model="search" :placeholder="t('wt.search')" />
      </label>
      <select v-model="statusFilter" :aria-label="t('wt.statusFilter')">
        <option value="ACTIVE">{{ t('wt.activeFilter') }}</option>
        <option value="ALL">{{ t('wt.allStatuses') }}</option>
        <option value="TODO">{{ formatLegalCode('TODO', locale) }}</option>
        <option value="IN_PROGRESS">{{ formatLegalCode('IN_PROGRESS', locale) }}</option>
        <option value="DONE">{{ formatLegalCode('DONE', locale) }}</option>
        <option value="CANCELLED">{{ formatLegalCode('CANCELLED', locale) }}</option>
      </select>
      <select v-model="ownershipFilter" :aria-label="t('wt.ownershipFilter')">
        <option value="ALL">{{ t('wt.allVisible') }}</option>
        <option value="MINE">{{ t('wt.owned') }}</option>
        <option value="ASSIGNED">{{ t('wt.assignedByMe') }}</option>
      </select>
      <select v-model="priorityFilter" :aria-label="t('wt.priorityFilter')">
        <option value="ALL">{{ t('wt.allPriorities') }}</option>
        <option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option>
        <option value="HIGH">{{ formatLegalCode('HIGH', locale) }}</option>
        <option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option>
        <option value="LOW">{{ formatLegalCode('LOW', locale) }}</option>
      </select>
    </section>

    <div class="task-layout">
      <section class="task-queue" :aria-label="t('wt.list')">
        <div class="queue-heading">
          <div><span class="eyebrow">ACTION QUEUE</span><strong>{{ filtered.length }} {{ t('wt.items') }}</strong></div>
          <span>{{ t('wt.sortHint') }}</span>
        </div>
        <div v-if="loading" class="task-empty">{{ t('wt.loading') }}</div>
        <button
          v-for="task in filtered"
          v-else
          :key="task.id"
          class="task-list-item"
          :class="{ selected: selectedId === task.id, overdue: isOverdue(task) }"
          @click="selectTask(task.id)"
        >
          <span class="task-state" :data-status="task.status"></span>
          <span class="task-copy">
            <span class="task-meta">
              <span class="priority-chip" :data-priority="task.priority">{{ formatLegalCode(task.priority, locale) }}</span>
              <span v-if="task.relatedBusinessLabel">{{ task.relatedBusinessLabel }}</span>
            </span>
            <strong>{{ task.title }}</strong>
            <span>{{ task.ownerName }} · {{ taskDate(task) }}</span>
          </span>
          <span class="task-counts">
            <span><MessageSquareText :size="13" /> {{ task.commentCount }}</span>
            <ChevronRight :size="16" />
          </span>
        </button>
        <div v-if="!loading && filtered.length === 0" class="task-empty">
          <CheckCircle2 :size="32" />
          <strong>{{ t('wt.noMatches') }}</strong>
          <span>{{ t('wt.noMatchesHint') }}</span>
        </div>
      </section>

      <aside class="task-detail" aria-live="polite">
        <div v-if="detailLoading" class="task-empty">{{ t('wt.loadingEvidence') }}</div>
        <template v-else-if="detail">
          <header class="detail-heading">
            <div>
              <span class="eyebrow">{{ selectedOffice || t('wt.teamTask') }}</span>
              <h3>{{ detail.task.title }}</h3>
              <p>{{ detail.task.description || t('wt.noBrief') }}</p>
            </div>
            <button v-if="canEditSelected" class="icon-action" :aria-label="t('wt.edit')" @click="openEdit">
              <PencilLine :size="16" />
            </button>
          </header>

          <div class="detail-tags">
            <span class="status-pill">{{ formatLegalCode(detail.task.status, locale) }}</span>
            <span class="priority-chip" :data-priority="detail.task.priority">{{ formatLegalCode(detail.task.priority, locale) }}</span>
            <span v-if="isOverdue(detail.task)" class="overdue-badge"><CircleAlert :size="13" /> {{ t('db.overdue') }}</span>
          </div>

          <dl class="task-facts">
            <div><dt>{{ t('wt.owner') }}</dt><dd>{{ detail.task.ownerName }}</dd></div>
            <div><dt>{{ t('wt.assignedBy') }}</dt><dd>{{ detail.task.assignerName }}</dd></div>
            <div><dt>{{ t('wt.due') }}</dt><dd>{{ taskDate(detail.task) }}</dd></div>
            <div><dt>{{ t('wt.related') }}</dt><dd>{{ detail.task.relatedBusinessLabel || t('wt.standalone') }}</dd></div>
          </dl>

          <section class="detail-section">
            <div class="section-heading"><UsersRound :size="16" /><strong>{{ t('wt.collaborators') }}</strong><span>{{ detail.participants.length }}</span></div>
            <div class="people-line">
              <span class="person-chip owner">{{ detail.task.ownerName }}</span>
              <span v-for="person in detail.participants" :key="person.userId" class="person-chip">{{ person.displayName }}</span>
              <span v-if="!detail.participants.length" class="muted">{{ t('wt.noCollaborators') }}</span>
            </div>
          </section>

          <section class="detail-section">
            <div class="section-heading"><MessageSquareText :size="16" /><strong>{{ t('wt.discussion') }}</strong><span>{{ detail.comments.length }}</span></div>
            <div class="comment-list">
              <article v-for="item in detail.comments" :key="item.id">
                <div><strong>{{ item.authorName }}</strong><time>{{ fullDate(item.createdAt) }}</time></div>
                <p>{{ item.content }}</p>
              </article>
              <span v-if="!detail.comments.length" class="muted">{{ t('wt.noComments') }}</span>
            </div>
            <form class="comment-box" @submit.prevent="addComment">
              <textarea v-model="comment" maxlength="1000" rows="2" :placeholder="t('wt.commentPlaceholder')" />
              <button class="icon-action" :disabled="saving || !comment.trim()" :aria-label="t('wt.sendComment')">
                <Send :size="16" />
              </button>
            </form>
          </section>

          <section class="detail-section">
            <div class="section-heading"><FileText :size="16" /><strong>{{ t('wt.evidence') }}</strong><span>{{ detail.events.length }}</span></div>
            <ol class="event-line">
              <li v-for="event in detail.events" :key="event.id">
                <span class="event-dot"></span>
                <div>
                  <strong>{{ eventLabel(event.action) }}</strong>
                  <span>{{ event.actorName }} · {{ fullDate(event.occurredAt) }}</span>
                  <p v-if="event.note">{{ event.note }}</p>
                </div>
              </li>
            </ol>
          </section>

          <footer class="task-actions">
            <button v-if="detail.task.status === 'TODO'" class="secondary-action" @click="openAction('IN_PROGRESS')">
              <Clock3 :size="15" /> {{ t('wt.start') }}
            </button>
            <button v-if="detail.task.status === 'IN_PROGRESS'" class="secondary-action" @click="openAction('TODO')">
              <RotateCcw :size="15" /> {{ t('wt.backTodo') }}
            </button>
            <button v-if="['TODO', 'IN_PROGRESS'].includes(detail.task.status)" class="primary-action" @click="openAction('DONE')">
              <Check :size="15" /> {{ t('wt.complete') }}
            </button>
            <button
              v-if="['TODO', 'IN_PROGRESS'].includes(detail.task.status)
                && (canManage || detail.task.assignerName === currentUser?.displayName)"
              class="danger-action"
              @click="openAction('CANCELLED')"
            >
              <X :size="15" /> {{ t('wt.cancel') }}
            </button>
            <button v-if="detail.task.status === 'DONE'" class="secondary-action" @click="openAction('IN_PROGRESS')">
              <RotateCcw :size="15" /> {{ t('wt.reopen') }}
            </button>
            <button v-if="detail.task.status === 'CANCELLED'" class="secondary-action" @click="openAction('TODO')">
              <RotateCcw :size="15" /> {{ t('wt.restore') }}
            </button>
          </footer>
        </template>
        <div v-else class="task-empty">
          <ListChecks :size="36" />
          <strong>{{ t('wt.select') }}</strong>
          <span>{{ t('wt.selectHint') }}</span>
        </div>
      </aside>
    </div>

    <ElDialog v-model="editorVisible" :title="form.id ? t('wt.editDialog') : t('wt.assignDialog')" width="min(680px, 94vw)">
      <div class="task-editor">
        <label class="wide"><span>{{ t('wt.name') }}</span><input v-model="form.title" maxlength="300" :placeholder="t('wt.namePlaceholder')" /></label>
        <label><span>{{ t('wt.owner') }}</span>
          <select v-model="form.ownerUserId">
            <option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option>
          </select>
        </label>
        <label><span>{{ t('wt.priority') }}</span>
          <select v-model="form.priority">
            <option value="LOW">{{ formatLegalCode('LOW', locale) }}</option>
            <option value="NORMAL">{{ formatLegalCode('NORMAL', locale) }}</option>
            <option value="HIGH">{{ formatLegalCode('HIGH', locale) }}</option>
            <option value="URGENT">{{ formatLegalCode('URGENT', locale) }}</option>
          </select>
        </label>
        <label><span>{{ t('wt.due') }}</span><input v-model="form.dueAt" type="datetime-local" /></label>
        <label><span>{{ t('wt.relatedMatter') }}</span>
          <select v-model="form.relatedBusinessId">
            <option value="">{{ t('wt.noMatter') }}</option>
            <option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option>
          </select>
        </label>
        <label class="wide"><span>{{ t('wt.participants') }}</span>
          <ElSelect v-model="form.participantUserIds" multiple filterable :placeholder="t('wt.participantPlaceholder')" style="width: 100%">
            <ElOption v-for="user in users.filter((item) => item.id !== form.ownerUserId)" :key="user.id" :label="user.displayName" :value="user.id" />
          </ElSelect>
        </label>
        <label class="wide"><span>{{ t('wt.brief') }}</span><textarea v-model="form.description" maxlength="5000" rows="5" :placeholder="t('wt.briefPlaceholder')" /></label>
      </div>
      <template #footer>
        <button class="secondary-action" @click="editorVisible = false">{{ t('wt.cancel') }}</button>
        <button class="primary-action" :disabled="saving" @click="saveTask">{{ saving ? t('wt.saving') : t('wt.save') }}</button>
      </template>
    </ElDialog>

    <ElDialog v-model="actionVisible" :title="t('wt.statusDialog')" width="min(520px, 92vw)">
      <div class="action-sheet">
        <span class="action-target"><ArrowRight :size="16" /> {{ formatLegalCode(actionStatus, locale) }}</span>
        <label>
          <span>{{ actionStatus === 'CANCELLED' || detail?.task.status === 'DONE' || detail?.task.status === 'CANCELLED'
            ? t('wt.reasonLabel')
            : t('wt.progressLabel') }}</span>
          <textarea v-model="actionNote" rows="4" maxlength="1000" :placeholder="t('wt.actionPlaceholder')" />
        </label>
      </div>
      <template #footer>
        <button class="secondary-action" @click="actionVisible = false">{{ t('wt.back') }}</button>
        <button class="primary-action" :disabled="saving" @click="submitAction">{{ t('wt.confirm') }}</button>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.task-workbench { display: grid; gap: 18px; }
.task-hero {
  position: relative; overflow: hidden; display: flex; align-items: flex-end; justify-content: space-between;
  gap: 24px; padding: 28px 30px; color: #f7f3e9; background:
    radial-gradient(circle at 78% 15%, rgba(190, 155, 82, .2), transparent 32%),
    linear-gradient(125deg, #102c25 0%, #173e34 62%, #0f2923 100%);
  border: 1px solid rgba(194, 163, 98, .28); border-radius: 16px;
}
.task-hero::after { content: "§"; position: absolute; right: 14%; bottom: -48px; color: rgba(255,255,255,.035); font-family: Georgia, serif; font-size: 180px; line-height: 1; }
.task-hero > * { position: relative; z-index: 1; }
.task-hero h2 { margin: 8px 0; color: #fffdf8; font-family: Georgia, "Songti SC", serif; font-size: clamp(26px, 3vw, 38px); font-weight: 500; }
.task-hero p { max-width: 680px; margin: 0; color: rgba(245,242,232,.72); font-size: 12px; line-height: 1.8; }
.task-hero .eyebrow { color: #d0b471; }
.task-metrics { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.task-metrics article { display: grid; grid-template-columns: auto 1fr auto; gap: 9px; align-items: center; padding: 16px 18px; background: var(--paper-light); border: 1px solid var(--line); border-radius: 12px; }
.task-metrics svg { color: var(--green); }
.task-metrics span { color: var(--muted); font-size: 10px; }
.task-metrics strong { color: var(--ink); font-family: Georgia, serif; font-size: 25px; font-weight: 500; }
.task-metrics article.danger { border-color: rgba(153, 65, 55, .3); background: #fff9f6; }
.task-metrics article.danger svg, .task-metrics article.danger strong { color: var(--oxblood); }
.task-toolbar { display: grid; grid-template-columns: minmax(260px, 1fr) auto auto auto; gap: 10px; padding: 12px; background: #ebe8df; border: 1px solid var(--line); border-radius: 12px; }
.task-toolbar select, .task-search { min-height: 40px; background: var(--paper-light); border: 1px solid var(--line); border-radius: 8px; }
.task-toolbar select { padding: 0 34px 0 12px; font-size: 11px; }
.task-search { display: flex; gap: 9px; align-items: center; padding: 0 12px; }
.task-search svg { color: var(--muted); }
.task-search input { width: 100%; padding: 0; background: transparent; border: 0; outline: 0; }
.task-layout { display: grid; grid-template-columns: minmax(360px, .86fr) minmax(520px, 1.14fr); gap: 16px; align-items: start; }
.task-queue, .task-detail { min-height: 640px; background: var(--paper-light); border: 1px solid var(--line); border-radius: 14px; box-shadow: var(--shadow-sm); }
.task-queue { overflow: hidden; }
.queue-heading { display: flex; align-items: end; justify-content: space-between; gap: 16px; padding: 20px; border-bottom: 1px solid var(--line); }
.queue-heading div { display: grid; gap: 5px; }
.queue-heading strong { font-size: 15px; }
.queue-heading > span { max-width: 190px; color: var(--muted); font-size: 9px; text-align: right; }
.task-list-item { width: 100%; display: grid; grid-template-columns: 4px minmax(0, 1fr) auto; gap: 14px; align-items: center; padding: 16px 18px; color: inherit; text-align: left; background: transparent; border: 0; border-bottom: 1px solid var(--line); cursor: pointer; transition: background .16s ease, transform .16s ease; }
.task-list-item:hover { background: #f5f2ea; }
.task-list-item.selected { background: #eef3ed; box-shadow: inset 3px 0 #2c6252; }
.task-list-item.overdue { background-image: linear-gradient(90deg, rgba(140,56,48,.05), transparent 45%); }
.task-state { width: 4px; height: 38px; background: #b4b6ad; border-radius: 5px; }
.task-state[data-status="IN_PROGRESS"] { background: #2c6252; }
.task-state[data-status="DONE"] { background: #7b967c; }
.task-state[data-status="CANCELLED"] { background: #9c817b; }
.task-copy { min-width: 0; display: grid; gap: 6px; }
.task-copy strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.task-copy > span:last-child { color: var(--muted); font-size: 9px; }
.task-meta { display: flex; gap: 8px; align-items: center; min-width: 0; color: var(--muted); font-size: 8px; }
.task-meta > span:last-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.priority-chip { display: inline-flex; align-items: center; padding: 3px 7px; color: #40564d; background: #e8eee9; border: 1px solid #d4ded6; border-radius: 999px; font-size: 8px; letter-spacing: .04em; white-space: nowrap; }
.priority-chip[data-priority="URGENT"], .priority-chip[data-priority="HIGH"] { color: #7f3029; background: #f7e9e4; border-color: #ebcbc2; }
.task-counts { display: flex; gap: 9px; align-items: center; color: var(--muted); }
.task-counts span { display: inline-flex; gap: 4px; align-items: center; font-size: 9px; }
.task-detail { position: sticky; top: 92px; max-height: calc(100vh - 112px); overflow: auto; padding: 24px; }
.detail-heading { display: flex; gap: 18px; justify-content: space-between; }
.detail-heading h3 { margin: 7px 0 8px; font-family: Georgia, "Songti SC", serif; font-size: 24px; font-weight: 500; line-height: 1.35; }
.detail-heading p { margin: 0; color: var(--muted); font-size: 10px; line-height: 1.8; white-space: pre-wrap; }
.icon-action { display: inline-grid; flex: 0 0 36px; width: 36px; height: 36px; place-items: center; color: var(--green); background: #edf2ee; border: 1px solid #d7e0d9; border-radius: 9px; cursor: pointer; }
.icon-action:disabled { opacity: .45; cursor: not-allowed; }
.detail-tags { display: flex; gap: 7px; align-items: center; margin: 18px 0; }
.overdue-badge { display: inline-flex; gap: 5px; align-items: center; color: #8a382f; font-size: 9px; }
.task-facts { display: grid; grid-template-columns: repeat(2, 1fr); margin: 0 0 22px; border: 1px solid var(--line); border-radius: 10px; }
.task-facts div { padding: 13px 15px; border-right: 1px solid var(--line); border-bottom: 1px solid var(--line); }
.task-facts div:nth-child(2n) { border-right: 0; }
.task-facts div:nth-last-child(-n+2) { border-bottom: 0; }
.task-facts dt { color: var(--muted); font-size: 8px; text-transform: uppercase; letter-spacing: .08em; }
.task-facts dd { margin: 5px 0 0; font-size: 10px; line-height: 1.5; }
.detail-section { padding: 18px 0; border-top: 1px solid var(--line); }
.section-heading { display: flex; gap: 8px; align-items: center; margin-bottom: 13px; }
.section-heading svg { color: var(--green); }
.section-heading strong { font-size: 11px; }
.section-heading span { margin-left: auto; color: var(--muted); font-size: 9px; }
.people-line { display: flex; flex-wrap: wrap; gap: 7px; }
.person-chip { padding: 6px 9px; color: #43544d; background: #f0f2ed; border-radius: 7px; font-size: 9px; }
.person-chip.owner { color: #fff; background: #315f51; }
.muted { color: var(--muted); font-size: 9px; }
.comment-list { display: grid; gap: 9px; }
.comment-list article { padding: 11px 12px; background: #f5f3ed; border-radius: 8px; }
.comment-list article div { display: flex; justify-content: space-between; gap: 12px; }
.comment-list strong { font-size: 9px; }
.comment-list time { color: var(--muted); font-size: 8px; }
.comment-list p { margin: 7px 0 0; font-size: 10px; line-height: 1.65; white-space: pre-wrap; }
.comment-box { display: grid; grid-template-columns: 1fr auto; gap: 8px; align-items: end; margin-top: 11px; }
.comment-box textarea { resize: vertical; }
.event-line { display: grid; gap: 0; margin: 0; padding: 0; list-style: none; }
.event-line li { position: relative; display: grid; grid-template-columns: 14px 1fr; gap: 9px; padding-bottom: 15px; }
.event-line li:not(:last-child)::before { content: ""; position: absolute; left: 5px; top: 9px; bottom: -1px; width: 1px; background: #d8ddd6; }
.event-dot { position: relative; z-index: 1; width: 11px; height: 11px; margin-top: 2px; background: #d0b471; border: 3px solid #f7f5ef; border-radius: 50%; box-shadow: 0 0 0 1px #d0b471; }
.event-line div { display: grid; gap: 4px; }
.event-line strong { font-size: 9px; }
.event-line span { color: var(--muted); font-size: 8px; }
.event-line p { margin: 2px 0 0; color: #4e5c56; font-size: 9px; line-height: 1.55; }
.task-actions { position: sticky; bottom: -24px; display: flex; flex-wrap: wrap; gap: 8px; margin: 8px -24px -24px; padding: 16px 24px; background: rgba(250,248,242,.95); border-top: 1px solid var(--line); backdrop-filter: blur(8px); }
.danger-action { display: inline-flex; gap: 6px; align-items: center; min-height: 36px; padding: 0 12px; color: #87372f; background: #fff8f5; border: 1px solid #e8c8c0; border-radius: 8px; cursor: pointer; }
.task-empty { min-height: 210px; display: flex; flex-direction: column; gap: 8px; align-items: center; justify-content: center; padding: 30px; color: var(--muted); font-size: 10px; text-align: center; }
.task-empty strong { color: var(--ink); font-size: 12px; }
.task-editor { display: grid; grid-template-columns: repeat(2, 1fr); gap: 16px; }
.task-editor label, .action-sheet label { display: grid; gap: 7px; }
.task-editor label > span, .action-sheet label > span { color: var(--muted); font-size: 9px; }
.task-editor .wide { grid-column: 1 / -1; }
.action-sheet { display: grid; gap: 18px; }
.action-target { display: inline-flex; gap: 8px; align-items: center; width: fit-content; padding: 8px 11px; color: #275547; background: #eaf0eb; border-radius: 8px; font-size: 10px; }

@media (max-width: 1100px) {
  .task-layout { grid-template-columns: minmax(320px, .8fr) minmax(420px, 1.2fr); }
  .task-toolbar { grid-template-columns: 1fr 1fr; }
}
@media (max-width: 820px) {
  .task-hero { align-items: flex-start; flex-direction: column; padding: 22px; }
  .task-metrics { grid-template-columns: repeat(2, 1fr); }
  .task-layout { grid-template-columns: 1fr; }
  .task-detail { position: static; max-height: none; min-height: 0; }
  .task-toolbar { grid-template-columns: 1fr; }
}
@media (max-width: 520px) {
  .task-metrics { grid-template-columns: 1fr 1fr; }
  .task-metrics article { grid-template-columns: auto 1fr; padding: 13px; }
  .task-metrics strong { grid-column: 2; font-size: 21px; }
  .task-editor { grid-template-columns: 1fr; }
  .task-editor .wide { grid-column: auto; }
  .task-facts { grid-template-columns: 1fr; }
  .task-facts div { border-right: 0; }
  .task-facts div:nth-last-child(-n+2) { border-bottom: 1px solid var(--line); }
  .task-facts div:last-child { border-bottom: 0; }
  .queue-heading > span { display: none; }
}
</style>

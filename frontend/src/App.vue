<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { clearBrowserSessionAuthentication } from '@/router'
import {
  Archive,
  Bell,
  BriefcaseBusiness,
  Building2,
  CalendarClock,
  CalendarDays,
  ContactRound,
  FileCheck2,
  FileStack,
  Landmark,
  LibraryBig,
  Settings2,
  Gavel,
  Globe2,
  LayoutDashboard,
  Languages,
  ListTodo,
  LogOut,
  Megaphone,
  Menu,
  ReceiptText,
  Scale,
  SearchCheck,
  UsersRound,
  X,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'
import { useI18n } from '@/i18n'
import { useTenant } from '@/tenant'
import { formatLegalCode } from '@/legalFormat'

const route = useRoute()
const router = useRouter()
const user = ref<CurrentUser | null>(null)
const compact = ref(false)
const notificationOpen = ref(false)
const notificationLoading = ref(false)
const notificationDrawer = ref<HTMLElement | null>(null)
const notificationClose = ref<HTMLButtonElement | null>(null)
const liveStatus = ref('')
let previouslyFocused: HTMLElement | null = null
const unreadCount = ref(0)
const notifications = ref<NotificationItem[]>([])
const { locale, t, toggleLocale } = useI18n()
const { tenant, shortName } = useTenant()
const title = computed(() => t(String(route.meta.titleKey ?? 'page.dashboard')))
const officeName = computed(() => locale.value === 'en-US'
  ? user.value?.officeNameEn
  : user.value?.officeNameZh)

interface NotificationItem {
  id: string
  notificationType: string
  title: string
  content: string
  resourceType?: string
  resourceId?: string
  actionUrl?: string
  priority: string
  status: 'UNREAD' | 'READ' | 'ARCHIVED'
  readAt?: string
  createdAt: string
}

watch(() => route.path, () => {
  compact.value = false
})
watch(notificationOpen, async (open) => {
  if (open) {
    previouslyFocused = document.activeElement instanceof HTMLElement
      ? document.activeElement
      : null
    await nextTick()
    notificationClose.value?.focus()
    return
  }
  previouslyFocused?.focus()
  previouslyFocused = null
})
watch([title, shortName], () => {
  document.title = `${title.value} · ${shortName.value}`
}, { immediate: true })

const navGroups = [
  {
    titleKey: 'nav.daily',
    items: [
      { to: '/', labelKey: 'nav.dashboard', icon: LayoutDashboard },
      { to: '/matters', labelKey: 'nav.matters', icon: BriefcaseBusiness },
      { to: '/deadlines', labelKey: 'nav.deadlines', icon: CalendarClock },
      { to: '/approvals', labelKey: 'nav.approvals', icon: FileCheck2 },
      { to: '/tasks', labelKey: 'nav.tasks', icon: ListTodo },
      { to: '/leave', labelKey: 'nav.leave', icon: CalendarDays },
      { to: '/expenses', labelKey: 'nav.expenses', icon: ReceiptText },
      { to: '/finance', labelKey: 'nav.finance', icon: Landmark },
      { to: '/meetings', labelKey: 'nav.meetings', icon: Building2 },
    ],
  },
  {
    titleKey: 'nav.organization',
    items: [
      { to: '/announcements', labelKey: 'nav.announcements', icon: Megaphone },
      { to: '/directory', labelKey: 'nav.directory', icon: ContactRound },
      { to: '/offices', labelKey: 'nav.offices', icon: Globe2 },
      { to: '/admin', labelKey: 'nav.admin', icon: Settings2 },
    ],
  },
  {
    titleKey: 'nav.assets',
    items: [
      { to: '/parties', labelKey: 'nav.parties', icon: UsersRound },
      { to: '/conflicts', labelKey: 'nav.conflicts', icon: SearchCheck },
      { to: '/contracts', labelKey: 'nav.contracts', icon: FileStack },
      { to: '/documents', labelKey: 'nav.documents', icon: FileStack },
      { to: '/document-governance', labelKey: 'nav.documentGovernance', icon: LibraryBig },
      { to: '/archives', labelKey: 'nav.archives', icon: Archive },
    ],
  },
]

async function loadCurrentUser() {
  try {
    user.value = (await http.get<CurrentUser>('/me')).data
    await loadUnreadCount()
  } catch {
    user.value = null
  }
}

async function loadUnreadCount() {
  try {
    unreadCount.value = (await http.get<{ count: number }>('/notifications/unread-count')).data.count
  } catch {
    unreadCount.value = 0
  }
}

async function openNotifications() {
  notificationOpen.value = true
  notificationLoading.value = true
  try {
    const response = await http.get<{ items: NotificationItem[] }>('/notifications', {
      params: { page: 1, size: 30 },
    })
    notifications.value = response.data.items
    await loadUnreadCount()
    liveStatus.value = locale.value === 'en-US'
      ? `${notifications.value.length} notifications loaded`
      : `已加载 ${notifications.value.length} 条通知`
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (
      locale.value === 'en-US' ? 'Failed to load notifications' : '通知加载失败'
    ))
  } finally {
    notificationLoading.value = false
  }
}

function handleNotificationKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    notificationOpen.value = false
    return
  }
  if (event.key !== 'Tab' || !notificationDrawer.value) return
  const focusable = Array.from(notificationDrawer.value.querySelectorAll<HTMLElement>(
    'button:not([disabled]), a[href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  ))
  const first = focusable.at(0)
  const last = focusable.at(-1)
  if (!first || !last) return
  if (event.shiftKey && document.activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && document.activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

function safeNotificationTarget(actionUrl?: string) {
  if (!actionUrl || !actionUrl.startsWith('/') || actionUrl.startsWith('//')) return null
  const parsed = new URL(actionUrl, window.location.origin)
  if (parsed.origin !== window.location.origin) return null
  if (parsed.pathname === '/workflows') return `/approvals${parsed.search}`
  const allowed = [
    '/matters', '/approvals', '/tasks', '/announcements', '/deadlines',
    '/contracts', '/documents', '/archives', '/expenses', '/leave', '/meetings',
    '/document-governance', '/finance', '/admin',
  ]
  return allowed.some((prefix) => parsed.pathname === prefix || parsed.pathname.startsWith(`${prefix}/`))
    ? `${parsed.pathname}${parsed.search}${parsed.hash}`
    : null
}

async function openNotification(item: NotificationItem) {
  try {
    if (item.status === 'UNREAD') {
      await http.patch(`/notifications/${item.id}/read`)
      item.status = 'READ'
      unreadCount.value = Math.max(0, unreadCount.value - 1)
    }
    const target = safeNotificationTarget(item.actionUrl)
    if (!target) {
      ElMessage.warning(locale.value === 'en-US'
        ? 'The target is unavailable or the link is not trusted.'
        : '目标已不可用，或通知链接不在可信范围内。')
      return
    }
    if (item.resourceType === 'MATTER' && item.resourceId) {
      try {
        await http.get(`/matters/${item.resourceId}`)
      } catch {
        ElMessage.warning(locale.value === 'en-US'
          ? 'You no longer have access to this matter.'
          : '你已无权访问该案件，通知仍保留供审计。')
        return
      }
    }
    notificationOpen.value = false
    await router.push(target)
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (
      locale.value === 'en-US' ? 'Could not open notification' : '无法打开通知'
    ))
  }
}

watch(
  () => [route.name, route.meta.standalone] as const,
  async ([routeName, standalone]) => {
    if (!routeName || standalone === true) {
      user.value = null
      return
    }
    await loadCurrentUser()
  },
  { immediate: true },
)

async function logout() {
  try {
    await http.post('/auth/logout')
  } catch {
    // 本地状态仍应清除，避免失效会话把用户留在业务页面。
  }
  sessionStorage.removeItem('law_oa_demo_entered')
  clearBrowserSessionAuthentication()
  user.value = null
  notificationOpen.value = false
  unreadCount.value = 0
  notifications.value = []
  await router.replace('/login')
}
</script>

<template>
  <RouterView v-if="route.meta.standalone" />
  <div v-if="!route.meta.standalone" class="app-shell" :class="{ 'is-compact': compact }">
    <a class="skip-link" href="#main-content">{{ locale === 'en-US' ? 'Skip to main content' : '跳到主要内容' }}</a>
    <aside id="primary-navigation" class="sidebar">
      <div class="brand">
        <div class="brand-mark"><Scale :size="24" /></div>
        <div class="brand-copy">
          <strong>{{ locale === 'en-US' ? tenant.shortNameEn : tenant.shortNameZh }}</strong>
          <span>{{ locale === 'en-US' ? 'GLOBAL LEGAL' : tenant.shortNameEn }}</span>
        </div>
      </div>

      <nav class="nav" :aria-label="locale === 'en-US' ? 'Primary navigation' : '主导航'">
        <section v-for="group in navGroups" :key="group.titleKey" class="nav-group">
          <div class="nav-heading">{{ t(group.titleKey) }}</div>
          <RouterLink
            v-for="item in group.items"
            :key="item.to"
            :to="item.to"
            class="nav-item"
          >
            <component :is="item.icon" :size="18" stroke-width="1.8" />
            <span>{{ t(item.labelKey) }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="sidebar-foot">
        <div class="security-seal">
          <Gavel :size="17" />
          <div>
            <strong>{{ t('shell.security') }}</strong>
            <span>{{ t('shell.securityDetail') }}</span>
          </div>
        </div>
      </div>
    </aside>

    <main id="main-content" class="main" tabindex="-1">
      <header class="topbar">
        <div class="topbar-left">
          <button
            class="icon-button mobile-menu"
            :aria-label="t('shell.menu')"
            aria-controls="primary-navigation"
            :aria-expanded="compact"
            @click="compact = !compact"
          >
            <Menu :size="20" />
          </button>
          <div>
            <span class="breadcrumb">{{ shortName }} / {{ title }}</span>
            <h1>{{ title }}</h1>
          </div>
        </div>
        <div class="topbar-actions">
          <button
            class="language-switch"
            type="button"
            :aria-label="t('shell.language')"
            :title="t('shell.language')"
            @click="toggleLocale"
          >
            <Languages :size="17" />
            <span>{{ locale === 'zh-CN' ? 'EN' : '中文' }}</span>
          </button>
          <button class="icon-button notification-button" :aria-label="t('shell.notifications')" @click="openNotifications">
            <Bell :size="19" />
            <span v-if="unreadCount" class="notification-badge">{{ unreadCount > 99 ? '99+' : unreadCount }}</span>
          </button>
          <div class="profile">
            <span class="avatar">{{ user?.displayName?.slice(0, 1) ?? (locale === 'en-US' ? 'A' : '管') }}</span>
            <div>
              <strong>{{ user?.displayName ?? t('shell.connecting') }}</strong>
              <span>{{ officeName || t('shell.session') }}</span>
            </div>
          </div>
          <button class="icon-button" :aria-label="t('shell.logout')" :title="t('shell.logout')" @click="logout">
            <LogOut :size="18" />
          </button>
        </div>
      </header>

      <div class="content">
        <RouterView />
      </div>
    </main>
    <div class="sr-only" aria-live="polite" aria-atomic="true">{{ liveStatus }}</div>
    <Transition name="drawer">
      <div v-if="notificationOpen" class="notification-layer" @click.self="notificationOpen = false">
        <aside
          ref="notificationDrawer"
          class="notification-drawer"
          role="dialog"
          aria-modal="true"
          :aria-label="t('shell.notifications')"
          @keydown="handleNotificationKeydown"
        >
          <header>
            <div><span class="eyebrow">INBOX</span><h2>{{ locale === 'en-US' ? 'Notifications' : '通知中心' }}</h2></div>
            <button ref="notificationClose" class="icon-button" :aria-label="locale === 'en-US' ? 'Close notifications' : '关闭通知'" @click="notificationOpen = false"><X :size="18" /></button>
          </header>
          <div v-if="notificationLoading" class="empty-state">{{ locale === 'en-US' ? 'Loading…' : '正在加载通知…' }}</div>
          <div v-else-if="notifications.length" class="notification-list">
            <button
              v-for="item in notifications"
              :key="item.id"
              class="notification-item"
              :class="{ unread: item.status === 'UNREAD' }"
              @click="openNotification(item)"
            >
              <span class="notification-dot" />
              <span class="notification-copy">
                <strong>{{ item.title }}</strong>
                <span>{{ item.content }}</span>
                <small>{{ new Date(item.createdAt).toLocaleString(locale) }} · {{ formatLegalCode(item.priority, locale) }}</small>
              </span>
            </button>
          </div>
          <div v-else class="empty-state">
            <Bell :size="30" />
            <strong>{{ locale === 'en-US' ? 'No notifications' : '暂无通知' }}</strong>
          </div>
        </aside>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.notification-button { position: relative; }
.notification-badge { position: absolute; top: -6px; right: -7px; min-width: 18px; height: 18px; display: grid; place-items: center; padding: 0 4px; border: 2px solid var(--paper-light); border-radius: 10px; background: var(--oxblood); color: white; font: 700 8px/1 sans-serif; }
.notification-layer { position: fixed; inset: 0; z-index: 80; background: rgba(20,35,30,.34); }
.notification-drawer { position: absolute; inset: 0 0 0 auto; width: min(440px, 96vw); display: flex; flex-direction: column; background: var(--paper-light); box-shadow: -18px 0 50px rgba(20,35,30,.18); }
.notification-drawer > header { min-height: 92px; display: flex; align-items: center; justify-content: space-between; padding: 20px 24px; border-bottom: 1px solid var(--line); }
.notification-drawer h2 { margin: 0; font: 700 22px "Songti SC", serif; }
.notification-list { overflow-y: auto; }
.notification-item { width: 100%; display: grid; grid-template-columns: 8px 1fr; gap: 12px; padding: 18px 22px; border: 0; border-bottom: 1px solid var(--line); background: transparent; color: var(--ink); text-align: left; cursor: pointer; }
.notification-item:hover { background: white; }
.notification-item.unread { background: #f6f1e5; }
.notification-dot { width: 7px; height: 7px; margin-top: 5px; border-radius: 50%; background: transparent; }
.notification-item.unread .notification-dot { background: var(--brass); }
.notification-copy strong, .notification-copy span, .notification-copy small { display: block; }
.notification-copy strong { font-size: 13px; }
.notification-copy > span { margin-top: 6px; color: var(--ink-soft); font-size: 10px; line-height: 1.65; }
.notification-copy small { margin-top: 8px; color: var(--muted); font-size: 8px; }
.drawer-enter-active, .drawer-leave-active { transition: opacity .18s ease; }
.drawer-enter-active .notification-drawer, .drawer-leave-active .notification-drawer { transition: transform .18s ease; }
.drawer-enter-from, .drawer-leave-to { opacity: 0; }
.drawer-enter-from .notification-drawer, .drawer-leave-to .notification-drawer { transform: translateX(100%); }
</style>

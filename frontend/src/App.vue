<script setup lang="ts">
import { computed, ref, watch } from 'vue'
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
} from '@lucide/vue'
import { http } from '@/api/http'
import type { CurrentUser } from '@/api/types'
import { useI18n } from '@/i18n'
import { useTenant } from '@/tenant'

const route = useRoute()
const router = useRouter()
const user = ref<CurrentUser | null>(null)
const compact = ref(false)
const { locale, t, toggleLocale } = useI18n()
const { tenant, shortName } = useTenant()
const title = computed(() => t(String(route.meta.titleKey ?? 'page.dashboard')))
const officeName = computed(() => locale.value === 'en-US'
  ? user.value?.officeNameEn
  : user.value?.officeNameZh)

watch(() => route.path, () => {
  compact.value = false
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
      { to: '/meetings', labelKey: 'nav.meetings', icon: Building2 },
    ],
  },
  {
    titleKey: 'nav.organization',
    items: [
      { to: '/announcements', labelKey: 'nav.announcements', icon: Megaphone },
      { to: '/directory', labelKey: 'nav.directory', icon: ContactRound },
      { to: '/offices', labelKey: 'nav.offices', icon: Globe2 },
    ],
  },
  {
    titleKey: 'nav.assets',
    items: [
      { to: '/parties', labelKey: 'nav.parties', icon: UsersRound },
      { to: '/conflicts', labelKey: 'nav.conflicts', icon: SearchCheck },
      { to: '/contracts', labelKey: 'nav.contracts', icon: FileStack },
      { to: '/documents', labelKey: 'nav.documents', icon: FileStack },
      { to: '/archives', labelKey: 'nav.archives', icon: Archive },
    ],
  },
]

async function loadCurrentUser() {
  try {
    user.value = (await http.get<CurrentUser>('/me')).data
  } catch {
    user.value = null
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
  await router.replace('/login')
}
</script>

<template>
  <RouterView v-if="route.meta.standalone" />
  <div v-if="!route.meta.standalone" class="app-shell" :class="{ 'is-compact': compact }">
    <aside class="sidebar">
      <div class="brand">
        <div class="brand-mark"><Scale :size="24" /></div>
        <div class="brand-copy">
          <strong>{{ locale === 'en-US' ? tenant.shortNameEn : tenant.shortNameZh }}</strong>
          <span>{{ locale === 'en-US' ? 'GLOBAL LEGAL' : tenant.shortNameEn }}</span>
        </div>
      </div>

      <nav class="nav">
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

    <main class="main">
      <header class="topbar">
        <div class="topbar-left">
          <button class="icon-button mobile-menu" :aria-label="t('shell.menu')" @click="compact = !compact">
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
          <button class="icon-button" :aria-label="t('shell.notifications')"><Bell :size="19" /></button>
          <div class="profile">
            <span class="avatar">{{ user?.displayName?.slice(0, 1) ?? '管' }}</span>
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
  </div>
</template>

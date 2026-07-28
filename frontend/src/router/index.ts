import { createRouter, createWebHistory } from 'vue-router'
import { translate, useI18n } from '@/i18n'
import { useTenant } from '@/tenant'
const LoginView = () => import('@/views/LoginView.vue')
const DashboardView = () => import('@/views/DashboardView.vue')
const MatterListView = () => import('@/views/MatterListView.vue')
const PartyListView = () => import('@/views/PartyListView.vue')
const ConflictView = () => import('@/views/ConflictView.vue')
const ContractListView = () => import('@/views/ContractListView.vue')
const DeadlineListView = () => import('@/views/DeadlineListView.vue')
const DocumentListView = () => import('@/views/DocumentListView.vue')
const ApprovalCenterView = () => import('@/views/ApprovalCenterView.vue')
const ArchiveListView = () => import('@/views/ArchiveListView.vue')
const DingTalkCallbackView = () => import('@/views/DingTalkCallbackView.vue')
const AnnouncementView = () => import('@/views/AnnouncementView.vue')
const WorkTaskView = () => import('@/views/WorkTaskView.vue')
const LeaveView = () => import('@/views/LeaveView.vue')
const ExpenseView = () => import('@/views/ExpenseView.vue')
const MeetingView = () => import('@/views/MeetingView.vue')
const DirectoryView = () => import('@/views/DirectoryView.vue')
const OfficesView = () => import('@/views/OfficesView.vue')

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: LoginView,
      meta: { titleKey: 'page.login', standalone: true },
    },
    {
      path: '/auth/dingtalk/callback',
      name: 'dingtalk-callback',
      component: DingTalkCallbackView,
      meta: { titleKey: 'page.dingtalk', standalone: true },
    },
    { path: '/', name: 'dashboard', component: DashboardView, meta: { titleKey: 'page.dashboard' } },
    { path: '/matters', name: 'matters', component: MatterListView, meta: { titleKey: 'page.matters' } },
    { path: '/parties', name: 'parties', component: PartyListView, meta: { titleKey: 'page.parties' } },
    { path: '/conflicts', name: 'conflicts', component: ConflictView, meta: { titleKey: 'page.conflicts' } },
    {
      path: '/contracts',
      name: 'contracts',
      component: ContractListView,
      meta: { titleKey: 'page.contracts' },
    },
    {
      path: '/documents',
      name: 'documents',
      component: DocumentListView,
      meta: { titleKey: 'page.documents' },
    },
    {
      path: '/deadlines',
      name: 'deadlines',
      component: DeadlineListView,
      meta: { titleKey: 'page.deadlines' },
    },
    {
      path: '/approvals',
      name: 'approvals',
      component: ApprovalCenterView,
      meta: { titleKey: 'page.approvals' },
    },
    {
      path: '/archives',
      name: 'archives',
      component: ArchiveListView,
      meta: { titleKey: 'page.archives' },
    },
    { path: '/announcements', name: 'announcements', component: AnnouncementView, meta: { titleKey: 'page.announcements' } },
    { path: '/tasks', name: 'tasks', component: WorkTaskView, meta: { titleKey: 'page.tasks' } },
    { path: '/leave', name: 'leave', component: LeaveView, meta: { titleKey: 'page.leave' } },
    { path: '/expenses', name: 'expenses', component: ExpenseView, meta: { titleKey: 'page.expenses' } },
    { path: '/meetings', name: 'meetings', component: MeetingView, meta: { titleKey: 'page.meetings' } },
    { path: '/directory', name: 'directory', component: DirectoryView, meta: { titleKey: 'page.directory' } },
    { path: '/offices', name: 'offices', component: OfficesView, meta: { titleKey: 'page.offices' } },
  ],
})

let authMode: 'dev' | 'dingtalk' | null = null
let browserSessionAuthenticated: boolean | null = null

export function markBrowserSessionAuthenticated() {
  browserSessionAuthenticated = true
}

export function clearBrowserSessionAuthentication() {
  browserSessionAuthenticated = false
}

async function hasBrowserSession() {
  if (browserSessionAuthenticated === true) {
    return true
  }
  try {
    const response = await fetch('/api/me', { credentials: 'same-origin' })
    browserSessionAuthenticated = response.ok
  } catch {
    browserSessionAuthenticated = false
  }
  return browserSessionAuthenticated
}

router.beforeEach(async (to) => {
  if (to.name === 'login' || to.name === 'dingtalk-callback') {
    return true
  }
  if (authMode === null) {
    try {
      const response = await fetch('/api/auth/config')
      const payload = await response.json() as { authMode?: 'dev' | 'dingtalk' }
      authMode = payload.authMode ?? 'dingtalk'
    } catch {
      authMode = 'dingtalk'
    }
  }
  const authenticated = authMode === 'dev'
    ? sessionStorage.getItem('law_oa_demo_entered') === 'true'
    : await hasBrowserSession()
  if (!authenticated) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const { locale } = useI18n()
  const { tenant } = useTenant()
  const firmName = locale.value === 'en-US' ? tenant.shortNameEn : tenant.shortNameZh
  document.title = `${translate(String(to.meta.titleKey ?? 'page.dashboard'))} · ${firmName}`
})

export default router

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Activity,
  Building2,
  CheckCircle2,
  ChevronRight,
  CircleAlert,
  FileClock,
  GitBranch,
  KeyRound,
  LockKeyhole,
  Pencil,
  PlugZap,
  Save,
  Search,
  Settings2,
  ShieldCheck,
  UserCog,
  UsersRound,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Office } from '@/api/types'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

type AdminTab = 'people' | 'roles' | 'system' | 'audit'

interface Settings {
  organizationId: string
  brandNameZh: string
  brandNameEn: string
  shortNameZh: string
  shortNameEn: string
  defaultLocale: string
  supportedLocales: string[]
  primaryTimezone: string
  baseCurrency: string
  websiteUrl?: string
  updatedAt: string
}

interface OfficeAssignment {
  officeId: string
  primary: boolean
  accessLevel: 'MEMBER' | 'MANAGER'
  validUntil?: string
}

interface User {
  id: string
  username: string
  displayName: string
  email?: string
  status: string
  roleCodes: string[]
  offices: OfficeAssignment[]
  updatedAt: string
}

interface Role {
  code: string
  name: string
  userCount: number
  permissions: string[]
  systemRole: boolean
}

interface Permission {
  code: string
  name: string
  resourceType: string
  action: string
  assignedRoleCount: number
}

interface Integration {
  integrationType: string
  providerCode: string
  configurationStatus: string
  healthStatus: string
  message: string
  checkedAt: string
}

interface WorkflowRule {
  id: string
  businessType: string
  assigneeRoleCode?: string
  reminderMinutes: number
  escalationMinutes: number
  priority: number
  enabled: boolean
}

interface AuditItem {
  id: string
  actorName?: string
  action: string
  resourceType: string
  result: string
  reason?: string
  ipAddress?: string
  occurredAt: string
}

interface EditableOffice {
  officeId: string
  enabled: boolean
  primary: boolean
  accessLevel: 'MEMBER' | 'MANAGER'
  validUntil: string
}

const { locale, t } = useI18n()
const loading = ref(true)
const activeTab = ref<AdminTab>('people')
const currentUser = ref<CurrentUser | null>(null)
const settings = ref<Settings | null>(null)
const users = ref<User[]>([])
const roles = ref<Role[]>([])
const permissions = ref<Permission[]>([])
const offices = ref<Office[]>([])
const integrations = ref<Integration[]>([])
const rules = ref<WorkflowRule[]>([])
const auditItems = ref<AuditItem[]>([])
const auditTotal = ref(0)
const userQuery = ref('')
const userDialog = ref(false)
const roleDialog = ref(false)
const savingUser = ref(false)
const savingRole = ref(false)
const savingSettings = ref(false)
const selectedUser = ref<User | null>(null)
const selectedRole = ref<Role | null>(null)
const selectedPermissionCodes = ref<string[]>([])
const editableOffices = ref<EditableOffice[]>([])

const settingsForm = reactive({
  brandNameZh: '',
  brandNameEn: '',
  shortNameZh: '',
  shortNameEn: '',
  defaultLocale: 'zh-CN',
  primaryTimezone: '',
  baseCurrency: '',
  websiteUrl: '',
})

const tabs = computed(() => [
  { key: 'people' as const, label: t('admin.people'), icon: UsersRound, count: users.value.length },
  { key: 'roles' as const, label: t('admin.roles'), icon: KeyRound, count: roles.value.length },
  { key: 'system' as const, label: t('admin.system'), icon: Settings2, count: integrations.value.length },
  { key: 'audit' as const, label: t('admin.audit'), icon: FileClock, count: auditTotal.value },
])

const canManageUsers = computed(() => currentUser.value?.permissions.includes('USER_ACCESS_MANAGE'))
const canManageRoles = computed(() => currentUser.value?.permissions.includes('ROLE_PERMISSION_MANAGE'))
const canManageSettings = computed(() => currentUser.value?.permissions.includes('TENANT_BRAND_MANAGE'))
const canViewAudit = computed(() => currentUser.value?.permissions.includes('AUDIT_VIEW'))
const activeUsers = computed(() => users.value.filter((item) => item.status === 'ACTIVE').length)
const healthyIntegrations = computed(() => integrations.value
  .filter((item) => item.healthStatus === 'UP').length)

const permissionGroups = computed(() => {
  const groups = new Map<string, Permission[]>()
  for (const permission of permissions.value) {
    const group = groups.get(permission.resourceType) ?? []
    group.push(permission)
    groups.set(permission.resourceType, group)
  }
  return Array.from(groups, ([resourceType, items]) => ({ resourceType, items }))
})

function localizedOffice(office: Office) {
  return locale.value === 'en-US' ? office.nameEn : office.nameZh
}

function permissionLabel(permission: Permission) {
  if (locale.value === 'zh-CN') return permission.name
  return permission.code
    .toLowerCase()
    .split('_')
    .map((word) => word.charAt(0).toUpperCase() + word.slice(1))
    .join(' ')
}

function healthLabel(status: string) {
  if (status === 'UP') return t('p1.up')
  if (status === 'DOWN') return t('p1.down')
  return t('p1.unknown')
}

function applySettings(value: Settings) {
  settings.value = value
  settingsForm.brandNameZh = value.brandNameZh
  settingsForm.brandNameEn = value.brandNameEn
  settingsForm.shortNameZh = value.shortNameZh
  settingsForm.shortNameEn = value.shortNameEn
  settingsForm.defaultLocale = value.defaultLocale
  settingsForm.primaryTimezone = value.primaryTimezone
  settingsForm.baseCurrency = value.baseCurrency
  settingsForm.websiteUrl = value.websiteUrl ?? ''
}

async function loadUsers() {
  const response = await http.get<{ items: User[] }>('/admin/users', {
    params: { query: userQuery.value.trim() || undefined, page: 1, size: 100 },
  })
  users.value = response.data.items
}

async function load() {
  loading.value = true
  try {
    currentUser.value = (await http.get<CurrentUser>('/me')).data
    const [
      settingsResult,
      roleResult,
      permissionResult,
      officeResult,
      integrationResult,
      ruleResult,
    ] = await Promise.all([
      http.get<Settings>('/admin/settings'),
      http.get<Role[]>('/admin/roles'),
      http.get<Permission[]>('/admin/permissions'),
      http.get<Office[]>('/offices'),
      http.get<Integration[]>('/admin/integrations'),
      http.get<WorkflowRule[]>('/admin/workflow-rules'),
      loadUsers(),
    ])
    applySettings(settingsResult.data)
    roles.value = roleResult.data
    permissions.value = permissionResult.data
    offices.value = officeResult.data
    integrations.value = integrationResult.data
    rules.value = ruleResult.data
    if (canViewAudit.value) {
      const auditResult = await http.get<{ items: AuditItem[]; total: number }>('/audit-logs', {
        params: { page: 1, size: 50 },
      })
      auditItems.value = auditResult.data.items
      auditTotal.value = auditResult.data.total
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    loading.value = false
  }
}

function openUser(item: User) {
  selectedUser.value = {
    ...item,
    roleCodes: [...item.roleCodes],
    offices: item.offices.map((office) => ({ ...office })),
  }
  editableOffices.value = offices.value.map((office) => {
    const assignment = item.offices.find((entry) => entry.officeId === office.id)
    return {
      officeId: office.id,
      enabled: Boolean(assignment),
      primary: assignment?.primary ?? false,
      accessLevel: assignment?.accessLevel ?? 'MEMBER',
      validUntil: assignment?.validUntil?.slice(0, 16) ?? '',
    }
  })
  userDialog.value = true
}

function selectPrimaryOffice(officeId: string) {
  for (const item of editableOffices.value) {
    item.primary = item.officeId === officeId
    if (item.primary) {
      item.enabled = true
      item.validUntil = ''
    }
  }
}

async function saveUser() {
  if (!selectedUser.value) return
  if (!selectedUser.value.roleCodes.length) {
    ElMessage.warning(t('admin.roleRequired'))
    return
  }
  savingUser.value = true
  try {
    const response = await http.put<User>(`/admin/users/${selectedUser.value.id}/access`, {
      status: selectedUser.value.status,
      roleCodes: selectedUser.value.roleCodes,
      offices: editableOffices.value
        .filter((office) => office.enabled)
        .map((office) => ({
          officeId: office.officeId,
          primary: office.primary,
          accessLevel: office.accessLevel,
          validUntil: office.validUntil
            ? new Date(office.validUntil).toISOString()
            : null,
        })),
    })
    const index = users.value.findIndex((item) => item.id === response.data.id)
    if (index >= 0) users.value[index] = response.data
    userDialog.value = false
    ElMessage.success(t('admin.userSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('admin.userSaveFailed'))
  } finally {
    savingUser.value = false
  }
}

function openRole(item: Role) {
  selectedRole.value = item
  selectedPermissionCodes.value = [...item.permissions]
  roleDialog.value = true
}

async function saveRolePermissions() {
  if (!selectedRole.value) return
  savingRole.value = true
  try {
    const response = await http.put<Role>(
      `/admin/roles/${selectedRole.value.code}/permissions`,
      { permissionCodes: selectedPermissionCodes.value },
    )
    const index = roles.value.findIndex((item) => item.code === response.data.code)
    if (index >= 0) roles.value[index] = response.data
    roleDialog.value = false
    ElMessage.success(t('admin.permissionsSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('admin.permissionsSaveFailed'))
  } finally {
    savingRole.value = false
  }
}

async function saveSettings() {
  savingSettings.value = true
  try {
    const response = await http.put<Settings>('/admin/settings', {
      ...settingsForm,
      baseCurrency: settingsForm.baseCurrency.toUpperCase(),
      websiteUrl: settingsForm.websiteUrl || null,
    })
    applySettings(response.data)
    ElMessage.success(t('admin.settingsSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('admin.settingsSaveFailed'))
  } finally {
    savingSettings.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page admin-page">
    <header class="page-intro admin-intro">
      <div>
        <span class="eyebrow">{{ t('admin.kicker') }}</span>
        <h2>{{ t('page.admin') }}</h2>
        <p>{{ t('admin.headline') }}</p>
      </div>
      <div class="admin-assurance">
        <ShieldCheck :size="22" />
        <span><strong>{{ t('admin.leastPrivilege') }}</strong><small>{{ t('admin.auditedChanges') }}</small></span>
      </div>
    </header>

    <div v-if="loading" class="panel empty-state">
      <Activity class="spin" :size="28" />
      <strong>{{ t('admin.loading') }}</strong>
    </div>

    <template v-else>
      <section class="admin-summary" :aria-label="t('admin.summary')">
        <article>
          <span class="summary-icon"><UsersRound :size="19" /></span>
          <span><small>{{ t('admin.activeUsers') }}</small><strong>{{ activeUsers }}</strong></span>
        </article>
        <article>
          <span class="summary-icon"><KeyRound :size="19" /></span>
          <span><small>{{ t('admin.configuredRoles') }}</small><strong>{{ roles.length }}</strong></span>
        </article>
        <article>
          <span class="summary-icon"><LockKeyhole :size="19" /></span>
          <span><small>{{ t('admin.availablePermissions') }}</small><strong>{{ permissions.length }}</strong></span>
        </article>
        <article>
          <span class="summary-icon"><PlugZap :size="19" /></span>
          <span><small>{{ t('admin.healthyIntegrations') }}</small><strong>{{ healthyIntegrations }}/{{ integrations.length }}</strong></span>
        </article>
      </section>

      <nav class="admin-tabs" :aria-label="t('admin.sections')">
        <button
          v-for="tab in tabs"
          :key="tab.key"
          type="button"
          :class="{ active: activeTab === tab.key }"
          :aria-current="activeTab === tab.key ? 'page' : undefined"
          @click="activeTab = tab.key"
        >
          <component :is="tab.icon" :size="17" />
          <span>{{ tab.label }}</span>
          <b>{{ tab.count }}</b>
        </button>
      </nav>

      <section v-if="activeTab === 'people'" class="panel admin-workspace">
        <header class="workspace-heading">
          <div>
            <span class="eyebrow">{{ t('admin.identityAccess') }}</span>
            <h3>{{ t('admin.peopleTitle') }}</h3>
            <p>{{ t('admin.peopleHint') }}</p>
          </div>
          <form class="admin-search" @submit.prevent="loadUsers">
            <Search :size="16" />
            <input v-model="userQuery" :placeholder="t('admin.searchUsers')" />
            <button type="submit">{{ t('governance.searchAction') }}</button>
          </form>
        </header>

        <div class="admin-table-wrap">
          <table>
            <thead>
              <tr>
                <th>{{ t('admin.user') }}</th>
                <th>{{ t('p1.status') }}</th>
                <th>{{ t('p1.roles') }}</th>
                <th>{{ t('admin.officeScope') }}</th>
                <th><span class="sr-only">{{ t('admin.actions') }}</span></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in users" :key="item.id">
                <td>
                  <div class="user-cell">
                    <span class="user-avatar">{{ item.displayName.slice(0, 1) }}</span>
                    <span><strong>{{ item.displayName }}</strong><small>{{ item.username }} · {{ item.email || t('admin.noEmail') }}</small></span>
                  </div>
                </td>
                <td><span class="status-pill" :class="item.status.toLowerCase()">{{ formatLegalCode(item.status, locale) }}</span></td>
                <td><div class="tag-list"><span v-for="role in item.roleCodes" :key="role">{{ formatLegalCode(role, locale) }}</span></div></td>
                <td>
                  <span class="scope-copy">
                    {{ item.offices.length ? t('admin.officeCount').replace('{count}', String(item.offices.length)) : t('admin.noOffice') }}
                  </span>
                </td>
                <td>
                  <button
                    v-if="canManageUsers"
                    class="table-action"
                    type="button"
                    :aria-label="t('admin.editAccess')"
                    :title="t('admin.editAccess')"
                    @click="openUser(item)"
                  >
                    <Pencil :size="14" />
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <section v-else-if="activeTab === 'roles'" class="panel admin-workspace">
        <header class="workspace-heading">
          <div>
            <span class="eyebrow">{{ t('admin.authorization') }}</span>
            <h3>{{ t('admin.rolesTitle') }}</h3>
            <p>{{ t('admin.rolesHint') }}</p>
          </div>
        </header>
        <div class="role-list">
          <button v-for="role in roles" :key="role.code" type="button" @click="openRole(role)">
            <span class="role-icon"><KeyRound :size="17" /></span>
            <span class="role-copy">
              <strong>{{ formatLegalCode(role.code, locale) }}</strong>
              <small>{{ role.code }} · {{ t('admin.userCount').replace('{count}', String(role.userCount)) }}</small>
            </span>
            <span class="role-permission-count">{{ t('admin.permissionCount').replace('{count}', String(role.permissions.length)) }}</span>
            <ChevronRight :size="16" />
          </button>
        </div>
      </section>

      <section v-else-if="activeTab === 'system'" class="system-grid">
        <form class="panel settings-panel" @submit.prevent="saveSettings">
          <header class="workspace-heading compact-heading">
            <div>
              <span class="eyebrow">{{ t('admin.organization') }}</span>
              <h3>{{ t('p1.settings') }}</h3>
              <p>{{ t('admin.settingsHint') }}</p>
            </div>
          </header>
          <div class="settings-form">
            <label><span>{{ t('admin.brandZh') }}</span><input v-model="settingsForm.brandNameZh" required maxlength="200" /></label>
            <label><span>{{ t('admin.brandEn') }}</span><input v-model="settingsForm.brandNameEn" required maxlength="200" /></label>
            <label><span>{{ t('admin.shortZh') }}</span><input v-model="settingsForm.shortNameZh" required maxlength="80" /></label>
            <label><span>{{ t('admin.shortEn') }}</span><input v-model="settingsForm.shortNameEn" required maxlength="80" /></label>
            <label><span>{{ t('admin.defaultLocale') }}</span><select v-model="settingsForm.defaultLocale"><option value="zh-CN">{{ t('admin.localeZh') }}</option><option value="en-US">{{ t('admin.localeEn') }}</option></select></label>
            <label><span>{{ t('admin.timezone') }}</span><input v-model="settingsForm.primaryTimezone" required maxlength="80" /></label>
            <label><span>{{ t('admin.currency') }}</span><input v-model="settingsForm.baseCurrency" required maxlength="3" /></label>
            <label><span>{{ t('admin.website') }}</span><input v-model="settingsForm.websiteUrl" type="url" maxlength="500" /></label>
          </div>
          <footer>
            <span>{{ settings ? `${t('admin.lastUpdated')} ${new Date(settings.updatedAt).toLocaleString(locale)}` : '' }}</span>
            <button v-if="canManageSettings" class="primary-action" type="submit" :disabled="savingSettings">
              <Save :size="16" /> {{ savingSettings ? t('common.saving') : t('admin.saveSettings') }}
            </button>
          </footer>
        </form>

        <section class="panel integrations-panel">
          <header class="workspace-heading compact-heading">
            <div><span class="eyebrow">{{ t('admin.serviceHealth') }}</span><h3>{{ t('p1.integrations') }}</h3><p>{{ t('admin.integrationsHint') }}</p></div>
          </header>
          <div class="integration-list">
            <article v-for="item in integrations" :key="item.integrationType">
              <span class="health-icon" :class="item.healthStatus.toLowerCase()">
                <CheckCircle2 v-if="item.healthStatus === 'UP'" :size="17" />
                <CircleAlert v-else :size="17" />
              </span>
              <span><strong>{{ formatLegalCode(item.integrationType, locale) }}</strong><small>{{ item.providerCode }} · {{ item.message }}</small></span>
              <span class="health-copy"><strong>{{ healthLabel(item.healthStatus) }}</strong><small>{{ item.configurationStatus }}</small></span>
            </article>
          </div>
        </section>

        <section class="panel rules-panel">
          <header class="workspace-heading compact-heading">
            <div><span class="eyebrow">{{ t('admin.delivery') }}</span><h3>{{ t('p1.workflowRules') }}</h3><p>{{ t('admin.workflowHint') }}</p></div>
          </header>
          <div class="rule-list">
            <article v-for="item in rules" :key="item.id">
              <span class="rule-icon"><GitBranch :size="17" /></span>
              <span><strong>{{ formatLegalCode(item.businessType, locale) }}</strong><small>{{ item.assigneeRoleCode ? formatLegalCode(item.assigneeRoleCode, locale) : t('p1.unknown') }}</small></span>
              <span><strong>P{{ item.priority }}</strong><small>{{ item.reminderMinutes }} / {{ item.escalationMinutes }}</small></span>
            </article>
          </div>
        </section>
      </section>

      <section v-else class="panel admin-workspace">
        <header class="workspace-heading">
          <div>
            <span class="eyebrow">{{ t('admin.traceability') }}</span>
            <h3>{{ t('admin.auditTitle') }}</h3>
            <p>{{ t('admin.auditHint') }}</p>
          </div>
        </header>
        <div v-if="canViewAudit && auditItems.length" class="audit-list">
          <article v-for="item in auditItems" :key="item.id">
            <span class="audit-result" :class="item.result.toLowerCase()" />
            <span class="audit-copy">
              <strong>{{ formatLegalCode(item.action, locale) }}</strong>
              <small>{{ item.actorName || t('admin.systemActor') }} · {{ formatLegalCode(item.resourceType, locale) }}</small>
            </span>
            <span class="audit-meta">
              <strong>{{ formatLegalCode(item.result, locale) }}</strong>
              <small>{{ new Date(item.occurredAt).toLocaleString(locale) }}</small>
            </span>
          </article>
        </div>
        <div v-else class="empty-state">
          <FileClock :size="30" />
          <strong>{{ canViewAudit ? t('admin.noAudit') : t('admin.auditRestricted') }}</strong>
        </div>
      </section>
    </template>

    <el-dialog v-model="userDialog" :title="t('admin.editUserAccess')" width="min(760px, 94vw)" append-to-body>
      <div v-if="selectedUser" class="access-editor">
        <header class="editor-person">
          <span class="user-avatar large">{{ selectedUser.displayName.slice(0, 1) }}</span>
          <span><strong>{{ selectedUser.displayName }}</strong><small>{{ selectedUser.username }} · {{ selectedUser.email || t('admin.noEmail') }}</small></span>
        </header>
        <label class="editor-field">
          <span>{{ t('p1.status') }}</span>
          <select v-model="selectedUser.status">
            <option value="ACTIVE">{{ formatLegalCode('ACTIVE', locale) }}</option>
            <option value="INACTIVE">{{ formatLegalCode('INACTIVE', locale) }}</option>
            <option value="SUSPENDED">{{ formatLegalCode('SUSPENDED', locale) }}</option>
          </select>
        </label>
        <fieldset>
          <legend>{{ t('admin.assignRoles') }}</legend>
          <div class="choice-grid">
            <label v-for="role in roles" :key="role.code" class="choice-card">
              <input v-model="selectedUser.roleCodes" type="checkbox" :value="role.code" />
              <span><strong>{{ formatLegalCode(role.code, locale) }}</strong><small>{{ t('admin.permissionCount').replace('{count}', String(role.permissions.length)) }}</small></span>
            </label>
          </div>
        </fieldset>
        <fieldset>
          <legend>{{ t('admin.officeAccess') }}</legend>
          <div class="office-access-list">
            <article v-for="office in editableOffices" :key="office.officeId">
              <label class="office-toggle">
                <input v-model="office.enabled" type="checkbox" />
                <span><strong>{{ localizedOffice(offices.find((item) => item.id === office.officeId)!) }}</strong><small>{{ offices.find((item) => item.id === office.officeId)?.code }}</small></span>
              </label>
              <select v-model="office.accessLevel" :disabled="!office.enabled">
                <option value="MEMBER">{{ t('offices.levelMember') }}</option>
                <option value="MANAGER">{{ t('offices.levelManager') }}</option>
              </select>
              <label class="primary-choice">
                <input
                  type="radio"
                  name="primary-office"
                  :checked="office.primary"
                  :disabled="!office.enabled"
                  @change="selectPrimaryOffice(office.officeId)"
                />
                <span>{{ t('admin.primary') }}</span>
              </label>
              <input v-model="office.validUntil" type="datetime-local" :disabled="!office.enabled || office.primary" :aria-label="t('offices.validUntil')" />
            </article>
          </div>
        </fieldset>
      </div>
      <template #footer>
        <button class="secondary-action" type="button" @click="userDialog = false">{{ t('common.close') }}</button>
        <button class="primary-action" type="button" :disabled="savingUser" @click="saveUser">
          <Save :size="16" /> {{ savingUser ? t('common.saving') : t('admin.saveAccess') }}
        </button>
      </template>
    </el-dialog>

    <el-dialog v-model="roleDialog" :title="t('admin.editRolePermissions')" width="min(860px, 94vw)" append-to-body>
      <div v-if="selectedRole" class="permission-editor">
        <header>
          <span class="role-icon large"><KeyRound :size="20" /></span>
          <span><strong>{{ formatLegalCode(selectedRole.code, locale) }}</strong><small>{{ selectedRole.code }} · {{ t('admin.userCount').replace('{count}', String(selectedRole.userCount)) }}</small></span>
        </header>
        <aside><ShieldCheck :size="17" /><span>{{ t('admin.permissionSafety') }}</span></aside>
        <section v-for="group in permissionGroups" :key="group.resourceType">
          <h4>{{ formatLegalCode(group.resourceType, locale) }}</h4>
          <div class="permission-grid">
            <label v-for="permission in group.items" :key="permission.code">
              <input v-model="selectedPermissionCodes" type="checkbox" :value="permission.code" :disabled="!canManageRoles" />
              <span><strong>{{ permissionLabel(permission) }}</strong><small>{{ permission.code }}</small></span>
            </label>
          </div>
        </section>
      </div>
      <template #footer>
        <button class="secondary-action" type="button" @click="roleDialog = false">{{ t('common.close') }}</button>
        <button v-if="canManageRoles" class="primary-action" type="button" :disabled="savingRole" @click="saveRolePermissions">
          <Save :size="16" /> {{ savingRole ? t('common.saving') : t('admin.savePermissions') }}
        </button>
      </template>
    </el-dialog>
  </section>
</template>

<style scoped>
.admin-page { overflow-x: hidden; padding-bottom: 28px; }
.admin-intro { margin-bottom: 14px; }
.admin-assurance { display: flex; align-items: center; gap: 10px; padding: 11px 14px; border: 1px solid #cfddd6; border-radius: 10px; background: #eff5f1; color: var(--forest-2); }
.admin-assurance span, .admin-assurance strong, .admin-assurance small { display: block; }
.admin-assurance strong { font-size: 11px; }
.admin-assurance small { margin-top: 2px; color: var(--muted); font-size: 9px; }
.admin-summary { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 14px; }
.admin-summary article { display: flex; align-items: center; gap: 12px; min-height: 76px; padding: 14px 16px; border: 1px solid var(--line); border-radius: var(--radius-md); background: white; box-shadow: var(--shadow-sm); }
.summary-icon { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; background: var(--forest-3); color: var(--forest-2); }
.admin-summary span, .admin-summary small, .admin-summary strong { display: block; }
.admin-summary small { color: var(--muted); font-size: 9px; }
.admin-summary strong { margin-top: 2px; font: 600 22px/1.1 Georgia, serif; }
.admin-tabs { display: flex; gap: 5px; margin-bottom: 14px; padding: 5px; border: 1px solid var(--line); border-radius: 11px; background: rgba(255,255,255,.68); }
.admin-tabs button { flex: 1; min-height: 42px; display: flex; align-items: center; justify-content: center; gap: 8px; border: 0; border-radius: 8px; background: transparent; color: var(--muted); cursor: pointer; font-size: 11px; font-weight: 650; }
.admin-tabs button:hover { background: white; color: var(--ink); }
.admin-tabs button.active { background: var(--forest); color: white; box-shadow: 0 6px 16px rgba(18,59,48,.14); }
.admin-tabs b { min-width: 20px; padding: 2px 5px; border-radius: 999px; background: rgba(255,255,255,.12); font-size: 8px; }
.admin-tabs button:not(.active) b { background: var(--forest-3); color: var(--forest-2); }
.admin-workspace { overflow: hidden; }
.workspace-heading { min-height: 88px; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 18px 20px; border-bottom: 1px solid var(--line); background: linear-gradient(100deg, white, #f7f9f6); }
.workspace-heading h3 { margin: 0; font: 700 18px "Songti SC", "Noto Serif CJK SC", serif; }
.workspace-heading p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.compact-heading { min-height: 82px; }
.admin-search { width: min(410px, 48%); display: flex; align-items: center; gap: 9px; padding-left: 12px; border: 1px solid var(--line); border-radius: 9px; background: white; color: var(--muted); }
.admin-search input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; font-size: 11px; }
.admin-search button { align-self: stretch; padding: 0 14px; border: 0; border-radius: 0 8px 8px 0; background: var(--forest); color: white; cursor: pointer; font-size: 10px; }
.admin-table-wrap { overflow-x: auto; }
.admin-table-wrap table { min-width: 920px; }
.user-cell { display: flex; align-items: center; gap: 10px; }
.user-cell span, .user-cell strong, .user-cell small { display: block; }
.user-avatar { width: 32px; height: 32px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 9px; background: var(--forest); color: white; font: 600 13px Georgia, serif; }
.user-avatar.large { width: 42px; height: 42px; font-size: 17px; }
.user-cell small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.tag-list { display: flex; flex-wrap: wrap; gap: 4px; }
.tag-list span { padding: 4px 7px; border-radius: 999px; background: var(--forest-3); color: var(--forest-2); font-size: 8px; font-weight: 650; }
.scope-copy { color: var(--muted); font-size: 10px; }
.status-pill.inactive, .status-pill.suspended { background: #f4e9e7; color: var(--oxblood); }
.role-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 1px; background: var(--line); }
.role-list > button { min-height: 76px; display: grid; grid-template-columns: auto 1fr auto auto; gap: 11px; align-items: center; padding: 14px 18px; border: 0; background: white; color: var(--ink); text-align: left; cursor: pointer; }
.role-list > button:hover { background: #f9fbf8; }
.role-icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 9px; background: var(--forest-3); color: var(--forest-2); }
.role-icon.large { width: 42px; height: 42px; }
.role-copy, .role-copy strong, .role-copy small { display: block; min-width: 0; }
.role-copy small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.role-permission-count { color: var(--muted); font-size: 9px; }
.system-grid { display: grid; grid-template-columns: 1.2fr .8fr; gap: 14px; }
.settings-panel { grid-row: span 2; overflow: hidden; }
.settings-form { display: grid; grid-template-columns: repeat(2, 1fr); gap: 13px; padding: 18px 20px; }
.settings-form label > span, .editor-field > span { display: block; margin-bottom: 6px; color: var(--muted); font-size: 9px; font-weight: 650; }
.settings-form input, .settings-form select, .editor-field select { width: 100%; min-height: 39px; padding: 0 11px; border: 1px solid var(--line); border-radius: 8px; background: var(--surface-subtle); color: var(--ink); font-size: 11px; }
.settings-panel > footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 14px 20px; border-top: 1px solid var(--line); color: var(--muted); font-size: 9px; }
.integrations-panel, .rules-panel { overflow: hidden; }
.integration-list article, .rule-list article { min-height: 63px; display: grid; grid-template-columns: auto 1fr auto; gap: 10px; align-items: center; padding: 11px 16px; border-bottom: 1px solid var(--line); }
.integration-list article:last-child, .rule-list article:last-child { border-bottom: 0; }
.integration-list span, .integration-list strong, .integration-list small, .rule-list span, .rule-list strong, .rule-list small { display: block; }
.integration-list small, .rule-list small { margin-top: 3px; color: var(--muted); font-size: 8px; }
.health-icon, .rule-icon { width: 32px; height: 32px; display: grid; place-items: center; border-radius: 8px; background: var(--surface-subtle); color: var(--muted); }
.health-icon.up { background: var(--forest-3); color: var(--forest-2); }
.health-icon.down { background: #f4e4e2; color: var(--oxblood); }
.health-copy, .rule-list article > span:last-child { text-align: right; }
.audit-list article { min-height: 64px; display: grid; grid-template-columns: auto 1fr auto; gap: 12px; align-items: center; padding: 12px 20px; border-bottom: 1px solid var(--line); }
.audit-list article:last-child { border-bottom: 0; }
.audit-result { width: 8px; height: 8px; border-radius: 50%; background: var(--muted); }
.audit-result.success { background: var(--forest-2); box-shadow: 0 0 0 4px var(--forest-3); }
.audit-result.failure { background: var(--oxblood); box-shadow: 0 0 0 4px #f4e4e2; }
.audit-copy span, .audit-copy strong, .audit-copy small, .audit-meta strong, .audit-meta small { display: block; }
.audit-copy small, .audit-meta small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.audit-meta { text-align: right; }
.access-editor { display: grid; gap: 18px; }
.editor-person, .permission-editor > header { display: flex; align-items: center; gap: 11px; padding-bottom: 15px; border-bottom: 1px solid var(--line); }
.editor-person span, .editor-person strong, .editor-person small, .permission-editor > header span, .permission-editor > header strong, .permission-editor > header small { display: block; }
.editor-person small, .permission-editor > header small { margin-top: 3px; color: var(--muted); font-size: 9px; }
.editor-field { width: min(280px, 100%); }
fieldset { min-width: 0; margin: 0; padding: 0; border: 0; }
legend { margin-bottom: 10px; font-size: 11px; font-weight: 750; }
.choice-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 7px; }
.choice-card { display: flex; align-items: center; gap: 9px; padding: 10px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.choice-card:has(input:checked) { border-color: #8ca99d; background: var(--forest-3); }
.choice-card span, .choice-card strong, .choice-card small { display: block; }
.choice-card small { margin-top: 2px; color: var(--muted); font-size: 8px; }
.office-access-list { display: grid; gap: 6px; }
.office-access-list article { display: grid; grid-template-columns: minmax(180px, 1fr) 130px 90px minmax(170px, .8fr); gap: 8px; align-items: center; padding: 9px 10px; border: 1px solid var(--line); border-radius: 8px; }
.office-toggle, .primary-choice { display: flex; align-items: center; gap: 8px; }
.office-toggle span, .office-toggle strong, .office-toggle small { display: block; }
.office-toggle small { color: var(--muted); font-size: 8px; }
.office-access-list select, .office-access-list input[type="datetime-local"] { min-height: 35px; padding: 0 8px; border: 1px solid var(--line); border-radius: 7px; background: white; font-size: 9px; }
.primary-choice span { color: var(--muted); font-size: 9px; }
.permission-editor { display: grid; gap: 17px; }
.permission-editor > aside { display: flex; gap: 9px; align-items: center; padding: 11px 13px; border-radius: 8px; background: var(--brass-soft); color: var(--brass); font-size: 10px; }
.permission-editor section h4 { margin: 0 0 8px; color: var(--brass); font: 700 9px Georgia, serif; letter-spacing: .12em; }
.permission-grid { display: grid; grid-template-columns: repeat(2, 1fr); gap: 6px; }
.permission-grid label { display: flex; gap: 8px; align-items: center; min-width: 0; padding: 9px 10px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.permission-grid label:has(input:checked) { border-color: #a9bdb3; background: var(--forest-3); }
.permission-grid span, .permission-grid strong, .permission-grid small { display: block; min-width: 0; }
.permission-grid strong { font-size: 10px; }
.permission-grid small { margin-top: 2px; overflow: hidden; color: var(--muted); font-size: 7px; text-overflow: ellipsis; white-space: nowrap; }
@media (max-width: 1050px) {
  .admin-summary { grid-template-columns: repeat(2, 1fr); }
  .system-grid { grid-template-columns: 1fr; }
  .settings-panel { grid-row: auto; }
  .office-access-list article { grid-template-columns: 1fr 120px; }
}
@media (max-width: 700px) {
  .admin-summary, .role-list, .settings-form, .choice-grid, .permission-grid { grid-template-columns: 1fr; }
  .admin-tabs { overflow-x: auto; }
  .admin-tabs button { min-width: 120px; }
  .workspace-heading { align-items: stretch; flex-direction: column; }
  .admin-search { width: 100%; }
  .office-access-list article { grid-template-columns: 1fr; }
  .settings-panel > footer { align-items: stretch; flex-direction: column; }
}
</style>

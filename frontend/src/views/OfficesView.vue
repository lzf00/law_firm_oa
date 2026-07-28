<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Building2, Clock3, Coins, Globe2, MapPin, ShieldCheck, Trash2, UsersRound } from '@lucide/vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { http } from '@/api/http'
import type { CurrentUser, Office, OfficeMember, OrganizationUser } from '@/api/types'
import { useI18n } from '@/i18n'
import { canManageOffice } from '@/officeScope'

const { locale, t } = useI18n()
const offices = ref<Office[]>([])
const currentUser = ref<CurrentUser | null>(null)
const organizationUsers = ref<OrganizationUser[]>([])
const selectedOffice = ref<Office | null>(null)
const members = ref<OfficeMember[]>([])
const membersLoading = ref(false)
const saving = ref(false)
const loading = ref(true)
const memberForm = ref({
  userId: '',
  accessLevel: 'MEMBER' as 'MEMBER' | 'MANAGER',
  primary: false,
  validUntil: '',
})

const groups = computed(() => [
  {
    key: 'middleEast',
    title: t('offices.middleEast'),
    offices: offices.value.filter((office) => ['AE', 'SA', 'EG', 'IQ', 'OM'].includes(office.countryCode)),
  },
  {
    key: 'africa',
    title: t('offices.africa'),
    offices: offices.value.filter((office) => ['ZA'].includes(office.countryCode)),
  },
  {
    key: 'china',
    title: t('offices.china'),
    offices: offices.value.filter((office) => office.countryCode === 'CN'),
  },
])

function localized(office: Office, field: 'name' | 'city' | 'address') {
  const english = office[`${field}En` as keyof Office]
  const chinese = office[`${field}Zh` as keyof Office]
  return String(locale.value === 'en-US' ? english || chinese || '' : chinese || english || '')
}

function canManage(office: Office) {
  return currentUser.value ? canManageOffice(currentUser.value, office.id) : false
}

async function openMembers(office: Office) {
  selectedOffice.value = office
  membersLoading.value = true
  try {
    const [memberResult, userResult] = await Promise.all([
      http.get<OfficeMember[]>(`/offices/${office.id}/members`),
      http.get<OrganizationUser[]>('/organization/users'),
    ])
    members.value = memberResult.data
    organizationUsers.value = userResult.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('offices.membersLoadFailed'))
  } finally {
    membersLoading.value = false
  }
}

async function saveMember() {
  if (!selectedOffice.value || !memberForm.value.userId) {
    ElMessage.warning(t('offices.chooseMember'))
    return
  }
  saving.value = true
  try {
    await http.put(
      `/offices/${selectedOffice.value.id}/members/${memberForm.value.userId}`,
      {
        accessLevel: memberForm.value.accessLevel,
        primary: memberForm.value.primary,
        validUntil: memberForm.value.validUntil
          ? new Date(memberForm.value.validUntil).toISOString()
          : null,
      },
    )
    ElMessage.success(t('offices.memberSaved'))
    memberForm.value = { userId: '', accessLevel: 'MEMBER', primary: false, validUntil: '' }
    await openMembers(selectedOffice.value)
    offices.value = (await http.get<Office[]>('/offices')).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('offices.memberSaveFailed'))
  } finally {
    saving.value = false
  }
}

async function revokeMember(member: OfficeMember) {
  if (!selectedOffice.value) return
  try {
    await ElMessageBox.confirm(
      t('offices.revokeConfirm').replace('{name}', member.displayName),
      t('offices.revoke'),
      { type: 'warning' },
    )
    await http.delete(`/offices/${selectedOffice.value.id}/members/${member.userId}`)
    ElMessage.success(t('offices.memberRevoked'))
    await openMembers(selectedOffice.value)
    offices.value = (await http.get<Office[]>('/offices')).data
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof Error ? error.message : t('offices.memberRevokeFailed'))
  }
}

onMounted(async () => {
  try {
    const [officeResult, meResult] = await Promise.all([
      http.get<Office[]>('/offices'),
      http.get<CurrentUser>('/me'),
    ])
    offices.value = officeResult.data
    currentUser.value = meResult.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('offices.empty'))
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <section class="module-page">
    <div class="page-intro office-intro">
      <div>
        <span class="eyebrow">GLOBAL NETWORK · {{ offices.length }} OFFICES</span>
        <h2>{{ t('headline.offices') }}</h2>
        <p>{{ t('offices.intro') }}</p>
      </div>
      <div class="network-mark"><Globe2 :size="26" /><span>MENA · CHINA · AFRICA</span></div>
    </div>

    <div v-if="loading" class="panel empty-state">Loading global offices…</div>
    <template v-else>
      <section v-for="group in groups" :key="group.key" class="office-region">
        <header>
          <h3>{{ group.title }}</h3>
          <strong>{{ String(group.offices.length).padStart(2, '0') }}</strong>
        </header>
        <div class="office-grid">
          <article v-for="office in group.offices" :key="office.id" class="office-card">
            <div class="office-card-top">
              <span class="office-code">{{ office.code }}</span>
              <span class="country-code">{{ office.countryCode }}</span>
            </div>
            <Building2 :size="23" />
            <h3>{{ localized(office, 'name') }}</h3>
            <p><MapPin :size="14" />{{ localized(office, 'address') || localized(office, 'city') }}</p>
            <dl>
              <div><dt><Clock3 :size="13" />{{ t('offices.timezone') }}</dt><dd>{{ office.timezone }}</dd></div>
              <div><dt><Coins :size="13" />{{ t('offices.currency') }}</dt><dd>{{ office.defaultCurrency }}</dd></div>
              <div><dt><UsersRound :size="13" />{{ t('offices.members') }}</dt><dd>{{ office.memberCount }}</dd></div>
            </dl>
            <button
              v-if="canManage(office)"
              class="member-manage-button"
              type="button"
              @click="openMembers(office)"
            >
              <ShieldCheck :size="15" />{{ t('offices.manageMembers') }}
            </button>
          </article>
        </div>
      </section>

      <section v-if="selectedOffice" class="panel member-panel">
        <header class="member-panel-header">
          <div>
            <span class="eyebrow">OFFICE ACCESS CONTROL</span>
            <h3>{{ localized(selectedOffice, 'name') }} · {{ t('offices.manageMembers') }}</h3>
          </div>
          <button class="plain-button" type="button" @click="selectedOffice = null">
            {{ t('common.close') }}
          </button>
        </header>

        <form class="member-form" @submit.prevent="saveMember">
          <label>
            <span>{{ t('offices.member') }}</span>
            <select v-model="memberForm.userId" required data-testid="office-member-user">
              <option value="">{{ t('offices.chooseMember') }}</option>
              <option v-for="user in organizationUsers" :key="user.id" :value="user.id">
                {{ user.displayName }} · {{ user.username }}
              </option>
            </select>
          </label>
          <label>
            <span>{{ t('offices.accessLevel') }}</span>
            <select v-model="memberForm.accessLevel" data-testid="office-member-level">
              <option value="MEMBER">{{ t('offices.levelMember') }}</option>
              <option v-if="currentUser?.globalOfficeAccess" value="MANAGER">{{ t('offices.levelManager') }}</option>
            </select>
          </label>
          <label>
            <span>{{ t('offices.validUntil') }}</span>
            <input v-model="memberForm.validUntil" type="datetime-local" />
          </label>
          <label v-if="currentUser?.globalOfficeAccess" class="check-label">
            <input v-model="memberForm.primary" type="checkbox" />
            <span>{{ t('offices.primaryOffice') }}</span>
          </label>
          <button class="primary-button" type="submit" :disabled="saving" data-testid="office-member-save">
            {{ saving ? t('common.saving') : t('offices.saveAccess') }}
          </button>
        </form>

        <div v-if="membersLoading" class="empty-state">Loading members…</div>
        <div v-else class="member-list" data-testid="office-member-list">
          <article v-for="member in members" :key="member.userId">
            <div>
              <strong>{{ member.displayName }}</strong>
              <span>{{ member.username }} · {{ member.email || '—' }}</span>
            </div>
            <div class="member-tags">
              <span>{{ member.accessLevel === 'MANAGER' ? t('offices.levelManager') : t('offices.levelMember') }}</span>
              <span v-if="member.primary">{{ t('offices.primaryOffice') }}</span>
              <span v-if="member.validUntil">{{ t('offices.expires') }} {{ new Date(member.validUntil).toLocaleString() }}</span>
            </div>
            <button
              v-if="!member.primary"
              class="icon-danger"
              type="button"
              :aria-label="t('offices.revoke')"
              @click="revokeMember(member)"
            >
              <Trash2 :size="16" />
            </button>
          </article>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.member-manage-button { margin-top: 18px; width: 100%; display: flex; align-items: center; justify-content: center; gap: 8px; padding: 10px 14px; border: 1px solid rgba(197, 162, 91, .38); color: #856a32; background: rgba(197, 162, 91, .08); cursor: pointer; }
.member-panel { margin-top: 30px; padding: 24px; }
.member-panel-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.member-panel-header h3 { margin: 6px 0 0; }
.member-form { display: grid; grid-template-columns: 1.5fr 1fr 1.2fr auto auto; gap: 14px; align-items: end; margin: 24px 0; }
.member-form label { display: grid; gap: 7px; color: #65716b; font-size: 12px; }
.member-form select, .member-form input { min-height: 40px; border: 1px solid #d9dfdb; padding: 8px 10px; background: #fff; }
.check-label { display: flex !important; grid-template-columns: none; align-items: center; padding-bottom: 10px; white-space: nowrap; }
.check-label input { min-height: auto; }
.primary-button, .plain-button { min-height: 40px; border: 0; padding: 0 18px; cursor: pointer; }
.primary-button { background: #15372c; color: #fff; }
.plain-button { background: #edf1ef; color: #30463d; }
.member-list { display: grid; gap: 10px; }
.member-list article { display: grid; grid-template-columns: minmax(180px, 1fr) 1fr auto; gap: 16px; align-items: center; padding: 14px 16px; border: 1px solid #e4e9e6; }
.member-list strong, .member-list span { display: block; }
.member-list div > span { margin-top: 4px; color: #77817c; font-size: 12px; }
.member-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.member-tags span { margin: 0; padding: 4px 8px; background: #f1f4f2; color: #516159; }
.icon-danger { border: 0; background: transparent; color: #a54a4a; cursor: pointer; }
@media (max-width: 900px) {
  .member-form { grid-template-columns: 1fr; }
  .member-list article { grid-template-columns: 1fr auto; }
  .member-tags { grid-column: 1 / -1; }
}
</style>

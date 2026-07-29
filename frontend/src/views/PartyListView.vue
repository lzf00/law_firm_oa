<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import {
  BadgeCheck,
  Building2,
  CircleAlert,
  ContactRound,
  FilePenLine,
  Plus,
  Search,
  ShieldCheck,
  UserRound,
  UserRoundCheck,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { useDebounceFn } from '@vueuse/core'
import { useRoute, useRouter } from 'vue-router'
import { http } from '@/api/http'
import type { Client, CurrentUser, OrganizationUser, Party } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

type RelationshipFilter = 'all' | 'client' | 'prospect' | 'risk'

const route = useRoute()
const router = useRouter()
const parties = ref<Party[]>([])
const clients = ref<Client[]>([])
const users = ref<OrganizationUser[]>([])
const currentUser = ref<CurrentUser | null>(null)
const query = ref('')
const relationshipFilter = ref<RelationshipFilter>('all')
const selectedPartyId = ref('')
const loading = ref(false)
const partyDialog = ref(false)
const clientDialog = ref(false)
const saving = ref(false)
const editingPartyId = ref('')
const editingClientId = ref('')
const { locale } = useI18n()

const partyForm = reactive({
  partyType: 'ORGANIZATION',
  displayName: '',
  unifiedSocialCreditCode: '',
  aliasesText: '',
  notes: '',
})
const clientForm = reactive({
  partyId: '',
  clientNumber: '',
  ownerUserId: '',
  source: '',
})

const canCreateParty = computed(() => currentUser.value?.permissions.includes('PARTY_CREATE') ?? false)
const canManageParty = computed(() => currentUser.value?.permissions.includes('PARTY_MANAGE') ?? false)
const canCreateClient = computed(() => currentUser.value?.permissions.includes('CLIENT_CREATE') ?? false)
const canManageClient = computed(() => currentUser.value?.permissions.includes('CLIENT_MANAGE') ?? false)
const clientByParty = computed(() => new Map(clients.value.map((client) => [client.partyId, client])))
const clientCount = computed(() => clients.value.filter((client) => client.status === 'ACTIVE').length)
const riskCount = computed(() => parties.value.filter((party) => party.riskLevel !== 'NORMAL').length)
const prospectCount = computed(() => parties.value.filter((party) => !clientByParty.value.has(party.id)).length)

const visibleParties = computed(() => parties.value.filter((party) => {
  const client = clientByParty.value.get(party.id)
  if (relationshipFilter.value === 'client') return Boolean(client)
  if (relationshipFilter.value === 'prospect') return !client
  if (relationshipFilter.value === 'risk') return party.riskLevel !== 'NORMAL'
  return true
}))

const selectedParty = computed(() => (
  visibleParties.value.find((party) => party.id === selectedPartyId.value)
  ?? visibleParties.value[0]
  ?? null
))
const selectedClient = computed(() => (
  selectedParty.value ? clientByParty.value.get(selectedParty.value.id) ?? null : null
))

const filters = computed<Array<{ value: RelationshipFilter; label: string }>>(() => [
  { value: 'all', label: t('party.filterAll') },
  { value: 'client', label: t('party.filterClients') },
  { value: 'prospect', label: t('party.filterProspects') },
  { value: 'risk', label: t('party.filterRisk') },
])

async function load() {
  loading.value = true
  try {
    const [partyResponse, clientResponse, userResponse, meResponse] = await Promise.all([
      http.get<Party[]>('/parties', { params: { query: query.value.trim() || undefined } }),
      http.get<Client[]>('/clients'),
      http.get<OrganizationUser[]>('/organization/users'),
      http.get<CurrentUser>('/me'),
    ])
    parties.value = partyResponse.data
    clients.value = clientResponse.data
    users.value = userResponse.data
    currentUser.value = meResponse.data
    const requestedId = typeof route.query.id === 'string' ? route.query.id : ''
    if (requestedId && parties.value.some((party) => party.id === requestedId)) {
      selectedPartyId.value = requestedId
    } else if (!parties.value.some((party) => party.id === selectedPartyId.value)) {
      selectedPartyId.value = parties.value[0]?.id ?? ''
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('party.loadFailed'))
  } finally {
    loading.value = false
  }
}

function selectParty(party: Party) {
  selectedPartyId.value = party.id
  void router.replace({ query: { ...route.query, id: party.id } })
}

function openParty(party?: Party) {
  editingPartyId.value = party?.id ?? ''
  Object.assign(partyForm, {
    partyType: party?.partyType ?? 'ORGANIZATION',
    displayName: party?.displayName ?? '',
    unifiedSocialCreditCode: party?.unifiedSocialCreditCode ?? '',
    aliasesText: party?.aliases.join('，') ?? '',
    notes: party?.notes ?? '',
  })
  partyDialog.value = true
}

async function saveParty() {
  if (!partyForm.displayName.trim()) {
    ElMessage.warning(t('party.nameRequired'))
    return
  }
  saving.value = true
  try {
    const payload = {
      partyType: partyForm.partyType,
      displayName: partyForm.displayName,
      unifiedSocialCreditCode: partyForm.unifiedSocialCreditCode || null,
      notes: partyForm.notes || null,
      aliases: partyForm.aliasesText.split(/[，,]/).map((item) => item.trim()).filter(Boolean),
    }
    const response = editingPartyId.value
      ? await http.put<Party>(`/parties/${editingPartyId.value}`, payload)
      : await http.post<Party>('/parties', payload)
    partyDialog.value = false
    selectedPartyId.value = response.data.id
    await load()
    ElMessage.success(t('party.saved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('party.saveFailed'))
  } finally {
    saving.value = false
  }
}

function openClient(party: Party, client?: Client) {
  editingClientId.value = client?.id ?? ''
  Object.assign(clientForm, {
    partyId: party.id,
    clientNumber: client?.clientNumber ?? '',
    ownerUserId: client?.ownerUserId ?? '',
    source: client?.source ?? '',
  })
  clientDialog.value = true
}

async function saveClient() {
  if (!clientForm.clientNumber.trim()) {
    ElMessage.warning(t('party.clientNumberRequired'))
    return
  }
  saving.value = true
  try {
    const payload = {
      partyId: clientForm.partyId,
      clientNumber: clientForm.clientNumber,
      ownerUserId: clientForm.ownerUserId || null,
      source: clientForm.source || null,
    }
    if (editingClientId.value) {
      await http.put(`/clients/${editingClientId.value}`, payload)
    } else {
      await http.post('/clients', payload)
    }
    clientDialog.value = false
    selectedPartyId.value = clientForm.partyId
    await load()
    ElMessage.success(t('party.clientSaved'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('party.clientSaveFailed'))
  } finally {
    saving.value = false
  }
}

watch(query, useDebounceFn(load, 250))
watch(relationshipFilter, () => {
  if (!visibleParties.value.some((party) => party.id === selectedPartyId.value)) {
    selectedPartyId.value = visibleParties.value[0]?.id ?? ''
  }
})
onMounted(load)
</script>

<template>
  <section class="module-page relationship-desk">
    <header class="relationship-hero">
      <div>
        <span class="eyebrow">{{ t('party.kicker') }}</span>
        <h2>{{ t('party.heroTitle') }}</h2>
        <p>{{ t('party.heroDescription') }}</p>
      </div>
      <button v-if="canCreateParty" class="primary-action" type="button" @click="openParty()">
        <Plus :size="17" /> {{ t('party.newParty') }}
      </button>
    </header>

    <div class="relationship-stats" :aria-label="t('party.overview')">
      <article>
        <ContactRound :size="19" />
        <span>{{ t('party.totalParties') }}</span>
        <strong>{{ parties.length }}</strong>
        <small>{{ t('party.totalHint') }}</small>
      </article>
      <article>
        <BadgeCheck :size="19" />
        <span>{{ t('party.activeClients') }}</span>
        <strong>{{ clientCount }}</strong>
        <small>{{ t('party.clientHint') }}</small>
      </article>
      <article>
        <UserRoundCheck :size="19" />
        <span>{{ t('party.prospects') }}</span>
        <strong>{{ prospectCount }}</strong>
        <small>{{ t('party.prospectHint') }}</small>
      </article>
      <article :class="{ attention: riskCount > 0 }">
        <CircleAlert :size="19" />
        <span>{{ t('party.riskParties') }}</span>
        <strong>{{ riskCount }}</strong>
        <small>{{ t('party.riskHint') }}</small>
      </article>
    </div>

    <div class="relationship-toolbar">
      <label class="search-box">
        <Search :size="17" />
        <input v-model="query" :placeholder="t('party.searchPlaceholder')" />
      </label>
      <div class="filter-tabs" :aria-label="t('party.filters')">
        <button
          v-for="filter in filters"
          :key="filter.value"
          type="button"
          :class="{ active: relationshipFilter === filter.value }"
          @click="relationshipFilter = filter.value"
        >{{ filter.label }}</button>
      </div>
    </div>

    <div class="relationship-layout">
      <aside class="relationship-queue panel">
        <div class="desk-heading">
          <div>
            <span class="eyebrow">{{ t('party.queueKicker') }}</span>
            <h3>{{ t('party.queueTitle') }}</h3>
          </div>
          <span class="status-pill">{{ visibleParties.length }}</span>
        </div>
        <div v-if="loading" class="desk-empty">{{ t('party.loading') }}</div>
        <button
          v-for="party in visibleParties"
          v-else
          :key="party.id"
          type="button"
          class="relationship-row"
          :class="{ selected: selectedParty?.id === party.id }"
          @click="selectParty(party)"
        >
          <span class="party-symbol">
            <UserRound v-if="party.partyType === 'PERSON'" :size="18" />
            <Building2 v-else :size="18" />
          </span>
          <span class="relationship-row-copy">
            <strong>{{ party.displayName }}</strong>
            <small>
              {{ formatLegalCode(party.partyType, locale) }}
              <template v-if="party.aliases.length"> · {{ party.aliases[0] }}</template>
            </small>
          </span>
          <span class="relationship-state">
            {{ clientByParty.has(party.id) ? t('party.clientBadge') : t('party.prospectBadge') }}
          </span>
        </button>
        <div v-if="!loading && visibleParties.length === 0" class="desk-empty">
          <ContactRound :size="28" />
          <strong>{{ t('party.noMatch') }}</strong>
          <span>{{ t('party.noMatchHint') }}</span>
        </div>
      </aside>

      <main class="relationship-profile panel">
        <template v-if="selectedParty">
          <div class="profile-heading">
            <div class="profile-identity">
              <span class="party-symbol large">
                <UserRound v-if="selectedParty.partyType === 'PERSON'" :size="24" />
                <Building2 v-else :size="24" />
              </span>
              <div>
                <span class="eyebrow">{{ t('party.profileKicker') }}</span>
                <h3>{{ selectedParty.displayName }}</h3>
                <p>{{ formatLegalCode(selectedParty.partyType, locale) }}</p>
              </div>
            </div>
            <div class="profile-actions">
              <button
                v-if="canManageParty"
                type="button"
                class="secondary-action compact-action"
                @click="openParty(selectedParty)"
              ><FilePenLine :size="15" /> {{ t('party.editParty') }}</button>
              <button
                v-if="!selectedClient && canCreateClient"
                type="button"
                class="primary-action compact"
                @click="openClient(selectedParty)"
              ><UserRoundCheck :size="15" /> {{ t('party.convertClient') }}</button>
            </div>
          </div>

          <div class="profile-grid">
            <section>
              <span>{{ t('party.identityCode') }}</span>
              <strong class="mono">{{ selectedParty.unifiedSocialCreditCode || '—' }}</strong>
            </section>
            <section>
              <span>{{ t('party.riskLevel') }}</span>
              <strong>{{ formatLegalCode(selectedParty.riskLevel, locale) }}</strong>
            </section>
            <section>
              <span>{{ t('party.aliases') }}</span>
              <strong>{{ selectedParty.aliases.join('、') || '—' }}</strong>
            </section>
            <section>
              <span>{{ t('party.relationshipStatus') }}</span>
              <strong>{{ selectedClient ? t('party.registeredClient') : t('party.notClient') }}</strong>
            </section>
          </div>

          <section class="profile-note">
            <div class="section-title"><ShieldCheck :size="17" /><strong>{{ t('party.internalNote') }}</strong></div>
            <p>{{ selectedParty.notes || t('party.noNote') }}</p>
          </section>

          <section class="client-record" :class="{ empty: !selectedClient }">
            <div class="section-title">
              <BadgeCheck :size="17" />
              <div>
                <strong>{{ t('party.clientRecord') }}</strong>
                <span>{{ t('party.clientRecordHint') }}</span>
              </div>
              <button
                v-if="selectedClient && canManageClient"
                type="button"
                class="secondary-action compact-action"
                @click="openClient(selectedParty, selectedClient)"
              >{{ t('party.editClient') }}</button>
            </div>
            <div v-if="selectedClient" class="client-facts">
              <div><span>{{ t('party.clientNumber') }}</span><strong class="mono">{{ selectedClient.clientNumber }}</strong></div>
              <div><span>{{ t('party.owner') }}</span><strong>{{ selectedClient.ownerName || '—' }}</strong></div>
              <div><span>{{ t('party.source') }}</span><strong>{{ selectedClient.source || '—' }}</strong></div>
              <div><span>{{ t('party.status') }}</span><strong>{{ formatLegalCode(selectedClient.status, locale) }}</strong></div>
            </div>
            <div v-else class="client-empty">
              <span>{{ t('party.notConverted') }}</span>
              <small>{{ t('party.notConvertedHint') }}</small>
            </div>
          </section>
        </template>
        <div v-else class="desk-empty">
          <ContactRound :size="32" />
          <strong>{{ t('party.selectParty') }}</strong>
        </div>
      </main>
    </div>

    <ElDialog
      v-model="partyDialog"
      :title="editingPartyId ? t('party.editParty') : t('party.newParty')"
      width="min(640px, 94vw)"
    >
      <form class="dialog-form two-column-form" @submit.prevent="saveParty">
        <label>
          <span>{{ t('party.partyType') }}</span>
          <select v-model="partyForm.partyType">
            <option value="ORGANIZATION">{{ formatLegalCode('ORGANIZATION', locale) }}</option>
            <option value="PERSON">{{ formatLegalCode('PERSON', locale) }}</option>
            <option value="GOVERNMENT">{{ formatLegalCode('GOVERNMENT', locale) }}</option>
            <option value="OTHER">{{ formatLegalCode('OTHER', locale) }}</option>
          </select>
        </label>
        <label><span>{{ t('party.identityCode') }}</span><input v-model="partyForm.unifiedSocialCreditCode" maxlength="64" /></label>
        <label class="full-field"><span>{{ t('party.displayName') }}</span><input v-model="partyForm.displayName" required maxlength="300" /></label>
        <label class="full-field"><span>{{ t('party.aliasInput') }}</span><input v-model="partyForm.aliasesText" maxlength="1000" :placeholder="t('party.aliasHint')" /></label>
        <label class="full-field"><span>{{ t('party.internalNote') }}</span><textarea v-model="partyForm.notes" rows="3" maxlength="500" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">
          {{ saving ? t('common.saving') : t('party.saveParty') }}
        </button>
      </form>
    </ElDialog>

    <ElDialog
      v-model="clientDialog"
      :title="editingClientId ? t('party.editClient') : t('party.convertClient')"
      width="min(560px, 94vw)"
    >
      <form class="dialog-form" @submit.prevent="saveClient">
        <label><span>{{ t('party.clientNumber') }}</span><input v-model="clientForm.clientNumber" required maxlength="60" /></label>
        <label>
          <span>{{ t('party.owner') }}</span>
          <select v-model="clientForm.ownerUserId">
            <option value="">{{ t('party.unassigned') }}</option>
            <option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option>
          </select>
        </label>
        <label><span>{{ t('party.source') }}</span><input v-model="clientForm.source" maxlength="100" :placeholder="t('party.sourceHint')" /></label>
        <button class="primary-action full" type="submit" :disabled="saving">
          {{ saving ? t('common.saving') : t('party.saveClient') }}
        </button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.relationship-desk { padding-top: 6px; }
.relationship-hero {
  min-height: 190px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  padding: 34px 38px;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  background:
    linear-gradient(112deg, rgba(18, 59, 48, .98), rgba(26, 76, 61, .96)),
    repeating-linear-gradient(90deg, transparent 0 54px, rgba(255,255,255,.04) 54px 55px);
  color: white;
}
.relationship-hero .eyebrow { color: var(--brass-on-dark); }
.relationship-hero h2 { margin: 0; font: 700 31px/1.35 "Songti SC", serif; }
.relationship-hero p { max-width: 700px; margin: 11px 0 0; color: #c2d0ca; font-size: 13px; line-height: 1.75; }
.relationship-hero .primary-action { border-color: #d1b46f; background: #d1b46f; color: var(--ink); }
.relationship-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  overflow: hidden;
  border: 1px solid var(--line);
  border-top: 0;
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
  background: white;
  box-shadow: var(--shadow-sm);
}
.relationship-stats article { min-height: 118px; position: relative; padding: 22px; border-right: 1px solid var(--line); }
.relationship-stats article:last-child { border-right: 0; }
.relationship-stats svg { position: absolute; right: 20px; top: 20px; color: var(--forest-2); }
.relationship-stats span, .relationship-stats small { display: block; color: var(--muted); font-size: 10px; }
.relationship-stats strong { display: block; margin: 12px 0 4px; font: 600 29px/1 Georgia, serif; }
.relationship-stats .attention strong, .relationship-stats .attention svg { color: var(--oxblood); }
.relationship-toolbar { display: flex; justify-content: space-between; gap: 18px; margin: 20px 0 12px; padding: 12px; border: 1px solid var(--line); border-radius: var(--radius-md); background: white; }
.relationship-layout { display: grid; grid-template-columns: minmax(300px, .72fr) minmax(0, 1.55fr); gap: 16px; align-items: stretch; }
.relationship-queue, .relationship-profile { min-height: 570px; overflow: hidden; }
.desk-heading { min-height: 82px; display: flex; justify-content: space-between; align-items: center; padding: 18px 20px; border-bottom: 1px solid var(--line); }
.desk-heading h3 { margin: 0; font: 700 18px "Songti SC", serif; }
.relationship-row {
  width: 100%;
  min-height: 82px;
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 13px 18px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: white;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.relationship-row:hover { background: var(--surface-subtle); }
.relationship-row.selected { background: var(--forest-3); box-shadow: inset 3px 0 var(--brass); }
.party-symbol { width: 38px; height: 38px; display: grid; place-items: center; border-radius: 10px; color: var(--forest-2); background: #e5ece8; }
.party-symbol.large { width: 52px; height: 52px; border-radius: 13px; }
.relationship-row-copy { min-width: 0; }
.relationship-row-copy strong, .relationship-row-copy small { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.relationship-row-copy strong { font: 700 14px "Songti SC", serif; }
.relationship-row-copy small { margin-top: 5px; color: var(--muted); font-size: 9px; }
.relationship-state { padding: 4px 7px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 9px; }
.desk-empty { min-height: 260px; display: flex; flex-direction: column; justify-content: center; align-items: center; gap: 8px; padding: 26px; color: var(--muted); text-align: center; font-size: 11px; }
.profile-heading { min-height: 126px; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding: 24px 28px; border-bottom: 1px solid var(--line); }
.profile-identity { min-width: 0; display: flex; align-items: center; gap: 16px; }
.profile-identity h3 { overflow-wrap: anywhere; margin: 0; font: 700 24px/1.35 "Songti SC", serif; }
.profile-identity p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.profile-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.primary-action.compact { min-height: 34px; padding: 0 11px; font-size: 11px; }
.profile-grid { display: grid; grid-template-columns: repeat(4, 1fr); border-bottom: 1px solid var(--line); }
.profile-grid section { min-height: 106px; padding: 21px; border-right: 1px solid var(--line); }
.profile-grid section:last-child { border-right: 0; }
.profile-grid span, .profile-grid strong { display: block; }
.profile-grid span { color: var(--muted); font-size: 9px; }
.profile-grid strong { margin-top: 11px; font-size: 12px; overflow-wrap: anywhere; }
.profile-note, .client-record { margin: 20px 24px 0; padding: 20px; border: 1px solid var(--line); border-radius: 10px; background: var(--surface-subtle); }
.section-title { display: flex; align-items: center; gap: 9px; color: var(--forest-2); }
.section-title > div { flex: 1; }
.section-title strong, .section-title span { display: block; }
.section-title strong { color: var(--ink); font-size: 12px; }
.section-title span { margin-top: 3px; color: var(--muted); font-size: 9px; }
.profile-note p { margin: 12px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.75; white-space: pre-wrap; }
.client-record { margin-bottom: 24px; background: white; }
.client-record.empty { border-style: dashed; }
.client-facts { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-top: 18px; }
.client-facts div { padding: 12px; border-left: 2px solid var(--forest-2); background: var(--surface-subtle); }
.client-facts span, .client-facts strong { display: block; }
.client-facts span { color: var(--muted); font-size: 8px; }
.client-facts strong { margin-top: 6px; font-size: 11px; overflow-wrap: anywhere; }
.client-empty { margin-top: 15px; padding: 16px; text-align: center; color: var(--muted); }
.client-empty span, .client-empty small { display: block; }
.client-empty small { margin-top: 4px; font-size: 9px; }

@media (max-width: 1100px) {
  .relationship-stats { grid-template-columns: repeat(2, 1fr); }
  .relationship-stats article:nth-child(2) { border-right: 0; }
  .relationship-stats article:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .relationship-layout { grid-template-columns: minmax(280px, .7fr) minmax(0, 1.3fr); }
  .profile-grid, .client-facts { grid-template-columns: repeat(2, 1fr); }
  .profile-grid section:nth-child(2) { border-right: 0; }
  .profile-grid section:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
}

@media (max-width: 760px) {
  .relationship-hero { min-height: 245px; padding: 26px 22px; align-items: flex-start; flex-direction: column; }
  .relationship-hero h2 { font-size: 26px; }
  .relationship-stats article { min-height: 108px; padding: 17px; }
  .relationship-toolbar { flex-direction: column; }
  .relationship-layout { grid-template-columns: 1fr; }
  .relationship-queue, .relationship-profile { min-height: 0; }
  .relationship-queue { max-height: 390px; overflow-y: auto; }
  .profile-heading { align-items: flex-start; flex-direction: column; padding: 20px; }
  .profile-actions { justify-content: flex-start; }
  .profile-grid { grid-template-columns: 1fr 1fr; }
  .profile-note, .client-record { margin: 14px; }
  .client-facts { grid-template-columns: 1fr 1fr; }
}
</style>

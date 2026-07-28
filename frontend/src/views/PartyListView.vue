<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Building2, Pencil, Plus, Search, UserRound, UserRoundCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { useDebounceFn } from '@vueuse/core'
import { http } from '@/api/http'
import type { OrganizationUser, Party } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Client {
  id: string
  partyId: string
  clientNumber: string
  displayName: string
  partyType: string
  ownerUserId?: string
  ownerName?: string
  status: string
}

const parties = ref<Party[]>([])
const clients = ref<Client[]>([])
const users = ref<OrganizationUser[]>([])
const query = ref('')
const loading = ref(false)
const partyDialog = ref(false)
const clientDialog = ref(false)
const saving = ref(false)
const editingPartyId = ref('')
const editingClientId = ref('')
const { locale } = useI18n()
const isEnglish = computed(() => locale.value === 'en-US')
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

async function load() {
  loading.value = true
  try {
    const [partyResponse, clientResponse, userResponse] = await Promise.all([
      http.get<Party[]>('/parties', { params: { query: query.value } }),
      http.get<Client[]>('/clients'),
      http.get<OrganizationUser[]>('/organization/users'),
    ])
    parties.value = partyResponse.data
    clients.value = clientResponse.data
    users.value = userResponse.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0287'))
  } finally {
    loading.value = false
  }
}

function openParty(party?: Party) {
  editingPartyId.value = party?.id ?? ''
  Object.assign(partyForm, {
    partyType: party?.partyType ?? 'ORGANIZATION',
    displayName: party?.displayName ?? '',
    unifiedSocialCreditCode: party?.unifiedSocialCreditCode ?? '',
    aliasesText: party?.aliases.join('，') ?? '',
    notes: '',
  })
  partyDialog.value = true
}

async function saveParty() {
  if (!partyForm.displayName.trim()) {
    ElMessage.warning(t('copy.0288'))
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
    if (editingPartyId.value) {
      await http.put(`/parties/${editingPartyId.value}`, payload)
    } else {
      await http.post('/parties', payload)
    }
    partyDialog.value = false
    await load()
    ElMessage.success(t('copy.0289'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0290'))
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
    source: '',
  })
  clientDialog.value = true
}

function editClient(client: Client) {
  const party = parties.value.find((item) => item.id === client.partyId)
  if (party) openClient(party, client)
}

async function saveClient() {
  if (!clientForm.clientNumber.trim()) {
    ElMessage.warning(t('copy.0291'))
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
    await load()
    ElMessage.success(t('copy.0292'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0293'))
  } finally {
    saving.value = false
  }
}

watch(query, useDebounceFn(load, 250))
onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">PARTY MASTER</span>
        <h2>{{ t('headline.parties') }}</h2>
        <p>{{ t('copy.0372') }}</p>
      </div>
      <button class="primary-action" @click="openParty()"><Plus :size="17" /> {{ t('copy.0373') }}</button>
    </div>

    <div class="toolbar">
      <label class="search-box wide">
        <Search :size="17" />
        <input v-model="query" :placeholder="t('copy.0374')" />
      </label>
    </div>

    <div class="party-grid">
      <article v-for="party in parties" :key="party.id" class="party-card">
        <div class="party-icon">
          <UserRound v-if="party.partyType === 'PERSON'" :size="21" />
          <Building2 v-else :size="21" />
        </div>
        <div class="party-copy">
          <strong>{{ party.displayName }}</strong>
          <span>{{ formatLegalCode(party.partyType, locale) }}</span>
          <small v-if="party.aliases.length">{{ t('copy.0375') }}：{{ party.aliases.join('、') }}</small>
        </div>
        <div class="card-actions">
          <button class="table-action" :aria-label="t('copy.0376')" @click="openParty(party)"><Pencil :size="15" /></button>
          <button
            v-if="!clients.some((client) => client.partyId === party.id)"
            class="table-action"
            @click="openClient(party)"
          ><UserRoundCheck :size="15" /> {{ t('copy.0377') }}</button>
        </div>
        <span class="risk-dot" :class="party.riskLevel.toLowerCase()">{{ formatLegalCode(party.riskLevel, locale) }}</span>
      </article>
    </div>
    <div v-if="!loading && parties.length === 0" class="panel empty-state">{{ t('copy.0378') }}</div>

    <div class="panel table-panel" tabindex="0">
      <div class="panel-heading">
        <div><span class="eyebrow">CLIENT REGISTER</span><h3>{{ t('copy.0379') }}</h3></div>
        <span class="status-pill">{{ clients.length }}</span>
      </div>
      <table v-if="clients.length">
        <thead><tr><th>{{ t('copy.0380') }}</th><th>{{ t('copy.0381') }}</th><th>{{ t('copy.0382') }}</th><th>{{ t('copy.0189') }}</th><th>{{ t('copy.0383') }}</th></tr></thead>
        <tbody>
          <tr v-for="client in clients" :key="client.id">
            <td class="mono">{{ client.clientNumber }}</td><td><strong>{{ client.displayName }}</strong></td>
            <td>{{ client.ownerName || '—' }}</td><td><span class="status-pill">{{ formatLegalCode(client.status, locale) }}</span></td>
            <td><button class="table-action" @click="editClient(client)"><Pencil :size="15" /> {{ t('copy.0326') }}</button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">{{ t('copy.0384') }}</div>
    </div>

    <ElDialog v-model="partyDialog" :title="editingPartyId ? (t('copy.0376')) : (t('copy.0373'))" width="min(640px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="saveParty">
        <label><span>{{ t('copy.0385') }}</span><select v-model="partyForm.partyType"><option value="ORGANIZATION">{{ t('copy.0386') }}</option><option value="PERSON">{{ t('copy.0387') }}</option><option value="GOVERNMENT">{{ t('copy.0388') }}</option><option value="OTHER">{{ t('copy.0389') }}</option></select></label>
        <label><span>{{ t('copy.0390') }}</span><input v-model="partyForm.unifiedSocialCreditCode" maxlength="64" /></label>
        <label class="full-field"><span>{{ t('copy.0391') }}</span><input v-model="partyForm.displayName" required maxlength="300" /></label>
        <label class="full-field"><span>{{ t('copy.0392') }}</span><input v-model="partyForm.aliasesText" maxlength="1000" /></label>
        <label class="full-field"><span>{{ t('copy.0282') }}</span><textarea v-model="partyForm.notes" rows="3" maxlength="500" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (t('copy.0393')) }}</button>
      </form>
    </ElDialog>

    <ElDialog v-model="clientDialog" :title="editingClientId ? (t('copy.0394')) : (t('copy.0395'))" width="min(560px, 94vw)">
      <form class="dialog-form" @submit.prevent="saveClient">
        <label><span>{{ t('copy.0396') }}</span><input v-model="clientForm.clientNumber" required maxlength="60" /></label>
        <label><span>{{ t('copy.0397') }}</span><select v-model="clientForm.ownerUserId"><option value="">{{ t('copy.0398') }}</option><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ t('copy.0399') }}</span><input v-model="clientForm.source" maxlength="100" /></label>
        <button class="primary-action full" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (t('copy.0400')) }}</button>
      </form>
    </ElDialog>
  </section>
</template>

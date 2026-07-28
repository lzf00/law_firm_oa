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
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not load parties' : '主体加载失败'))
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
    ElMessage.warning(isEnglish.value ? 'Party name is required' : '请填写主体名称')
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
    ElMessage.success(isEnglish.value ? 'Party saved' : '主体已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not save party' : '主体保存失败'))
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
    ElMessage.warning(isEnglish.value ? 'Client number is required' : '请填写客户编号')
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
    ElMessage.success(isEnglish.value ? 'Client profile saved' : '客户档案已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not save client' : '客户保存失败'))
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
        <p>{{ isEnglish ? 'Manage clients, counterparties and aliases in one conflict-searchable register.' : '客户、相对方及关联主体统一管理，别名也会进入冲突检索。' }}</p>
      </div>
      <button class="primary-action" @click="openParty()"><Plus :size="17" /> {{ isEnglish ? 'New party' : '新建主体' }}</button>
    </div>

    <div class="toolbar">
      <label class="search-box wide">
        <Search :size="17" />
        <input v-model="query" :placeholder="isEnglish ? 'Search names, short names or former names' : '按主体名称、简称或曾用名检索'" />
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
          <small v-if="party.aliases.length">{{ isEnglish ? 'Aliases' : '别名' }}：{{ party.aliases.join('、') }}</small>
        </div>
        <div class="card-actions">
          <button class="table-action" :aria-label="isEnglish ? 'Edit party' : '编辑主体'" @click="openParty(party)"><Pencil :size="15" /></button>
          <button
            v-if="!clients.some((client) => client.partyId === party.id)"
            class="table-action"
            @click="openClient(party)"
          ><UserRoundCheck :size="15" /> {{ isEnglish ? 'Make client' : '转为客户' }}</button>
        </div>
        <span class="risk-dot" :class="party.riskLevel.toLowerCase()">{{ formatLegalCode(party.riskLevel, locale) }}</span>
      </article>
    </div>
    <div v-if="!loading && parties.length === 0" class="panel empty-state">{{ isEnglish ? 'No matching party' : '未找到匹配主体' }}</div>

    <div class="panel table-panel">
      <div class="panel-heading">
        <div><span class="eyebrow">CLIENT REGISTER</span><h3>{{ isEnglish ? 'Client profiles' : '客户档案' }}</h3></div>
        <span class="status-pill">{{ clients.length }}</span>
      </div>
      <table v-if="clients.length">
        <thead><tr><th>{{ isEnglish ? 'Client no.' : '客户编号' }}</th><th>{{ isEnglish ? 'Name' : '客户名称' }}</th><th>{{ isEnglish ? 'Owner' : '客户负责人' }}</th><th>{{ isEnglish ? 'Status' : '状态' }}</th><th>{{ isEnglish ? 'Action' : '操作' }}</th></tr></thead>
        <tbody>
          <tr v-for="client in clients" :key="client.id">
            <td class="mono">{{ client.clientNumber }}</td><td><strong>{{ client.displayName }}</strong></td>
            <td>{{ client.ownerName || '—' }}</td><td><span class="status-pill">{{ formatLegalCode(client.status, locale) }}</span></td>
            <td><button class="table-action" @click="editClient(client)"><Pencil :size="15" /> {{ isEnglish ? 'Edit' : '编辑' }}</button></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">{{ isEnglish ? 'No client profiles yet' : '尚无客户档案' }}</div>
    </div>

    <ElDialog v-model="partyDialog" :title="editingPartyId ? (isEnglish ? 'Edit party' : '编辑主体') : (isEnglish ? 'New party' : '新建主体')" width="min(640px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="saveParty">
        <label><span>{{ isEnglish ? 'Party type' : '主体类型' }}</span><select v-model="partyForm.partyType"><option value="ORGANIZATION">{{ isEnglish ? 'Organization' : '机构' }}</option><option value="PERSON">{{ isEnglish ? 'Individual' : '自然人' }}</option><option value="GOVERNMENT">{{ isEnglish ? 'Government' : '政府机构' }}</option><option value="OTHER">{{ isEnglish ? 'Other' : '其他' }}</option></select></label>
        <label><span>{{ isEnglish ? 'Unified credit code' : '统一社会信用代码' }}</span><input v-model="partyForm.unifiedSocialCreditCode" maxlength="64" /></label>
        <label class="full-field"><span>{{ isEnglish ? 'Display name' : '主体名称' }}</span><input v-model="partyForm.displayName" required maxlength="300" /></label>
        <label class="full-field"><span>{{ isEnglish ? 'Aliases (comma separated)' : '别名（逗号分隔）' }}</span><input v-model="partyForm.aliasesText" maxlength="1000" /></label>
        <label class="full-field"><span>{{ isEnglish ? 'Notes' : '备注' }}</span><textarea v-model="partyForm.notes" rows="3" maxlength="500" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (isEnglish ? 'Save party' : '保存主体') }}</button>
      </form>
    </ElDialog>

    <ElDialog v-model="clientDialog" :title="editingClientId ? (isEnglish ? 'Edit client profile' : '编辑客户档案') : (isEnglish ? 'Create client profile' : '建立客户档案')" width="min(560px, 94vw)">
      <form class="dialog-form" @submit.prevent="saveClient">
        <label><span>{{ isEnglish ? 'Client number' : '客户编号' }}</span><input v-model="clientForm.clientNumber" required maxlength="60" /></label>
        <label><span>{{ isEnglish ? 'Client owner' : '客户负责人' }}</span><select v-model="clientForm.ownerUserId"><option value="">{{ isEnglish ? 'Unassigned' : '暂不指定' }}</option><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ isEnglish ? 'Source' : '客户来源' }}</span><input v-model="clientForm.source" maxlength="100" /></label>
        <button class="primary-action full" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (isEnglish ? 'Save client' : '保存客户') }}</button>
      </form>
    </ElDialog>
  </section>
</template>

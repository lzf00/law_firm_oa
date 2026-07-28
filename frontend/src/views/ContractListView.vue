<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { FileSignature, Pencil, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter, OrganizationUser } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Contract {
  id: string
  contractNumber: string
  title: string
  status: string
  clientId?: string
  clientName?: string
  responsibleUserId: string
  responsibleName: string
  effectiveDate?: string
  expiryDate?: string
  amount?: number
  currency: string
  matterCount: number
  matterIds: string[]
}

interface Client {
  id: string
  displayName: string
  clientNumber: string
}

const contracts = ref<Contract[]>([])
const clients = ref<Client[]>([])
const matters = ref<Matter[]>([])
const users = ref<OrganizationUser[]>([])
const loading = ref(true)
const saving = ref(false)
const submitting = ref('')
const dialogVisible = ref(false)
const editingId = ref('')
const { locale } = useI18n()
const isEnglish = computed(() => locale.value === 'en-US')
const form = reactive({
  contractNumber: '',
  title: '',
  clientId: '',
  responsibleUserId: '',
  effectiveDate: '',
  expiryDate: '',
  amount: undefined as number | undefined,
  currency: 'CNY',
  matterIds: [] as string[],
})

async function load() {
  loading.value = true
  try {
    const [contractResponse, clientResponse, matterResponse, userResponse] = await Promise.all([
      http.get<Contract[]>('/contracts'),
      http.get<Client[]>('/clients'),
      http.get<Matter[]>('/matters'),
      http.get<OrganizationUser[]>('/organization/users'),
    ])
    contracts.value = contractResponse.data
    clients.value = clientResponse.data
    matters.value = matterResponse.data
    users.value = userResponse.data
    if (dialogVisible.value && !form.responsibleUserId) {
      form.responsibleUserId = users.value[0]?.id ?? ''
    }
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0096'))
  } finally {
    loading.value = false
  }
}

function openForm(contract?: Contract) {
  editingId.value = contract?.id ?? ''
  Object.assign(form, {
    contractNumber: contract?.contractNumber ?? '',
    title: contract?.title ?? '',
    clientId: contract?.clientId ?? '',
    responsibleUserId: contract?.responsibleUserId ?? users.value[0]?.id ?? '',
    effectiveDate: contract?.effectiveDate ?? '',
    expiryDate: contract?.expiryDate ?? '',
    amount: contract?.amount,
    currency: contract?.currency ?? 'CNY',
    matterIds: [...(contract?.matterIds ?? [])],
  })
  dialogVisible.value = true
}

async function save() {
  if (!form.contractNumber.trim() || !form.title.trim() || !form.responsibleUserId) {
    ElMessage.warning(t('copy.0097'))
    return
  }
  if (form.effectiveDate && form.expiryDate && form.expiryDate < form.effectiveDate) {
    ElMessage.warning(t('copy.0098'))
    return
  }
  saving.value = true
  try {
    const payload = {
      ...form,
      clientId: form.clientId || null,
      effectiveDate: form.effectiveDate || null,
      expiryDate: form.expiryDate || null,
      amount: form.amount ?? null,
      currency: form.currency.toUpperCase(),
    }
    if (editingId.value) await http.put(`/contracts/${editingId.value}`, payload)
    else await http.post('/contracts', payload)
    dialogVisible.value = false
    await load()
    ElMessage.success(t('copy.0099'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0100'))
  } finally {
    saving.value = false
  }
}

async function submitReview(contract: Contract) {
  submitting.value = contract.id
  try {
    await http.post('/workflows', {
      businessType: 'CONTRACT',
      businessId: contract.id,
      variables: {},
    }, { headers: { 'Idempotency-Key': crypto.randomUUID() } })
    await load()
    ElMessage.success(t('copy.0101'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('copy.0102'))
  } finally {
    submitting.value = ''
  }
}

function money(contract: Contract) {
  if (contract.amount == null) return '—'
  return new Intl.NumberFormat(locale.value, {
    style: 'currency', currency: contract.currency, maximumFractionDigits: 0,
  }).format(contract.amount)
}

onMounted(load)
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">CONTRACTS</span>
        <h2>{{ t('headline.contracts') }}</h2>
        <p>{{ t('copy.0315') }}</p>
      </div>
      <button class="primary-action" :disabled="loading" @click="openForm()"><Plus :size="17" /> {{ t('copy.0316') }}</button>
    </div>

    <div class="panel table-panel" tabindex="0">
      <div v-if="loading" class="empty-state">{{ t('copy.0317') }}</div>
      <table v-else-if="contracts.length">
        <thead><tr><th>{{ t('copy.0318') }}</th><th>{{ t('copy.0319') }}</th><th>{{ t('copy.0320') }}</th><th>{{ t('copy.0310') }}</th><th>{{ t('copy.0321') }}</th><th>{{ t('copy.0322') }}</th><th>{{ t('copy.0189') }}</th><th>{{ t('copy.0323') }}</th></tr></thead>
        <tbody>
          <tr v-for="contract in contracts" :key="contract.id">
            <td><span class="mono">{{ contract.contractNumber }}</span></td>
            <td><strong>{{ contract.title }}</strong><small>{{ contract.effectiveDate || (t('copy.0324')) }} → {{ contract.expiryDate || (t('copy.0325')) }}</small></td>
            <td>{{ contract.clientName || '—' }}</td><td>{{ contract.responsibleName }}</td>
            <td>{{ money(contract) }}</td><td>{{ contract.matterCount }}</td>
            <td><span class="status-pill">{{ formatLegalCode(contract.status, locale) }}</span></td>
            <td><div class="table-actions"><button class="table-action" @click="openForm(contract)"><Pencil :size="15" /> {{ t('copy.0326') }}</button><button v-if="contract.status === 'DRAFT'" class="table-action approve" :disabled="submitting === contract.id" @click="submitReview(contract)"><Send :size="15" /> {{ t('copy.0327') }}</button></div></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state"><FileSignature :size="32" /><strong>{{ t('copy.0328') }}</strong><span>{{ t('copy.0329') }}</span></div>
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? (t('copy.0330')) : (t('copy.0316'))" width="min(760px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label><span>{{ t('copy.0331') }}</span><input v-model="form.contractNumber" required maxlength="80" /></label>
        <label><span>{{ t('copy.0332') }}</span><input v-model="form.currency" required maxlength="3" /></label>
        <label class="full-field"><span>{{ t('copy.0333') }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ t('copy.0320') }}</span><select v-model="form.clientId"><option value="">{{ t('copy.0334') }}</option><option v-for="client in clients" :key="client.id" :value="client.id">{{ client.clientNumber }} · {{ client.displayName }}</option></select></label>
        <label><span>{{ t('copy.0335') }}</span><select v-model="form.responsibleUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ t('copy.0336') }}</span><input v-model="form.effectiveDate" type="date" /></label>
        <label><span>{{ t('copy.0337') }}</span><input v-model="form.expiryDate" type="date" /></label>
        <label><span>{{ t('copy.0338') }}</span><input v-model.number="form.amount" type="number" min="0" step="0.01" /></label>
        <label class="full-field"><span>{{ t('copy.0339') }}</span><select v-model="form.matterIds" multiple size="4"><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (t('copy.0340')) }}</button>
      </form>
    </ElDialog>
  </section>
</template>

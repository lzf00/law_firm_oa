<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { FileSignature, Pencil, Plus, Send } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Matter, OrganizationUser } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'

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
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not load contracts' : '合同加载失败'))
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
    ElMessage.warning(isEnglish.value ? 'Number, title and responsible lawyer are required' : '请填写合同编号、名称与负责人')
    return
  }
  if (form.effectiveDate && form.expiryDate && form.expiryDate < form.effectiveDate) {
    ElMessage.warning(isEnglish.value ? 'Expiry date cannot be before effective date' : '到期日不得早于生效日')
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
    ElMessage.success(isEnglish.value ? 'Contract saved' : '合同已保存')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not save contract' : '合同保存失败'))
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
    ElMessage.success(isEnglish.value ? 'Contract review started' : '合同评审已发起')
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : (isEnglish.value ? 'Could not start review' : '评审发起失败'))
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
        <p>{{ isEnglish ? 'Contract access is independent from matter access and every review remains traceable.' : '合同权限独立于案件，可关联多个案件，每次评审均保留完整记录。' }}</p>
      </div>
      <button class="primary-action" @click="openForm()"><Plus :size="17" /> {{ isEnglish ? 'New contract' : '新建合同' }}</button>
    </div>

    <div class="panel table-panel">
      <div v-if="loading" class="empty-state">{{ isEnglish ? 'Loading contracts…' : '正在加载合同…' }}</div>
      <table v-else-if="contracts.length">
        <thead><tr><th>{{ isEnglish ? 'No.' : '合同编号' }}</th><th>{{ isEnglish ? 'Contract' : '合同名称' }}</th><th>{{ isEnglish ? 'Client' : '客户' }}</th><th>{{ isEnglish ? 'Owner' : '负责人' }}</th><th>{{ isEnglish ? 'Value' : '金额' }}</th><th>{{ isEnglish ? 'Matters' : '关联案件' }}</th><th>{{ isEnglish ? 'Status' : '状态' }}</th><th>{{ isEnglish ? 'Actions' : '操作' }}</th></tr></thead>
        <tbody>
          <tr v-for="contract in contracts" :key="contract.id">
            <td><span class="mono">{{ contract.contractNumber }}</span></td>
            <td><strong>{{ contract.title }}</strong><small>{{ contract.effectiveDate || (isEnglish ? 'Not effective' : '尚未生效') }} → {{ contract.expiryDate || (isEnglish ? 'Open-ended' : '长期') }}</small></td>
            <td>{{ contract.clientName || '—' }}</td><td>{{ contract.responsibleName }}</td>
            <td>{{ money(contract) }}</td><td>{{ contract.matterCount }}</td>
            <td><span class="status-pill">{{ contract.status }}</span></td>
            <td><div class="table-actions"><button class="table-action" @click="openForm(contract)"><Pencil :size="15" /> {{ isEnglish ? 'Edit' : '编辑' }}</button><button v-if="contract.status === 'DRAFT'" class="table-action approve" :disabled="submitting === contract.id" @click="submitReview(contract)"><Send :size="15" /> {{ isEnglish ? 'Submit' : '送审' }}</button></div></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state"><FileSignature :size="32" /><strong>{{ isEnglish ? 'No contracts yet' : '尚无合同' }}</strong><span>{{ isEnglish ? 'Create a contract to start review.' : '新建合同后可发起评审流程。' }}</span></div>
    </div>

    <ElDialog v-model="dialogVisible" :title="editingId ? (isEnglish ? 'Edit contract' : '编辑合同') : (isEnglish ? 'New contract' : '新建合同')" width="min(760px, 94vw)">
      <form class="dialog-form two-column-form" @submit.prevent="save">
        <label><span>{{ isEnglish ? 'Contract number' : '合同编号' }}</span><input v-model="form.contractNumber" required maxlength="80" /></label>
        <label><span>{{ isEnglish ? 'Currency' : '币种' }}</span><input v-model="form.currency" required maxlength="3" /></label>
        <label class="full-field"><span>{{ isEnglish ? 'Title' : '合同名称' }}</span><input v-model="form.title" required maxlength="300" /></label>
        <label><span>{{ isEnglish ? 'Client' : '客户' }}</span><select v-model="form.clientId"><option value="">{{ isEnglish ? 'No client' : '暂不关联' }}</option><option v-for="client in clients" :key="client.id" :value="client.id">{{ client.clientNumber }} · {{ client.displayName }}</option></select></label>
        <label><span>{{ isEnglish ? 'Responsible lawyer' : '合同负责人' }}</span><select v-model="form.responsibleUserId" required><option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option></select></label>
        <label><span>{{ isEnglish ? 'Effective date' : '生效日期' }}</span><input v-model="form.effectiveDate" type="date" /></label>
        <label><span>{{ isEnglish ? 'Expiry date' : '到期日期' }}</span><input v-model="form.expiryDate" type="date" /></label>
        <label><span>{{ isEnglish ? 'Contract value' : '合同金额' }}</span><input v-model.number="form.amount" type="number" min="0" step="0.01" /></label>
        <label class="full-field"><span>{{ isEnglish ? 'Related matters' : '关联案件' }}</span><select v-model="form.matterIds" multiple size="4"><option v-for="matter in matters" :key="matter.id" :value="matter.id">{{ matter.matterNumber }} · {{ matter.title }}</option></select></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving">{{ saving ? t('common.saving') : (isEnglish ? 'Save contract' : '保存合同') }}</button>
      </form>
    </ElDialog>
  </section>
</template>

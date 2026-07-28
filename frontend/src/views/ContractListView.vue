<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { FileSignature, Plus } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t } from '@/i18n'

interface Contract {
  id: string
  contractNumber: string
  title: string
  status: string
  clientName?: string
  responsibleName: string
  effectiveDate?: string
  expiryDate?: string
  amount?: number
  currency: string
  matterCount: number
}

const contracts = ref<Contract[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    contracts.value = (await http.get<Contract[]>('/contracts')).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '合同加载失败')
  } finally {
    loading.value = false
  }
})

function money(contract: Contract) {
  if (contract.amount == null) return '—'
  return new Intl.NumberFormat('zh-CN', {
    style: 'currency',
    currency: contract.currency,
    maximumFractionDigits: 0,
  }).format(contract.amount)
}
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">CONTRACTS</span>
        <h2>{{ t('headline.contracts') }}</h2>
        <p>合同权限独立于案件，可关联多个案件，但不会自动继承所有案件成员。</p>
      </div>
      <button class="primary-action"><Plus :size="17" /> 新建合同</button>
    </div>

    <div class="panel table-panel">
      <div v-if="loading" class="empty-state">正在加载合同…</div>
      <table v-else-if="contracts.length">
        <thead><tr><th>合同编号</th><th>合同名称</th><th>客户</th><th>负责人</th><th>金额</th><th>关联案件</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="contract in contracts" :key="contract.id">
            <td><span class="mono">{{ contract.contractNumber }}</span></td>
            <td><strong>{{ contract.title }}</strong><small>{{ contract.effectiveDate || '尚未生效' }} → {{ contract.expiryDate || '长期' }}</small></td>
            <td>{{ contract.clientName || '—' }}</td>
            <td>{{ contract.responsibleName }}</td>
            <td>{{ money(contract) }}</td>
            <td>{{ contract.matterCount }} 个</td>
            <td><span class="status-pill">{{ contract.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div v-else class="empty-state">
        <FileSignature :size="32" />
        <strong>尚无合同</strong>
        <span>新建合同后可发起评审与签署流程。</span>
      </div>
    </div>
  </section>
</template>

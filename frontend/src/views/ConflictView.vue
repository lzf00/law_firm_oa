<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { SearchCheck, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Party } from '@/api/types'
import { translate as t } from '@/i18n'

interface ConflictHit {
  partyId: string
  partyName: string
  matterId: string
  matterNumber: string
  matterTitle: string
  partyRole: string
  side: string
  matterStatus: string
}
interface ConflictResult {
  riskLevel: 'CLEAR' | 'MEDIUM' | 'HIGH'
  hitCount: number
  hits: ConflictHit[]
}

const parties = ref<Party[]>([])
const selected = ref<string[]>([])
const matterTitle = ref('')
const result = ref<ConflictResult | null>(null)
const checking = ref(false)
const canCheck = computed(() => matterTitle.value.trim() && selected.value.length > 0)

onMounted(async () => {
  parties.value = (await http.get<Party[]>('/parties')).data
})

async function runCheck() {
  if (!canCheck.value) return
  checking.value = true
  try {
    result.value = (await http.post<ConflictResult>('/conflict-checks/preview', {
      matterTitle: matterTitle.value,
      partyIds: selected.value,
    })).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '检索失败')
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <section class="module-page conflict-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">CONFLICT CHECK</span>
        <h2>{{ t('headline.conflicts') }}</h2>
        <p>检索客户、相对方、历史案件与主体关联；正式结论仍需合伙人复核。</p>
      </div>
    </div>

    <div class="conflict-layout">
      <div class="panel conflict-form">
        <div class="form-number">01</div>
        <label>
          <span>拟办事项</span>
          <input v-model="matterTitle" placeholder="例如：华辰科技采购合同争议" />
        </label>
        <label>
          <span>涉及主体</span>
          <select v-model="selected" multiple>
            <option v-for="party in parties" :key="party.id" :value="party.id">{{ party.displayName }}</option>
          </select>
          <small>按住 Command / Ctrl 可多选</small>
        </label>
        <button class="primary-action full" :disabled="!canCheck || checking" @click="runCheck">
          <SearchCheck :size="18" /> {{ checking ? '正在交叉检索…' : '开始冲突检索' }}
        </button>
      </div>

      <div class="panel result-panel" :class="result?.riskLevel.toLowerCase()">
        <template v-if="!result">
          <ShieldCheck :size="44" stroke-width="1.3" />
          <h3>等待检索</h3>
          <p>结果将展示命中案件与当事人角色，不会自动替代人工判断。</p>
        </template>
        <template v-else>
          <span class="eyebrow">PREVIEW RESULT</span>
          <div class="result-score">{{ result.riskLevel }}</div>
          <h3>发现 {{ result.hitCount }} 条关联记录</h3>
          <div v-for="hit in result.hits" :key="`${hit.matterId}-${hit.partyId}`" class="conflict-hit">
            <strong>{{ hit.partyName }}</strong>
            <span>{{ hit.matterNumber }} · {{ hit.matterTitle }}</span>
            <small>{{ hit.side }} / {{ hit.partyRole }} / {{ hit.matterStatus }}</small>
          </div>
          <p v-if="result.hitCount === 0">当前主体未命中历史案件，可提交正式审核。</p>
        </template>
      </div>
    </div>
  </section>
</template>

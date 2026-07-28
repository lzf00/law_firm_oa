<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { SearchCheck, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Party } from '@/api/types'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

const { locale } = useI18n()
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
    ElMessage.error(error instanceof Error ? error.message : t('copy.0085'))
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
        <p>{{ t('copy.0086') }}</p>
      </div>
    </div>

    <div class="conflict-layout">
      <div class="panel conflict-form">
        <div class="form-number" aria-hidden="true">01</div>
        <label>
          <span>{{ t('copy.0087') }}</span>
          <input v-model="matterTitle" :placeholder="t('copy.0088')" />
        </label>
        <label>
          <span>{{ t('copy.0089') }}</span>
          <select v-model="selected" multiple>
            <option v-for="party in parties" :key="party.id" :value="party.id">{{ party.displayName }}</option>
          </select>
          <small>{{ t('copy.0090') }}</small>
        </label>
        <button class="primary-action full" :disabled="!canCheck || checking" @click="runCheck">
          <SearchCheck :size="18" /> {{ checking ? t('copy.0091') : t('copy.0092') }}
        </button>
      </div>

      <div class="panel result-panel" :class="result?.riskLevel.toLowerCase()">
        <template v-if="!result">
          <ShieldCheck :size="44" stroke-width="1.3" />
          <h3>{{ t('copy.0093') }}</h3>
          <p>{{ t('copy.0094') }}</p>
        </template>
        <template v-else>
          <span class="eyebrow">PREVIEW RESULT</span>
          <div class="result-score">{{ formatLegalCode(result.riskLevel, locale) }}</div>
          <h3>{{ tp('copy.dynamic.conflictHits', { count: result.hitCount }) }}</h3>
          <div v-for="hit in result.hits" :key="`${hit.matterId}-${hit.partyId}`" class="conflict-hit">
            <strong>{{ hit.partyName }}</strong>
            <span>{{ hit.matterNumber }} · {{ hit.matterTitle }}</span>
            <small>{{ formatLegalCode(hit.side, locale) }} / {{ formatLegalCode(hit.partyRole, locale) }} / {{ formatLegalCode(hit.matterStatus, locale) }}</small>
          </div>
          <p v-if="result.hitCount === 0">{{ t('copy.0095') }}</p>
        </template>
      </div>
    </div>
  </section>
</template>

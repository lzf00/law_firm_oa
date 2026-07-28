<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Building2, Plus, Search, UserRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { useDebounceFn } from '@vueuse/core'
import { http } from '@/api/http'
import type { Party } from '@/api/types'
import { translate as t } from '@/i18n'

const parties = ref<Party[]>([])
const query = ref('')
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    parties.value = (await http.get<Party[]>('/parties', { params: { query: query.value } })).data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '主体加载失败')
  } finally {
    loading.value = false
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
        <p>客户、相对方及关联主体统一管理，别名也会进入冲突检索。</p>
      </div>
      <button class="primary-action"><Plus :size="17" /> 新建主体</button>
    </div>

    <div class="toolbar">
      <label class="search-box wide">
        <Search :size="17" />
        <input v-model="query" placeholder="按主体名称、简称或曾用名检索" />
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
          <span>{{ party.partyType === 'PERSON' ? '自然人' : '机构主体' }}</span>
          <small v-if="party.aliases.length">别名：{{ party.aliases.join('、') }}</small>
        </div>
        <span class="risk-dot" :class="party.riskLevel.toLowerCase()">{{ party.riskLevel }}</span>
      </article>
    </div>
    <div v-if="!loading && parties.length === 0" class="panel empty-state">未找到匹配主体</div>
  </section>
</template>

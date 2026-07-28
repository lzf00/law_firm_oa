<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArchiveRestore, FileSearch2, LibraryBig, Scale, ShieldCheck } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { useI18n } from '@/i18n'
import { formatLegalCode } from '@/legalFormat'

interface Template {
  id: string
  code: string
  nameZh: string
  nameEn: string
  category: string
  status: string
  versionNumber: number
  updatedAt: string
}

interface Clause {
  id: string
  code: string
  titleZh: string
  titleEn: string
  category: string
  riskLevel: string
  status: string
  versionNumber: number
  updatedAt: string
}

interface Hold {
  id: string
  name: string
  reason: string
  status: string
  resourceCount: number
  createdAt: string
}

interface Rule {
  id: string
  resourceType: string
  documentType?: string
  retentionYears: number
  dispositionAction: string
  enabled: boolean
}

interface SearchHit {
  documentId: string
  logicalName: string
  filename: string
  documentType: string
  excerpt: string
  updatedAt: string
}

const { locale, t } = useI18n()
const loading = ref(true)
const query = ref('')
const searching = ref(false)
const templates = ref<Template[]>([])
const clauses = ref<Clause[]>([])
const holds = ref<Hold[]>([])
const rules = ref<Rule[]>([])
const results = ref<SearchHit[]>([])

function localized(item: Template | Clause) {
  if ('nameZh' in item) return locale.value === 'en-US' ? item.nameEn : item.nameZh
  return locale.value === 'en-US' ? item.titleEn : item.titleZh
}

async function load() {
  loading.value = true
  try {
    const [templateResult, clauseResult, holdResult, ruleResult] = await Promise.all([
      http.get<Template[]>('/document-governance/templates'),
      http.get<Clause[]>('/document-governance/clauses'),
      http.get<Hold[]>('/document-governance/legal-holds'),
      http.get<Rule[]>('/document-governance/retention-rules'),
    ])
    templates.value = templateResult.data
    clauses.value = clauseResult.data
    holds.value = holdResult.data
    rules.value = ruleResult.data
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    loading.value = false
  }
}

async function search() {
  if (!query.value.trim()) return
  searching.value = true
  try {
    const response = await http.get<{ items: SearchHit[] }>('/document-governance/search', {
      params: { query: query.value.trim(), page: 1, size: 30 },
    })
    results.value = response.data.items
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    searching.value = false
  }
}

async function createExport() {
  try {
    await http.post('/document-governance/exports', { resourceType: 'DOCUMENT', query: '' })
    ElMessage.success(t('p1.exportQueued'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page governance-page">
    <header class="page-intro governance-intro">
      <div>
        <span class="eyebrow">KNOWLEDGE · RETENTION · EVIDENCE</span>
        <h2>{{ t('page.documentGovernance') }}</h2>
        <p>{{ t('p1.governanceHeadline') }}</p>
      </div>
      <div class="governance-mark"><Scale :size="24" /><span>LEGAL OPS P1</span></div>
    </header>

    <section class="panel search-panel">
      <div class="section-heading">
        <FileSearch2 :size="20" />
        <h3>{{ t('p1.search') }}</h3>
      </div>
      <form class="search-form" @submit.prevent="search">
        <label class="sr-only" for="governance-search">{{ t('p1.search') }}</label>
        <input
          id="governance-search"
          v-model="query"
          :placeholder="t('p1.searchPlaceholder')"
          maxlength="200"
        />
        <button class="primary-button" type="submit" :disabled="searching">
          {{ t('p1.searchAction') }}
        </button>
      </form>
      <div v-if="results.length" class="search-results">
        <article v-for="item in results" :key="item.documentId">
          <FileSearch2 :size="17" />
          <div>
            <strong>{{ item.logicalName }}</strong>
            <span>{{ item.filename }} · {{ item.documentType }}</span>
            <p>{{ item.excerpt }}</p>
          </div>
        </article>
      </div>
    </section>

    <div v-if="loading" class="panel empty-state">{{ t('shell.connecting') }}</div>
    <div v-else class="governance-grid">
      <section class="panel">
        <div class="section-heading">
          <LibraryBig :size="20" />
          <h3>{{ t('p1.templates') }}</h3>
          <span>{{ templates.length }}</span>
        </div>
        <div v-if="templates.length" class="record-list">
          <article v-for="item in templates" :key="item.id">
            <div><strong>{{ localized(item) }}</strong><span>{{ item.code }} · {{ item.category }}</span></div>
            <small>{{ t('p1.version') }} {{ item.versionNumber }} · {{ formatLegalCode(item.status, locale) }}</small>
          </article>
        </div>
        <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
      </section>

      <section class="panel">
        <div class="section-heading">
          <ShieldCheck :size="20" />
          <h3>{{ t('p1.clauses') }}</h3>
          <span>{{ clauses.length }}</span>
        </div>
        <div v-if="clauses.length" class="record-list">
          <article v-for="item in clauses" :key="item.id">
            <div><strong>{{ localized(item) }}</strong><span>{{ item.code }} · {{ item.category }}</span></div>
            <small>{{ t('p1.risk') }} · {{ item.riskLevel }}</small>
          </article>
        </div>
        <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
      </section>

      <section class="panel">
        <div class="section-heading">
          <Scale :size="20" />
          <h3>{{ t('p1.holds') }}</h3>
          <span>{{ holds.length }}</span>
        </div>
        <div v-if="holds.length" class="record-list">
          <article v-for="item in holds" :key="item.id">
            <div><strong>{{ item.name }}</strong><span>{{ item.reason }}</span></div>
            <small>{{ formatLegalCode(item.status, locale) }} · {{ item.resourceCount }}</small>
          </article>
        </div>
        <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
      </section>

      <section class="panel">
        <div class="section-heading">
          <ArchiveRestore :size="20" />
          <h3>{{ t('p1.retention') }}</h3>
          <span>{{ rules.length }}</span>
        </div>
        <div v-if="rules.length" class="record-list">
          <article v-for="item in rules" :key="item.id">
            <div><strong>{{ item.resourceType }}</strong><span>{{ item.documentType || t('p1.scope') }}</span></div>
            <small>{{ item.retentionYears }} {{ t('p1.years') }} · {{ item.dispositionAction }}</small>
          </article>
        </div>
        <p v-else class="empty-copy">{{ t('p1.noData') }}</p>
        <button class="secondary-button export-button" type="button" @click="createExport">
          {{ t('p1.exportAction') }}
        </button>
      </section>
    </div>
  </section>
</template>

<style scoped>
.governance-intro { background: linear-gradient(125deg, #f3efe4, #fff 58%, #e7eee9); }
.governance-mark { display: flex; align-items: center; gap: .65rem; color: #1f4b3a; font-weight: 750; letter-spacing: .12em; }
.search-panel { margin-bottom: 1rem; }
.section-heading { display: flex; align-items: center; gap: .55rem; margin-bottom: 1rem; }
.section-heading h3 { margin: 0; flex: 1; }
.section-heading > span { padding: .2rem .55rem; border-radius: 99px; background: #edf1ee; font-size: .78rem; }
.search-form { display: grid; grid-template-columns: 1fr auto; gap: .7rem; }
.search-form input { min-width: 0; }
.search-results { display: grid; gap: .6rem; margin-top: 1rem; }
.search-results article { display: grid; grid-template-columns: auto 1fr; gap: .7rem; padding: .8rem; border: 1px solid var(--border-color); border-radius: 12px; }
.search-results span, .record-list span { display: block; color: var(--text-muted); font-size: .8rem; }
.search-results p { margin: .35rem 0 0; font-size: .86rem; }
.governance-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1rem; }
.record-list { display: grid; gap: .55rem; }
.record-list article { display: flex; justify-content: space-between; gap: 1rem; padding: .75rem 0; border-top: 1px solid var(--border-color); }
.record-list small { color: var(--text-muted); text-align: right; }
.empty-copy { color: var(--text-muted); }
.export-button { margin-top: 1rem; }
@media (max-width: 840px) {
  .governance-grid { grid-template-columns: 1fr; }
  .search-form { grid-template-columns: 1fr; }
}
</style>

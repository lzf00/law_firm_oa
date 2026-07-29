<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArchiveRestore,
  BookOpenCheck,
  CheckCircle2,
  FileClock,
  FileSearch2,
  FileText,
  LibraryBig,
  Scale,
  Search,
  ShieldCheck,
  X,
} from '@lucide/vue'
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

type GovernanceSection = 'templates' | 'clauses' | 'holds' | 'rules'

const { locale, t, tp } = useI18n()
const loading = ref(true)
const query = ref('')
const searching = ref(false)
const searchPerformed = ref(false)
const exporting = ref(false)
const activeSection = ref<GovernanceSection>('templates')
const templates = ref<Template[]>([])
const clauses = ref<Clause[]>([])
const holds = ref<Hold[]>([])
const rules = ref<Rule[]>([])
const results = ref<SearchHit[]>([])

const sections = computed(() => [
  {
    key: 'templates' as const,
    label: t('p1.templates'),
    description: t('governance.templatesHint'),
    count: templates.value.length,
    icon: LibraryBig,
  },
  {
    key: 'clauses' as const,
    label: t('governance.clauses'),
    description: t('governance.clausesHint'),
    count: clauses.value.length,
    icon: ShieldCheck,
  },
  {
    key: 'holds' as const,
    label: t('p1.holds'),
    description: t('governance.holdsHint'),
    count: holds.value.length,
    icon: Scale,
  },
  {
    key: 'rules' as const,
    label: t('p1.retention'),
    description: t('governance.rulesHint'),
    count: rules.value.length,
    icon: ArchiveRestore,
  },
])

const activeMeta = computed(() =>
  sections.value.find((section) => section.key === activeSection.value) ?? sections.value[0]!,
)

function localized(item: Template | Clause) {
  if ('nameZh' in item) return locale.value === 'en-US' ? item.nameEn : item.nameZh
  return locale.value === 'en-US' ? item.titleEn : item.titleZh
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  }).format(new Date(value))
}

function clearSearch() {
  query.value = ''
  results.value = []
  searchPerformed.value = false
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
  const normalizedQuery = query.value.trim()
  if (!normalizedQuery) {
    ElMessage.warning(t('governance.searchEmpty'))
    return
  }
  searching.value = true
  searchPerformed.value = false
  try {
    const response = await http.get<{ items: SearchHit[] }>('/document-governance/search', {
      params: { query: normalizedQuery, page: 1, size: 30 },
    })
    results.value = response.data.items
    searchPerformed.value = true
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    searching.value = false
  }
}

async function createExport() {
  if (exporting.value) return
  exporting.value = true
  try {
    await http.post('/document-governance/exports', { resourceType: 'DOCUMENT', query: '' })
    ElMessage.success(t('p1.exportQueued'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('p1.loadFailed'))
  } finally {
    exporting.value = false
  }
}

onMounted(load)
</script>

<template>
  <section class="module-page governance-page">
    <header class="page-intro governance-intro">
      <div>
        <span class="eyebrow">{{ t('governance.kicker') }}</span>
        <h2>{{ t('page.documentGovernance') }}</h2>
        <p>{{ t('governance.headline') }}</p>
      </div>
      <button class="secondary-action export-action" type="button" :disabled="exporting" @click="createExport">
        <FileText :size="17" />
        {{ exporting ? t('governance.exporting') : t('p1.exportAction') }}
      </button>
    </header>

    <aside class="workflow-hint" :aria-label="t('governance.guideTitle')">
      <BookOpenCheck :size="19" />
      <div>
        <strong>{{ t('governance.guideTitle') }}</strong>
        <span>{{ t('governance.guideBody') }}</span>
      </div>
    </aside>

    <section class="search-panel" aria-labelledby="governance-search-title">
      <div class="search-copy">
        <span class="search-icon"><FileSearch2 :size="21" /></span>
        <div>
          <h3 id="governance-search-title">{{ t('p1.search') }}</h3>
          <p>{{ t('governance.searchHint') }}</p>
        </div>
      </div>
      <form class="search-form" @submit.prevent="search">
        <Search :size="18" />
        <label class="sr-only" for="governance-search">{{ t('p1.search') }}</label>
        <input
          id="governance-search"
          v-model="query"
          :placeholder="t('governance.searchExample')"
          maxlength="200"
        />
        <button v-if="query" class="clear-search" type="button" :aria-label="t('governance.clearSearch')" @click="clearSearch">
          <X :size="16" />
        </button>
        <button class="primary-action search-action" type="submit" :disabled="searching">
          {{ searching ? t('governance.searching') : t('governance.searchAction') }}
        </button>
      </form>
    </section>

    <section v-if="searchPerformed" class="panel search-results-panel" aria-live="polite">
      <header>
        <div>
          <span class="eyebrow">{{ t('governance.searchResults') }}</span>
          <h3>{{ tp('governance.found', { count: results.length }) }}</h3>
        </div>
        <button class="secondary-action compact-action" type="button" @click="clearSearch">
          {{ t('governance.backOverview') }}
        </button>
      </header>
      <div v-if="results.length" class="search-results">
        <article v-for="item in results" :key="item.documentId">
          <span class="result-icon"><FileText :size="18" /></span>
          <div>
            <strong>{{ item.logicalName }}</strong>
            <span>{{ item.filename }} · {{ formatLegalCode(item.documentType, locale) }} · {{ formatDate(item.updatedAt) }}</span>
            <p>{{ item.excerpt }}</p>
          </div>
        </article>
      </div>
      <div v-else class="empty-state compact-empty">
        <FileSearch2 :size="30" />
        <strong>{{ t('governance.noResults') }}</strong>
        <span>{{ t('governance.noResultsHint') }}</span>
      </div>
    </section>

    <template v-else>
      <section class="governance-overview" :aria-label="t('page.documentGovernance')">
        <button
          v-for="section in sections"
          :key="section.key"
          class="overview-card"
          :class="{ active: activeSection === section.key }"
          type="button"
          :aria-pressed="activeSection === section.key"
          @click="activeSection = section.key"
        >
          <span class="overview-icon"><component :is="section.icon" :size="19" /></span>
          <span class="overview-copy">
            <strong>{{ section.label }}</strong>
            <small>{{ section.description }}</small>
          </span>
          <b>{{ section.count }}</b>
        </button>
      </section>

      <section class="panel governance-workspace">
        <header class="workspace-heading">
          <span class="workspace-icon"><component :is="activeMeta.icon" :size="21" /></span>
          <div>
            <h3>{{ activeMeta.label }}</h3>
            <p>{{ activeMeta.description }}</p>
          </div>
          <span class="record-count">{{ tp('governance.items', { count: activeMeta.count }) }}</span>
        </header>

        <div v-if="loading" class="empty-state">
          <FileClock class="spin" :size="28" />
          <strong>{{ t('governance.loading') }}</strong>
        </div>

        <div v-else-if="activeSection === 'templates' && templates.length" class="governance-list">
          <article v-for="item in templates" :key="item.id">
            <span class="record-symbol"><FileText :size="18" /></span>
            <div class="record-main">
              <strong>{{ localized(item) }}</strong>
              <span>{{ item.code }} · {{ formatLegalCode(item.category, locale) }}</span>
            </div>
            <div class="record-meta">
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
              <small>{{ tp('governance.version', { version: item.versionNumber }) }} · {{ formatDate(item.updatedAt) }}</small>
            </div>
          </article>
        </div>

        <div v-else-if="activeSection === 'clauses' && clauses.length" class="governance-list">
          <article v-for="item in clauses" :key="item.id">
            <span class="record-symbol"><ShieldCheck :size="18" /></span>
            <div class="record-main">
              <strong>{{ localized(item) }}</strong>
              <span>{{ item.code }} · {{ formatLegalCode(item.category, locale) }}</span>
            </div>
            <div class="record-meta">
              <span class="risk-label" :class="item.riskLevel.toLowerCase()">
                {{ t('governance.risk') }} · {{ formatLegalCode(item.riskLevel, locale) }}
              </span>
              <small>{{ tp('governance.version', { version: item.versionNumber }) }} · {{ formatDate(item.updatedAt) }}</small>
            </div>
          </article>
        </div>

        <div v-else-if="activeSection === 'holds' && holds.length" class="governance-list">
          <article v-for="item in holds" :key="item.id">
            <span class="record-symbol"><Scale :size="18" /></span>
            <div class="record-main">
              <strong>{{ item.name }}</strong>
              <span>{{ item.reason }}</span>
            </div>
            <div class="record-meta">
              <span class="status-pill">{{ formatLegalCode(item.status, locale) }}</span>
              <small>{{ tp('governance.protected', { count: item.resourceCount }) }} · {{ formatDate(item.createdAt) }}</small>
            </div>
          </article>
        </div>

        <div v-else-if="activeSection === 'rules' && rules.length" class="governance-list">
          <article v-for="item in rules" :key="item.id">
            <span class="record-symbol"><ArchiveRestore :size="18" /></span>
            <div class="record-main">
              <strong>{{ formatLegalCode(item.documentType || item.resourceType, locale) }}</strong>
              <span>{{ t('governance.appliesTo') }} · {{ formatLegalCode(item.resourceType, locale) }}</span>
            </div>
            <div class="record-meta">
              <span class="status-pill" :class="{ disabled: !item.enabled }">
                <CheckCircle2 :size="12" />
                {{ item.enabled ? t('governance.enabled') : t('governance.disabled') }}
              </span>
              <small>{{ tp('governance.keepYears', { years: item.retentionYears }) }} · {{ formatLegalCode(item.dispositionAction, locale) }}</small>
            </div>
          </article>
        </div>

        <div v-else class="empty-state">
          <component :is="activeMeta.icon" :size="30" />
          <strong>{{ t('p1.noData') }}</strong>
          <span>{{ t('governance.noAttention') }}</span>
        </div>
      </section>
    </template>
  </section>
</template>

<style scoped>
.governance-intro { margin-bottom: 14px; }
.governance-intro p { color: var(--muted); }
.export-action { flex: 0 0 auto; }
.workflow-hint {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 12px;
  align-items: start;
  margin-bottom: 14px;
  padding: 14px 16px;
  border: 1px solid #d8e2dc;
  border-radius: var(--radius-md);
  background: #eef4f0;
  color: var(--forest-2);
}
.workflow-hint > svg { margin-top: 2px; }
.workflow-hint strong, .workflow-hint span { display: block; }
.workflow-hint strong { font-size: 12px; }
.workflow-hint span { margin-top: 3px; color: #426157; font-size: 11px; line-height: 1.65; }
.search-panel {
  display: grid;
  grid-template-columns: minmax(220px, .34fr) minmax(440px, 1fr);
  gap: 24px;
  align-items: center;
  margin-bottom: 16px;
  padding: 19px 20px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: white;
  box-shadow: var(--shadow-sm);
}
.search-copy { display: flex; align-items: center; gap: 12px; }
.search-icon, .result-icon, .record-symbol, .workspace-icon, .overview-icon {
  display: grid;
  place-items: center;
  flex: 0 0 auto;
  color: var(--forest-2);
  background: var(--forest-3);
}
.search-icon { width: 40px; height: 40px; border-radius: 10px; }
.search-copy h3 { margin: 0; font: 700 16px/1.3 "Songti SC", "Noto Serif CJK SC", serif; }
.search-copy p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.search-form {
  min-height: 46px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding-left: 14px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: var(--surface-subtle);
  color: var(--muted);
}
.search-form:focus-within { border-color: var(--brass); background: white; box-shadow: 0 0 0 3px rgba(138, 106, 45, .1); }
.search-form input { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--ink); font-size: 13px; }
.clear-search { width: 30px; height: 30px; display: grid; place-items: center; border: 0; border-radius: 7px; background: transparent; color: var(--muted); cursor: pointer; }
.clear-search:hover { background: #e9ece8; color: var(--ink); }
.search-action { min-height: 44px; align-self: stretch; margin: 0 -1px; border-radius: 0 9px 9px 0; box-shadow: none; }
.search-results-panel { margin-bottom: 18px; overflow: hidden; }
.search-results-panel > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
  border-bottom: 1px solid var(--line);
}
.search-results-panel h3 { margin: 0; font: 700 18px "Songti SC", "Noto Serif CJK SC", serif; }
.search-results { display: grid; }
.search-results article {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: 13px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--line);
}
.search-results article:last-child { border-bottom: 0; }
.result-icon { width: 36px; height: 36px; border-radius: 9px; }
.search-results strong, .search-results span { display: block; }
.search-results strong { font-size: 13px; }
.search-results span { margin-top: 3px; color: var(--muted); font-size: 10px; }
.search-results p { margin: 7px 0 0; color: var(--ink-soft); font-size: 11px; line-height: 1.7; }
.compact-empty { min-height: 210px; }
.governance-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 14px; }
.overview-card {
  min-width: 0;
  display: grid;
  grid-template-columns: 38px 1fr auto;
  gap: 11px;
  align-items: center;
  padding: 15px;
  border: 1px solid var(--line);
  border-radius: var(--radius-md);
  background: rgba(255,255,255,.8);
  color: var(--ink);
  text-align: left;
  cursor: pointer;
  box-shadow: var(--shadow-sm);
}
.overview-card:hover { border-color: #b8c8bf; background: white; transform: translateY(-1px); }
.overview-card.active { border-color: #8ca99d; background: white; box-shadow: inset 0 0 0 1px #8ca99d, var(--shadow-sm); }
.overview-icon { width: 38px; height: 38px; border-radius: 10px; }
.overview-card.active .overview-icon { background: var(--forest); color: white; }
.overview-copy { min-width: 0; }
.overview-copy strong, .overview-copy small { display: block; }
.overview-copy strong { font-size: 12px; }
.overview-copy small { margin-top: 3px; overflow: hidden; color: var(--muted); font-size: 9px; line-height: 1.45; }
.overview-card b { color: var(--forest-2); font: 600 22px Georgia, serif; }
.governance-workspace { overflow: hidden; }
.workspace-heading {
  min-height: 84px;
  display: grid;
  grid-template-columns: auto 1fr auto;
  gap: 13px;
  align-items: center;
  padding: 17px 20px;
  border-bottom: 1px solid var(--line);
  background: linear-gradient(100deg, #fff, #f6f8f5);
}
.workspace-icon { width: 42px; height: 42px; border-radius: 11px; }
.workspace-heading h3 { margin: 0; font: 700 18px "Songti SC", "Noto Serif CJK SC", serif; }
.workspace-heading p { margin: 4px 0 0; color: var(--muted); font-size: 10px; }
.record-count { padding: 5px 9px; border-radius: 999px; background: var(--forest-3); color: var(--forest-2); font-size: 10px; font-weight: 650; }
.governance-list { display: grid; }
.governance-list article {
  min-height: 78px;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) minmax(210px, auto);
  gap: 13px;
  align-items: center;
  padding: 13px 20px;
  border-bottom: 1px solid var(--line);
}
.governance-list article:last-child { border-bottom: 0; }
.governance-list article:hover { background: #fbfcfa; }
.record-symbol { width: 36px; height: 36px; border-radius: 9px; }
.record-main { min-width: 0; }
.record-main strong, .record-main span, .record-meta small { display: block; }
.record-main strong { overflow: hidden; text-overflow: ellipsis; font-size: 13px; white-space: nowrap; }
.record-main span { margin-top: 4px; color: var(--muted); font-size: 10px; overflow-wrap: anywhere; }
.record-meta { min-width: 190px; text-align: right; }
.record-meta .status-pill, .risk-label { margin-left: auto; }
.record-meta small { margin-top: 6px; color: var(--muted); font-size: 9px; }
.status-pill.disabled { border-color: var(--line); background: var(--surface-subtle); color: var(--muted); }
.risk-label { width: max-content; display: block; padding: 5px 9px; border-radius: 999px; background: var(--brass-soft); color: var(--brass); font-size: 9px; font-weight: 700; }
.risk-label.high { background: #f4e4e2; color: var(--oxblood); }
.risk-label.low { background: var(--forest-3); color: var(--forest-2); }

@media (max-width: 1050px) {
  .search-panel { grid-template-columns: 1fr; gap: 14px; }
  .governance-overview { grid-template-columns: repeat(2, 1fr); }
}

@media (max-width: 680px) {
  .governance-intro { align-items: stretch; }
  .export-action { width: 100%; }
  .search-panel { padding: 15px; }
  .search-form { display: grid; grid-template-columns: auto 1fr auto; }
  .search-action { grid-column: 1 / -1; width: calc(100% + 28px); margin: 0 0 -1px -14px; border-radius: 0 0 9px 9px; }
  .governance-overview { grid-template-columns: 1fr; }
  .overview-copy small { white-space: normal; }
  .workspace-heading { grid-template-columns: auto 1fr; }
  .record-count { grid-column: 2; width: max-content; }
  .governance-list article { grid-template-columns: 36px 1fr; }
  .record-meta { grid-column: 2; min-width: 0; text-align: left; }
  .record-meta .status-pill, .risk-label { margin-left: 0; }
  .search-results-panel > header { align-items: flex-start; flex-direction: column; }
}
</style>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import {
  ArrowRight,
  BriefcaseBusiness,
  Building2,
  CircleDot,
  FolderOpen,
  Globe2,
  Plus,
  Search,
  ShieldCheck,
  UserRoundCheck,
  Users,
} from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import type { Client, CurrentUser, Matter, Office, Party } from '@/api/types'
import { translate as t, useI18n } from '@/i18n'
import { useRouter } from 'vue-router'
import { formatLegalCode } from '@/legalFormat'

type MatterFilter = 'all' | 'active' | 'intake' | 'paused' | 'closed'

const router = useRouter()
const { locale } = useI18n()
const allMatters = ref<Matter[]>([])
const offices = ref<Office[]>([])
const users = ref<Array<{ id: string; displayName: string }>>([])
const clients = ref<Client[]>([])
const parties = ref<Party[]>([])
const currentUser = ref<CurrentUser | null>(null)
const loading = ref(false)
const query = ref('')
const activeFilter = ref<MatterFilter>('all')
const selectedMatterId = ref('')
const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive({
  matterNumber: '',
  title: '',
  matterType: 'CROSS_BORDER',
  responsibleUserId: '',
  officeId: '',
  countryCode: '',
  jurisdiction: '',
  workingLanguage: 'zh-CN',
  billingCurrency: '',
  primaryClientId: '',
  opposingPartyIds: [] as string[],
})

const canCreate = computed(() => currentUser.value?.permissions.includes('MATTER_CREATE') ?? false)
const selectedOffice = computed(() => offices.value.find((office) => office.id === form.officeId))
const selectedClient = computed(() => clients.value.find((client) => client.id === form.primaryClientId))
const clientPartyId = computed(() => selectedClient.value?.partyId ?? '')
const opposingCandidates = computed(() => parties.value.filter((party) => party.id !== clientPartyId.value))
const activeCount = computed(() => allMatters.value.filter((matter) => matter.status === 'ACTIVE').length)
const intakeCount = computed(() => allMatters.value.filter((matter) => matter.status === 'CONFLICT_REVIEW').length)
const closedCount = computed(() => allMatters.value.filter((matter) => ['CLOSED', 'ARCHIVED'].includes(matter.status)).length)
const officeCount = computed(() => new Set(allMatters.value.map((matter) => matter.officeId).filter(Boolean)).size)

const filteredMatters = computed(() => {
  const needle = query.value.trim().toLocaleLowerCase()
  return allMatters.value.filter((matter) => {
    if (activeFilter.value === 'active' && matter.status !== 'ACTIVE') return false
    if (activeFilter.value === 'intake' && matter.status !== 'CONFLICT_REVIEW') return false
    if (activeFilter.value === 'paused' && matter.status !== 'SUSPENDED') return false
    if (activeFilter.value === 'closed' && !['CLOSED', 'ARCHIVED'].includes(matter.status)) return false
    if (!needle) return true
    return [
      matter.matterNumber,
      matter.title,
      matter.responsibleName,
      matter.jurisdiction,
      locale.value === 'en-US' ? matter.officeNameEn : matter.officeNameZh,
    ].some((value) => value?.toLocaleLowerCase().includes(needle))
  })
})

const selectedMatter = computed(() => (
  filteredMatters.value.find((matter) => matter.id === selectedMatterId.value)
  ?? filteredMatters.value[0]
  ?? null
))

const filters = computed<Array<{ value: MatterFilter; label: string }>>(() => [
  { value: 'all', label: t('matter.filterAll') },
  { value: 'active', label: t('matter.filterActive') },
  { value: 'intake', label: t('matter.filterIntake') },
  { value: 'paused', label: t('matter.filterPaused') },
  { value: 'closed', label: t('matter.filterClosed') },
])

async function load() {
  loading.value = true
  try {
    const [matterResult, officeResult, userResult, clientResult, partyResult, meResult] = await Promise.all([
      http.get<Matter[]>('/matters'),
      http.get<Office[]>('/offices'),
      http.get<Array<{ id: string; displayName: string }>>('/organization/users'),
      http.get<Client[]>('/clients'),
      http.get<Party[]>('/parties'),
      http.get<CurrentUser>('/me'),
    ])
    allMatters.value = matterResult.data
    offices.value = officeResult.data
    users.value = userResult.data
    clients.value = clientResult.data
    parties.value = partyResult.data
    currentUser.value = meResult.data
    if (!selectedMatterId.value || !allMatters.value.some((matter) => matter.id === selectedMatterId.value)) {
      selectedMatterId.value = allMatters.value[0]?.id ?? ''
    }
    if (!form.responsibleUserId) form.responsibleUserId = meResult.data.userId
    if (!form.officeId) selectOffice(meResult.data.primaryOfficeId ?? offices.value[0]?.id ?? '')
    if (!form.primaryClientId) form.primaryClientId = clients.value[0]?.id ?? ''
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('matter.loadFailed'))
  } finally {
    loading.value = false
  }
}

function selectOffice(officeId: string) {
  form.officeId = officeId
  const office = offices.value.find((item) => item.id === officeId)
  if (office) {
    form.countryCode = office.countryCode
    form.billingCurrency = office.defaultCurrency
  }
}

function openCreate() {
  form.opposingPartyIds = form.opposingPartyIds.filter((id) => id !== clientPartyId.value)
  dialogVisible.value = true
}

function toggleOpposingParty(partyId: string) {
  const index = form.opposingPartyIds.indexOf(partyId)
  if (index >= 0) form.opposingPartyIds.splice(index, 1)
  else form.opposingPartyIds.push(partyId)
}

async function createMatter() {
  if (!form.matterNumber.trim() || !form.title.trim() || !form.primaryClientId) {
    ElMessage.warning(t('matter.requiredFields'))
    return
  }
  const primary = clients.value.find((client) => client.id === form.primaryClientId)
  if (!primary) {
    ElMessage.warning(t('matter.clientRequired'))
    return
  }
  saving.value = true
  try {
    const response = await http.post<{ summary: Matter }>('/matters', {
      matterNumber: form.matterNumber,
      title: form.title,
      matterType: form.matterType,
      responsibleUserId: form.responsibleUserId,
      officeId: form.officeId || null,
      countryCode: form.countryCode || null,
      jurisdiction: form.jurisdiction || null,
      workingLanguage: form.workingLanguage,
      billingCurrency: form.billingCurrency || null,
      clientIds: [form.primaryClientId],
      parties: [
        { partyId: primary.partyId, partyRole: 'CLIENT', side: 'CLIENT' },
        ...form.opposingPartyIds
          .filter((partyId) => partyId !== primary.partyId)
          .map((partyId) => ({ partyId, partyRole: 'OPPOSING_PARTY', side: 'OPPOSING' })),
      ],
    })
    dialogVisible.value = false
    selectedMatterId.value = response.data.summary.id
    Object.assign(form, {
      matterNumber: '',
      title: '',
      jurisdiction: '',
      opposingPartyIds: [],
    })
    await load()
    ElMessage.success(t('matter.created'))
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : t('matter.createFailed'))
  } finally {
    saving.value = false
  }
}

function openWorkspace(matterId: string) {
  void router.push(`/matters/${matterId}`)
}

onMounted(load)
</script>

<template>
  <section class="module-page matter-portfolio">
    <header class="portfolio-hero">
      <div>
        <span class="eyebrow">{{ t('matter.kicker') }}</span>
        <h2>{{ t('matter.heroTitle') }}</h2>
        <p>{{ t('matter.heroDescription') }}</p>
      </div>
      <button v-if="canCreate" class="primary-action" type="button" @click="openCreate">
        <Plus :size="17" /> {{ t('matter.newMatter') }}
      </button>
    </header>

    <div class="portfolio-stats" :aria-label="t('matter.overview')">
      <article><FolderOpen :size="19" /><span>{{ t('matter.active') }}</span><strong>{{ activeCount }}</strong><small>{{ t('matter.activeHint') }}</small></article>
      <article :class="{ attention: intakeCount > 0 }"><ShieldCheck :size="19" /><span>{{ t('matter.intake') }}</span><strong>{{ intakeCount }}</strong><small>{{ t('matter.intakeHint') }}</small></article>
      <article><BriefcaseBusiness :size="19" /><span>{{ t('matter.closed') }}</span><strong>{{ closedCount }}</strong><small>{{ t('matter.closedHint') }}</small></article>
      <article><Globe2 :size="19" /><span>{{ t('matter.offices') }}</span><strong>{{ officeCount }}</strong><small>{{ t('matter.officesHint') }}</small></article>
    </div>

    <div class="portfolio-toolbar">
      <label class="search-box">
        <Search :size="17" />
        <input v-model="query" :placeholder="t('matter.searchPlaceholder')" />
      </label>
      <div class="filter-tabs" :aria-label="t('matter.filters')">
        <button
          v-for="filter in filters"
          :key="filter.value"
          type="button"
          :class="{ active: activeFilter === filter.value }"
          @click="activeFilter = filter.value"
        >{{ filter.label }}</button>
      </div>
    </div>

    <div class="portfolio-layout">
      <main class="matter-list panel">
        <div class="portfolio-heading">
          <div><span class="eyebrow">{{ t('matter.listKicker') }}</span><h3>{{ t('matter.listTitle') }}</h3></div>
          <span class="status-pill">{{ filteredMatters.length }}</span>
        </div>
        <div v-if="loading" class="portfolio-empty">{{ t('matter.loading') }}</div>
        <button
          v-for="matter in filteredMatters"
          v-else
          :key="matter.id"
          type="button"
          class="matter-row"
          :class="{ selected: selectedMatter?.id === matter.id }"
          @click="selectedMatterId = matter.id"
          @dblclick="openWorkspace(matter.id)"
        >
          <span class="matter-code">{{ matter.matterNumber }}</span>
          <span class="matter-copy">
            <strong>{{ matter.title }}</strong>
            <small>
              {{ matter.responsibleName }} ·
              {{ locale === 'en-US' ? matter.officeNameEn : matter.officeNameZh }}
            </small>
          </span>
          <span class="matter-jurisdiction">{{ matter.jurisdiction || matter.countryCode || '—' }}</span>
          <span class="status-pill">{{ formatLegalCode(matter.status, locale) }}</span>
          <ArrowRight :size="16" />
        </button>
        <div v-if="!loading && filteredMatters.length === 0" class="portfolio-empty">
          <BriefcaseBusiness :size="30" />
          <strong>{{ t('matter.noMatch') }}</strong>
          <span>{{ t('matter.noMatchHint') }}</span>
        </div>
      </main>

      <aside class="matter-preview panel">
        <template v-if="selectedMatter">
          <div class="preview-head">
            <span class="preview-icon"><BriefcaseBusiness :size="22" /></span>
            <div>
              <span class="eyebrow">{{ t('matter.previewKicker') }}</span>
              <h3>{{ selectedMatter.title }}</h3>
              <p class="matter-code">{{ selectedMatter.matterNumber }}</p>
            </div>
          </div>
          <div class="preview-status">
            <div>
              <span>{{ t('matter.lifecycle') }}</span>
              <strong>{{ formatLegalCode(selectedMatter.status, locale) }}</strong>
            </div>
            <CircleDot :size="20" />
          </div>
          <dl class="preview-facts">
            <div><dt><UserRoundCheck :size="15" />{{ t('matter.responsible') }}</dt><dd>{{ selectedMatter.responsibleName }}</dd></div>
            <div><dt><Building2 :size="15" />{{ t('matter.office') }}</dt><dd>{{ locale === 'en-US' ? selectedMatter.officeNameEn : selectedMatter.officeNameZh }}</dd></div>
            <div><dt><Globe2 :size="15" />{{ t('matter.jurisdiction') }}</dt><dd>{{ selectedMatter.jurisdiction || selectedMatter.countryCode || '—' }}</dd></div>
            <div><dt><Users :size="15" />{{ t('matter.team') }}</dt><dd>{{ selectedMatter.memberCount }}</dd></div>
          </dl>
          <div class="preview-language">
            <span>{{ formatLegalCode(selectedMatter.workingLanguage, locale) }}</span>
            <span>{{ selectedMatter.billingCurrency }}</span>
            <span>{{ formatLegalCode(selectedMatter.confidentialityLevel, locale) }}</span>
          </div>
          <button class="primary-action full" type="button" @click="openWorkspace(selectedMatter.id)">
            {{ t('matter.openWorkspace') }} <ArrowRight :size="16" />
          </button>
          <p class="preview-hint">{{ t('matter.workspaceHint') }}</p>
        </template>
        <div v-else class="portfolio-empty"><BriefcaseBusiness :size="30" /><strong>{{ t('matter.selectMatter') }}</strong></div>
      </aside>
    </div>

    <ElDialog v-model="dialogVisible" :title="t('matter.newMatter')" width="min(780px, 94vw)">
      <form class="dialog-form two-column-form intake-form" @submit.prevent="createMatter">
        <div class="intake-callout full-field">
          <ShieldCheck :size="18" />
          <div><strong>{{ t('matter.intakeGuide') }}</strong><span>{{ t('matter.intakeGuideHint') }}</span></div>
        </div>
        <label><span>{{ t('matter.number') }}</span><input v-model="form.matterNumber" required maxlength="80" :placeholder="t('matter.numberHint')" /></label>
        <label>
          <span>{{ t('matter.type') }}</span>
          <select v-model="form.matterType">
            <option value="CROSS_BORDER">{{ formatLegalCode('CROSS_BORDER', locale) }}</option>
            <option value="LITIGATION">{{ formatLegalCode('LITIGATION', locale) }}</option>
            <option value="ARBITRATION">{{ formatLegalCode('ARBITRATION', locale) }}</option>
            <option value="CORPORATE">{{ formatLegalCode('CORPORATE', locale) }}</option>
            <option value="ADVISORY">{{ formatLegalCode('ADVISORY', locale) }}</option>
          </select>
        </label>
        <label class="full-field"><span>{{ t('matter.title') }}</span><input v-model="form.title" required maxlength="300" :placeholder="t('matter.titleHint')" /></label>
        <label class="full-field">
          <span>{{ t('matter.primaryClient') }}</span>
          <select v-model="form.primaryClientId" required @change="form.opposingPartyIds = form.opposingPartyIds.filter((id) => id !== clientPartyId)">
            <option value="" disabled>{{ t('matter.selectClient') }}</option>
            <option v-for="client in clients" :key="client.id" :value="client.id">{{ client.clientNumber }} · {{ client.displayName }}</option>
          </select>
          <small>{{ t('matter.primaryClientHint') }}</small>
        </label>
        <fieldset class="party-picker full-field">
          <legend>{{ t('matter.opposingParties') }}</legend>
          <p>{{ t('matter.opposingHint') }}</p>
          <div>
            <button
              v-for="party in opposingCandidates"
              :key="party.id"
              type="button"
              :class="{ selected: form.opposingPartyIds.includes(party.id) }"
              @click="toggleOpposingParty(party.id)"
            >
              <span>{{ party.displayName }}</span>
              <small>{{ formatLegalCode(party.partyType, locale) }}</small>
            </button>
          </div>
          <span v-if="!opposingCandidates.length" class="picker-empty">{{ t('matter.noOpposingCandidates') }}</span>
        </fieldset>
        <label>
          <span>{{ t('matter.responsible') }}</span>
          <select v-model="form.responsibleUserId" required>
            <option v-for="user in users" :key="user.id" :value="user.id">{{ user.displayName }}</option>
          </select>
        </label>
        <label>
          <span>{{ t('matter.office') }}</span>
          <select :value="form.officeId" required @change="selectOffice(($event.target as HTMLSelectElement).value)">
            <option v-for="office in offices" :key="office.id" :value="office.id">{{ locale === 'en-US' ? office.nameEn : office.nameZh }}</option>
          </select>
        </label>
        <label><span>{{ t('matter.jurisdiction') }}</span><input v-model="form.jurisdiction" :placeholder="selectedOffice ? `${selectedOffice.countryCode} · ${selectedOffice.cityEn}` : ''" /></label>
        <label>
          <span>{{ t('matter.language') }}</span>
          <select v-model="form.workingLanguage">
            <option value="zh-CN">{{ formatLegalCode('zh-CN', locale) }}</option>
            <option value="en-US">{{ formatLegalCode('en-US', locale) }}</option>
            <option value="ar">{{ formatLegalCode('ar', locale) }}</option>
          </select>
        </label>
        <label><span>{{ t('matter.country') }}</span><input v-model="form.countryCode" maxlength="2" /></label>
        <label><span>{{ t('matter.currency') }}</span><input v-model="form.billingCurrency" maxlength="3" /></label>
        <button class="primary-action full full-field" type="submit" :disabled="saving || !clients.length">
          {{ saving ? t('common.saving') : t('matter.createAndReview') }}
        </button>
      </form>
    </ElDialog>
  </section>
</template>

<style scoped>
.matter-portfolio { padding-top: 6px; }
.portfolio-hero {
  min-height: 190px;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  padding: 34px 38px;
  overflow: hidden;
  border-radius: var(--radius-lg) var(--radius-lg) 0 0;
  background:
    radial-gradient(circle at 78% 18%, rgba(223,194,123,.12), transparent 14rem),
    linear-gradient(115deg, #153e33, #1e5948);
  color: white;
}
.portfolio-hero .eyebrow { color: var(--brass-on-dark); }
.portfolio-hero h2 { margin: 0; font: 700 31px/1.35 "Songti SC", serif; }
.portfolio-hero p { max-width: 700px; margin: 11px 0 0; color: #c4d1cc; font-size: 13px; line-height: 1.75; }
.portfolio-hero .primary-action { border-color: #d1b46f; background: #d1b46f; color: var(--ink); }
.portfolio-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  overflow: hidden;
  border: 1px solid var(--line);
  border-top: 0;
  border-radius: 0 0 var(--radius-lg) var(--radius-lg);
  background: white;
  box-shadow: var(--shadow-sm);
}
.portfolio-stats article { min-height: 118px; position: relative; padding: 22px; border-right: 1px solid var(--line); }
.portfolio-stats article:last-child { border-right: 0; }
.portfolio-stats svg { position: absolute; right: 20px; top: 20px; color: var(--forest-2); }
.portfolio-stats span, .portfolio-stats small { display: block; color: var(--muted); font-size: 10px; }
.portfolio-stats strong { display: block; margin: 12px 0 4px; font: 600 29px/1 Georgia, serif; }
.portfolio-stats .attention strong, .portfolio-stats .attention svg { color: var(--brass); }
.portfolio-toolbar { display: flex; justify-content: space-between; gap: 18px; margin: 20px 0 12px; padding: 12px; border: 1px solid var(--line); border-radius: var(--radius-md); background: white; }
.portfolio-layout { display: grid; grid-template-columns: minmax(0, 1.5fr) minmax(310px, .58fr); gap: 16px; align-items: start; }
.matter-list, .matter-preview { overflow: hidden; }
.portfolio-heading { min-height: 82px; display: flex; align-items: center; justify-content: space-between; padding: 18px 20px; border-bottom: 1px solid var(--line); }
.portfolio-heading h3 { margin: 0; font: 700 18px "Songti SC", serif; }
.matter-row {
  width: 100%;
  min-height: 88px;
  display: grid;
  grid-template-columns: 105px minmax(220px, 1fr) minmax(100px, .5fr) auto 18px;
  align-items: center;
  gap: 15px;
  padding: 14px 19px;
  border: 0;
  border-bottom: 1px solid var(--line);
  background: white;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}
.matter-row:hover { background: var(--surface-subtle); }
.matter-row.selected { background: var(--forest-3); box-shadow: inset 3px 0 var(--brass); }
.matter-code { color: var(--brass); font: 600 10px Georgia, monospace; letter-spacing: .03em; overflow-wrap: anywhere; }
.matter-copy { min-width: 0; }
.matter-copy strong, .matter-copy small { display: block; }
.matter-copy strong { overflow: hidden; text-overflow: ellipsis; font: 700 14px/1.4 "Songti SC", serif; white-space: nowrap; }
.matter-copy small { margin-top: 6px; color: var(--muted); font-size: 9px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.matter-jurisdiction { color: var(--muted); font-size: 9px; line-height: 1.5; }
.portfolio-empty { min-height: 280px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 8px; padding: 24px; color: var(--muted); text-align: center; font-size: 11px; }
.matter-preview { position: sticky; top: 92px; min-height: 500px; padding: 24px; }
.preview-head { display: flex; gap: 14px; align-items: flex-start; padding-bottom: 20px; border-bottom: 1px solid var(--line); }
.preview-icon { flex: 0 0 auto; width: 46px; height: 46px; display: grid; place-items: center; border-radius: 11px; color: var(--forest-2); background: var(--forest-3); }
.preview-head h3 { margin: 0; font: 700 19px/1.45 "Songti SC", serif; overflow-wrap: anywhere; }
.preview-head p { margin: 7px 0 0; }
.preview-status { display: flex; align-items: center; justify-content: space-between; margin: 20px 0; padding: 16px; border-left: 3px solid var(--brass); background: var(--surface-subtle); color: var(--forest-2); }
.preview-status span, .preview-status strong { display: block; }
.preview-status span { color: var(--muted); font-size: 9px; }
.preview-status strong { margin-top: 5px; font-size: 12px; }
.preview-facts { margin: 0; }
.preview-facts div { display: grid; grid-template-columns: 112px minmax(0, 1fr); gap: 10px; padding: 13px 0; border-bottom: 1px solid var(--line); }
.preview-facts dt { display: flex; align-items: center; gap: 7px; color: var(--muted); font-size: 9px; }
.preview-facts dd { margin: 0; font-size: 10px; text-align: right; overflow-wrap: anywhere; }
.preview-language { display: flex; flex-wrap: wrap; gap: 6px; margin: 18px 0; }
.preview-language span { padding: 5px 8px; border: 1px solid var(--line); border-radius: 999px; color: var(--muted); font-size: 8px; }
.preview-hint { margin: 12px 0 0; color: var(--muted); font-size: 9px; line-height: 1.65; text-align: center; }
.intake-callout { display: flex; gap: 11px; padding: 14px; border: 1px solid #cad8d1; border-radius: 9px; background: var(--forest-3); color: var(--forest-2); }
.intake-callout div { min-width: 0; }
.intake-callout strong, .intake-callout span { display: block; }
.intake-callout strong { font-size: 11px; }
.intake-callout span { margin-top: 4px; color: var(--muted); font-size: 9px; }
.intake-form label small { display: block; margin-top: 6px; color: var(--muted); font-size: 9px; }
.party-picker { min-width: 0; margin: 0; padding: 14px; border: 1px solid var(--line); border-radius: 9px; }
.party-picker legend { padding: 0 6px; color: var(--ink-soft); font-size: 11px; }
.party-picker > p { margin: 0 0 11px; color: var(--muted); font-size: 9px; }
.party-picker > div { max-height: 148px; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; overflow-y: auto; }
.party-picker button { min-width: 0; display: flex; justify-content: space-between; gap: 8px; padding: 9px 10px; border: 1px solid var(--line); border-radius: 7px; background: white; color: var(--ink); text-align: left; cursor: pointer; }
.party-picker button:hover { border-color: #aebfb7; }
.party-picker button.selected { border-color: var(--forest-2); background: var(--forest-3); box-shadow: inset 3px 0 var(--forest-2); }
.party-picker button span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; }
.party-picker button small { flex: 0 0 auto; color: var(--muted); font-size: 8px; }
.picker-empty { display: block; padding: 15px; color: var(--muted); text-align: center; font-size: 9px; }

@media (max-width: 1100px) {
  .portfolio-stats { grid-template-columns: repeat(2, 1fr); }
  .portfolio-stats article:nth-child(2) { border-right: 0; }
  .portfolio-stats article:nth-child(-n+2) { border-bottom: 1px solid var(--line); }
  .portfolio-layout { grid-template-columns: 1fr; }
  .matter-preview { position: static; min-height: 0; }
}

@media (max-width: 760px) {
  .portfolio-hero { min-height: 245px; padding: 26px 22px; align-items: flex-start; flex-direction: column; }
  .portfolio-hero h2 { font-size: 26px; }
  .portfolio-stats article { min-height: 108px; padding: 17px; }
  .portfolio-toolbar { flex-direction: column; }
  .matter-row { grid-template-columns: 80px minmax(0, 1fr) auto; gap: 10px; padding: 14px; }
  .matter-row .matter-jurisdiction, .matter-row > svg { display: none; }
  .matter-row .status-pill { grid-column: 3; grid-row: 1; }
  .party-picker > div { grid-template-columns: 1fr; }
}
</style>

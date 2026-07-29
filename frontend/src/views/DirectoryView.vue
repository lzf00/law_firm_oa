<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Building2, Mail, Search, UsersRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, translateWithParams as tp, useI18n } from '@/i18n'
import { formatLegalCodeList } from '@/legalFormat'

interface Department {
  id: string; name: string; memberCount: number
  officeNameZh?: string; officeNameEn?: string
}
interface Person {
  id: string; username: string; displayName: string; mobileMasked?: string
  email?: string; departments: string; roles: string; preferredLocale: string
  officeNameZh?: string; officeNameEn?: string; timezone?: string; defaultCurrency?: string
}
const { locale } = useI18n()
const departments = ref<Department[]>([])
const people = ref<Person[]>([])
const keyword = ref('')
const selectedDepartment = ref('')
const officeCount = computed(() => new Set(
  people.value.map((person) => locale.value === 'en-US'
    ? person.officeNameEn
    : person.officeNameZh).filter(Boolean),
).size)
const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  return people.value.filter((person) => {
    const departmentMatches = !selectedDepartment.value
      || person.departments.split(/[,，]/).map((value) => value.trim()).includes(selectedDepartment.value)
    const keywordMatches = !needle
      || `${person.displayName} ${person.departments} ${person.roles} ${person.email}`.toLowerCase().includes(needle)
    return departmentMatches && keywordMatches
  })
})
onMounted(async () => {
  try {
    const [departmentResult, peopleResult] = await Promise.all([
      http.get<Department[]>('/organization/departments'),
      http.get<Person[]>('/organization/directory'),
    ])
    departments.value = departmentResult.data
    people.value = peopleResult.data
  } catch { ElMessage.error(t('copy.0131')) }
})
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">ORGANIZATION · {{ people.length }} PEOPLE</span><h2>{{ t('headline.directory') }}</h2><p>{{ t('copy.0132') }}</p></div>
    </div>
    <div class="directory-layout">
      <aside class="panel department-panel">
        <div class="panel-heading"><div><span class="eyebrow">DEPARTMENTS</span><h3>{{ t('copy.0133') }}</h3></div><Building2 :size="20" /></div>
        <div class="department-list">
          <button type="button" :class="{ active: !selectedDepartment }" @click="selectedDepartment = ''">
            <span>{{ t('directory.allPeople') }}</span><strong>{{ people.length }}</strong>
          </button>
          <button
            v-for="department in departments"
            :key="department.id"
            type="button"
            :class="{ active: selectedDepartment === department.name }"
            @click="selectedDepartment = department.name"
          >
            <span>{{ department.name }}</span><strong>{{ department.memberCount }}</strong>
          </button>
        </div>
      </aside>
      <div>
        <section class="directory-metrics" :aria-label="t('directory.summary')">
          <span><strong>{{ filtered.length }}</strong><small>{{ t('directory.visiblePeople') }}</small></span>
          <span><strong>{{ departments.length }}</strong><small>{{ t('directory.departments') }}</small></span>
          <span><strong>{{ officeCount }}</strong><small>{{ t('directory.offices') }}</small></span>
        </section>
        <div class="toolbar">
          <label class="search-box wide"><Search :size="16" /><input v-model="keyword" :placeholder="t('copy.0134')" /></label>
          <button v-if="selectedDepartment || keyword" type="button" class="clear-filter" @click="selectedDepartment = ''; keyword = ''">{{ t('directory.clear') }}</button>
        </div>
        <div class="directory-grid">
          <article v-for="person in filtered" :key="person.id" class="person-card">
            <div class="person-avatar">{{ person.displayName.slice(0, 1) }}</div>
            <div><h3>{{ person.displayName }}</h3><span>{{ (locale === 'en-US' ? person.officeNameEn : person.officeNameZh) || person.departments || (t('copy.0361')) }}</span><small>{{ formatLegalCodeList(person.roles, locale) }} · {{ person.timezone || '—' }}</small></div>
            <a v-if="person.email" :href="`mailto:${person.email}`" :aria-label="tp('copy.dynamic.emailPerson', { name: person.displayName })"><Mail :size="16" /></a>
          </article>
          <div v-if="!filtered.length" class="panel empty-state"><UsersRound :size="28" /> {{ t('copy.0135') }}</div>
        </div>
      </div>
    </div>
  </section>
</template>

<style scoped>
.department-list { display: grid; gap: 3px; }
.department-list button {
  width: 100%; min-height: 43px; display: flex; align-items: center; justify-content: space-between;
  padding: 0 10px; border: 0; border-radius: 8px; background: transparent;
  color: var(--ink); cursor: pointer; text-align: left; font-size: 10px;
}
.department-list button:hover { background: var(--forest-3); }
.department-list button.active { background: var(--forest); color: white; }
.department-list button strong { color: inherit; font: 650 11px Georgia, serif; }
.directory-metrics {
  display: grid; grid-template-columns: repeat(3, 1fr); gap: 1px; margin-bottom: 10px;
  overflow: hidden; border: 1px solid var(--line); border-radius: 10px; background: var(--line);
}
.directory-metrics > span { padding: 11px 14px; background: rgba(255,255,255,.8); }
.directory-metrics strong, .directory-metrics small { display: block; }
.directory-metrics strong { font: 650 18px/1 Georgia, serif; color: var(--forest); }
.directory-metrics small { margin-top: 4px; color: var(--muted); font-size: 8px; }
.toolbar { display: flex; align-items: center; gap: 8px; }
.clear-filter {
  min-height: 40px; padding: 0 12px; border: 1px solid var(--line); border-radius: 8px;
  background: white; color: var(--forest-2); cursor: pointer; font-size: 9px; font-weight: 650;
}
.person-card { transition: transform .18s ease, box-shadow .18s ease; }
.person-card:hover { transform: translateY(-2px); box-shadow: var(--shadow-md); }
@media (max-width: 700px) {
  .directory-metrics { grid-template-columns: 1fr 1fr 1fr; }
  .toolbar { align-items: stretch; flex-direction: column; }
}
</style>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Building2, Mail, Search, UsersRound } from '@lucide/vue'
import { ElMessage } from 'element-plus'
import { http } from '@/api/http'
import { translate as t, useI18n } from '@/i18n'
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
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
const departments = ref<Department[]>([])
const people = ref<Person[]>([])
const keyword = ref('')
const filtered = computed(() => {
  const needle = keyword.value.trim().toLowerCase()
  return needle ? people.value.filter((person) =>
    `${person.displayName} ${person.departments} ${person.roles} ${person.email}`.toLowerCase().includes(needle),
  ) : people.value
})
onMounted(async () => {
  try {
    const [departmentResult, peopleResult] = await Promise.all([
      http.get<Department[]>('/organization/departments'),
      http.get<Person[]>('/organization/directory'),
    ])
    departments.value = departmentResult.data
    people.value = peopleResult.data
  } catch { ElMessage.error(text('通讯录加载失败', 'Failed to load directory')) }
})
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div><span class="eyebrow">ORGANIZATION · {{ people.length }} PEOPLE</span><h2>{{ t('headline.directory') }}</h2><p>{{ text('通讯录与钉钉组织身份可同步，展示部门、岗位角色、脱敏联系方式和账号状态。', 'The directory can synchronize DingTalk identities and shows departments, roles, masked contacts and account status.') }}</p></div>
    </div>
    <div class="directory-layout">
      <aside class="panel department-panel">
        <div class="panel-heading"><div><span class="eyebrow">DEPARTMENTS</span><h3>{{ text('组织架构', 'Organization') }}</h3></div><Building2 :size="20" /></div>
        <div class="department-list"><div v-for="department in departments" :key="department.id"><span>{{ department.name }}</span><strong>{{ department.memberCount }}</strong></div></div>
      </aside>
      <div>
        <div class="toolbar"><label class="search-box wide"><Search :size="16" /><input v-model="keyword" :placeholder="text('搜索姓名、部门、角色或邮箱', 'Search name, department, role or email')" /></label></div>
        <div class="directory-grid">
          <article v-for="person in filtered" :key="person.id" class="person-card">
            <div class="person-avatar">{{ person.displayName.slice(0, 1) }}</div>
            <div><h3>{{ person.displayName }}</h3><span>{{ (locale === 'en-US' ? person.officeNameEn : person.officeNameZh) || person.departments || (locale === 'en-US' ? 'Unassigned' : '未分配部门') }}</span><small>{{ formatLegalCodeList(person.roles, locale) }} · {{ person.timezone || '—' }}</small></div>
            <a v-if="person.email" :href="`mailto:${person.email}`" :aria-label="text(`邮件联系${person.displayName}`, `Email ${person.displayName}`)"><Mail :size="16" /></a>
          </article>
          <div v-if="!filtered.length" class="panel empty-state"><UsersRound :size="28" /> {{ text('未找到匹配成员', 'No matching members') }}</div>
        </div>
      </div>
    </div>
  </section>
</template>

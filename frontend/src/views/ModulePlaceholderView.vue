<script setup lang="ts">
import { Construction, LockKeyhole } from '@lucide/vue'
import { useI18n } from '@/i18n'

defineProps<{ title: string; eyebrow: string; description: string }>()
const { locale } = useI18n()
const text = (zh: string, en: string) => locale.value === 'en-US' ? en : zh
</script>

<template>
  <section class="module-page">
    <div class="page-intro">
      <div>
        <span class="eyebrow">{{ eyebrow }}</span>
        <h2>{{ title }}</h2>
        <p>{{ description }}</p>
      </div>
    </div>
    <div class="panel placeholder-panel">
      <div class="placeholder-icon"><Construction :size="28" /></div>
      <h3>{{ text('模块骨架已就位', 'Module foundation is ready') }}</h3>
      <p>{{ text('数据库边界与导航已建立，业务接口和权限测试正在按计划接入。', 'Database boundaries and navigation are in place; APIs and authorization tests are being added.') }}</p>
      <div class="secure-note"><LockKeyhole :size="16" /> {{ text('本模块上线前必须通过越权与审计验证', 'Authorization and audit checks are required before release') }}</div>
    </div>
  </section>
</template>

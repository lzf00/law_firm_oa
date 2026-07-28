<script setup lang="ts">
import { Construction, LockKeyhole } from '@lucide/vue'
import { useI18n } from '@/i18n'

defineProps<{ title: string; eyebrow: string; description: string }>()
const { locale, t } = useI18n()
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
      <h3>{{ t('copy.0284') }}</h3>
      <p>{{ t('copy.0285') }}</p>
      <div class="secure-note"><LockKeyhole :size="16" /> {{ t('copy.0286') }}</div>
    </div>
  </section>
</template>

<script setup lang="ts">
import { Construction, LockKeyhole } from '@lucide/vue'

defineProps<{ title: string; eyebrow: string; description: string }>()
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
      <h3>模块骨架已就位</h3>
      <p>数据库边界与导航已建立，业务接口和权限测试正在按计划接入。</p>
      <div class="secure-note"><LockKeyhole :size="16" /> 本模块上线前必须通过越权与审计验证</div>
    </div>
  </section>
</template>

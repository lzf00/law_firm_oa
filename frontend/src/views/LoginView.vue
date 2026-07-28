<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  BriefcaseBusiness,
  Check,
  FileLock2,
  Languages,
  LoaderCircle,
  Scale,
  ShieldCheck,
} from '@lucide/vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '@/api/http'
import { useI18n } from '@/i18n'
import { useTenant } from '@/tenant'

type LoginConfiguration = {
  authMode: 'dev' | 'dingtalk'
  dingtalkConfigured: boolean
  authorizationStateTtlSeconds: number
}

const route = useRoute()
const router = useRouter()
const configuration = ref<LoginConfiguration | null>(null)
const loading = ref(true)
const submitting = ref(false)
const error = ref('')
const { locale, t, toggleLocale } = useI18n()
const { tenant, brandName } = useTenant()
const isDevelopment = computed(() => configuration.value?.authMode === 'dev')
const reason = computed(() => route.query.reason === 'session-expired'
  ? t('login.sessionExpired')
  : '')

onMounted(async () => {
  try {
    configuration.value = (await http.get<LoginConfiguration>('/auth/config')).data
  } catch {
    error.value = t('login.identityUnavailable')
  } finally {
    loading.value = false
  }
})

async function enterDevelopment() {
  sessionStorage.setItem('law_oa_demo_entered', 'true')
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
  await router.replace(redirect)
}

async function beginDingTalkLogin() {
  submitting.value = true
  error.value = ''
  try {
    const response = await http.post<{ authorizationUrl: string }>('/auth/dingtalk/begin')
    window.location.assign(response.data.authorizationUrl)
  } catch (exception) {
    error.value = exception instanceof Error ? exception.message : t('login.identityUnavailable')
    submitting.value = false
  }
}
</script>

<template>
  <main class="login-page">
    <div class="login-grain" aria-hidden="true" />
    <section class="login-story">
      <div class="login-brand">
        <span class="login-brand-mark"><Scale :size="24" /></span>
        <div>
          <strong>{{ brandName }}</strong>
          <span>{{ locale === 'zh-CN' ? tenant.brandNameEn : tenant.shortNameZh }}</span>
        </div>
      </div>

      <div class="login-statement">
        <span class="login-kicker">{{ t('login.kicker') }}</span>
        <h1>{{ t('login.headline') }}</h1>
        <p>{{ t('login.story') }}</p>
      </div>

      <div class="login-principles">
        <div><span>01</span><p>{{ t('login.principle1') }}</p></div>
        <div><span>02</span><p>{{ t('login.principle2') }}</p></div>
        <div><span>03</span><p>{{ t('login.principle3') }}</p></div>
      </div>
    </section>

    <section class="login-access">
      <button
        class="login-language"
        type="button"
        :aria-label="t('shell.language')"
        @click="toggleLocale"
      >
        <Languages :size="16" />{{ locale === 'zh-CN' ? 'EN' : '中文' }}
      </button>
      <div class="access-card">
        <div class="access-icon"><BriefcaseBusiness :size="25" /></div>
        <span class="access-eyebrow">INTERNAL ACCESS</span>
        <h2>{{ t('login.access') }}</h2>
        <p class="access-intro">{{ t('login.accessIntro') }}</p>

        <div v-if="loading" class="login-loading">
          <LoaderCircle :size="22" class="spin" />
          {{ t('login.checking') }}
        </div>

        <template v-else>
          <p v-if="reason" class="login-notice">{{ reason }}</p>
          <p v-if="error" class="login-error">{{ error }}</p>

          <button
            v-if="isDevelopment"
            class="login-primary"
            type="button"
            @click="enterDevelopment"
          >
            <span><small>LOCAL DEMO</small>{{ t('login.demo') }}</span>
            <ArrowRight :size="20" />
          </button>

          <button
            v-else
            class="login-primary"
            type="button"
            :disabled="submitting || !configuration?.dingtalkConfigured"
            @click="beginDingTalkLogin"
          >
            <span>
              <small>DINGTALK SSO</small>
              {{ submitting ? t('login.redirecting') : t('login.dingtalk') }}
            </span>
            <LoaderCircle v-if="submitting" :size="20" class="spin" />
            <ArrowRight v-else :size="20" />
          </button>

          <p
            v-if="!isDevelopment && !configuration?.dingtalkConfigured"
            class="configuration-note"
          >
            {{ t('login.dingtalkMissing') }}
          </p>
        </template>

        <div class="access-divider"><span>{{ t('login.boundary') }}</span></div>
        <ul class="access-assurances">
          <li><ShieldCheck :size="17" /><span>{{ t('login.assurance1') }}</span><Check :size="15" /></li>
          <li><FileLock2 :size="17" /><span>{{ t('login.assurance2') }}</span><Check :size="15" /></li>
        </ul>
        <p class="access-footnote">{{ t('login.footnote') }}</p>
      </div>

      <div class="login-corner">
        <span>WIN / OA</span>
        <small>PRIVATE DEPLOYMENT</small>
      </div>
    </section>
  </main>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1.18fr) minmax(420px, .82fr);
  color: #f4f0e7;
  background: #10241e;
  overflow: hidden;
  position: relative;
}
.login-grain {
  position: fixed;
  inset: 0;
  opacity: .16;
  pointer-events: none;
  z-index: 3;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 180 180' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='.9' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)' opacity='.16'/%3E%3C/svg%3E");
}
.login-story {
  min-height: 100vh;
  padding: clamp(34px, 5vw, 72px) clamp(38px, 7vw, 110px);
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  position: relative;
  isolation: isolate;
}
.login-story::before {
  content: "";
  position: absolute;
  inset: 0;
  z-index: -2;
  background:
    radial-gradient(circle at 78% 18%, rgba(178, 142, 74, .16), transparent 30%),
    linear-gradient(135deg, transparent 0 56%, rgba(255, 255, 255, .025) 56% 57%, transparent 57%);
}
.login-story::after {
  content: "法";
  position: absolute;
  right: 4%;
  bottom: -10%;
  z-index: -1;
  font: 700 clamp(280px, 38vw, 620px) "Songti SC", "STSong", serif;
  color: transparent;
  -webkit-text-stroke: 1px rgba(210, 190, 146, .09);
  line-height: 1;
}
.login-brand {
  display: flex;
  align-items: center;
  gap: 15px;
  animation: rise-in .6s ease both;
}
.login-brand-mark {
  width: 48px;
  height: 48px;
  display: grid;
  place-items: center;
  color: #c4a96e;
  border: 1px solid rgba(196, 169, 110, .58);
}
.login-brand strong, .login-brand span { display: block; }
.login-brand strong {
  font: 600 17px "Songti SC", "STSong", serif;
  letter-spacing: .12em;
}
.login-brand span {
  margin-top: 5px;
  color: rgba(244, 240, 231, .46);
  font-size: 9px;
  letter-spacing: .28em;
}
.login-statement { max-width: 650px; margin: 8vh 0; }
.login-kicker {
  color: #c4a96e;
  font-size: 10px;
  letter-spacing: .32em;
  animation: rise-in .7s .08s ease both;
}
.login-statement h1 {
  margin: 24px 0 28px;
  font: 600 clamp(56px, 7vw, 104px)/1.02 "Songti SC", "STSong", serif;
  letter-spacing: -.04em;
  animation: rise-in .8s .14s ease both;
}
.login-statement p {
  max-width: 460px;
  margin: 0;
  color: rgba(244, 240, 231, .62);
  font: 14px/2 "Noto Serif SC", "Songti SC", serif;
  letter-spacing: .08em;
  animation: rise-in .7s .22s ease both;
}
.login-principles {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  max-width: 620px;
  border-top: 1px solid rgba(244, 240, 231, .16);
  animation: rise-in .7s .3s ease both;
}
.login-principles div { padding: 20px 24px 0 0; }
.login-principles span { color: #c4a96e; font: 10px/1 monospace; }
.login-principles p {
  margin: 11px 0 0;
  color: rgba(244, 240, 231, .72);
  font: 12px/1.75 "Songti SC", serif;
  letter-spacing: .12em;
  white-space: pre-line;
}
.login-language {
  position: absolute;
  top: 28px;
  right: 32px;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  min-height: 36px;
  padding: 0 12px;
  color: #17372d;
  border: 1px solid #cfc7b7;
  background: rgba(255, 253, 248, .78);
  cursor: pointer;
  z-index: 4;
}
.login-access {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: clamp(26px, 5vw, 76px);
  position: relative;
  color: #1c2b27;
  background:
    linear-gradient(rgba(226, 219, 202, .24) 1px, transparent 1px),
    linear-gradient(90deg, rgba(226, 219, 202, .2) 1px, transparent 1px),
    #f3efe6;
  background-size: 44px 44px;
}
.access-card {
  width: min(450px, 100%);
  padding: clamp(34px, 5vw, 58px);
  background: rgba(255, 253, 248, .94);
  border: 1px solid #d8d0bf;
  box-shadow: 0 26px 70px rgba(30, 40, 35, .14);
  animation: card-in .75s .1s cubic-bezier(.2, .8, .2, 1) both;
}
.access-icon {
  width: 52px;
  height: 52px;
  display: grid;
  place-items: center;
  margin-bottom: 28px;
  color: #f7f3e9;
  background: #17372d;
  box-shadow: 7px 7px 0 #c4a96e;
}
.access-eyebrow { color: #8b7445; font-size: 9px; letter-spacing: .3em; }
.access-card h2 {
  margin: 13px 0 9px;
  font: 600 31px/1.25 "Songti SC", "STSong", serif;
}
.access-intro, .configuration-note, .access-footnote {
  color: #7c817c;
  font-size: 12px;
}
.login-loading {
  height: 68px;
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 28px;
  color: #69736e;
  font-size: 13px;
}
.login-notice, .login-error {
  margin: 22px 0 -10px;
  padding: 10px 12px;
  font-size: 12px;
  line-height: 1.6;
}
.login-notice { color: #725a2b; background: #f7efd9; border-left: 3px solid #b28e4a; }
.login-error { color: #842f27; background: #f7e7e4; border-left: 3px solid #a64a3e; }
.login-primary {
  width: 100%;
  min-height: 68px;
  margin-top: 28px;
  padding: 0 22px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  color: #fffdf7;
  background: #17372d;
  border: 0;
  cursor: pointer;
  transition: transform .2s ease, background .2s ease, box-shadow .2s ease;
}
.login-primary:hover:not(:disabled) {
  transform: translateY(-2px);
  background: #214b3e;
  box-shadow: 0 12px 24px rgba(23, 55, 45, .18);
}
.login-primary:disabled { cursor: not-allowed; opacity: .48; }
.login-primary span {
  display: grid;
  gap: 5px;
  text-align: left;
  font: 600 14px "Songti SC", serif;
  letter-spacing: .08em;
}
.login-primary small {
  color: #cdb77f;
  font: 8px/1 sans-serif;
  letter-spacing: .28em;
}
.configuration-note { margin: 12px 0 0; color: #9b4238; line-height: 1.7; }
.access-divider {
  margin: 34px 0 20px;
  display: flex;
  align-items: center;
  gap: 12px;
  color: #9b998f;
  font-size: 10px;
  letter-spacing: .18em;
}
.access-divider::before, .access-divider::after {
  content: "";
  height: 1px;
  flex: 1;
  background: #ded8ca;
}
.access-assurances { display: grid; gap: 14px; margin: 0; padding: 0; list-style: none; }
.access-assurances li {
  display: grid;
  grid-template-columns: auto 1fr auto;
  align-items: center;
  gap: 10px;
  color: #646b67;
  font-size: 11px;
}
.access-assurances li > svg:first-child { color: #856e40; }
.access-assurances li > svg:last-child { color: #4b7565; }
.access-footnote {
  margin: 28px 0 0;
  padding-top: 20px;
  border-top: 1px solid #e0dbd0;
  line-height: 1.7;
  text-align: center;
}
.login-corner { position: absolute; right: 30px; bottom: 24px; display: grid; text-align: right; }
.login-corner span { color: #17372d; font: 700 12px/1 monospace; letter-spacing: .18em; }
.login-corner small { margin-top: 5px; color: #9d978a; font-size: 7px; letter-spacing: .2em; }
.spin { animation: spin 1s linear infinite; }
@keyframes rise-in {
  from { opacity: 0; transform: translateY(18px); }
  to { opacity: 1; transform: translateY(0); }
}
@keyframes card-in {
  from { opacity: 0; transform: translateY(25px) scale(.985); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}
@keyframes spin { to { transform: rotate(360deg); } }
@media (max-width: 900px) {
  .login-page { grid-template-columns: 1fr; overflow: visible; }
  .login-story { min-height: 54vh; padding: 32px 30px 42px; }
  .login-statement { margin: 10vh 0 6vh; }
  .login-statement h1 { font-size: clamp(50px, 15vw, 76px); }
  .login-principles { display: none; }
  .login-access { min-height: 46vh; padding: 34px 22px 70px; }
  .access-card { margin-top: -36px; position: relative; z-index: 2; }
}
@media (max-width: 520px) {
  .login-brand strong { font-size: 15px; }
  .login-story { min-height: 48vh; }
  .login-statement p { font-size: 12px; }
  .access-card { padding: 32px 25px; }
}
@media (prefers-reduced-motion: reduce) {
  *, *::before, *::after { animation-duration: .01ms !important; }
}
</style>

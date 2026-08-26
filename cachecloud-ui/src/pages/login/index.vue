<script lang="ts" setup>
import type { FormRules } from "element-plus"
import type { LoginRequestData } from "./apis/type"
import ThemeSwitch from "@@/components/ThemeSwitch/index.vue"
import { Lock, User } from "@element-plus/icons-vue"
import { useSettingsStore } from "@/pinia/stores/settings"
import { useUserStore } from "@/pinia/stores/user"
import { loginApi } from "./apis"

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const settingsStore = useSettingsStore()

const loginFormRef = useTemplateRef("loginFormRef")
const loading = ref(false)

const parallax = reactive({ x: 0, y: 0, targetX: 0, targetY: 0 })
let parallaxFrame = 0
let parallaxEnabled = true

const parallaxStyle = computed(() => ({
  "--px": parallax.x,
  "--py": parallax.y
}))

function updateParallaxTarget(clientX: number, clientY: number) {
  parallax.targetX = (clientX / window.innerWidth - 0.5) * 2
  parallax.targetY = (clientY / window.innerHeight - 0.5) * 2
  if (!parallaxFrame) parallaxFrame = requestAnimationFrame(tickParallax)
}

function handleMouseMove(event: MouseEvent) {
  if (!parallaxEnabled) return
  updateParallaxTarget(event.clientX, event.clientY)
}

function tickParallax() {
  parallax.x += (parallax.targetX - parallax.x) * 0.1
  parallax.y += (parallax.targetY - parallax.y) * 0.1
  if (Math.abs(parallax.targetX - parallax.x) > 0.002 || Math.abs(parallax.targetY - parallax.y) > 0.002) {
    parallaxFrame = requestAnimationFrame(tickParallax)
  } else {
    parallaxFrame = 0
  }
}

onMounted(() => {
  parallaxEnabled = !window.matchMedia("(prefers-reduced-motion: reduce)").matches
})

onUnmounted(() => {
  if (parallaxFrame) cancelAnimationFrame(parallaxFrame)
})

const loginFormData: LoginRequestData = reactive({
  username: "",
  password: "",
  isAdmin: true
})

const loginFormRules: FormRules = {
  username: [{ required: true, message: "请输入用户名", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }]
}

function handleLogin() {
  loginFormRef.value?.validate((valid) => {
    if (!valid) {
      ElMessage.error("表单校验不通过")
      return
    }
    loading.value = true
    loginApi(loginFormData).then(({ data }) => {
      userStore.setToken(data.token)
      router.push(route.query.redirect ? decodeURIComponent(route.query.redirect as string) : "/total/statlist")
    }).catch(() => {
      loginFormData.password = ""
    }).finally(() => {
      loading.value = false
    })
  })
}
</script>

<template>
  <div class="login-container" @mousemove="handleMouseMove">
    <div class="login-bg" :style="parallaxStyle" aria-hidden="true">
      <div class="login-bg__mesh" />
      <div class="login-bg__grid" />
      <div class="login-bg__orb login-bg__orb--1" />
      <div class="login-bg__orb login-bg__orb--2" />
      <div class="login-bg__orb login-bg__orb--3" />
      <div class="login-bg__cluster">
        <span class="login-bg__node login-bg__node--center" />
        <span class="login-bg__node login-bg__node--a" />
        <span class="login-bg__node login-bg__node--b" />
        <span class="login-bg__node login-bg__node--c" />
        <span class="login-bg__node login-bg__node--d" />
        <span class="login-bg__link login-bg__link--1" />
        <span class="login-bg__link login-bg__link--2" />
        <span class="login-bg__link login-bg__link--3" />
        <span class="login-bg__link login-bg__link--4" />
      </div>
    </div>
    <ThemeSwitch v-if="settingsStore.showThemeSwitch" class="theme-switch" />
    <div class="login-shell">
      <div class="login-card">
        <div class="login-brand">
          <div class="login-brand__icon">R</div>
          <h1 class="login-brand__title">Redis 管理平台</h1>
          <p class="login-brand__subtitle">统一运维 · 监控 · 纳管</p>
        </div>
        <div class="login-form">
          <el-form ref="loginFormRef" :model="loginFormData" :rules="loginFormRules" @submit.prevent="handleLogin" @keyup.enter="handleLogin">
            <el-form-item prop="username">
              <label class="login-field-label" for="login-username">用户名</label>
              <el-input
                id="login-username"
                v-model.trim="loginFormData.username"
                placeholder="请输入用户名"
                type="text"
                tabindex="1"
                :prefix-icon="User"
                size="large"
              />
            </el-form-item>
            <el-form-item prop="password">
              <label class="login-field-label" for="login-password">密码</label>
              <el-input
                id="login-password"
                v-model.trim="loginFormData.password"
                placeholder="请输入密码"
                type="password"
                tabindex="2"
                :prefix-icon="Lock"
                size="large"
                show-password
              />
            </el-form-item>
            <el-button :loading="loading" type="primary" size="large" native-type="button" class="login-submit" @click.prevent="handleLogin">
              登 录
            </el-button>
          </el-form>
        </div>
      </div>
    </div>
  </div>
</template>

<style lang="scss" scoped>
.login-container {
  position: relative;
  width: 100%;
  min-height: 100vh;
  min-height: 100dvh;
  color: var(--rp-text, #1a2332);
  background: var(--rp-bg, #f0f4f8);

  .theme-switch {
    position: fixed;
    top: 5%;
    right: 5%;
    z-index: 2;
    cursor: pointer;
  }
}

.login-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  background: linear-gradient(145deg, #dce8ff 0%, #edf2f9 38%, #f5f8fc 68%, #e6eef9 100%);
}

.login-bg__mesh {
  position: absolute;
  inset: -8%;
  will-change: transform;
  transform: translate3d(
    calc(var(--px, 0) * 22px),
    calc(var(--py, 0) * 16px),
    0
  );
  background:
    radial-gradient(
      ellipse 45% 35% at calc(50% + var(--px, 0) * 6%) calc(50% + var(--py, 0) * 5%),
      rgba(77, 141, 255, 0.34),
      transparent 62%
    ),
    radial-gradient(ellipse 80% 60% at 10% 15%, rgba(77, 141, 255, 0.28), transparent 55%),
    radial-gradient(ellipse 70% 55% at 92% 88%, rgba(45, 107, 232, 0.22), transparent 52%),
    radial-gradient(ellipse 50% 40% at 78% 18%, rgba(107, 160, 255, 0.16), transparent 48%);
  transition: transform 0.12s ease-out;
}

.login-bg__grid {
  position: absolute;
  inset: -4%;
  will-change: transform;
  transform: translate3d(
    calc(var(--px, 0) * -10px),
    calc(var(--py, 0) * -8px),
    0
  );
  opacity: 0.45;
  background-image:
    linear-gradient(rgba(77, 141, 255, 0.07) 1px, transparent 1px),
    linear-gradient(90deg, rgba(77, 141, 255, 0.07) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 85% 75% at 50% 45%, #000 20%, transparent 78%);
}

.login-bg__orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(40px);
  opacity: 0.75;
  will-change: transform;
}

.login-bg__orb--1 {
  width: 420px;
  height: 420px;
  top: -8%;
  left: -6%;
  background: radial-gradient(circle, rgba(107, 160, 255, 0.55) 0%, rgba(77, 141, 255, 0.08) 70%);
  transform: translate3d(
    calc(var(--px, 0) * 36px),
    calc(var(--py, 0) * 28px),
    0
  );
}

.login-bg__orb--2 {
  width: 360px;
  height: 360px;
  right: -4%;
  bottom: -6%;
  background: radial-gradient(circle, rgba(45, 107, 232, 0.45) 0%, rgba(45, 107, 232, 0.06) 72%);
  transform: translate3d(
    calc(var(--px, 0) * -30px),
    calc(var(--py, 0) * -24px),
    0
  );
}

.login-bg__orb--3 {
  width: 240px;
  height: 240px;
  top: 42%;
  left: 58%;
  background: radial-gradient(circle, rgba(130, 175, 255, 0.35) 0%, transparent 70%);
  transform: translate3d(
    calc(var(--px, 0) * 20px),
    calc(var(--py, 0) * -18px),
    0
  );
}

.login-bg__cluster {
  position: absolute;
  right: 8%;
  top: 50%;
  width: 320px;
  height: 320px;
  opacity: 0.55;
  will-change: transform;
  transform: translate3d(
    calc(var(--px, 0) * -26px),
    calc(-50% + var(--py, 0) * -22px),
    0
  );
}

.login-bg__node {
  position: absolute;
  border-radius: 50%;
  border: 2px solid rgba(77, 141, 255, 0.35);
  background: rgba(255, 255, 255, 0.72);
  box-shadow: 0 8px 24px rgba(77, 141, 255, 0.18);
}

.login-bg__node--center {
  width: 72px;
  height: 72px;
  left: 50%;
  top: 50%;
  transform: translate(-50%, -50%);
  background: linear-gradient(145deg, rgba(107, 160, 255, 0.35), rgba(255, 255, 255, 0.9));
}

.login-bg__node--a {
  width: 44px;
  height: 44px;
  left: 18%;
  top: 22%;
}

.login-bg__node--b {
  width: 40px;
  height: 40px;
  right: 12%;
  top: 28%;
}

.login-bg__node--c {
  width: 38px;
  height: 38px;
  left: 14%;
  bottom: 18%;
}

.login-bg__node--d {
  width: 42px;
  height: 42px;
  right: 16%;
  bottom: 14%;
}

.login-bg__link {
  position: absolute;
  height: 2px;
  background: linear-gradient(90deg, transparent, rgba(77, 141, 255, 0.35), transparent);
  transform-origin: left center;
}

.login-bg__link--1 {
  width: 110px;
  left: 28%;
  top: 34%;
  transform: rotate(24deg);
}

.login-bg__link--2 {
  width: 120px;
  left: 52%;
  top: 30%;
  transform: rotate(-18deg);
}

.login-bg__link--3 {
  width: 108px;
  left: 26%;
  top: 62%;
  transform: rotate(-28deg);
}

.login-bg__link--4 {
  width: 115px;
  left: 54%;
  top: 66%;
  transform: rotate(20deg);
}

.login-shell {
  position: relative;
  z-index: 1;
  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px 20px;
}

.login-card {
  width: 100%;
  max-width: 420px;
  padding: 40px 36px 32px;
  border: 1px solid rgba(255, 255, 255, 0.75);
  border-radius: 18px;
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(20px);
  box-shadow:
    0 24px 60px rgba(26, 35, 50, 0.14),
    0 0 0 1px rgba(77, 141, 255, 0.08) inset;
}

.login-brand {
  text-align: center;
  margin-bottom: 32px;
}

.login-brand__icon {
  width: 56px;
  height: 56px;
  margin: 0 auto 16px;
  border-radius: 14px;
  background: var(--rp-primary-gradient, linear-gradient(135deg, #6ba0ff 0%, #4d8dff 55%, #2d6be8 100%));
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 30px;
  font-weight: 700;
  box-shadow: 0 10px 24px rgba(77, 141, 255, 0.35);
}

.login-brand__title {
  margin: 0 0 8px;
  font-size: 26px;
  font-weight: 600;
  color: var(--rp-text, #1a2332);
}

.login-brand__subtitle {
  margin: 0;
  font-size: var(--rp-font-size-sm);
  color: var(--rp-text-muted, #6b7a90);
}

.login-form {
  :deep(.el-form-item) {
    margin-bottom: 18px;
  }

  .login-field-label {
    display: block;
    margin-bottom: 8px;
    font-size: var(--rp-font-size-sm);
    font-weight: 500;
    color: var(--rp-text-muted, #6b7a90);
  }

  :deep(.el-input__wrapper) {
    min-height: 44px;
    border-radius: 10px;
    box-shadow: 0 0 0 1px var(--rp-border, #e8edf3) inset;
  }

  :deep(.el-input__wrapper.is-focus) {
    box-shadow: 0 0 0 1px var(--rp-primary, #4d8dff) inset, 0 0 0 3px rgba(77, 141, 255, 0.15);
  }

  .login-submit {
    width: 100%;
    height: 46px;
    margin-top: 8px;
    border: none;
    border-radius: 10px;
    background: var(--rp-primary-gradient, linear-gradient(135deg, #6ba0ff 0%, #4d8dff 55%, #2d6be8 100%));
    box-shadow: 0 8px 20px rgba(77, 141, 255, 0.32);
    font-size: var(--rp-font-size-base);
    font-weight: 600;
    letter-spacing: 0.08em;
  }

  .login-submit:hover,
  .login-submit:focus {
    background: var(--rp-primary-gradient, linear-gradient(135deg, #6ba0ff 0%, #4d8dff 55%, #2d6be8 100%));
    opacity: 0.92;
  }
}

@media (max-width: 960px) {
  .login-bg__cluster {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-bg__mesh,
  .login-bg__grid,
  .login-bg__orb,
  .login-bg__cluster {
    transform: none !important;
    transition: none !important;
  }
}
</style>

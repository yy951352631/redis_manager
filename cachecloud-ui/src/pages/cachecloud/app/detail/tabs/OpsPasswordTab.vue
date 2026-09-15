<script lang="ts" setup>
import { Hide, View } from "@element-plus/icons-vue"
import { checkAppPasswordApi, getAppPasswordApi, updateAppPasswordApi } from "@/api/cachecloud"
import "@/common/assets/styles/app-ops.scss"

const props = defineProps<{
  appId: number
}>()

const passwordInfo = ref<Awaited<ReturnType<typeof getAppPasswordApi>>["data"] | null>(null)
const newPassword = ref("")
const passwordVisible = ref(false)
const loading = ref(false)

async function fetchPassword() {
  loading.value = true
  try {
    const { data } = await getAppPasswordApi(props.appId)
    passwordInfo.value = data
    newPassword.value = data?.customPassword || data?.appPassword || ""
  } finally {
    loading.value = false
  }
}

async function handleUpdatePassword() {
  if (!newPassword.value) {
    ElMessage.warning("请输入密码")
    return
  }
  const { data } = await updateAppPasswordApi(props.appId, newPassword.value)
  if (!data?.success) {
    ElMessage.error("密码更新失败")
    return
  }
  ElMessage.success("密码已更新")
  fetchPassword()
}

async function handleCheckPassword() {
  const { data } = await checkAppPasswordApi(props.appId)
  if (data?.success) {
    ElMessage.success("集群密码有效且一致")
  } else {
    ElMessage.error("集群密码无效或不一致")
  }
}

onMounted(() => {
  fetchPassword()
})
</script>

<template>
  <div v-loading="loading" class="app-password-panel">
    <div class="app-password-panel__intro">
      <h4 class="app-password-panel__title">
        Redis 密码
      </h4>
      <p class="app-password-panel__hint">
        平台使用该密码连接 Redis 进行采集与运维。修改后建议点击「校验」确认各节点密码一致。
      </p>
    </div>

    <div class="app-password-panel__form">
      <label class="app-password-panel__label" for="app-detail-ops-password">密码</label>
      <div class="app-password-input-wrap">
        <el-input
          id="app-detail-ops-password"
          v-model="newPassword"
          :type="passwordVisible ? 'text' : 'password'"
          class="app-password-input"
          placeholder="请输入 Redis 密码"
          autocomplete="off"
        />
        <button
          type="button"
          class="app-password-toggle"
          :title="passwordVisible ? '隐藏密码' : '显示密码'"
          :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
          @click="passwordVisible = !passwordVisible"
        >
          <el-icon><component :is="passwordVisible ? Hide : View" /></el-icon>
        </button>
      </div>
      <div class="app-password-panel__actions">
        <el-button type="primary" @click="handleUpdatePassword">
          更新
        </el-button>
        <el-button @click="handleCheckPassword">
          校验
        </el-button>
      </div>
    </div>
  </div>
</template>

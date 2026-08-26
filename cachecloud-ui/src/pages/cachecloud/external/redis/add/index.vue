<script lang="ts" setup>
import type { ExternalRedisCreateForm } from "@/api/cachecloud"
import {
  checkExternalRedisApi,
  checkExternalRedisNameApi,
  getExternalRedisCreateFormApi,
  saveExternalRedisApi
} from "@/api/cachecloud"
import { ArrowLeft } from "@element-plus/icons-vue"
import { formatRedisVersion } from "@/common/utils/redis-version"

const router = useRouter()
const loading = ref(false)
const saving = ref(false)
const checked = ref(false)
const formData = ref<ExternalRedisCreateForm | null>(null)
const form = reactive({
  name: "",
  intro: "",
  appType: 5,
  officer: [] as string[],
  password: "",
  sentinelPassword: "",
  redisVersion: "",
  appInstanceInfo: ""
})

const instancePlaceholder = [
  "1. standalone：",
  "10.0.0.1:6379",
  "2. sentinel：",
  "10.0.0.1:6379:0",
  "10.0.0.1:6380:0",
  "10.0.0.2:26379:mymaster",
  "10.0.0.3:26379:mymaster",
  "10.0.0.4:26379:mymaster",
  "3. cluster：",
  "10.0.0.1:6379",
  "10.0.0.1:6380",
  "10.0.0.2:6379",
  "10.0.0.2:6380"
].join("\n")

async function fetchForm() {
  loading.value = true
  try {
    const { data } = await getExternalRedisCreateFormApi()
    formData.value = data
    if (data) {
      form.officer = [String(data.currentUserId)]
    }
  } finally {
    loading.value = false
  }
}

function resetCheckState() {
  checked.value = false
  form.redisVersion = ""
}

async function handleCheckName() {
  if (!form.name.trim()) return
  try {
    await checkExternalRedisNameApi(form.name.trim())
  } catch {
    // 错误提示由 axios 拦截器统一弹出
  }
}

async function handleCheck() {
  if (!form.name.trim()) {
    ElMessage.warning("集群名称不能为空")
    return
  }
  if (!form.officer.length) {
    ElMessage.warning("项目负责人不能为空")
    return
  }
  if (!form.appInstanceInfo.trim()) {
    ElMessage.warning("节点详情不能为空")
    return
  }
  try {
    const { data } = await checkExternalRedisApi({
      appType: form.appType,
      password: form.password,
      sentinelPassword: form.sentinelPassword,
      appInstanceInfo: form.appInstanceInfo
    })
    form.redisVersion = formatRedisVersion(data?.redisVersion)
    checked.value = true
    ElMessage.success(data?.message || "检查通过")
  } catch {
    // 错误提示由 axios 拦截器统一弹出
    checked.value = false
  }
}

async function handleSave() {
  if (!checked.value) {
    ElMessage.warning("请先点击「连接测试」并通过后再导入")
    return
  }
  saving.value = true
  try {
    await saveExternalRedisApi({
      name: form.name,
      intro: form.intro,
      appType: form.appType,
      password: form.password,
      sentinelPassword: form.sentinelPassword,
      appInstanceInfo: form.appInstanceInfo,
      officer: form.officer.join(",")
    })
    ElMessage.success("集群导入成功，请查看列表")
    router.push("/app/list")
  } catch {
    // 错误提示由 axios 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

function goBack() {
  router.push("/app/list")
}

watch(() => [form.appType, form.appInstanceInfo, form.password, form.sentinelPassword], resetCheckState)

onMounted(fetchForm)
</script>

<template>
  <div v-loading="loading" class="external-redis-page">
    <div class="page-header">
      <el-button :icon="ArrowLeft" link @click="goBack">返回列表</el-button>
    </div>
    <el-card shadow="never" class="form-card">
      <el-form label-width="120px" class="detail-form">
        <el-form-item label="集群名称" required>
          <el-input v-model="form.name" placeholder="集群名称" @blur="handleCheckName" />
          <div class="help-block">不超过 128 个字符，可以包含中文</div>
        </el-form-item>
        <el-form-item label="集群描述">
          <el-input v-model="form.intro" type="textarea" :rows="3" placeholder="选填，便于识别用途" />
        </el-form-item>
        <el-form-item label="集群类型" required>
          <el-select v-model="form.appType" style="width: 100%">
            <el-option label="Redis-standalone" :value="6" />
            <el-option label="Redis-sentinel" :value="5" />
            <el-option label="Redis-cluster" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="项目负责人" required>
          <el-select v-model="form.officer" multiple filterable style="width: 100%">
            <el-option v-for="u in formData?.users ?? []" :key="u.id" :label="u.name" :value="String(u.id)" />
          </el-select>
        </el-form-item>
        <el-form-item label="Redis 密码">
          <el-input v-model="form.password" type="password" show-password placeholder="无密码可留空" />
        </el-form-item>
        <el-form-item v-if="form.appType === 5" label="Sentinel 密码">
          <el-input
            v-model="form.sentinelPassword"
            type="password"
            show-password
            placeholder="哨兵节点无密码可留空"
          />
        </el-form-item>
        <el-form-item label="Redis 版本">
          <el-input v-model="form.redisVersion" disabled placeholder="连接测试后自动获取" />
        </el-form-item>
        <el-form-item label="节点详情" required>
          <el-input
            v-model="form.appInstanceInfo"
            type="textarea"
            :rows="14"
            :placeholder="instancePlaceholder"
          />
          <div class="help-block help-block--format">
            <div>每行格式：ip:port；sentinel 数据节点 ip:port，哨兵节点 ip:port:masterName</div>
            <div>1. standalone：10.0.0.1:6379</div>
            <div>2. sentinel：数据节点写 ip:port，哨兵写 ip:port:masterName（各占一行）</div>
            <div>3. cluster：多行 master/slave 节点 ip:port</div>
          </div>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :disabled="checked" @click="handleCheck">连接测试</el-button>
          <el-button type="success" :disabled="!checked" :loading="saving" @click="handleSave">开始导入</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.external-redis-page {
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 16px;
}

.form-card {
  :deep(.el-card__body) {
    padding: 16px 20px 24px;
  }
}

.detail-form {
  max-width: 640px;
}

.help-block {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}

.help-block--format {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
</style>

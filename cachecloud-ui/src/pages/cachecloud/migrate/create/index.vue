<script lang="ts" setup>
import type { MigrateInit } from "@/api/cachecloud"
import { checkMigrateApi, getMigrateAppInstancesApi, getMigrateInitApi, startMigrateApi } from "@/api/cachecloud"
import { ArrowLeft } from "@element-plus/icons-vue"
import "@/common/assets/styles/migrate.scss"

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const submitting = ref(false)
const checkPassed = ref(false)
const initData = ref<MigrateInit | null>(null)

const MIGRATE_TYPES = [
  { value: 5, label: "非 cluster" },
  { value: 7, label: "cluster" }
]

const form = reactive({
  versionId: -461,
  sourceRedisMigrateIndex: 5,
  targetRedisMigrateIndex: 5,
  sourceDataType: 1,
  targetDataType: 1,
  sourceAppId: "",
  targetAppId: "",
  sourceServers: "",
  targetServers: "",
  redisSourcePass: "",
  redisTargetPass: "",
  clearTarget: false,
  rdbRestoreCommandBehavior: "rewrite",
  allowKeyPrefix: "",
  allowKeySuffix: "",
  allowKeyRegex: "",
  blockKeyPrefix: "",
  blockKeySuffix: "",
  blockKeyRegex: "",
  pipelineCountLimit: 1024,
  targetRedisMaxQps: 300000
})

function resetCheckState() {
  checkPassed.value = false
}

function mapAppType(appType: number) {
  return appType === 2 ? 7 : 5
}

async function fillAppInstances(appId: string, side: "source" | "target") {
  if (!appId) return
  const { data } = await getMigrateAppInstancesApi(Number(appId), 0)
  if (!data) return
  if (side === "source") {
    form.sourceRedisMigrateIndex = mapAppType(data.appType)
  } else {
    form.targetRedisMigrateIndex = mapAppType(data.appType)
  }
  resetCheckState()
}

async function fetchInit() {
  loading.value = true
  try {
    const importId = route.query.importId ? Number(route.query.importId) : undefined
    const { data } = await getMigrateInitApi(importId)
    initData.value = data
    if (data?.targetAppId) form.targetAppId = String(data.targetAppId)
    if (data?.sourceServers) form.sourceServers = data.sourceServers
    if (data?.redisSourcePass) form.redisSourcePass = data.redisSourcePass
    if (data?.sourceType === 7) form.sourceRedisMigrateIndex = 7
    if (data?.sourceDataType !== undefined) form.sourceDataType = data.sourceDataType
    if (form.targetAppId && form.targetDataType === 1) await fillAppInstances(form.targetAppId, "target")
  } finally {
    loading.value = false
  }
}

function buildPayload() {
  return {
    ...form,
    sourceAppId: Number(form.sourceAppId) || 0,
    targetAppId: Number(form.targetAppId) || 0
  }
}

async function handleCheck() {
  if (form.sourceDataType === 1 && !form.sourceAppId) return ElMessage.warning("请选择源集群")
  if (form.sourceDataType === 0 && !form.sourceServers.trim()) return ElMessage.warning("请填写源节点地址")
  if (form.targetDataType === 1 && !form.targetAppId) return ElMessage.warning("请选择目标集群")
  if (form.targetDataType === 0 && !form.targetServers.trim()) return ElMessage.warning("请填写目标节点地址")
  submitting.value = true
  try {
    const { data } = await checkMigrateApi(buildPayload())
    checkPassed.value = data?.status === 1
    if (checkPassed.value) {
      const message = data?.message || "检查通过"
      if (message.includes("SCAN + UNLINK")) ElMessage.warning({ message, duration: 8000 })
      else ElMessage.success(message)
    }
    else ElMessage.error(data?.message || "检查失败")
  } catch (error: unknown) {
    checkPassed.value = false
    ElMessage.error(error instanceof Error ? error.message : "检查失败")
  } finally {
    submitting.value = false
  }
}

async function handleStart() {
  if (!checkPassed.value) return ElMessage.warning("请先检查配置")
  submitting.value = true
  try {
    await startMigrateApi(buildPayload())
    ElMessage.success("迁移任务已启动")
    router.push({ path: "/migrate", query: { status: "-2" } })
  } catch (error: unknown) {
    ElMessage.error(error instanceof Error ? error.message : "启动失败")
  } finally {
    submitting.value = false
  }
}

watch(() => form.sourceAppId, value => form.sourceDataType === 1 && fillAppInstances(value, "source"))
watch(() => form.targetAppId, value => form.targetDataType === 1 && fillAppInstances(value, "target"))
watch(() => form.sourceDataType, resetCheckState)
watch(() => form.targetDataType, resetCheckState)
onMounted(fetchInit)
</script>

<template>
  <div v-loading="loading" class="migrate-form-page">
    <div class="page-header">
      <el-button :icon="ArrowLeft" link @click="router.push('/migrate')">返回列表</el-button>
      <h2 class="page-title">添加迁移任务</h2>
    </div>

    <el-card shadow="never" class="migrate-form-card">
      <section class="migrate-form-section">
        <h3 class="migrate-form-section__title">迁移配置</h3>
        <div class="migrate-form-grid">
          <el-form label-width="120px">
            <el-form-item label="清空目标库">
              <el-switch v-model="form.clearTarget" @change="resetCheckState" />
            </el-form-item>
            <el-form-item label="目标 Pipeline">
              <el-input-number v-model="form.pipelineCountLimit" :min="1" :max="100000" style="width: 100%" @change="resetCheckState" />
            </el-form-item>
            <el-form-item label="目标最大 QPS">
              <el-input-number v-model="form.targetRedisMaxQps" :min="1" :max="300000" style="width: 100%" @change="resetCheckState" />
            </el-form-item>
            <el-form-item label="key冲突策略">
              <el-select v-model="form.rdbRestoreCommandBehavior" style="width: 100%" @change="resetCheckState">
                <el-option label="rewrite" value="rewrite" />
                <el-option label="skip" value="skip" />
                <el-option label="panic" value="panic" />
              </el-select>
            </el-form-item>
          </el-form>
        </div>
      </section>

      <section class="migrate-form-section">
        <h3 class="migrate-form-section__title">源和目标配置</h3>
        <div class="migrate-form-grid">
          <el-form label-width="120px">
            <el-form-item label="数据源">
              <el-select v-model="form.sourceDataType" style="width: 100%">
                <el-option label="Redis 管理平台纳管" :value="1" />
                <el-option label="手工配置" :value="0" />
              </el-select>
            </el-form-item>
            <el-form-item label="源类型">
              <el-select v-model="form.sourceRedisMigrateIndex" :disabled="form.sourceDataType === 1" style="width: 100%" @change="resetCheckState">
                <el-option v-for="item in MIGRATE_TYPES" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.sourceDataType === 1" label="选择集群">
              <el-select v-model="form.sourceAppId" filterable clearable style="width: 100%" placeholder="搜索集群名称或编码">
                <el-option v-for="app in initData?.apps ?? []" :key="app.value" :label="app.label" :value="String(app.value)" />
              </el-select>
            </el-form-item>
            <template v-else>
              <el-form-item label="源密码">
                <el-input v-model="form.redisSourcePass" show-password placeholder="无密码可留空" @input="resetCheckState" />
              </el-form-item>
              <el-form-item label="源节点地址">
                <el-input v-model="form.sourceServers" type="textarea" :rows="6" placeholder="ip:port" @input="resetCheckState" />
              </el-form-item>
            </template>
          </el-form>

          <el-form label-width="120px">
            <el-form-item label="数据源">
              <el-select v-model="form.targetDataType" style="width: 100%">
                <el-option label="Redis 管理平台纳管" :value="1" />
                <el-option label="手工配置" :value="0" />
              </el-select>
            </el-form-item>
            <el-form-item label="目标类型">
              <el-select v-model="form.targetRedisMigrateIndex" :disabled="form.targetDataType === 1" style="width: 100%" @change="resetCheckState">
                <el-option v-for="item in MIGRATE_TYPES" :key="item.value" :label="item.label" :value="item.value" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="form.targetDataType === 1" label="选择集群">
              <el-select v-model="form.targetAppId" filterable clearable style="width: 100%" placeholder="搜索集群名称或编码">
                <el-option v-for="app in initData?.apps ?? []" :key="app.value" :label="app.label" :value="String(app.value)" />
              </el-select>
            </el-form-item>
            <template v-else>
              <el-form-item label="目标密码">
                <el-input v-model="form.redisTargetPass" show-password placeholder="无密码可留空" @input="resetCheckState" />
              </el-form-item>
              <el-form-item label="目标节点地址">
                <el-input v-model="form.targetServers" type="textarea" :rows="6" placeholder="ip:port" @input="resetCheckState" />
              </el-form-item>
            </template>
          </el-form>
        </div>
      </section>

      <section class="migrate-form-section">
        <h3 class="migrate-form-section__title">Key 过滤（可选）</h3>
        <div class="migrate-form-grid">
          <el-form label-width="120px">
            <el-form-item label="允许前缀"><el-input v-model="form.allowKeyPrefix" type="textarea" :rows="3" placeholder="每行一个前缀" @input="resetCheckState" /></el-form-item>
            <el-form-item label="允许后缀"><el-input v-model="form.allowKeySuffix" type="textarea" :rows="3" placeholder="每行一个后缀" @input="resetCheckState" /></el-form-item>
            <el-form-item label="允许正则"><el-input v-model="form.allowKeyRegex" type="textarea" :rows="3" placeholder="每行一个正则表达式" @input="resetCheckState" /></el-form-item>
          </el-form>
          <el-form label-width="120px">
            <el-form-item label="排除前缀"><el-input v-model="form.blockKeyPrefix" type="textarea" :rows="3" placeholder="每行一个前缀" @input="resetCheckState" /></el-form-item>
            <el-form-item label="排除后缀"><el-input v-model="form.blockKeySuffix" type="textarea" :rows="3" placeholder="每行一个后缀" @input="resetCheckState" /></el-form-item>
            <el-form-item label="排除正则"><el-input v-model="form.blockKeyRegex" type="textarea" :rows="3" placeholder="每行一个正则表达式" @input="resetCheckState" /></el-form-item>
          </el-form>
        </div>
      </section>

      <div class="migrate-form-actions">
        <el-button type="primary" plain :loading="submitting" @click="handleCheck">检查配置</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!checkPassed" @click="handleStart">开始迁移</el-button>
      </div>
    </el-card>
  </div>
</template>

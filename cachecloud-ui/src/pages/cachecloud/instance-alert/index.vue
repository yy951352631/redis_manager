<script lang="ts" setup>
import type { InstanceAlertItem, InstanceAlertPage } from "@/api/cachecloud"
import {
  addAppInstanceAlertApi,
  addInstanceAlertApi,
  deleteInstanceAlertApi,
  getInstanceAlertPageApi,
  updateInstanceAlertApi
} from "@/api/cachecloud"

const IMPORTANT_LEVEL_OPTIONS = [
  { value: 0, label: "一般" },
  { value: 1, label: "重要" },
  { value: 2, label: "紧急" }
] as const

const loading = ref(false)
const page = ref<InstanceAlertPage | null>(null)

const globalForm = reactive({
  alertConfig: "",
  alertValue: "",
  configInfo: "",
  compareType: 1,
  checkCycle: 1,
  importantLevel: 0,
  type: 1
})

const specialForm = reactive({
  alertConfig: "",
  alertValue: "",
  configInfo: "",
  compareType: 1,
  checkCycle: 1,
  importantLevel: 0,
  instanceHostPort: "",
  type: 2
})

const appForm = reactive({
  alertConfig: "",
  alertValue: "",
  configInfo: "",
  compareType: 1,
  checkCycle: 1,
  importantLevel: 0,
  appId: undefined as number | undefined,
  type: 3
})

async function fetchPage() {
  loading.value = true
  try {
    const { data } = await getInstanceAlertPageApi()
    page.value = data
    if (data?.compareTypes?.length && !globalForm.compareType) {
      globalForm.compareType = data.compareTypes[0].value
    }
  } finally {
    loading.value = false
  }
}

function optionLabel(options: { value: number, label: string }[] | undefined, value?: number) {
  if (value == null) return "-"
  return options?.find(o => o.value === value)?.label ?? String(value)
}

function importantLevelLabel(level?: number) {
  return optionLabel(IMPORTANT_LEVEL_OPTIONS as unknown as { value: number, label: string }[], level)
}

function onAlertConfigChange(form: typeof globalForm, value: string) {
  const opt = page.value?.alertConfigs.find(c => c.value === value)
  form.configInfo = opt?.info ?? ""
}

async function saveGlobal() {
  await addInstanceAlertApi({ ...globalForm })
  ElMessage.success("添加成功")
  fetchPage()
}

async function saveSpecial() {
  await addInstanceAlertApi({ ...specialForm })
  ElMessage.success("添加成功")
  fetchPage()
}

async function saveApp() {
  if (!appForm.appId) {
    ElMessage.warning("请填写集群编码")
    return
  }
  await addAppInstanceAlertApi({ ...appForm })
  ElMessage.success("添加成功")
  fetchPage()
}

async function handleUpdate(row: InstanceAlertItem) {
  await ElMessageBox.confirm("确认保存该报警配置？", "提示", { type: "warning" })
  await updateInstanceAlertApi(row.id, {
    alertValue: row.alertValue,
    checkCycle: row.checkCycle,
    compareType: row.compareType,
    importantLevel: row.importantLevel ?? 0
  })
  ElMessage.success("已更新")
  fetchPage()
}

async function handleRemove(id: number) {
  await ElMessageBox.confirm("确认删除该报警配置？", "提示", { type: "warning" })
  await deleteInstanceAlertApi(id)
  ElMessage.success("已删除")
  fetchPage()
}

onMounted(fetchPage)
</script>

<template>
  <div v-loading="loading" class="alert-page">
    <el-card shadow="never" class="section-card">
      <template #header>全局报警配置</template>
      <el-table :data="page?.globalAlerts ?? []" stripe border size="small">
        <el-table-column prop="configInfo" label="配置项" min-width="180" />
        <el-table-column prop="alertConfig" label="key" width="160" />
        <el-table-column label="阈值" width="120">
          <template #default="{ row }">
            <el-input v-model="row.alertValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="周期" width="120">
          <template #default="{ row }">
            <el-select v-model="row.checkCycle" size="small">
              <el-option v-for="o in page?.checkCycles ?? []" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="比较" width="120">
          <template #default="{ row }">
            <el-select v-model="row.compareType" size="small">
              <el-option v-for="o in page?.compareTypes ?? []" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="重要度" width="110">
          <template #default="{ row }">
            <el-select :model-value="row.importantLevel ?? 0" size="small" style="width: 90px" @update:model-value="row.importantLevel = $event">
              <el-option v-for="o in IMPORTANT_LEVEL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="handleUpdate(row)">保存</el-button>
              <el-button type="danger" size="small" @click="handleRemove(row.id)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="add-form">
        <el-select v-model="globalForm.alertConfig" clearable placeholder="配置项" style="width: 220px" @change="onAlertConfigChange(globalForm, $event)">
          <el-option v-for="c in page?.alertConfigs ?? []" :key="c.value" :label="c.info" :value="c.value" />
        </el-select>
        <el-input v-model="globalForm.alertValue" placeholder="阈值" style="width: 120px" />
        <el-select v-model="globalForm.checkCycle" placeholder="周期" style="width: 110px">
          <el-option v-for="o in page?.checkCycles ?? []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="globalForm.compareType" placeholder="比较" style="width: 100px">
          <el-option v-for="o in page?.compareTypes ?? []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="globalForm.importantLevel" placeholder="重要度" style="width: 100px">
          <el-option v-for="o in IMPORTANT_LEVEL_OPTIONS" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="saveGlobal">添加全局配置</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="section-card">
      <template #header>节点/集群特殊报警</template>
      <el-table :data="page?.specialAlerts ?? []" stripe border size="small">
        <el-table-column prop="instanceHostPort" label="节点/集群" width="180" />
        <el-table-column prop="configInfo" label="配置项" min-width="180" />
        <el-table-column prop="alertConfig" label="key" width="150" show-overflow-tooltip />
        <el-table-column label="比较" width="90">
          <template #default="{ row }">
            {{ row.compareInfo || optionLabel(page?.compareTypes, row.compareType) }}
          </template>
        </el-table-column>
        <el-table-column label="阈值" width="120">
          <template #default="{ row }">
            <el-input v-model="row.alertValue" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="周期" width="110">
          <template #default="{ row }">
            <el-select v-model="row.checkCycle" size="small" style="width: 96px">
              <el-option v-for="o in page?.checkCycles ?? []" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="重要度" width="90">
          <template #default="{ row }">
            {{ importantLevelLabel(row.importantLevel) }}
          </template>
        </el-table-column>
        <el-table-column prop="lastCheckTime" label="最近检测" width="160" />
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="handleUpdate(row)">保存</el-button>
              <el-button type="danger" size="small" @click="handleRemove(row.id)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <div class="add-form">
        <el-input v-model="specialForm.instanceHostPort" placeholder="ip:port" style="width: 160px" />
        <el-select
          v-model="specialForm.alertConfig"
          clearable
          placeholder="配置项"
          style="width: 220px"
          @change="onAlertConfigChange(specialForm, $event)"
        >
          <el-option v-for="c in page?.usedGlobalConfigs ?? []" :key="c.value" :label="c.info" :value="c.value" />
        </el-select>
        <el-input v-model="specialForm.alertValue" placeholder="阈值" style="width: 100px" />
        <el-select v-model="specialForm.compareType" placeholder="比较" style="width: 100px">
          <el-option v-for="o in page?.compareTypes ?? []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-select v-model="specialForm.checkCycle" placeholder="周期" style="width: 110px">
          <el-option v-for="o in page?.checkCycles ?? []" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
        <el-button type="primary" @click="saveSpecial">添加节点配置</el-button>
        <el-input-number v-model="appForm.appId" placeholder="集群编码" :min="1" controls-position="right" />
        <el-select
          v-model="appForm.alertConfig"
          clearable
          placeholder="配置项"
          style="width: 200px"
          @change="onAlertConfigChange(appForm, $event)"
        >
          <el-option v-for="c in page?.usedGlobalConfigs ?? []" :key="c.value" :label="c.info" :value="c.value" />
        </el-select>
        <el-input v-model="appForm.alertValue" placeholder="阈值" style="width: 100px" />
        <el-button type="success" @click="saveApp">添加集群配置</el-button>
      </div>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.alert-page { padding: 16px; }
.page-title { margin: 0 0 16px; font-size: 20px; font-weight: 600; }
.section-card { margin-bottom: 16px; }
.add-form { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 12px; align-items: center; }
</style>

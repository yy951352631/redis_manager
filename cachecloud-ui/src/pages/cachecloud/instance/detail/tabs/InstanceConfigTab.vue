<script lang="ts" setup>
import { formatRedisConfigOptionLabel, getInstanceConfigApi, updateInstanceConfigApi } from "@/api/cachecloud"
import { getRedisConfigValueOptions } from "@/common/utils/redis-config"
import { getRedisConfigDefault, getRedisConfigDescription, isRedisConfigChanged, maskRedisConfigValue } from "@/common/utils/redis-config-meta"

const props = defineProps<{
  instanceId: number
  appId: number
  hostPort: string
}>()

const emit = defineEmits<{ refreshed: [] }>()

const loading = ref(false)
const saving = ref(false)
const configMap = ref<Record<string, string>>({})
const modalVisible = ref(false)
const configKey = ref("")
const configKeyCustom = ref("")
const configValue = ref("")
const changeMessage = ref("")
const changeError = ref(false)

/** 显示空配置项：关闭时隐藏空值项；开启时只看空值项 */
const showEmptyOnly = ref(false)
/** 仅显示变动值：只保留与官方默认值不同的项 */
const onlyChanged = ref(true)

const allConfigRows = computed(() =>
  Object.entries(configMap.value).map(([key, value]) => {
    const def = getRedisConfigDefault(key)
    return {
      key,
      value,
      defaultValue: def ?? "-",
      description: getRedisConfigDescription(key) ?? "-",
      changed: isRedisConfigChanged(key, value),
      // 比对用原值，展示用掩码值：凭据不该明文铺在表格里
      displayValue: maskRedisConfigValue(key, value)
    }
  })
)

const configRows = computed(() =>
  allConfigRows.value.filter((row) => {
    const isEmpty = !(row.value ?? "").trim()
    // 两个开关是「与」的关系：先按空值维度筛，再按是否变动筛
    if (showEmptyOnly.value ? !isEmpty : isEmpty) return false
    if (onlyChanged.value && !row.changed) return false
    return true
  })
)
const configSelectOptions = computed(() =>
  [...allConfigRows.value].sort((a, b) => a.key.localeCompare(b.key)).map(row => ({
    ...row,
    label: formatRedisConfigOptionLabel(row.key, row.value ?? "")
  }))
)
const activeConfigKey = computed(() => configKeyCustom.value.trim() || configKey.value.trim())
const configValueOptions = computed(() => getRedisConfigValueOptions(activeConfigKey.value))

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getInstanceConfigApi(props.instanceId)
    configMap.value = data ?? {}
  } finally {
    loading.value = false
  }
}

function openModal() {
  configKey.value = ""
  configKeyCustom.value = ""
  configValue.value = ""
  changeMessage.value = ""
  changeError.value = false
  modalVisible.value = true
}

function fillValueFromSelect() {
  if (!configKey.value) return
  configValue.value = configMap.value[configKey.value] ?? ""
}

async function submitChange() {
  const key = configKeyCustom.value.trim() || configKey.value.trim()
  if (!key) {
    ElMessage.warning("配置项不能为空")
    return
  }
  try {
    await ElMessageBox.confirm(`确认修改配置项 ${key} 为 ${configValue.value}？`, "修改配置", { type: "warning" })
  } catch {
    return
  }
  saving.value = true
  changeMessage.value = ""
  try {
    const { data } = await updateInstanceConfigApi(props.instanceId, {
      configName: key,
      configValue: configValue.value
    })
    changeMessage.value = data?.message || "配置更新成功"
    changeError.value = !data?.rewriteSuccess
    if (data?.rewriteSuccess) ElMessage.success(changeMessage.value)
    else ElMessage.warning(changeMessage.value)
    await fetchData()
    emit("refreshed")
  } catch (e: unknown) {
    changeError.value = true
    changeMessage.value = e instanceof Error ? e.message : "修改失败，请检查网络后重试"
  } finally {
    saving.value = false
  }
}

watch(() => props.instanceId, fetchData, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="app-tab-embed app-instance-config-page">
    <div class="app-instance-config-toolbar">
      <el-button type="primary" size="small" @click="openModal">
        修改配置
      </el-button>
      <span class="app-instance-config-switch">
        <el-switch v-model="showEmptyOnly" size="small" />
        <span class="app-instance-config-switch__label">显示空配置项</span>
      </span>
      <span class="app-instance-config-switch">
        <el-switch v-model="onlyChanged" size="small" />
        <span class="app-instance-config-switch__label">仅显示变动值</span>
      </span>
    </div>
    <el-alert
      type="info"
      :closable="false"
      show-icon
      :title="`来源：Redis CONFIG GET *，共 ${allConfigRows.length} 项，当前按筛选显示 ${configRows.length} 项。默认值取自官方 Redis 6.2 未改动实例，不同大版本可能有出入；未收录的配置项默认值显示为 -，不参与「变动」判定。`"
      class="app-instance-config-source"
    />
    <table class="app-instance-config-table">
      <thead>
        <tr>
          <th>配置项</th>
          <th>配置值</th>
          <th>默认值</th>
          <th>配置说明</th>
        </tr>
      </thead>
      <tbody>
        <tr v-if="!configRows.length">
          <td colspan="4" class="app-instance-config-empty">
            没有符合当前筛选条件的配置项
          </td>
        </tr>
        <tr v-for="row in configRows" :key="row.key">
          <td>{{ row.key }}</td>
          <td :class="{ 'app-instance-config-changed': row.changed }">
            {{ row.displayValue || "（空）" }}
          </td>
          <td class="app-instance-config-default">
            {{ row.defaultValue || "（空）" }}
          </td>
          <td class="app-instance-config-desc">
            {{ row.description }}
          </td>
        </tr>
      </tbody>
    </table>

    <el-dialog v-model="modalVisible" title="修改节点配置" width="520px" destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="配置项" required>
          <el-select v-model="configKey" placeholder="请选择" filterable style="width: 100%" @change="fillValueFromSelect">
            <el-option v-for="row in configSelectOptions" :key="row.key" :label="row.label" :value="row.key" />
          </el-select>
          <el-input v-model="configKeyCustom" class="app-instance-config-custom-key" placeholder="新增配置项" style="margin-top: 8px" />
        </el-form-item>
        <el-form-item label="新配置值" required>
          <el-select v-if="configValueOptions.length" v-model="configValue" style="width: 100%">
            <el-option v-for="value in configValueOptions" :key="value" :label="value" :value="value" />
          </el-select>
          <el-input v-else v-model="configValue" placeholder="填写新的配置值（允许空值）" />
        </el-form-item>
      </el-form>
      <el-alert v-if="changeMessage" :type="changeError ? 'error' : 'success'" :title="changeMessage" :closable="false" show-icon />
      <template #footer>
        <el-button @click="modalVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitChange">确认修改</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.app-instance-config-custom-key {
  margin-top: 8px;
}

.app-instance-config-switch {
  display: inline-flex;
  gap: 6px;
  align-items: center;
  margin-left: 16px;
}

.app-instance-config-switch__label {
  font-size: 13px;
  color: var(--rp-text-muted, #6b7a90);
}

/* 与默认值不同的配置值加重显示，扫一眼就能定位被改过的项 */
.app-instance-config-changed {
  font-weight: 600;
  color: var(--rp-primary, #4d8dff);
}

.app-instance-config-default {
  color: var(--rp-text-muted, #6b7a90);
}

.app-instance-config-desc {
  font-size: 12px;
  line-height: 1.6;
  color: var(--rp-text-muted, #6b7a90);
}
</style>

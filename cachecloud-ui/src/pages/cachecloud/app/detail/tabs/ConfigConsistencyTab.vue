<script lang="ts" setup>
import type { AppConfigConsistency, AppConfigConsistencyItem } from "@/api/cachecloud"
import { Refresh, Search } from "@element-plus/icons-vue"
import { getAppConfigConsistencyApi } from "@/api/cachecloud"

const props = defineProps<{ appId: number }>()

const loading = ref(false)
const result = ref<AppConfigConsistency | null>(null)
const keyword = ref("")
const onlyDifferences = ref(true)

const successfulNodes = computed(() => result.value?.nodes.filter(node => node.success) ?? [])

const resultTitle = computed(() => {
  if (!result.value) return ""
  if (result.value.failedInstances > 0) return `有 ${result.value.failedInstances} 个实例配置采集失败`
  if (!result.value.consistent) return `发现 ${result.value.inconsistentConfigCount} 个差异配置项`
  return "配置一致"
})

const filteredItems = computed(() => {
  const query = keyword.value.trim().toLowerCase()
  return (result.value?.items ?? []).filter((item) => {
    if (onlyDifferences.value && item.consistent) return false
    if (!query) return true
    return item.configName.toLowerCase().includes(query)
      || item.group.toLowerCase().includes(query)
      || Object.values(item.values).some(value => value.toLowerCase().includes(query))
  })
})

async function runCheck() {
  loading.value = true
  try {
    const { data } = await getAppConfigConsistencyApi(props.appId)
    result.value = data ?? null
  } finally {
    loading.value = false
  }
}

function valueFor(item: AppConfigConsistencyItem, hostPort: string) {
  return item.values[hostPort] ?? "-"
}

function rowClassName({ row }: { row: AppConfigConsistencyItem }) {
  return row.consistent ? "" : "config-consistency-row--different"
}

watch(() => props.appId, runCheck, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="config-consistency">
    <div class="config-consistency__toolbar">
      <el-button type="primary" :icon="Refresh" :loading="loading" @click="runCheck">
        {{ result ? "重新校验" : "开始校验" }}
      </el-button>
      <el-input
        v-model="keyword"
        :prefix-icon="Search"
        clearable
        placeholder="搜索配置项或配置值"
        class="config-consistency__search"
      />
      <el-switch v-model="onlyDifferences" active-text="仅看差异" inactive-text="全部配置" />
    </div>

    <template v-if="result">
      <el-alert
        :type="result.consistent ? 'success' : 'warning'"
        :closable="false"
        show-icon
        class="config-consistency__result"
      >
        <template #title>
          {{ resultTitle }}
        </template>
      </el-alert>

      <el-descriptions :column="6" border size="small" class="config-consistency__summary">
        <el-descriptions-item label="在线实例">
          {{ result.totalInstances }}
        </el-descriptions-item>
        <el-descriptions-item label="采集成功">
          {{ result.checkedInstances }}
        </el-descriptions-item>
        <el-descriptions-item label="采集失败">
          {{ result.failedInstances }}
        </el-descriptions-item>
        <el-descriptions-item label="比较配置项">
          {{ result.comparedConfigCount }}
        </el-descriptions-item>
        <el-descriptions-item label="差异配置项">
          {{ result.inconsistentConfigCount }}
        </el-descriptions-item>
        <el-descriptions-item label="校验时间">
          {{ result.checkedAt }}
        </el-descriptions-item>
      </el-descriptions>

      <section class="config-consistency__section">
        <h4>实例采集状态</h4>
        <el-table :data="result.nodes" border stripe size="small">
          <el-table-column prop="hostPort" label="实例" min-width="180" />
          <el-table-column prop="group" label="比较分组" width="140" />
          <el-table-column prop="role" label="角色" width="100" />
          <el-table-column prop="configCount" label="配置项数" width="100" />
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="row.success ? 'success' : 'danger'" effect="light">
                {{ row.success ? "成功" : "失败" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="message" label="结果" min-width="220" show-overflow-tooltip />
        </el-table>
      </section>

      <section class="config-consistency__section">
        <div class="config-consistency__section-title">
          <h4>配置比对结果</h4>
          <el-tag :type="filteredItems.length ? 'primary' : 'info'">
            {{ filteredItems.length }} 项
          </el-tag>
        </div>
        <el-table
          :data="filteredItems"
          :row-class-name="rowClassName"
          border
          stripe
          size="small"
          height="520"
          empty-text="当前筛选条件下无配置项"
        >
          <el-table-column prop="group" label="比较分组" width="140" fixed="left" />
          <el-table-column prop="configName" label="配置项" min-width="220" fixed="left" show-overflow-tooltip>
            <template #default="{ row }">
              <span>{{ row.configName }}</span>
              <el-tag v-if="row.sensitive" type="info" size="small" class="config-consistency__sensitive">
                已脱敏
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="一致性" width="90" fixed="left">
            <template #default="{ row }">
              <el-tag :type="row.consistent ? 'success' : 'danger'" size="small">
                {{ row.consistent ? "一致" : "差异" }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            v-for="node in successfulNodes"
            :key="node.instanceId"
            :label="node.hostPort"
            min-width="210"
          >
            <template #default="{ row }">
              <span class="config-consistency__value">{{ valueFor(row, node.hostPort) }}</span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <el-collapse v-if="result.ignoredConfigCount" class="config-consistency__ignored">
        <el-collapse-item :title="`已排除实例标识配置 (${result.ignoredConfigCount})`">
          <div class="config-consistency__ignored-list">
            <el-tag v-for="name in result.ignoredConfigs" :key="name" type="info" effect="plain">
              {{ name }}
            </el-tag>
          </div>
        </el-collapse-item>
      </el-collapse>
    </template>

    <el-empty v-else-if="!loading" description="暂无校验结果" />
  </div>
</template>

<style lang="scss" scoped>
.config-consistency {
  min-height: 320px;
}

.config-consistency__toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px 16px;
  margin-bottom: 14px;
}

.config-consistency__search {
  width: min(340px, 100%);
}

.config-consistency__result {
  margin-bottom: 12px;
}

.config-consistency__summary {
  margin-bottom: 18px;
}

.config-consistency__section {
  margin-top: 18px;
}

.config-consistency__section h4 {
  margin: 0 0 10px;
  font-size: 14px;
  color: #1e293b;
}

.config-consistency__section-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.config-consistency__sensitive {
  margin-left: 6px;
}

.config-consistency__value {
  display: block;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.config-consistency__ignored {
  margin-top: 18px;
}

.config-consistency__ignored-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

:deep(.config-consistency-row--different td.el-table__cell) {
  background-color: #fff7ed;
}

@media (max-width: 900px) {
  .config-consistency__summary :deep(.el-descriptions__body) {
    overflow-x: auto;
  }
}
</style>

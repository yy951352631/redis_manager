<script lang="ts" setup>
import type { AppMachineTopology } from "@/api/cachecloud"
import { getAppMachineTopologyApi } from "@/api/cachecloud"
import { Monitor } from "@element-plus/icons-vue"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

const router = useRouter()
const loading = ref(false)
const data = ref<AppMachineTopology | null>(null)

function nodesInGroup(row: AppMachineTopology["machines"][0], groupId: number) {
  return row.nodes.filter(n => n.groupId === groupId)
}

function nodeClass(node: AppMachineTopology["machines"][0]["nodes"][0]): string {
  if (node.instanceType === 5 || node.roleDesc === "sentinel") return "app-topology-node--sentinel"
  if (node.roleDesc === "slave") return "app-topology-node--slave"
  if (node.roleDesc === "master") return "app-topology-node--master"
  return "app-topology-node--unknown"
}

function nodeBadge(node: AppMachineTopology["machines"][0]["nodes"][0]) {
  return node.instanceType === 5 || node.roleDesc === "sentinel" ? "N" : node.groupId
}

function openInstance(instanceId: number) {
  router.push({ path: `/external/redis/detail/${instanceId}`, query: { appId: String(props.appId) } })
}

async function fetchData() {
  loading.value = true
  try {
    const { data: res } = await getAppMachineTopologyApi(props.appId)
    data.value = res
  } finally {
    loading.value = false
  }
}

watch(() => props.appId, fetchData, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="app-machine-topology-page">
    <div class="app-topology-legend">
      <span class="app-topology-legend__item">
        <span class="app-topology-node app-topology-node--master is-static">
          <span class="app-topology-node__host"><el-icon><Monitor /></el-icon><span class="app-topology-node__badge">M</span></span>
        </span>
        <span class="app-topology-legend__label">Master 节点</span>
      </span>
      <span class="app-topology-legend__item">
        <span class="app-topology-node app-topology-node--slave is-static">
          <span class="app-topology-node__host"><el-icon><Monitor /></el-icon><span class="app-topology-node__badge">S</span></span>
        </span>
        <span class="app-topology-legend__label">Slave 节点</span>
      </span>
      <span v-if="data?.sentinelApp" class="app-topology-legend__item">
        <span class="app-topology-node app-topology-node--sentinel is-static">
          <span class="app-topology-node__host"><el-icon><Monitor /></el-icon><span class="app-topology-node__badge">N</span></span>
        </span>
        <span class="app-topology-legend__label">Sentinel 节点</span>
      </span>
    </div>

    <el-alert
      v-for="(warn, i) in data?.warnings ?? []"
      :key="i"
      type="warning"
      :closable="false"
      show-icon
      class="mb-2"
      :title="warn"
    />

    <div class="app-topology-table-wrap">
      <table class="app-topology-table app-topology-table--matrix">
        <thead>
          <tr>
            <th class="app-topology-table__machine-col">机器</th>
            <th v-for="g in data?.groupCount ?? 0" :key="g">{{ data?.groupLabel }} {{ g }}</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!data?.machines?.length">
            <td :colspan="(data?.groupCount ?? 0) + 1" class="app-topology-table__empty">暂无拓扑数据</td>
          </tr>
          <tr v-for="row in data?.machines ?? []" :key="row.ip">
            <td class="app-topology-machine">
              <span class="app-topology-machine__plain">
                <el-icon class="app-topology-machine__icon"><Monitor /></el-icon>
                <span>{{ row.ip }}</span>
              </span>
            </td>
            <td v-for="g in data?.groupCount ?? 0" :key="`${row.ip}-${g}`" class="app-topology-cell">
              <template v-for="node in nodesInGroup(row, g)" :key="node.id">
                <a
                  class="app-topology-node"
                  :class="nodeClass(node)"
                  :title="node.hostPort"
                  href="javascript:void(0)"
                  @click="openInstance(node.id)"
                >
                  <span class="app-topology-node__host">
                    <el-icon><Monitor /></el-icon>
                    <span class="app-topology-node__badge">{{ nodeBadge(node) }}</span>
                  </span>
                </a>
                <span v-if="node.offline || node.status === 0" class="app-topology-offline">异常</span>
              </template>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.mb-2 { margin-bottom: 12px; }
</style>

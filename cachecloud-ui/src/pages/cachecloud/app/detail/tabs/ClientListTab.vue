<script lang="ts" setup>
import type {
  AppClientInstanceStat,
  AppClientItem,
  AppTopologyInstance,
  InstanceClientConnection,
  InstanceClientItem
} from "@/api/cachecloud"
import { getAppTopologyApi, getInstanceClientsApi } from "@/api/cachecloud"
import RedisClientConnectionPanel from "@/pages/cachecloud/components/RedisClientConnectionPanel.vue"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

const INSTANCE_STATUS_RUNNING = 1

const loading = ref(false)
const condition = ref(3)
const clients = ref<AppClientItem[]>([])
const loadProgress = ref({ done: 0, total: 0 })

let fetchSeq = 0

const drawerVisible = ref(false)
const drawerClient = ref<AppClientItem | null>(null)
const selectedInstance = ref<AppClientInstanceStat | null>(null)
const connectionDetails = ref<InstanceClientConnection[]>([])
const detailMeta = ref({ total: 0, truncated: false })
const detailLoading = ref(false)
const totalConnections = ref(0)

const loadHint = computed(() => {
  if (!loading.value) return ""
  if (loadProgress.value.total === 0) return "正在获取节点列表..."
  if (loadProgress.value.done < loadProgress.value.total) {
    return `正在逐节点加载连接信息 ${loadProgress.value.done}/${loadProgress.value.total}，先完成的节点会先显示`
  }
  return "正在汇总连接数据..."
})

function mergeNodeClients(
  merged: Map<string, AppClientItem>,
  node: AppTopologyInstance,
  items: InstanceClientItem[]
) {
  for (const item of items) {
    let client = merged.get(item.addr)
    if (!client) {
      client = { addr: item.addr, size: 0, flags: [], instanceStats: [] }
      merged.set(item.addr, client)
    }
    client.size += item.count
    for (const type of item.clientTypes) {
      if (!client.flags.includes(type)) {
        client.flags.push(type)
      }
    }
    client.instanceStats.push({
      instanceId: node.id,
      hostPort: node.hostPort,
      count: item.count
    })
  }
}

function publishMergedClients(merged: Map<string, AppClientItem>) {
  clients.value = Array.from(merged.values()).sort((a, b) => b.size - a.size)
}

async function fetchData() {
  loading.value = true
  clients.value = []
  totalConnections.value = 0
  loadProgress.value = { done: 0, total: 0 }
  const merged = new Map<string, AppClientItem>()
  const seq = ++fetchSeq

  try {
    const { data: topology } = await getAppTopologyApi(props.appId)
    if (seq !== fetchSeq) return

    const nodes = (topology?.instances ?? []).filter(node => node.status === INSTANCE_STATUS_RUNNING)
    loadProgress.value.total = nodes.length
    if (!nodes.length) return

    await Promise.allSettled(nodes.map(async (node) => {
      try {
        const { data } = await getInstanceClientsApi(node.id, condition.value, { includeDetails: false })
        if (seq !== fetchSeq) return
        if (data?.totalConnections) {
          totalConnections.value += data.totalConnections
        }
        mergeNodeClients(merged, node, data?.items ?? [])
        publishMergedClients(merged)
      } finally {
        if (seq === fetchSeq) {
          loadProgress.value.done++
        }
      }
    }))
  } finally {
    if (seq === fetchSeq) {
      loading.value = false
    }
  }
}

function changeCondition(value: number) {
  if (loading.value) return
  condition.value = value
  fetchData()
}

function openInstanceStats(client: AppClientItem) {
  drawerClient.value = client
  selectedInstance.value = null
  connectionDetails.value = []
  detailMeta.value = { total: 0, truncated: false }
  drawerVisible.value = true
}

async function selectInstance(stat: AppClientInstanceStat) {
  if (!drawerClient.value) return
  selectedInstance.value = stat
  connectionDetails.value = []
  detailMeta.value = { total: 0, truncated: false }
  if (stat.count <= 0) return

  detailLoading.value = true
  try {
    const { data } = await getInstanceClientsApi(stat.instanceId, condition.value, {
      addr: drawerClient.value.addr,
      includeDetails: true,
      detailLimit: 200
    })
    const matched = data?.items?.[0]
    connectionDetails.value = matched?.connectionDetails ?? []
    detailMeta.value = {
      total: matched?.detailTotal ?? stat.count,
      truncated: matched?.detailsTruncated ?? false
    }
  } finally {
    detailLoading.value = false
  }
}

watch(() => props.appId, fetchData, { immediate: true })
</script>

<template>
  <div class="app-client-list-page">
    <div class="app-client-filter">
      <div class="app-client-filter__group" :class="{ 'is-disabled': loading }" role="radiogroup" aria-label="客户端筛选">
        <label class="app-client-filter__pill">
          <input type="radio" name="clientCondition" :value="3" :checked="condition === 3" @change="changeCondition(3)">
          <span>所有客户端</span>
        </label>
        <label class="app-client-filter__pill">
          <input type="radio" name="clientCondition" :value="2" :checked="condition === 2" @change="changeCondition(2)">
          <span>redis客户端</span>
        </label>
      </div>
    </div>

    <div class="app-client-list-body">
      <p v-if="loadHint" class="app-client-list-hint">
        {{ loadHint }}
      </p>
      <p v-if="totalConnections >= 1000" class="app-client-list-warn">
        集群当前约有 {{ totalConnections }} 条连接。列表仅展示按 IP 汇总的数据；详情按需加载，单次最多展示 200 条。
      </p>

      <table class="app-topology-table app-client-table" :class="{ 'is-loading': loading }">
        <thead>
          <tr>
            <td>序号</td>
            <td>客户端ip</td>
            <td>总连接数</td>
            <td>客户端类型</td>
            <td>节点详细</td>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!clients.length && !loading">
            <td colspan="5" class="app-instance-slow-empty">
              暂无数据
            </td>
          </tr>
          <tr v-for="(client, index) in clients" :key="client.addr">
            <td>{{ index + 1 }}</td>
            <td>{{ client.addr }}</td>
            <td>{{ client.size }}</td>
            <td>
              <span v-for="(flag, fi) in client.flags" :key="fi">
                {{ flag }}<br v-if="fi < client.flags.length - 1">
              </span>
            </td>
            <td>
              <el-button type="success" size="small" @click="openInstanceStats(client)">
                查看节点连接统计
              </el-button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="`客户端 ${drawerClient?.addr ?? ''} 连接统计`"
      size="58%"
      direction="rtl"
      class="app-client-drawer"
      destroy-on-close
    >
      <template v-if="drawerClient">
        <section class="app-client-drawer__summary">
          <div class="app-client-drawer__kv">
            <span class="app-client-drawer__label">客户端 IP</span>
            <span class="app-client-drawer__value">{{ drawerClient.addr }}</span>
          </div>
          <div class="app-client-drawer__kv">
            <span class="app-client-drawer__label">总连接数</span>
            <span class="app-client-drawer__value">{{ drawerClient.size }}</span>
          </div>
          <div class="app-client-drawer__kv">
            <span class="app-client-drawer__label">客户端类型</span>
            <span class="app-client-drawer__value">{{ drawerClient.flags.join(" / ") || "-" }}</span>
          </div>
        </section>

        <section class="app-client-drawer__section">
          <h4 class="app-client-drawer__title">
            各节点连接分布
          </h4>
          <p class="app-client-drawer__hint">
            点击节点行查看该节点上的连接详情
          </p>
          <el-table
            :data="drawerClient.instanceStats"
            stripe
            border
            size="small"
            highlight-current-row
            class="app-client-drawer__table"
            @row-click="selectInstance"
          >
            <el-table-column prop="instanceId" label="节点 ID" width="90" />
            <el-table-column prop="hostPort" label="节点" min-width="160" />
            <el-table-column prop="count" label="连接数" width="90" align="center" />
          </el-table>
        </section>

        <section v-if="selectedInstance" class="app-client-drawer__section">
          <h4 class="app-client-drawer__title">
            连接详情 · {{ selectedInstance.hostPort }}
          </h4>
          <div v-loading="detailLoading">
            <p v-if="detailMeta.truncated" class="app-client-drawer__warn">
              该 IP 在此节点共有 {{ detailMeta.total }} 条连接，当前展示前 {{ connectionDetails.length }} 条。
            </p>
            <RedisClientConnectionPanel :connections="connectionDetails" />
          </div>
        </section>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.app-client-drawer__summary {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 24px;
  padding: 12px 14px;
  margin-bottom: 16px;
  border-radius: 8px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
}

.app-client-drawer__kv {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 180px;
}

.app-client-drawer__label {
  font-size: 12px;
  color: #94a3b8;
}

.app-client-drawer__value {
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.app-client-drawer__section {
  margin-bottom: 20px;
}

.app-client-drawer__title {
  margin: 0 0 8px;
  font-size: 14px;
  font-weight: 600;
  color: #1e293b;
}

.app-client-drawer__hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: #94a3b8;
}

.app-client-drawer__table {
  width: 100%;
}

.app-client-list-hint {
  margin: 0 0 10px;
  font-size: 12px;
  color: #94a3b8;
}

.app-client-list-warn,
.app-client-drawer__warn {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
}

.app-client-table.is-loading {
  opacity: 0.72;
}

:deep(.el-drawer__body) {
  padding: 16px 20px 24px;
}

:deep(.app-client-drawer__table .el-table__row) {
  cursor: pointer;
}
</style>

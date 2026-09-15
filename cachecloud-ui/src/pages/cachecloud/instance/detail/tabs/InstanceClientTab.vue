<script lang="ts" setup>
import type { InstanceClientConnection, InstanceClientItem } from "@/api/cachecloud"
import { getInstanceClientsApi } from "@/api/cachecloud"
import RedisClientConnectionPanel from "@/pages/cachecloud/components/RedisClientConnectionPanel.vue"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ instanceId: number }>()

const RAW_LIST_THRESHOLD = 200

const loading = ref(false)
const clientCondition = ref(3)
const clients = ref<InstanceClientItem[]>([])
const totalConnections = ref(0)

const drawerVisible = ref(false)
const drawerClient = ref<InstanceClientItem | null>(null)
const drawerConnections = ref<InstanceClientConnection[]>([])
const drawerLoading = ref(false)
const detailMeta = ref({ total: 0, truncated: false })

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getInstanceClientsApi(props.instanceId, clientCondition.value, {
      includeDetails: false
    })
    clients.value = data?.items ?? []
    totalConnections.value = data?.totalConnections ?? 0
  } finally {
    loading.value = false
  }
}

async function changeCondition(value: number) {
  if (loading.value) return
  clientCondition.value = value
  await fetchData()
}

async function openConnections(client: InstanceClientItem) {
  drawerClient.value = client
  drawerConnections.value = []
  detailMeta.value = { total: 0, truncated: false }
  drawerVisible.value = true

  if (client.count <= 0) return

  drawerLoading.value = true
  try {
    const { data } = await getInstanceClientsApi(props.instanceId, clientCondition.value, {
      addr: client.addr,
      includeDetails: true,
      detailLimit: 200
    })
    const matched = data?.items?.[0]
    drawerConnections.value = matched?.connectionDetails ?? []
    detailMeta.value = {
      total: matched?.detailTotal ?? client.count,
      truncated: matched?.detailsTruncated ?? false
    }
  } finally {
    drawerLoading.value = false
  }
}

watch(() => props.instanceId, fetchData, { immediate: true })
</script>

<template>
  <div class="app-tab-embed app-client-list-page app-instance-client-list-page">
    <div class="app-client-filter">
      <div class="app-client-filter__group" :class="{ 'is-disabled': loading }" role="radiogroup" aria-label="客户端筛选">
        <label class="app-client-filter__pill">
          <input type="radio" name="clientCondition" :value="3" :checked="clientCondition === 3" @change="changeCondition(3)">
          <span>所有客户端</span>
        </label>
        <label class="app-client-filter__pill">
          <input type="radio" name="clientCondition" :value="2" :checked="clientCondition === 2" @change="changeCondition(2)">
          <span>redis客户端</span>
        </label>
      </div>
    </div>
    <div v-loading="loading" class="app-client-list-body">
      <p v-if="totalConnections >= RAW_LIST_THRESHOLD" class="app-instance-client-warn">
        当前节点约有 {{ totalConnections }} 条连接。列表按 IP 汇总展示；点击「查看连接信息」按需加载详情，单次最多 200 条。
      </p>
      <table class="app-topology-table app-client-table">
        <thead>
          <tr>
            <td>序号</td>
            <td>客户端ip</td>
            <td>客户端类型</td>
            <td>连接数</td>
            <td>连接信息</td>
          </tr>
        </thead>
        <tbody>
          <tr v-if="!clients.length && !loading">
            <td colspan="5" class="app-instance-slow-empty">
              暂无数据
            </td>
          </tr>
          <tr v-for="(row, index) in clients" :key="row.addr">
            <td>{{ index + 1 }}</td>
            <td>{{ row.addr }}</td>
            <td>
              <span v-for="(t, ti) in row.clientTypes" :key="t">
                {{ t }}<br v-if="ti < row.clientTypes.length - 1">
              </span>
            </td>
            <td>{{ row.count }}</td>
            <td>
              <el-button type="success" size="small" @click="openConnections(row)">
                查看连接信息
              </el-button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <el-drawer
      v-model="drawerVisible"
      :title="`客户端 ${drawerClient?.addr ?? ''} 连接信息`"
      size="58%"
      direction="rtl"
      destroy-on-close
    >
      <div v-loading="drawerLoading">
        <p v-if="detailMeta.truncated" class="app-instance-client-warn">
          该 IP 共有 {{ detailMeta.total }} 条连接，当前展示前 {{ drawerConnections.length }} 条。
        </p>
        <RedisClientConnectionPanel :connections="drawerConnections" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.app-instance-client-warn {
  margin: 0 0 10px;
  padding: 8px 10px;
  border-radius: 6px;
  font-size: 12px;
  line-height: 1.5;
  color: #b45309;
  background: #fffbeb;
  border: 1px solid #fde68a;
}
</style>

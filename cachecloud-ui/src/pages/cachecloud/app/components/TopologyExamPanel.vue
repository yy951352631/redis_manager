<script lang="ts" setup>
import type { TopologyExam } from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import {
  Box,
  Check,
  Close,
  Coin,
  Flag,
  Grid,
  List,
  Memo,
  Monitor,
  PieChart,
  PriceTag,
  Share,
  Switch,
  Warning
} from "@element-plus/icons-vue"
import "@/common/assets/styles/topology-exam.scss"

const props = defineProps<{ data: TopologyExam }>()

const DIAG_ICONS = [Share, Switch, Monitor, Grid]

function diagOk(no: number) {
  return props.data.diagnostics.find(d => d.no === no)?.ok ?? false
}

function samePhysicalMachineLabel(flag?: string) {
  if (flag === "yes") return { text: "是", cls: "app-topology-exam-pill--fail" }
  if (flag === "no") return { text: "否", cls: "app-topology-exam-pill--ok" }
  return { text: "无 Slave", cls: "app-topology-exam-pill--warn" }
}

function machineRoomRack(m: TopologyExam["machines"][0]) {
  const room = m.room || "-"
  const rack = m.rack || "-"
  return `${room}:${rack}`
}
</script>

<template>
  <div class="app-topology-exam-page">
    <div
      class="app-topology-exam-hero"
      :class="data.overallOk ? 'app-topology-exam-hero--ok' : 'app-topology-exam-hero--fail'"
    >
      <div class="app-topology-exam-hero__icon-wrap">
        <el-icon v-if="data.overallOk"><Check /></el-icon>
        <el-icon v-else><Close /></el-icon>
      </div>
      <div class="app-topology-exam-hero__body">
        <div class="app-topology-exam-hero__title-row">
          <h4 class="app-topology-exam-hero__title">{{ data.overallOk ? "拓扑健康" : "拓扑异常" }}</h4>
          <span
            class="app-topology-exam-hero__badge"
            :class="data.overallOk ? 'app-topology-exam-hero__badge--ok' : 'app-topology-exam-hero__badge--fail'"
          >
            <el-icon v-if="data.overallOk"><Check /></el-icon>
            <el-icon v-else><Warning /></el-icon>
            {{ data.overallOk ? "健康" : "需关注" }}
          </span>
        </div>
        <div class="app-topology-exam-meta app-topology-exam-meta--hero">
          <span class="app-topology-exam-meta__item">
            <el-icon><Box /></el-icon><em>集群编码</em>{{ formatClusterNo(data.clusterNo, data.appId) }}
          </span>
          <span class="app-topology-exam-meta__item">
            <el-icon><PriceTag /></el-icon><em>集群名称</em>{{ data.appName }}
          </span>
          <span class="app-topology-exam-meta__item">
            <el-icon><Coin /></el-icon><em>类型</em>{{ data.typeDesc }}
          </span>
          <span v-if="data.clusterOrSentinel" class="app-topology-exam-meta__item">
            <el-icon><PieChart /></el-icon><em>诊断通过</em>{{ data.diagPass }}/{{ data.diagTotal }}
          </span>
          <span class="app-topology-exam-meta__item">
            <el-icon><Memo /></el-icon><em>问题数</em>{{ data.issueCount }}
          </span>
        </div>
      </div>
    </div>

    <template v-if="data.clusterOrSentinel && data.diagnostics.length">
      <div class="app-topology-exam-section app-topology-exam-section--results">
        <h5 class="app-topology-exam-section__title app-topology-exam-section__title--bar">
          <el-icon><Flag /></el-icon>
          诊断结果
        </h5>
        <ul class="app-topology-exam-results-list">
          <li
            v-for="item in data.diagnostics"
            :key="item.no"
            class="app-topology-exam-result"
            :class="item.ok ? 'app-topology-exam-result--ok' : 'app-topology-exam-result--fail'"
          >
            <div class="app-topology-exam-result__head">
              <span class="app-topology-exam-result__no">{{ item.no }}</span>
              <span class="app-topology-exam-result__icon">
                <el-icon><component :is="DIAG_ICONS[item.no - 1] || Share" /></el-icon>
              </span>
              <span class="app-topology-exam-result__title">{{ item.title }}</span>
            </div>
            <div class="app-topology-exam-result__body">
              <div class="app-topology-exam-result__status">
                <el-icon v-if="item.ok"><Check /></el-icon>
                <el-icon v-else><Close /></el-icon>
                {{ item.statusText }}
              </div>
              <div class="app-topology-exam-result__conclusion">{{ item.conclusion }}</div>
            </div>
          </li>
        </ul>
      </div>

      <h5 class="app-topology-exam-detail-heading">
        <el-icon><List /></el-icon>
        诊断详情
      </h5>

      <!-- 诊断1：同网段分析 -->
      <div
        id="topology-diag-1"
        class="app-topology-exam-section app-topology-exam-section--diag"
        :class="diagOk(1) ? 'app-topology-exam-section--diag-ok' : 'app-topology-exam-section--diag-fail'"
      >
        <div class="app-topology-exam-section__head">
          <h5 class="app-topology-exam-section__title">
            <el-icon><Share /></el-icon>
            诊断1：同网段分析
          </h5>
        </div>
        <div v-if="diagOk(1)" class="app-topology-exam-inline-ok">
          <el-icon><Check /></el-icon> 集群节点位于同一网段
        </div>
        <div v-else class="app-topology-exam-inline-fail">
          <el-icon><Close /></el-icon> 存在跨网段节点，详见下方详情
        </div>
      </div>

      <!-- 诊断2：主从节点分析 -->
      <div
        id="topology-diag-2"
        class="app-topology-exam-section app-topology-exam-section--diag"
        :class="diagOk(2) ? 'app-topology-exam-section--diag-ok' : 'app-topology-exam-section--diag-fail'"
      >
        <div class="app-topology-exam-section__head">
          <h5 class="app-topology-exam-section__title">
            <el-icon><Switch /></el-icon>
            诊断2：主从节点分析
          </h5>
        </div>

        <div class="app-topology-exam-stats">
          <span v-if="data.appType === 5" class="app-topology-exam-stat">Sentinel 节点：<strong>{{ data.sentinelCount }}</strong></span>
          <span class="app-topology-exam-stat">主节点：<strong>{{ data.masterCount }}</strong></span>
          <span class="app-topology-exam-stat">从节点：<strong>{{ data.slaveNum }}</strong></span>
        </div>

        <div v-if="data.msFlag" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">主从节点分布在同一台物理机</span>
        </div>
        <div v-if="data.appType !== 5 && data.masterCount !== data.slaveNum" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">主从节点数量不匹配</span>
        </div>
        <div v-if="data.appType === 5 && data.masterCount <= 0" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">未识别到主节点</span>
        </div>
        <div v-if="data.appType === 5 && data.slaveNum < data.masterCount" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">从节点数量不足</span>
        </div>
        <div v-if="data.appType === 5 && data.sentinelCount < 3" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">Sentinel 节点数量少于 3 个</span>
        </div>

        <div v-if="data.appType === 5 && data.sentinels.length" class="app-topology-exam-table-wrap app-topology-exam-table-wrap--sub">
          <table class="table app-topology-exam-table">
            <thead>
              <tr>
                <th class="app-topology-exam-table__index">序号</th>
                <th>Sentinel 节点</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(s, idx) in data.sentinels" :key="s.id">
                <td class="app-topology-exam-table__index">{{ idx + 1 }}</td>
                <td><code class="app-topology-exam-code">{{ s.hostPort }}</code></td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="data.masterSlaves.length" class="app-topology-exam-table-wrap">
          <table class="table app-topology-exam-table">
            <thead>
              <tr>
                <th>Master</th>
                <th>Slave</th>
                <th class="app-topology-exam-table__narrow">同一物理机</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="group in data.masterSlaves" :key="group.master.id">
                <td><code class="app-topology-exam-code">{{ group.master.hostPort }}</code></td>
                <td>
                  <template v-if="group.slaves?.length">
                    <span v-for="(slave, sidx) in group.slaves" :key="slave.id">
                      <code class="app-topology-exam-code">{{ slave.hostPort }}</code>
                      <br v-if="sidx < group.slaves.length - 1">
                    </span>
                  </template>
                  <span v-else class="topology-muted">无</span>
                </td>
                <td>
                  <span
                    class="app-topology-exam-pill app-topology-exam-pill--sm"
                    :class="samePhysicalMachineLabel(group.samePhysicalMachine).cls"
                  >
                    {{ samePhysicalMachineLabel(group.samePhysicalMachine).text }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 诊断3：物理机 / 机架分布 -->
      <div
        id="topology-diag-3"
        class="app-topology-exam-section app-topology-exam-section--diag"
        :class="diagOk(3) ? 'app-topology-exam-section--diag-ok' : 'app-topology-exam-section--diag-fail'"
      >
        <div class="app-topology-exam-section__head">
          <h5 class="app-topology-exam-section__title">
            <el-icon><Monitor /></el-icon>
            诊断3：物理机 / 机架分布
          </h5>
        </div>

        <div class="app-topology-exam-stats">
          <span class="app-topology-exam-stat">物理机节点数：<strong>{{ data.machineGroupCount }}</strong></span>
        </div>

        <div v-if="data.machineGroupCount < 3" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">物理机分布数量少于 3 组</span>
        </div>
        <div v-if="data.failoverOk === false" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">主节点分布少于 3 台物理机，单台物理机宕机不满足故障转移条件</span>
        </div>

        <div v-if="data.machines.length" class="app-topology-exam-table-wrap">
          <table class="table app-topology-exam-table">
            <thead>
              <tr>
                <th>宿主机</th>
                <th>机房 / 机架</th>
                <th>节点信息</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="m in data.machines" :key="m.realIp">
                <td><code class="app-topology-exam-code">{{ m.realIp }}</code></td>
                <td>{{ machineRoomRack(m) }}</td>
                <td class="app-topology-exam-table__instances">
                  <span v-for="inst in m.instances" :key="inst.id" class="app-topology-exam-instance">
                    <code class="app-topology-exam-code">{{ inst.hostPort }}</code>
                    <span class="app-topology-exam-role">{{ inst.roleDesc }}</span>
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <!-- 诊断4：槽位分析 -->
      <div
        v-if="data.appType === 2 && data.slotExam"
        id="topology-diag-4"
        class="app-topology-exam-section app-topology-exam-section--diag"
        :class="diagOk(4) ? 'app-topology-exam-section--diag-ok' : 'app-topology-exam-section--diag-fail'"
      >
        <div class="app-topology-exam-section__head">
          <h5 class="app-topology-exam-section__title">
            <el-icon><Grid /></el-icon>
            诊断4：槽位分析
          </h5>
        </div>

        <div class="app-topology-exam-stats">
          <span class="app-topology-exam-stat">总槽位：<strong>{{ data.slotExam.totalSlots }}</strong></span>
          <span class="app-topology-exam-stat">已覆盖：<strong>{{ data.slotExam.coveredSlots }}</strong></span>
          <span class="app-topology-exam-stat">丢失：<strong>{{ data.slotExam.lostSlotsCount }}</strong></span>
          <span class="app-topology-exam-stat">Master 数：<strong>{{ data.slotExam.masterCount }}</strong></span>
        </div>

        <div v-if="!data.slotExam.fetchOk" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">无法获取 CLUSTER SLOTS，请检查集群连通性与节点状态</span>
        </div>
        <div v-if="data.slotExam.fetchOk && data.slotExam.slotComplete" class="app-topology-exam-alert app-topology-exam-alert--ok">
          <el-icon class="app-topology-exam-alert__icon"><Check /></el-icon>
          <span class="app-topology-exam-alert__text">16384 槽位完整覆盖，无丢失</span>
        </div>
        <div v-if="data.slotExam.fetchOk && !data.slotExam.slotComplete" class="app-topology-exam-alert app-topology-exam-alert--fail">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">
            槽位未完整覆盖：已覆盖 {{ data.slotExam.coveredSlots }} / {{ data.slotExam.totalSlots }}，缺失 {{ data.slotExam.lostSlotsCount }} 个
          </span>
        </div>
        <div v-if="data.slotExam.imbalance || data.slotExam.zeroSlotMaster" class="app-topology-exam-alert app-topology-exam-alert--warn">
          <el-icon class="app-topology-exam-alert__icon"><Warning /></el-icon>
          <span class="app-topology-exam-alert__text">
            槽位分配不均：最少 {{ data.slotExam.minSlotCount }}、最多 {{ data.slotExam.maxSlotCount }}，比值 {{ data.slotExam.imbalanceRatio }}（阈值 1.5）
          </span>
        </div>

        <template v-if="data.slotExam.lossRows?.length">
          <h6 class="app-topology-exam-subtitle">丢失槽位明细</h6>
          <div class="app-topology-exam-table-wrap app-topology-exam-table-wrap--sub">
            <table class="table app-topology-exam-table">
              <thead>
                <tr>
                  <th>节点</th>
                  <th>丢失区间</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in data.slotExam.lossRows" :key="row.hostPort + row.segments">
                  <td><code class="app-topology-exam-code">{{ row.hostPort }}</code></td>
                  <td>{{ row.segments }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>

        <template v-if="data.slotExam.distributionRows?.length">
          <h6 class="app-topology-exam-subtitle">槽位分配情况</h6>
          <div class="app-topology-exam-table-wrap">
            <table class="table app-topology-exam-table app-topology-exam-table--slots">
              <thead>
                <tr>
                  <th>Master</th>
                  <th>槽位区间</th>
                  <th class="app-topology-exam-table__narrow">槽位数</th>
                  <th class="app-topology-exam-table__narrow">占比</th>
                  <th>分布</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in data.slotExam.distributionRows" :key="row.hostPort">
                  <td><code class="app-topology-exam-code">{{ row.hostPort }}</code></td>
                  <td class="app-topology-exam-slot-ranges">{{ row.slotRanges }}</td>
                  <td>{{ row.slotCount }}</td>
                  <td>{{ row.percent }}%</td>
                  <td>
                    <div class="app-topology-exam-slot-bar" :title="`${row.percent}%`">
                      <div class="app-topology-exam-slot-bar__fill" :style="{ width: `${row.percent}%` }" />
                    </div>
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
      </div>
    </template>

    <div v-if="data.tips.length" class="app-topology-exam-section">
      <h5 class="app-topology-exam-section__title app-topology-exam-section__title--bar">
        <el-icon><List /></el-icon>
        问题列表
      </h5>
      <div class="app-topology-exam-table-wrap">
        <table class="table app-topology-exam-table">
          <thead>
            <tr>
              <th>类型</th>
              <th>状态</th>
              <th>描述</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(tip, idx) in data.tips" :key="idx">
              <td>{{ tip.type }}</td>
              <td>{{ tip.status }}</td>
              <td>{{ tip.desc }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>

<style scoped>
.topology-muted {
  color: #64748b;
  font-size: 11px;
}
.table {
  width: 100%;
  border-collapse: collapse;
}
</style>

<script lang="ts" setup>
import type { InstanceClientConnection } from "@/api/cachecloud"
import {
  buildClientDisplayFields,
  buildConnectionSummary
} from "@/common/utils/redis-client-connection"

const props = defineProps<{
  connections: InstanceClientConnection[]
  pageSize?: number
}>()

const expandedRaw = ref<Record<number, boolean>>({})
const visibleCount = ref(props.pageSize ?? 20)

const visibleConnections = computed(() => props.connections.slice(0, visibleCount.value))
const hasMore = computed(() => visibleCount.value < props.connections.length)

function fields(conn: InstanceClientConnection) {
  return buildClientDisplayFields(conn.detail, { flags: conn.flags, cmd: conn.cmd })
}

function summary(conn: InstanceClientConnection) {
  return buildConnectionSummary(conn.detail, { flags: conn.flags, cmd: conn.cmd })
}

function toggleRaw(index: number) {
  expandedRaw.value[index] = !expandedRaw.value[index]
}

function loadMore() {
  visibleCount.value += props.pageSize ?? 20
}

watch(() => props.connections, () => {
  visibleCount.value = props.pageSize ?? 20
  expandedRaw.value = {}
})
</script>

<template>
  <div v-if="connections.length" class="redis-conn-panel">
    <p v-if="connections.length > (pageSize ?? 20)" class="redis-conn-panel__hint">
      共 {{ connections.length }} 条连接，已展示 {{ visibleConnections.length }} 条
    </p>
    <div
      v-for="(conn, index) in visibleConnections"
      :key="index"
      class="redis-conn-card"
    >
      <div class="redis-conn-card__head">
        <span class="redis-conn-card__index">连接 {{ index + 1 }}</span>
        <span class="redis-conn-card__summary">{{ summary(conn) }}</span>
      </div>

      <el-descriptions :column="2" border size="small" class="redis-conn-card__desc">
        <el-descriptions-item
          v-for="field in fields(conn)"
          :key="field.key"
          :label="field.label"
        >
          {{ field.value }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="redis-conn-card__raw-toggle">
        <el-button link type="primary" size="small" @click="toggleRaw(index)">
          {{ expandedRaw[index] ? "收起原始数据" : "查看原始数据" }}
        </el-button>
      </div>
      <pre v-if="expandedRaw[index]" class="redis-conn-card__raw">{{ conn.detail }}</pre>
    </div>
    <div v-if="hasMore" class="redis-conn-panel__more">
      <el-button @click="loadMore">
        加载更多（剩余 {{ connections.length - visibleConnections.length }} 条）
      </el-button>
    </div>
  </div>
  <el-empty v-else description="暂无连接详情" :image-size="64" />
</template>

<style scoped>
.redis-conn-panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.redis-conn-panel__hint {
  margin: 0;
  font-size: 12px;
  color: #94a3b8;
}

.redis-conn-panel__more {
  display: flex;
  justify-content: center;
  padding-top: 4px;
}

.redis-conn-card {
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  overflow: hidden;
  background: #fff;
}

.redis-conn-card__head {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  padding: 10px 12px;
  background: #f8fafc;
  border-bottom: 1px solid #e2e8f0;
}

.redis-conn-card__index {
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: #3b82f6;
}

.redis-conn-card__summary {
  font-size: 12px;
  color: #64748b;
  line-height: 1.5;
}

.redis-conn-card__desc :deep(.el-descriptions__label) {
  width: 108px;
  font-size: 12px;
  color: #64748b;
  background: #fafbfc;
}

.redis-conn-card__desc :deep(.el-descriptions__content) {
  font-size: 13px;
  color: #1e293b;
  word-break: break-all;
}

.redis-conn-card__raw-toggle {
  padding: 4px 12px 8px;
}

.redis-conn-card__raw {
  margin: 0 12px 12px;
  padding: 10px 12px;
  border-radius: 6px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 11px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 160px;
  overflow: auto;
}
</style>

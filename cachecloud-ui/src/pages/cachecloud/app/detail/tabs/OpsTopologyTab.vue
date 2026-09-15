<script lang="ts" setup>
import type { AppDetail, TopologyExam } from "@/api/cachecloud"
import { getTopologyExamApi } from "@/api/cachecloud"
import TopologyExamPanel from "@/pages/cachecloud/app/components/TopologyExamPanel.vue"
import "@/common/assets/styles/app-ops.scss"

const props = defineProps<{
  appId: number
  detail?: AppDetail | null
}>()

const CACHE_KEY_PREFIX = "cc:topology-exam:"
const CACHE_TTL_MS = 5 * 60 * 1000

const topologyResult = ref<TopologyExam | null>(null)
const loading = ref(false)
const refreshing = ref(false)
const loadError = ref("")
let requestSeq = 0

function cacheKey(appId: number) {
  return `${CACHE_KEY_PREFIX}${appId}`
}

function readCache(appId: number): TopologyExam | null {
  try {
    const raw = sessionStorage.getItem(cacheKey(appId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as { data?: TopologyExam, ts?: number }
    if (!parsed.data || !parsed.ts || Date.now() - parsed.ts > CACHE_TTL_MS) {
      return null
    }
    return parsed.data
  } catch {
    return null
  }
}

function writeCache(appId: number, data: TopologyExam) {
  try {
    sessionStorage.setItem(cacheKey(appId), JSON.stringify({ data, ts: Date.now() }))
  } catch {
    // ignore quota errors
  }
}

function hydrateFromCache() {
  const cached = readCache(props.appId)
  if (cached) {
    topologyResult.value = cached
  }
}

async function fetchTopology(force = false) {
  if ((loading.value || refreshing.value) && !force) {
    return
  }
  const seq = ++requestSeq
  loadError.value = ""
  const hasDisplay = topologyResult.value != null

  if (!hasDisplay) {
    hydrateFromCache()
  }

  const showInitialLoading = !topologyResult.value
  if (showInitialLoading) {
    loading.value = true
  } else {
    refreshing.value = true
  }

  try {
    const { data } = await getTopologyExamApi(props.appId, force)
    if (seq !== requestSeq) return
    topologyResult.value = data ?? null
    if (data) {
      writeCache(props.appId, data)
    }
  } catch (e: unknown) {
    if (seq !== requestSeq) return
    loadError.value = e instanceof Error ? e.message : "拓扑诊断失败，请稍后重试"
    if (!topologyResult.value) {
      topologyResult.value = null
    }
  } finally {
    if (seq === requestSeq) {
      loading.value = false
      refreshing.value = false
    }
  }
}

/** 进入 Tab 先展示缓存，再后台刷新 */
onMounted(() => {
  hydrateFromCache()
  void fetchTopology(false)
})

onActivated(() => {
  hydrateFromCache()
  void fetchTopology(false)
})

watch(() => props.appId, () => {
  requestSeq++
  topologyResult.value = null
  loadError.value = ""
  hydrateFromCache()
  void fetchTopology(false)
})
</script>

<template>
  <div class="app-topology-exam-tab">
    <div class="app-topology-exam-tab__toolbar">
      <button
        type="button"
        class="btn btn-primary btn-sm topology-refetch-btn"
        :disabled="loading || refreshing"
        @click="fetchTopology(true)"
      >
        {{ refreshing ? "诊断中..." : "重新诊断" }}
      </button>
      <span v-if="refreshing && topologyResult" class="topology-refresh-hint">
        正在后台更新诊断结果…
      </span>
    </div>

    <el-alert
      v-if="loadError"
      type="error"
      :closable="false"
      show-icon
      class="topology-error-alert"
      :title="loadError"
    />

    <div v-if="loading && !topologyResult" class="topology-skeleton">
      <el-skeleton animated>
        <template #template>
          <div class="topology-skeleton__hero">
            <el-skeleton-item variant="circle" style="width: 36px; height: 36px" />
            <div class="topology-skeleton__hero-text">
              <el-skeleton-item variant="h3" style="width: 42%" />
              <el-skeleton-item variant="text" style="width: 72%; margin-top: 10px" />
              <el-skeleton-item variant="text" style="width: 58%; margin-top: 6px" />
            </div>
          </div>
          <el-skeleton-item variant="h3" style="width: 28%; margin: 16px 0 12px" />
          <div class="topology-skeleton__cards">
            <el-skeleton-item v-for="i in 3" :key="i" variant="rect" class="topology-skeleton__card" />
          </div>
          <el-skeleton-item variant="h3" style="width: 24%; margin: 18px 0 12px" />
          <el-skeleton-item variant="rect" style="width: 100%; height: 120px" />
        </template>
      </el-skeleton>
      <p class="topology-skeleton__tip">
        正在连接集群节点并检查拓扑、槽位与物理机分布，请稍候…
      </p>
    </div>

    <div v-else-if="topologyResult" class="topology-result-wrap" :class="{ 'topology-result-wrap--dimmed': refreshing }">
      <TopologyExamPanel :data="topologyResult" />
    </div>

    <div v-else-if="!loading" class="topology-empty">
      暂无拓扑诊断结果
    </div>
  </div>
</template>

<style lang="scss" scoped>
.app-topology-exam-tab {
  padding-top: 0;
}

.app-topology-exam-tab__toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}

.topology-refetch-btn {
  padding: 4px 12px;
  border: none;
  border-radius: 4px;
  background: var(--el-color-primary);
  color: #fff;
  font-size: 12px;
  cursor: pointer;
}

.topology-refetch-btn:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.topology-refresh-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.topology-error-alert {
  margin-bottom: 10px;
}

.topology-skeleton {
  padding: 4px 0 8px;
}

.topology-skeleton__hero {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.topology-skeleton__hero-text {
  flex: 1;
}

.topology-skeleton__cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.topology-skeleton__card {
  height: 92px;
  border-radius: 6px;
}

.topology-skeleton__tip {
  margin: 14px 0 0;
  text-align: center;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.topology-result-wrap--dimmed {
  opacity: 0.72;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.topology-empty {
  padding: 24px;
  text-align: center;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

@media (max-width: 992px) {
  .topology-skeleton__cards {
    grid-template-columns: 1fr;
  }
}
</style>

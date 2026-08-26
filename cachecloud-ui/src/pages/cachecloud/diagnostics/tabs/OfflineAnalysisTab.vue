<script lang="ts" setup>
import type { KeyAnalysisResult, OfflineAnalysisRecord } from "@/api/cachecloud"
import { Refresh, UploadFilled } from "@element-plus/icons-vue"
import {
  deleteOfflineAnalysisApi,
  getOfflineAnalysisListApi,
  getOfflineAnalysisResultApi,
  uploadOfflineAnalysisApi
} from "@/api/cachecloud"
import KeyAnalysisReport from "@/pages/cachecloud/components/KeyAnalysisReport.vue"
import "@/common/assets/styles/app-tab.scss"

const STATUS_PENDING = 0
const STATUS_RUNNING = 1
const STATUS_DONE = 2
const STATUS_FAILED = 3

/** 有记录处于排队/分析中时的轮询间隔 */
const POLL_INTERVAL_MS = 3000

const loading = ref(false)
const uploading = ref(false)
const uploadPercent = ref(0)
const records = ref<OfflineAnalysisRecord[]>([])
const total = ref(0)
const pageNo = ref(1)
const pageSize = ref(20)

const viewMode = ref<"list" | "result">("list")
const resultLoading = ref(false)
const result = ref<KeyAnalysisResult | null>(null)
const activeRecord = ref<OfflineAnalysisRecord | null>(null)

const fileInput = ref<HTMLInputElement | null>(null)
let pollTimer: ReturnType<typeof setInterval> | null = null

const hasRunning = computed(() =>
  records.value.some(r => r.status === STATUS_PENDING || r.status === STATUS_RUNNING)
)

function statusTagType(status: number) {
  if (status === STATUS_DONE) return "success"
  if (status === STATUS_FAILED) return "danger"
  if (status === STATUS_RUNNING) return "warning"
  return "info"
}

async function fetchList() {
  loading.value = true
  try {
    const { data } = await getOfflineAnalysisListApi({ pageNo: pageNo.value, pageSize: pageSize.value })
    records.value = data?.items ?? []
    total.value = data?.totalCount ?? 0
  } finally {
    loading.value = false
  }
}

// 只在确实有任务在跑时才轮询，列表全是终态时停掉，避免无意义的请求
function syncPolling() {
  if (hasRunning.value && !pollTimer) {
    pollTimer = setInterval(() => {
      void fetchList()
    }, POLL_INTERVAL_MS)
  } else if (!hasRunning.value && pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

function pickFile() {
  fileInput.value?.click()
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空 value，否则连续选同一个文件不会再触发 change
  input.value = ""
  if (!file) return

  uploading.value = true
  uploadPercent.value = 0
  try {
    await uploadOfflineAnalysisApi(file, (percent) => {
      uploadPercent.value = percent
    })
    ElMessage.success(`${file.name} 已上传，正在后台解析`)
    pageNo.value = 1
    await fetchList()
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

async function openResult(row: OfflineAnalysisRecord) {
  activeRecord.value = row
  viewMode.value = "result"
  resultLoading.value = true
  result.value = null
  try {
    const { data } = await getOfflineAnalysisResultApi(row.id)
    result.value = data
  } finally {
    resultLoading.value = false
  }
}

function backToList() {
  viewMode.value = "list"
  result.value = null
  activeRecord.value = null
  void fetchList()
}

async function handleDelete(row: OfflineAnalysisRecord) {
  await ElMessageBox.confirm(`确认删除记录「${row.fileName}」？删除后分析结果不可恢复。`, "提示", { type: "warning" })
  await deleteOfflineAnalysisApi(row.id)
  ElMessage.success("已删除")
  await fetchList()
}

watch(records, syncPolling)

onMounted(() => {
  void fetchList()
})
onBeforeUnmount(() => {
  if (pollTimer) clearInterval(pollTimer)
})
</script>

<template>
  <div class="app-tab-embed">
    <KeyAnalysisReport
      v-if="viewMode === 'result'"
      :result="result"
      :loading="resultLoading"
      :subtitle="`离线文件 · ${activeRecord?.fileName || ''}`"
      :export-name="`Redis离线分析_${activeRecord?.fileName || ''}`"
      @back="backToList"
    />

    <template v-else>
      <el-alert type="info" :closable="false" show-icon class="offline-hint">
        <template #title>
          <span class="offline-hint__text">
            上传Redis的RDB快照文件进行解析，解析在服务端流式进行，不会把数据写入任何 Redis 实例；源文件在分析结束后立即删除。
          </span>
        </template>
      </el-alert>

      <div class="offline-toolbar">
        <input ref="fileInput" type="file" accept=".rdb" class="offline-file-input" @change="handleFileChange">
        <el-button type="primary" :icon="UploadFilled" :loading="uploading" @click="pickFile">
          {{ uploading ? `上传中 ${uploadPercent}%` : "上传 RDB 文件" }}
        </el-button>
        <el-button :icon="Refresh" :loading="loading" @click="fetchList">
          刷新
        </el-button>
        <span v-if="hasRunning" class="offline-toolbar__hint">有任务正在解析，列表每 3 秒自动刷新</span>
      </div>

      <el-progress v-if="uploading" :percentage="uploadPercent" :stroke-width="12" class="offline-progress" />

      <el-table v-loading="loading" :data="records" stripe border style="width: 100%">
        <el-table-column prop="fileName" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column prop="fileSizeLabel" label="大小" width="110" align="right" />
        <el-table-column label="状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">
              {{ row.statusDesc }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="key 数量" width="120" align="right">
          <template #default="{ row }">
            {{ row.status === STATUS_DONE || row.status === STATUS_RUNNING ? row.keyCount.toLocaleString() : "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="userName" label="上传人" width="110" />
        <el-table-column prop="createTime" label="上传时间" width="170" />
        <el-table-column label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.errorMsg || "-" }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <div class="table-actions offline-actions">
              <el-button
                v-if="row.status === STATUS_DONE"
                type="primary"
                size="small"
                @click="openResult(row)"
              >
                查看结果
              </el-button>
              <el-button
                v-if="row.status !== STATUS_RUNNING"
                type="danger"
                size="small"
                @click="handleDelete(row)"
              >
                删除记录
              </el-button>
              <!-- 解析中：既不能看结果也不能删，避免出现空白单元格 -->
              <span v-if="row.status === STATUS_RUNNING" class="offline-muted">—</span>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          还没有离线分析记录，点上方按钮上传一个 RDB 文件
        </template>
      </el-table>

      <div class="offline-pagination">
        <el-pagination
          v-model:current-page="pageNo"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          @current-change="fetchList"
          @size-change="fetchList"
        />
      </div>
    </template>
  </div>
</template>

<style scoped>
.offline-hint {
  margin-bottom: 16px;
}

/* 沿用原正文那档小字号，避免提示比表格内容还抢眼 */
.offline-hint__text {
  font-size: 13px;
  font-weight: normal;
  line-height: 1.7;
}

.offline-toolbar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.offline-toolbar__hint {
  font-size: 12px;
  color: var(--rp-text-muted);
}

/* 用原生 input 承接选择，按钮只负责触发，避免引入额外的上传组件状态 */
.offline-file-input {
  display: none;
}

.offline-progress {
  margin-bottom: 12px;
}

/* table-actions 默认允许换行；这里两个按钮必须同行，
   否则窄列时「删除记录」会掉到第二行，与相邻行的按钮上下错位 */
.offline-actions {
  flex-wrap: nowrap;
}

.offline-muted {
  color: var(--rp-text-muted);
}

.offline-pagination {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>

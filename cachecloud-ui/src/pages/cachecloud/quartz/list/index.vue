<script lang="ts" setup>
import type { QuartzJob } from "@/api/cachecloud"
import {
  deleteQuartzJobApi,
  getQuartzJobListApi,
  pauseQuartzJobApi,
  resumeQuartzJobApi
} from "@/api/cachecloud"
import { Search } from "@element-plus/icons-vue"

const loading = ref(false)
const jobs = ref<QuartzJob[]>([])
const query = ref("")

async function fetchJobs() {
  loading.value = true
  try {
    const { data } = await getQuartzJobListApi(query.value || undefined)
    jobs.value = data ?? []
  } finally {
    loading.value = false
  }
}

async function handlePause(row: QuartzJob) {
  await ElMessageBox.confirm("确认暂停该任务？", "提示", { type: "warning" })
  await pauseQuartzJobApi(row.triggerName, row.triggerGroup)
  ElMessage.success("已暂停")
  fetchJobs()
}

async function handleResume(row: QuartzJob) {
  await ElMessageBox.confirm("确认恢复该任务？", "提示", { type: "warning" })
  await resumeQuartzJobApi(row.triggerName, row.triggerGroup)
  ElMessage.success("已恢复")
  fetchJobs()
}

async function handleRemove(row: QuartzJob) {
  await ElMessageBox.confirm("确认删除该任务？", "提示", { type: "warning" })
  await deleteQuartzJobApi(row.triggerName, row.triggerGroup)
  ElMessage.success("已删除")
  fetchJobs()
}

onMounted(fetchJobs)
</script>

<template>
  <div class="quartz-page">
    <div class="page-header">
      <div class="page-actions">
        <el-input v-model="query" placeholder="triggerName / group" clearable style="width: 200px" />
        <el-button type="primary" :icon="Search" @click="fetchJobs">查询</el-button>
      </div>
    </div>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="jobs" stripe border>
        <el-table-column prop="triggerName" label="triggerName" min-width="160" />
        <!-- 最长的组名 cleanUpMinuteStatisticsGroup 有 28 个字符，14px 下约 186px，
             加上单元格左右各 12px 的内边距要 210px 才放得下，原来的 140 必然换行。
             再配 show-overflow-tooltip，将来出现更长的组名也是省略号而不是撑成两行。 -->
        <el-table-column prop="triggerGroup" label="triggerGroup" width="230" show-overflow-tooltip />
        <el-table-column prop="cron" label="cron" min-width="140" />
        <el-table-column prop="nextFireDate" label="nextFireDate" width="170" />
        <el-table-column prop="prevFireDate" label="prevFireDate" width="170" />
        <el-table-column prop="startDate" label="startDate" width="170" />
        <el-table-column prop="triggerState" label="triggerState" width="110" />
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button v-if="row.paused" type="success" size="small" @click="handleResume(row)">恢复</el-button>
              <el-button v-else type="warning" size="small" @click="handlePause(row)">暂停</el-button>
              <el-button type="danger" size="small" @click="handleRemove(row)">删除</el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.quartz-page { padding: 16px; }
.page-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.page-title { margin: 0; font-size: 20px; font-weight: 600; }
.page-actions { display: flex; gap: 8px; }
</style>

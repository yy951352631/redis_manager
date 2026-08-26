<script lang="ts" setup>
import type { DataModelResult } from "@/api/cachecloud"
import { InfoFilled, Warning } from "@element-plus/icons-vue"

/**
 * 四个主题共用的外壳：统一处理「加载中 / 空态 / 样本不足」三种状态。
 *
 * 样本量必须摆在图上方——3 个点也能画出一条很有说服力的趋势线，
 * 不说明样本量，读图的人无从判断该给它多少信任。
 */
defineProps<{
  result: DataModelResult | null
  loading?: boolean
  /** 该主题在回答什么问题，写在标题下方 */
  question: string
}>()
</script>

<template>
  <div v-loading="loading" class="dm-topic">
    <div class="dm-topic__question">
      <el-icon><InfoFilled /></el-icon>
      <span>{{ question }}</span>
    </div>

    <el-empty v-if="result?.emptyReason" :description="result.emptyReason" />

    <template v-else-if="result">
      <div class="dm-topic__meta">
        参与统计：<strong>{{ result.instanceCount }}</strong> 个实例，
        覆盖 <strong>{{ result.coverDays.toFixed(1) }}</strong> 天
      </div>

      <el-alert v-if="result.sampleNote" type="warning" :closable="false" show-icon class="dm-topic__note">
        <template #title>
          <el-icon><Warning /></el-icon>
          {{ result.sampleNote }}
        </template>
      </el-alert>

      <slot />
    </template>
  </div>
</template>

<style scoped>
.dm-topic {
  min-height: 320px;
}

.dm-topic__question {
  display: flex;
  gap: 6px;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}

.dm-topic__meta {
  margin-bottom: 10px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.dm-topic__note {
  margin-bottom: 12px;
}
</style>

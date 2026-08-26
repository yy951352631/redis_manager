<script lang="ts" setup>
import type { AppDaily } from "@/api/cachecloud"
import { getAppDailyApi } from "@/api/cachecloud"
import { Coin, InfoFilled, Search } from "@element-plus/icons-vue"
import { useAutoQuery } from "@@/composables/useAutoQuery"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

const loading = ref(false)
const dailyDate = ref("")
const daily = ref<AppDaily | null>(null)

const NA = "--"

const placeholderClass = computed(() => (!daily.value?.hasData ? "app-daily-placeholder" : ""))

function disp(value: string | number | undefined | null, suffix = "") {
  if (!daily.value?.hasData || value == null || value === "") return NA
  return `${value}${suffix}`
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getAppDailyApi(props.appId, dailyDate.value || undefined)
    daily.value = data
    if (data?.dailyDate) silentDate(() => { dailyDate.value = data.dailyDate })
  } finally {
    loading.value = false
  }
}

// 选完日期即自动查询；fetchData 里对 dailyDate 的回写用 silent 包住，避免自触发
const { silent: silentDate } = useAutoQuery(dailyDate, () => fetchData())

watch(() => props.appId, fetchData, { immediate: true })
</script>

<template>
  <div v-loading="loading" :class="['app-daily-page', { 'app-daily-page--no-data': daily && !daily.hasData }]">
    <div class="app-daily-toolbar">
      <div class="app-daily-toolbar__search">
        <label class="app-daily-toolbar__label">日期</label>
        <el-date-picker
          v-model="dailyDate"
          type="date"
          value-format="YYYY-MM-DD"
          class="app-daily-date"
        />
        <el-button type="primary" class="app-daily-query-btn" :icon="Search" @click="fetchData">查询</el-button>
      </div>
    </div>

    <div v-if="daily && !daily.hasData" class="app-daily-empty-hint">
      <el-icon class="app-daily-empty-hint__icon"><InfoFilled /></el-icon>
      <div class="app-daily-empty-hint__text">
        <strong>{{ daily.dailyDate }}</strong> 暂无日报数据——当天没有采集到任何监控样本，
        通常是集群在该日期之后才纳管，或平台当天未运行。请换一个日期查询。
      </div>
    </div>

    <div v-else-if="daily?.hasData && !daily.persisted" class="app-daily-empty-hint">
      <el-icon class="app-daily-empty-hint__icon"><InfoFilled /></el-icon>
      <div class="app-daily-empty-hint__text">
        <strong>{{ daily.dailyDate }}</strong> 的日报由分钟采集表<strong>实时汇总</strong>得到。
        定时任务每天 10:00 汇总前一天并写入 <code>app_daily</code>，落库后本页改用落库数据。
      </div>
    </div>

    <div class="app-daily-section app-daily-section--server">
      <div class="app-daily-section__head">
        <span class="app-daily-section__badge app-daily-section__badge--server">
          <el-icon><Coin /></el-icon>
        </span>
        <span class="app-daily-section__title">服务端相关</span>
      </div>
      <div class="app-daily-grid">
        <div class="app-daily-card app-daily-card--red">
          <div class="app-daily-card__label">慢查询个数（全天）</div>
          <div class="app-daily-card__value">
            <span :class="placeholderClass">{{ disp(daily?.slowLogCount) }}</span>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--violet">
          <div class="app-daily-card__label">命令次数（每分钟）</div>
          <div class="app-daily-card__metric">
            <span>最大值</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxMinuteCommandCount) }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>平均值</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgMinuteCommandCount) }}</strong>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--green">
          <div class="app-daily-card__label">命中率（每分钟）</div>
          <div class="app-daily-card__metric">
            <span>最大值</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxMinuteHitRatio, '%') }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>最小值</span>
            <strong :class="placeholderClass">{{ disp(daily?.minMinuteHitRatio, '%') }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>平均值</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgHitRatio, '%') }}</strong>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--teal">
          <div class="app-daily-card__label">内存使用量（全天）</div>
          <div class="app-daily-card__metric">
            <span>平均使用量</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgUsedMemory, ' M') }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>最大使用量</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxUsedMemory, ' M') }}</strong>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--lime">
          <div class="app-daily-card__label">过期键数（全天）</div>
          <div class="app-daily-card__value">
            <span :class="placeholderClass">{{ disp(daily?.expiredKeysCount) }}</span>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--pink">
          <div class="app-daily-card__label">剔除键数（全天）</div>
          <div class="app-daily-card__value">
            <span :class="placeholderClass">{{ disp(daily?.evictedKeysCount) }}</span>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--sky">
          <div class="app-daily-card__label">键个数（全天）</div>
          <div class="app-daily-card__metric">
            <span>平均值</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgObjectSize) }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>最大值</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxObjectSize) }}</strong>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--blue">
          <div class="app-daily-card__label">input 流量（每分钟）</div>
          <div class="app-daily-card__metric">
            <span>平均值</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgMinuteNetInputByte, ' M') }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>最大值</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxMinuteNetInputByte, ' M') }}</strong>
          </div>
        </div>
        <div class="app-daily-card app-daily-card--indigo">
          <div class="app-daily-card__label">output 流量（每分钟）</div>
          <div class="app-daily-card__metric">
            <span>平均值</span>
            <strong :class="placeholderClass">{{ disp(daily?.avgMinuteNetOutputByte, ' M') }}</strong>
          </div>
          <div class="app-daily-card__metric">
            <span>最大值</span>
            <strong :class="placeholderClass">{{ disp(daily?.maxMinuteNetOutputByte, ' M') }}</strong>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

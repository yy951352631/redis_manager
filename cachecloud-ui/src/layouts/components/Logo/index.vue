<script lang="ts" setup>
import { useLayoutMode } from "@@/composables/useLayoutMode"

interface Props {
  collapse?: boolean
}

const { collapse = true } = defineProps<Props>()

const { isTop } = useLayoutMode()
</script>

<template>
  <div class="layout-logo-container" :class="{ 'collapse': collapse, 'layout-mode-top': isTop }">
    <router-link to="/" class="layout-logo-link">
      <span v-if="collapse" class="layout-logo-mark">R</span>
      <span v-else class="layout-logo-text">Redis管理平台</span>
    </router-link>
  </div>
</template>

<style lang="scss" scoped>
.layout-logo-container {
  position: relative;
  width: 100%;
  height: var(--v3-header-height);
  line-height: var(--v3-header-height);
  text-align: center;
  overflow: hidden;

  .layout-logo-link {
    display: inline-flex;
    align-items: center;
    justify-content: center;
    width: 100%;
    height: 100%;
    color: var(--rp-primary, #4d8dff);
    font-weight: 700;
    letter-spacing: 0.02em;
  }

  .layout-logo-mark {
    font-size: var(--rp-font-size-xl);
    color: var(--rp-primary, #4d8dff);
  }

  .layout-logo-text {
    font-size: var(--rp-font-size-lg);
    white-space: nowrap;
  }
}

.layout-mode-top {
  height: var(--v3-navigationbar-height);
  line-height: var(--v3-navigationbar-height);

  .layout-logo-link {
    color: var(--el-color-primary);
  }
}

.collapse {
  .layout-logo-mark {
    display: inline-block;
  }
}
</style>

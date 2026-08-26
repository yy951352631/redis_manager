<script lang="ts" setup>
</script>

<template>
  <footer class="layout-footer">
    上海长剑信息科技有限公司 Redis 管理平台 © {{ new Date().getFullYear() }}
  </footer>
</template>

<style lang="scss" scoped>
.layout-footer {
  width: 100%;
  min-height: 50px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
}
</style>

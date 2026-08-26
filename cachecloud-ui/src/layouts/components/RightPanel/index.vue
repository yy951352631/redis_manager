<script lang="ts" setup>
import { Setting } from "@element-plus/icons-vue"

const show = ref(false)
</script>

<template>
  <div class="handle-button" title="页面设置" aria-label="页面设置" @click="show = true">
    <el-icon :size="20">
      <Setting />
    </el-icon>
  </div>
  <el-drawer v-model="show" size="300px" :with-header="false">
    <slot />
  </el-drawer>
</template>

<style lang="scss" scoped>
.handle-button {
  width: 32px;
  height: 32px;
  background-color: var(--v3-rightpanel-button-bg-color);
  border-radius: 6px;
  cursor: pointer;
  pointer-events: auto;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.2s;

  &:hover {
    opacity: 0.9;
  }
}
</style>

<script lang="ts" setup>
import type { CurrentUserProfile } from "@@/apis/users/type"
import { getCurrentUserApi, updateCurrentUserProfileApi } from "@@/apis/users"

const visible = defineModel<boolean>({ default: false })

const loading = ref(false)
const saving = ref(false)

const form = reactive({
  name: "",
  chName: "",
  email: "",
  mobile: "",
  company: "",
  type: 2,
  isAlert: 0
})

async function loadProfile() {
  loading.value = true
  try {
    const { data } = await getCurrentUserApi()
    fillForm(data)
  } finally {
    loading.value = false
  }
}

function fillForm(data: CurrentUserProfile) {
  form.name = data.username
  form.chName = data.chName ?? ""
  form.email = data.email ?? ""
  form.mobile = data.mobile ?? ""
  form.company = data.company ?? ""
  form.type = data.type ?? 2
  form.isAlert = data.isAlert ?? 0
}

async function handleSave() {
  if (!form.chName.trim() || !form.email.trim() || !form.mobile.trim()) {
    ElMessage.warning("中文名、邮箱和手机不能为空")
    return
  }
  saving.value = true
  try {
    await updateCurrentUserProfileApi({
      chName: form.chName.trim(),
      email: form.email.trim(),
      mobile: form.mobile.trim(),
      company: form.company.trim(),
      isAlert: form.isAlert
    })
    ElMessage.success("资料已更新")
    visible.value = false
  } finally {
    saving.value = false
  }
}

watch(visible, (open) => {
  if (open) {
    loadProfile()
  }
})
</script>

<template>
  <el-dialog v-model="visible" title="修改资料" width="520px" destroy-on-close>
    <el-form v-loading="loading" label-width="100px">
      <el-form-item label="域账户">
        <el-input v-model="form.name" disabled />
      </el-form-item>
      <el-form-item label="中文名">
        <el-input v-model="form.chName" />
      </el-form-item>
      <el-form-item label="邮箱">
        <el-input v-model="form.email" />
      </el-form-item>
      <el-form-item label="手机">
        <el-input v-model="form.mobile" />
      </el-form-item>
      <el-form-item label="部门">
        <el-input v-model="form.company" />
      </el-form-item>
      <el-form-item label="类型">
        <el-select v-model="form.type" disabled style="width: 100%">
          <el-option label="管理员" :value="0" />
          <el-option label="普通用户" :value="2" />
        </el-select>
      </el-form-item>
      <el-form-item label="是否报警">
        <el-select v-model="form.isAlert" style="width: 100%">
          <el-option label="否" :value="0" />
          <el-option label="是" :value="1" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">
        取消
      </el-button>
      <el-button type="primary" :loading="saving" @click="handleSave">
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

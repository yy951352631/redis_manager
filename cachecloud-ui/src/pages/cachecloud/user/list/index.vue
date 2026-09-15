<script lang="ts" setup>
import type { UserListItem } from "@/api/cachecloud"
import { Plus, Search } from "@element-plus/icons-vue"
import {
  deleteUserApi,
  getUserListApi,
  saveUserApi,
  updateUserPasswordApi
} from "@/api/cachecloud"

const loading = ref(false)
const users = ref<UserListItem[]>([])
const searchChName = ref("")
const dialogVisible = ref(false)
const pwdDialogVisible = ref(false)
const saving = ref(false)
const editing = ref(false)
const currentUserId = ref<number | null>(null)

const form = reactive({
  name: "",
  password: "",
  chName: "",
  email: "",
  mobile: "",
  weChat: "",
  type: 2,
  isAlert: 0,
  company: "",
  purpose: ""
})

const pwdForm = reactive({ password: "" })

async function fetchUsers() {
  loading.value = true
  try {
    const { data } = await getUserListApi(searchChName.value || undefined)
    users.value = data ?? []
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  fetchUsers()
}

function openCreate() {
  editing.value = false
  currentUserId.value = null
  Object.assign(form, {
    name: "",
    password: "",
    chName: "",
    email: "",
    mobile: "",
    weChat: "",
    type: 2,
    isAlert: 0,
    company: "",
    purpose: ""
  })
  dialogVisible.value = true
}

function openEdit(row: UserListItem) {
  editing.value = true
  currentUserId.value = row.id!
  Object.assign(form, {
    name: row.name,
    password: "",
    chName: row.chName,
    email: row.email,
    mobile: row.mobile,
    weChat: "",
    type: row.type,
    isAlert: row.isAlert,
    company: row.company || "",
    purpose: ""
  })
  dialogVisible.value = true
}

function openPwd(row: UserListItem) {
  currentUserId.value = row.id!
  pwdForm.password = ""
  pwdDialogVisible.value = true
}

async function handleSave() {
  if (!editing.value && !form.password.trim()) {
    ElMessage.warning("请输入初始密码")
    return
  }
  saving.value = true
  try {
    await saveUserApi({
      id: editing.value ? currentUserId.value! : undefined,
      ...form
    })
    ElMessage.success("保存成功")
    dialogVisible.value = false
    fetchUsers()
  } finally {
    saving.value = false
  }
}

async function handleUpdatePwd() {
  if (!pwdForm.password.trim()) {
    ElMessage.warning("请输入密码")
    return
  }
  await updateUserPasswordApi(currentUserId.value!, pwdForm.password)
  ElMessage.success("密码已更新")
  pwdDialogVisible.value = false
}

async function handleDelete(row: UserListItem) {
  await ElMessageBox.confirm(`确认删除用户 ${row.chName}？`, "提示", { type: "warning" })
  await deleteUserApi(row.id!)
  ElMessage.success("已删除")
  fetchUsers()
}

onMounted(fetchUsers)
</script>

<template>
  <div class="user-list-page">
    <div class="page-header">
      <div class="page-actions">
        <el-button type="success" :icon="Plus" @click="openCreate">
          添加新用户
        </el-button>
      </div>
    </div>

    <el-card shadow="never" class="content-card">
      <el-form :inline="true" class="search-form" @submit.prevent="handleSearch">
        <el-form-item label="中文姓名">
          <el-input v-model="searchChName" placeholder="中文姓名" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">
            查询
          </el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="users" stripe border style="width: 100%">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="name" label="域账户" width="120" show-overflow-tooltip />
        <el-table-column prop="chName" label="中文名" width="120" show-overflow-tooltip />
        <el-table-column prop="email" label="邮箱" min-width="160" show-overflow-tooltip />
        <el-table-column prop="mobile" label="手机" width="120" />
        <el-table-column prop="company" label="部门" width="120" show-overflow-tooltip />
        <el-table-column prop="isAlertLabel" label="是否报警" width="90" />
        <el-table-column prop="typeLabel" label="类型" width="90" />
        <el-table-column prop="registerTime" label="注册时间" width="170" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <div class="table-actions">
              <el-button type="primary" size="small" @click="openEdit(row)">
                修改
              </el-button>
              <el-button type="primary" size="small" @click="openPwd(row)">
                修改密码
              </el-button>
              <el-button type="danger" size="small" @click="handleDelete(row)">
                删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="editing ? '修改用户' : '添加用户'" width="520px">
      <el-form label-width="100px">
        <el-form-item label="域账户">
          <el-input v-model="form.name" :disabled="editing" />
        </el-form-item>
        <el-form-item v-if="!editing" label="初始密码">
          <el-input v-model="form.password" type="password" show-password autocomplete="new-password" />
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
          <el-select v-model="form.type" style="width: 100%">
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
        <el-button @click="dialogVisible = false">
          取消
        </el-button>
        <el-button type="primary" :loading="saving" @click="handleSave">
          保存
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="pwdDialogVisible" title="修改密码" width="400px">
      <el-input v-model="pwdForm.password" type="password" show-password placeholder="新密码" />
      <template #footer>
        <el-button @click="pwdDialogVisible = false">
          取消
        </el-button>
        <el-button type="primary" @click="handleUpdatePwd">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style lang="scss" scoped>
.user-list-page {
  padding: 16px;
}

.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-actions {
  display: flex;
  gap: 8px;
}

.search-form {
  margin-bottom: 16px;
}

.content-card {
  :deep(.el-card__body) {
    padding: 16px;
  }
}
</style>

<script lang="ts" setup>
import type { AppDetailPanel, AppDetailPanelAlertConfig, AppDetailPanelUser, UserListItem } from "@/api/cachecloud"
import {
  addAppDetailUsersApi,
  deleteAppDetailUserApi,
  getAppDetailPanelApi,
  getUserListApi,
  updateAppAlertConfigApi,
  updateAppDetailInfoApi,
  updateAppDetailUserApi
} from "@/api/cachecloud"
import { formatClusterNo } from "@/common/utils/cluster-no"
import "@/common/assets/styles/app-tab.scss"

const props = defineProps<{ appId: number }>()

const PHONE_RE = /^(13\d|14[57]|15[0-35-9]|17[035-8]|18\d|166|198|199)\d{8}$/

const loading = ref(false)
const panel = ref<AppDetailPanel | null>(null)
const allUsers = ref<UserListItem[]>([])

const appInfoVisible = ref(false)
const alertConfigVisible = ref(false)
const addUserVisible = ref(false)
const editUserVisible = ref(false)

const saving = ref(false)
const appInfoForm = ref({ appName: "", intro: "", officerId: "" })
const alertForm = ref({ isAccessMonitor: 0 })
const addUserIds = ref<number[]>([])
const editUser = ref<AppDetailPanelUser | null>(null)
const editUserForm = ref({
  name: "",
  chName: "",
  email: "",
  mobile: "",
  company: "",
  isAlert: 1,
  type: 0
})

const candidateUsers = computed(() => {
  const existing = new Set((panel.value?.users ?? []).map(u => u.id))
  return allUsers.value.filter(u => u.id != null && !existing.has(u.id))
})

function resolveAlertConfig(data: AppDetailPanel): AppDetailPanelAlertConfig {
  if (data.alertConfig) {
    return { ...data.alertConfig }
  }
  const globalMetric = data.alertMetrics.find(m => m.id === 4)
  return { isAccessMonitor: globalMetric?.cycle === "监控开启" ? 1 : 0 }
}

async function fetchData() {
  loading.value = true
  try {
    const { data } = await getAppDetailPanelApi(props.appId)
    if (data) {
      data.alertConfig = resolveAlertConfig(data)
    }
    panel.value = data
  } finally {
    loading.value = false
  }
}

async function loadAllUsers() {
  try {
    const { data } = await getUserListApi()
    allUsers.value = data ?? []
  } catch {
    allUsers.value = []
  }
}

function userLabel(u: UserListItem) {
  return `${u.chName}(${u.name})`
}

function openAppInfoDialog() {
  if (!panel.value) return
  appInfoForm.value = {
    appName: panel.value.appInfo.appName,
    intro: panel.value.appInfo.intro ?? "",
    officerId: panel.value.appInfo.officerId ?? ""
  }
  appInfoVisible.value = true
  loadAllUsers()
}

function openAlertConfigDialog() {
  if (!panel.value) return
  alertForm.value = resolveAlertConfig(panel.value)
  alertConfigVisible.value = true
}

function openAddUserDialog() {
  addUserIds.value = []
  addUserVisible.value = true
  loadAllUsers()
}

function openEditUserDialog(user: AppDetailPanelUser) {
  editUser.value = user
  editUserForm.value = {
    name: user.name,
    chName: user.chName,
    email: user.email,
    mobile: user.mobile,
    company: user.company,
    isAlert: user.alert ? 1 : 0,
    type: user.type
  }
  editUserVisible.value = true
}

async function submitAppInfo() {
  if (!appInfoForm.value.appName.trim()) {
    ElMessage.warning("集群名称不能为空")
    return
  }
  if (!appInfoForm.value.intro.trim()) {
    ElMessage.warning("集群描述不能为空")
    return
  }
  if (!appInfoForm.value.officerId) {
    ElMessage.warning("负责人不能为空")
    return
  }
  saving.value = true
  try {
    await updateAppDetailInfoApi(props.appId, {
      appName: appInfoForm.value.appName.trim(),
      intro: appInfoForm.value.intro.trim(),
      officerId: appInfoForm.value.officerId
    })
    ElMessage.success("修改成功")
    appInfoVisible.value = false
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "修改失败")
  } finally {
    saving.value = false
  }
}

async function submitAlertConfig() {
  saving.value = true
  try {
    await updateAppAlertConfigApi(props.appId, { ...alertForm.value })
    ElMessage.success("修改成功")
    alertConfigVisible.value = false
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "修改失败")
  } finally {
    saving.value = false
  }
}

async function submitAddUsers() {
  if (!addUserIds.value.length) {
    ElMessage.warning("请选择用户")
    return
  }
  saving.value = true
  try {
    await addAppDetailUsersApi(props.appId, { userIds: addUserIds.value })
    ElMessage.success("用户添加成功")
    addUserVisible.value = false
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "添加失败，只能添加有 Redis 管理平台权限的用户")
  } finally {
    saving.value = false
  }
}

async function submitEditUser() {
  const form = editUserForm.value
  if (!form.name.trim() || !form.chName.trim() || !form.mobile.trim() || !form.company.trim()) {
    ElMessage.warning("域账户、中文名、手机、部门不能为空")
    return
  }
  if (!PHONE_RE.test(form.mobile.trim())) {
    ElMessage.warning("手机号格式错误")
    return
  }
  if (!editUser.value) return
  saving.value = true
  try {
    await updateAppDetailUserApi(props.appId, editUser.value.id, {
      name: form.name.trim(),
      chName: form.chName.trim(),
      email: form.email.trim(),
      mobile: form.mobile.trim(),
      company: form.company.trim(),
      isAlert: form.isAlert,
      type: form.type
    })
    ElMessage.success("更新成功")
    editUserVisible.value = false
    await fetchData()
  } catch (e: unknown) {
    ElMessage.error(e instanceof Error ? e.message : "更新失败")
  } finally {
    saving.value = false
  }
}

async function handleDeleteUser(user: AppDetailPanelUser) {
  try {
    await ElMessageBox.confirm("确认要删除该用户吗?", "提示", { type: "warning" })
    await deleteAppDetailUserApi(props.appId, user.id)
    ElMessage.success("删除成功")
    await fetchData()
  } catch {
    // cancelled or failed
  }
}

watch(() => props.appId, fetchData, { immediate: true })
</script>

<template>
  <div v-loading="loading" class="app-detail-page">
    <template v-if="panel">
      <div class="app-detail-section">
        <div class="app-detail-section__header app-detail-section__header--info">
          <h4>集群信息</h4>
          <el-button v-if="panel.hasAuth" type="primary" size="small" @click="openAppInfoDialog">
            修改集群信息
          </el-button>
        </div>
        <table class="app-detail-kv-table">
          <tbody>
            <tr>
              <td>集群编码</td>
              <td>{{ formatClusterNo(panel.appInfo.clusterNo, panel.appInfo.appId) }}</td>
              <td>集群名称</td>
              <td>{{ panel.appInfo.appName }}</td>
            </tr>
            <tr>
              <td>集群类型</td>
              <td>{{ panel.appInfo.typeDesc }}</td>
              <td>集群级别</td>
              <td>{{ panel.appInfo.importantLevelLabel }}</td>
            </tr>
            <tr>
              <td>报警用户</td>
              <td>{{ panel.appInfo.alertUsersText || "-" }}</td>
              <td>负责人</td>
              <td>{{ panel.appInfo.officerText || "-" }}</td>
            </tr>
            <tr>
              <td>内存空间</td>
              <td>{{ panel.appInfo.memTotalGb }}G</td>
              <td>分布机器数</td>
              <td>{{ panel.appInfo.machineNum }}</td>
            </tr>
            <tr>
              <td>主节点数</td>
              <td>{{ panel.appInfo.masterNum }}</td>
              <td>从节点数</td>
              <td>{{ panel.appInfo.slaveNum }}</td>
            </tr>
            <tr>
              <td>集群描述</td>
              <td colspan="3">
                {{ panel.appInfo.intro || "-" }}
              </td>
            </tr>
            <tr v-if="panel.appInfo.masterName">
              <td><span class="text-danger">哨兵masterName</span></td>
              <td>{{ panel.appInfo.masterName }}</td>
              <td><span class="text-danger">哨兵密码</span></td>
              <td>{{ panel.appInfo.hasSentinelPwd }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="app-detail-section">
        <div class="app-detail-section__header app-detail-section__header--alert">
          <h4>报警通知</h4>
          <el-button type="danger" size="small" @click="openAlertConfigDialog">
            通知设置
          </el-button>
        </div>
        <el-table :data="panel.alertMetrics" border stripe size="small">
          <el-table-column prop="alertKey" label="项" min-width="160" />
          <el-table-column prop="cycle" label="状态" width="120" />
        </el-table>
        <p class="app-detail-alert-hint">
          内存使用率、客户端连接数、平均命中率等报警阈值已统一收口到
          <router-link to="/instance-alert">
            报警配置
          </router-link>，可按全局或按集群设置。
        </p>
      </div>

      <div class="app-detail-section">
        <div class="app-detail-section__header app-detail-section__header--user">
          <h4>用户管理</h4>
          <el-button v-if="panel.hasAuth" type="success" size="small" @click="openAddUserDialog">
            添加用户
          </el-button>
        </div>
        <el-table :data="panel.users" border stripe size="small">
          <el-table-column prop="id" label="id" width="70" />
          <el-table-column prop="name" label="域账户" width="120" />
          <el-table-column prop="chName" label="中文名" width="120" />
          <el-table-column prop="email" label="邮箱" min-width="160" />
          <el-table-column prop="mobile" label="手机" width="120" />
          <el-table-column prop="company" label="部门" min-width="120" />
          <el-table-column label="是否报警" width="90">
            <template #default="{ row }">
              {{ row.alert ? "是" : "否" }}
            </template>
          </el-table-column>
          <el-table-column v-if="panel.hasAuth" label="操作" width="160">
            <template #default="{ row }">
              <div class="table-actions">
                <el-button type="primary" size="small" @click="openEditUserDialog(row)">
                  修改
                </el-button>
                <el-button type="danger" size="small" @click="handleDeleteUser(row)">
                  删除
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </template>

    <el-dialog v-model="appInfoVisible" title="集群信息修改" width="480px" append-to-body destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="集群名称" required>
          <el-input v-model="appInfoForm.appName" />
        </el-form-item>
        <el-form-item label="集群描述" required>
          <el-input v-model="appInfoForm.intro" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="项目负责人" required>
          <el-select v-model="appInfoForm.officerId" filterable placeholder="选择负责人" style="width: 100%">
            <el-option
              v-for="u in allUsers"
              :key="u.id"
              :label="userLabel(u)"
              :value="String(u.id)"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="appInfoVisible = false">
          Close
        </el-button>
        <el-button type="primary" :loading="saving" @click="submitAppInfo">
          Ok
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="alertConfigVisible" title="集群报警通知设置" width="520px" append-to-body destroy-on-close>
      <el-form label-width="160px">
        <el-form-item label="开启集群全局报警" required>
          <el-select v-model="alertForm.isAccessMonitor" style="width: 100%">
            <el-option :value="0" label="否" />
            <el-option :value="1" label="是" />
          </el-select>
          <div class="form-hint">
            是: 接收全局报警；否: 不接收
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="alertConfigVisible = false">
          Close
        </el-button>
        <el-button type="primary" :loading="saving" @click="submitAlertConfig">
          Ok
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="addUserVisible" title="添加用户" width="480px" append-to-body destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="用户名" required>
          <el-select
            v-model="addUserIds"
            multiple
            filterable
            placeholder="选择用户"
            style="width: 100%"
          >
            <el-option
              v-for="u in candidateUsers"
              :key="u.id"
              :label="userLabel(u)"
              :value="u.id!"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addUserVisible = false">
          Close
        </el-button>
        <el-button type="primary" :loading="saving" @click="submitAddUsers">
          Ok
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editUserVisible" title="管理用户" width="480px" append-to-body destroy-on-close>
      <el-form label-width="100px">
        <el-form-item label="域账户名" required>
          <el-input v-model="editUserForm.name" />
        </el-form-item>
        <el-form-item label="中文名" required>
          <el-input v-model="editUserForm.chName" />
        </el-form-item>
        <el-form-item label="邮箱">
          <el-input v-model="editUserForm.email" />
        </el-form-item>
        <el-form-item label="手机" required>
          <el-input v-model="editUserForm.mobile" />
        </el-form-item>
        <el-form-item label="部门" required>
          <el-input v-model="editUserForm.company" placeholder="一级部门/二级部门/三级部门" />
        </el-form-item>
        <el-form-item label="是否收报警" required>
          <el-select v-model="editUserForm.isAlert" style="width: 100%">
            <el-option :value="1" label="是" />
            <el-option :value="0" label="否" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editUserVisible = false">
          Close
        </el-button>
        <el-button type="primary" :loading="saving" @click="submitEditUser">
          Ok
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.text-danger {
  color: #d9534f;
}
.app-detail-alert-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.form-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
</style>

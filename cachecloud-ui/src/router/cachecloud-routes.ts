import type { RouteRecordRaw } from "vue-router"

const Layouts = () => import("@/layouts/index.vue")

/**
 * 侧边栏菜单按定义顺序渲染，因此可见路由的先后即菜单顺序：
 * 全局统计 → 集群管理 → 节点管理 → 数据迁移 → 运维工具 → 风险评估
 * → 报警配置 → 报警记录 → 用户管理 → 调度任务 → 审计日志。
 * hidden 路由（详情页、旧地址重定向）紧跟各自所属的可见菜单，不影响菜单顺序。
 */
export const cachecloudRoutes: RouteRecordRaw[] = [
  {
    path: "/",
    component: Layouts,
    redirect: "/total/statlist",
    meta: { hidden: true },
    children: [
      {
        path: "total/statlist",
        component: () => import("@/pages/cachecloud/dashboard/index.vue"),
        name: "TotalStatList",
        meta: { title: "全局统计", svgIcon: "dashboard", affix: true }
      },
      {
        path: "app/stat/server",
        redirect: "/total/statlist",
        meta: { hidden: true }
      },
      {
        path: "app/list",
        component: () => import("@/pages/cachecloud/app/list/index.vue"),
        name: "AppList",
        meta: { title: "集群管理", elIcon: "Grid" }
      },
      {
        path: "app/detail/:appId",
        component: () => import("@/pages/cachecloud/app/detail/index.vue"),
        name: "AppDetail",
        meta: { title: "集群运维", hidden: true, activeMenu: "/app/list" }
      },
      {
        path: "app/ops/:appId",
        redirect: (to) => {
          const tabMap: Record<string, string> = {
            topology: "app_ops_topology",
            fault: "app_ops_fault",
            // 集群密码修改已改为节点列表里的抽屉按钮，旧链接落到节点列表
            password: "app_topology",
            instance: "app_topology"
          }
          const rawTab = typeof to.query.tab === "string" ? to.query.tab : ""
          const tab = tabMap[rawTab] || rawTab || "app_ops_topology"
          return {
            path: `/app/detail/${to.params.appId}`,
            query: { ...to.query, tab }
          }
        },
        meta: { title: "集群运维", hidden: true, activeMenu: "/app/list" }
      },
      {
        path: "instance/ops",
        redirect: "/app/list",
        meta: { hidden: true }
      },
      {
        path: "external/redis/list",
        component: () => import("@/pages/cachecloud/external/redis/list/index.vue"),
        name: "ExternalRedisList",
        meta: { title: "节点管理", elIcon: "Monitor" }
      },
      {
        path: "external/redis/detail/:instanceId",
        component: () => import("@/pages/cachecloud/instance/detail/index.vue"),
        name: "NodeDetail",
        meta: { title: "节点详情", hidden: true, activeMenu: "/external/redis/list" }
      },
      {
        path: "external/redis/add",
        component: () => import("@/pages/cachecloud/external/redis/add/index.vue"),
        name: "ExternalRedisAdd",
        meta: { title: "纳管集群", hidden: true, activeMenu: "/app/list" }
      },
      {
        path: "external/redis",
        redirect: "/external/redis/list",
        meta: { hidden: true }
      },
      {
        path: "instance/detail/:instanceId",
        redirect: to => ({
          path: `/external/redis/detail/${String(to.params.instanceId)}`,
          query: to.query
        }),
        meta: { hidden: true }
      },
      {
        path: "migrate",
        component: () => import("@/pages/cachecloud/migrate/index.vue"),
        name: "DataMigrate",
        meta: { title: "数据迁移", elIcon: "Refresh" }
      },
      {
        path: "migrate/create",
        component: () => import("@/pages/cachecloud/migrate/create/index.vue"),
        name: "DataMigrateCreate",
        meta: { title: "添加新迁移", hidden: true, activeMenu: "/migrate" }
      },
      {
        path: "app/tool",
        component: () => import("@/pages/cachecloud/diagnostics/index.vue"),
        name: "AppTool",
        meta: { title: "运维工具", elIcon: "Tools" }
      },
      {
        path: "risk-assess/list",
        component: () => import("@/pages/cachecloud/risk-assess/list/index.vue"),
        name: "RiskAssessList",
        meta: { title: "风险评估", elIcon: "Warning" }
      },
      {
        path: "data-model",
        component: () => import("@/pages/cachecloud/data-model/index.vue"),
        name: "DataModel",
        meta: { title: "数据模型", elIcon: "DataAnalysis" }
      },
      {
        path: "instance-alert",
        component: () => import("@/pages/cachecloud/instance-alert/index.vue"),
        name: "InstanceAlert",
        meta: { title: "报警配置", elIcon: "Bell" }
      },
      {
        path: "alert-record",
        component: () => import("@/pages/cachecloud/alert-record/index.vue"),
        name: "AlertRecordList",
        meta: { title: "报警记录", elIcon: "BellFilled" }
      },
      {
        path: "user/list",
        component: () => import("@/pages/cachecloud/user/list/index.vue"),
        name: "UserList",
        meta: { title: "用户管理", elIcon: "User" }
      },
      {
        path: "quartz/list",
        component: () => import("@/pages/cachecloud/quartz/list/index.vue"),
        name: "QuartzList",
        meta: { title: "调度任务", elIcon: "Timer" }
      },
      {
        path: "audit/list",
        component: () => import("@/pages/cachecloud/audit/list/index.vue"),
        name: "OperationAuditList",
        meta: { title: "审计日志", elIcon: "Document" }
      }
    ]
  }
]

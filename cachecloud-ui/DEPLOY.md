# cachecloud-ui 部署说明（运维）

前端：Vue3 + Vite 静态站点  
后端：`cachecloud-web`（Spring Boot / Tomcat，默认 `8080`）

生产推荐：**构建 `dist` → Nginx 托管静态资源，并把 `/api/v1` 反代到后端**。不要在生产用 `npm run dev`。

---

## 1. 依赖

| 组件 | 要求 |
|------|------|
| Node.js | 20.19+ 或 22.12+（构建机） |
| pnpm | 10+（构建机） |
| Nginx | 托管 `dist` + 反代 API |
| cachecloud-web | 已部署并可访问（如 `127.0.0.1:8080`） |
| MySQL | 按 `cachecloud-web/sql/README.md` 完成 `init.sql` / `upgrade.sql` |

默认登录：`admin` / `admin%TGB7ygv`（`init.sql` 写入对应 MD5）。

---

## 2. 构建

在构建机或 CI 上执行：

```bash
cd cachecloud-ui
pnpm install
pnpm build
```

产物目录：`cachecloud-ui/dist/`

环境变量（已写入 `.env.production`，一般不用改）：

| 变量 | 生产值 | 说明 |
|------|--------|------|
| `VITE_BASE_URL` | `/api/v1` | 接口前缀（相对路径，走 Nginx 反代） |
| `VITE_PUBLIC_PATH` | `/` | 静态资源根路径；若挂子目录如 `/ui/`，构建前改成 `/ui/` |
| `VITE_ROUTER_HISTORY` | `hash`（见 `.env`） | hash 模式，Nginx 对 SPA 更省事 |

子路径部署示例：改 `.env.production` 中 `VITE_PUBLIC_PATH=/ui/` 后重新 `pnpm build`。

---

## 3. 发布静态文件

将 `dist/` 同步到服务器，例如：

```bash
rsync -avz --delete dist/ user@host:/app/tomcat/cachecloud-ui/dist/
```

路径可自定，需与 Nginx `root` 一致。仓库内示例配置：`scripts/nginx-cachecloud-ui.conf`（`root` 为 `/app/tomcat/cachecloud-ui/dist`）。

---

## 4. Nginx 配置（生产）

要点：

1. `root` 指向 `dist`
2. `location /` 使用 `try_files` 回退 `index.html`（SPA）
3. `/api/v1/` 反代到 `cachecloud-web`
4. 如仍依赖旧 JSP/静态路径，按需反代 `/manage/`、`/server/`、`/resources/`

示例（与 `scripts/nginx-cachecloud-ui.conf` 相同，按实际改 `listen` / `proxy_pass`）：

```nginx
server {
    listen 80;
    server_name _;

    root /app/tomcat/cachecloud-ui/dist;
    index index.html;

    location / {
        try_files $uri $uri/ /index.html;
    }

    location /api/v1/ {
        proxy_pass http://127.0.0.1:8080/api/v1/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header Cookie $http_cookie;
    }

    location /manage/ {
        proxy_pass http://127.0.0.1:8080/manage/;
        proxy_set_header Host $host;
        proxy_set_header Cookie $http_cookie;
    }

    location /server/ {
        proxy_pass http://127.0.0.1:8080/server/;
        proxy_set_header Host $host;
        proxy_set_header Cookie $http_cookie;
    }

    location /resources/ {
        proxy_pass http://127.0.0.1:8080/resources/;
        proxy_set_header Host $host;
        proxy_set_header Cookie $http_cookie;
    }
}
```

启用后检查：

```bash
nginx -t && nginx -s reload
curl -I http://127.0.0.1/          # 前端
curl -I http://127.0.0.1/api/v1/   # 应打到后端（非 404 静态）
```

浏览器访问 Nginx 地址（如 `http://<host>/`），登录 `admin` / `admin%TGB7ygv`。

---

## 5. 架构关系

```text
浏览器
  → Nginx :80
       ├─ /              → dist 静态资源（cachecloud-ui）
       └─ /api/v1/*      → cachecloud-web :8080
```

后端需单独部署（内嵌 Tomcat / 外置 Tomcat / `java -jar` 均可），与前端同机或分机；分机时改 Nginx `proxy_pass` 指向后端地址。

---

## 6. 仅开发/联调（勿用于生产）

```bash
# 后端
cd cachecloud-web && mvn spring-boot:run -Dspring-boot.run.profiles=local

# 前端（3333，Vite 代理到 8080）
cd cachecloud-ui && pnpm install && pnpm dev
```

访问：`http://localhost:3333`

仓库还有 `scripts/deploy-dev-to-44.sh`（远程起 Vite 开发服），**仅内网联调**，不要当生产方案。

---

## 7. 常见问题

| 现象 | 处理 |
|------|------|
| 打开页面空白 / JS 404 | `VITE_PUBLIC_PATH` 与实际访问路径不一致，改后重新构建 |
| 接口 404 / 跨域 | 检查 Nginx 是否反代 `/api/v1/`，生产不要把 `VITE_BASE_URL` 写成跨域绝对地址（除非后端开了 CORS） |
| 登录失败 | 查后端与库：`app_user` 中 `admin` 的 `password` 是否为 `admin`；后端是否在监听 |
| 刷新 404 | Nginx `try_files` 未配；或改用 hash 路由（当前默认 hash，一般无此问题） |

---

## 8. 清单（给运维勾选）

- [ ] Node/pnpm 构建出 `dist`
- [ ] `dist` 已发布到服务器目录
- [ ] Nginx `root` 指向该目录，`nginx -t` 通过
- [ ] `/api/v1` 反代到正确的 `cachecloud-web`
- [ ] 后端、MySQL 已就绪
- [ ] 浏览器可打开页面并用 `admin` / `admin%TGB7ygv` 登录

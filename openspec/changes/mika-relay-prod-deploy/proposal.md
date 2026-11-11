# 远程网关 mika.kfxh.work 生产部署

- 任务ID：mika-relay-prod-deploy
- 责任人：binny（决策与验收）；AI（部署执行与记录）
- 更新时间：2026-11-11

## Why

`tools/relay` 已具备自托管远程 CLI/MCP 网关能力，README 的「Existing Nginx server」路线面向已有 Nginx 的服务器。目标服务器 123.207.201.177 已运行宝塔 Nginx（80/443 已占用）、Docker 可用、`mika.kfxh.work` 站点与证书已存在（有效期至 2027-02-09），需要按该路线把网关接入既有 Nginx，而不改动既有站点配置结构。

## What Changes

- 服务器 `/opt/mika-relay` 放置源码副本与 `.env`（仅 `MIKA_DOMAIN=mika.kfxh.work`）。
- 使用 `compose.nginx.yaml`，Compose 项目名 `mika-relay`，容器仅发布 `127.0.0.1:18787`，数据卷 `mika-relay_relay_data`。
- 新增站点扩展配置 `/www/server/panel/vhost/nginx/extension/mika.kfxh.work/relay.conf`：复用全局 `$connection_upgrade` 映射，覆盖 `X-Forwarded-For $remote_addr`，关闭缓冲与重试，超时 90s。
- 新增文件只有上述两处；既不修改 `mika.kfxh.work.conf`，也不触碰其他站点。

不做（本期）：不启用 Caddy（`compose.yaml` / Caddyfile 路线）；不改动手机端配对与远程开关；不做网关之前的负载均衡；不提交 git。

## 方案要点

1. 静态资源兜底：站点既有 `location ~ .*\.(js|css)?$` 正则会把 `/app.js`、`/style.css`、`/console.css`、`/console.js` 指向 webroot，必须在扩展配置中用先出现的正则 location（`^/(app|style|console)\.(css|js)$`）把它们代理到网关（扩展 include 位于这些正则之前）。
2. 站点未改成 `^~ /`，避免影响模板中的 `.well-known` 正则 location；但实测发现该模板第 9 行 `try_files` 缺少分号，把 `.well-known` 的 include 吞成参数（既有的重定向循环报错，早于本次变更），需另行在宝塔面板修正。
3. owner key 只在数据卷内生成，不打印、不写入 webroot、不复制到本地仓库；初始化命令拒绝覆盖已有 key。
4. 主机路径 `/opt/mika-relay`、扩展配置、卷 `mika-relay_relay_data` 部署前均确认为不存在。

## 验收标准

- `nginx -t` 通过后才 reload；`https://mika.kfxh.work/healthz` 返回 `{"status":"ok"}`。
- 容器状态 running 且仅监听 `127.0.0.1:18787`；`/`、`/app`、`/style.css`、`/app.js` 由网关卡返回 200。
- 其他既有站点 HTTPS 状态码在部署前后无变化。
- 数据卷与 owner key 保留；失败时只回滚本次新增配置与容器。

## 阻塞/依赖

- 依赖 Docker Hub（镜像源 `https://docker.1ms.run`）构建 `golang:1.27.3-alpine`；构建失败则不启动容器。
- 服务器无法访问 `proxy.golang.org`，构建需 `--build-arg GOPROXY=https://goproxy.cn,direct`，并在服务器副本 Dockerfile 中提供该 ARG（仓库源码未改）。
- 依赖 `mika.kfxh.work` 已解析到本机（已确认）。
- 配对需要手机端真实访问 token，与 owner key 是不同凭据；不假设 LAN token 可直接用于远程认证。

## 影响评估

- 改动对象：仅 `mika.kfxh.work` 站点新增 location 代理与一个独立容器；不涉及数据库与持久化业务数据。
- 上下游：不影响其他虚拟主机；网关仅转发 `/mcp`、`/api/v1*` 与只读控制台路由。
- 性能/并发：单进程网关，请求体上限 1 MiB、并发 8；限流键取自 `$remote_addr`，不受客户端伪造 XFF 影响。
- 安全：容器 `read_only`、`cap_drop: ALL`、`no-new-privileges`，端口不对外暴露；TLS 由既有 Nginx 终结。
- 回归测试：reload 后抽查其他站点状态码。

## 回滚方案

触发条件：`nginx -t` 失败、容器反复重启、`/healthz` 不通、其他站点出现 5xx。

动作：删除新增的 `relay.conf` → `nginx -t` → reload → `docker compose -p mika-relay -f compose.nginx.yaml down`（保留 `mika-relay_relay_data` 卷）。owner key 与配对状态随卷保留，便于重试。

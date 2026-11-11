## 1. 前置核查

- [x] 1.1 确认仓库无 `TASKS.md`，既有任务文档位于 `openspec/changes/`；本次新建 `mika-relay-prod-deploy` 独立目录，未改动既有 change
- [x] 1.2 只读核查服务器：SSH root 免密、`nginx -t` 通过、`127.0.0.1:18787` 空闲、`/opt/mika-relay`、`relay.conf`、`mika-relay_relay_data` 卷均不存在
- [x] 1.3 确认证书 `CN=mika.kfxh.work`（2027-02-09 到期）且域名解析到 123.207.201.177；站点根目录为空，仅含 `.well-known`

## 2. 部署

- [x] 2.1 同步源码到 `/opt/mika-relay`（排除 `.env`、`owner-key`、`.git`）
- [x] 2.2 写入 `.env`（仅 `MIKA_DOMAIN=mika.kfxh.work`）并构建镜像；`go test ./...` 在构建阶段通过
- [x] 2.3 初始化 owner key（44 字节、600、属主 10001，未输出内容、未落 webroot），`up -d` 启动容器
- [x] 2.4 新增 `extension/mika.kfxh.work/relay.conf`；`nginx -t` 通过后两次 `nginx -s reload`

## 3. 验证与交付

- [x] 3.1 `/healthz` 200 `{"status":"ok"}`；`/`、`/app`、`/admin` 200 HTML；`/style.css`、`/app.js`、`/console.css`、`/console.js` 200；`/api/v1` 503 `device_offline`（未配对，符合预期）；`/pair`、`/language` GET 405（POST 路由）；容器 `Restarts=0`，仅监听 `127.0.0.1:18787`
- [x] 3.2 other sites 抽查：www/docs/ash5/rymp-api 200、asbt 404、notice 502（与 reload 前一致，属既有状态）
- [x] 3.3 遗留用户操作见下方「遗留」：owner key 已取、手机会话已配对、远程访问已开；经公网中继下发只读任务 `833b0837-4b3a-4323-b79a-5ab467d632f6` 并回收到结果（`status=done`，含电池/充电/亮屏/解锁四项读数），「提交 → 执行 → 结果回收」闭环验证通过

## 发现与偏离

- 构建阶段 `go mod download` 无法访问 `proxy.golang.org`。仅在服务器副本 `/opt/mika-relay/Dockerfile` 增加 `ARG GOPROXY` + `ENV GOPROXY`（默认值不变），并以 `--build-arg GOPROXY=https://goproxy.cn,direct` 构建；仓库源码未改动。
- 站点模板自带 `location ~ .*\.(js|css)?$` 会把静态资源指向 webroot，`/app.js` 首次 404 正是经 `error_page 404 /404.html` 转发到网关后返回的 JSON。扩展配置改为 `location ~ ^/(app|style|console)\.(css|js)$` 先于模板正则匹配并代理，四个静态资源均 200。
- 既有缺陷（非本次引入，日志 14:03:54 起即出现，早于本次变更）：`mika.kfxh.work.conf` 第 9 行 `try_files` 缺少分号，导致紧随其后的 `include .../well-known/mika.kfxh.work.conf;` 被吞为参数，出现 `rewrite or internal redirection cycle` 报错，且 `.well-known` 文件校验未被加载，存在**证书续期风险**。
- 上述缺陷已按用户确认修正：备份 `mika.kfxh.work.conf.bak-20261111-relaysemicolon`，仅补分号，`nginx -t` 通过后 reload；`/.well-known/acme-challenge/` 探测由 500 转 404（include 已生效），`/`、`/healthz`、`/app` 仍 200，`/api/v1` 仍 503，www/docs 站点 200，中继容器 Up。<br>注意：宝塔面板若重存该站点会按模板重写此文件，分号缺陷可能复现，届时需复查。

## 遗留

- 取 owner key（仅在数据卷内，不经过聊天/仓库）：`docker compose -p mika-relay -f compose.nginx.yaml cp relay:/data/owner-key ./owner-key && chmod 600 owner-key`
- 浏览器打开 `https://mika.kfxh.work/admin` 输入 owner key → **Create pairing code**（5 分钟、一次性）
- Mika：设置 → 连接电脑 → 远程，填 `https://mika.kfxh.work` 与配对码 → Pair gateway → 显式开启 **Allow remote access**
- 远程 CLI/MCP 使用手机端真实访问 token（README 明确它与 LAN 共用同一 token，不是 owner key），需 `--url` + `--token-file`

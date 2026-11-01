# 本机 Go 与 dsh 持续开发

```sh
npm run dev
npm run dev:status
npm run dev:stop
```

`dev` 在后台启动持续开发监督进程，等待 Go 和插件就绪后返回。再次执行不会重复启动。
默认使用本机 npm 安装的 `dsh`、Node 24、Go 及 `/usr/lib/postgresql/18/bin`；目前面向
Linux。可用 `REMOTE_DEV_GO`、`REMOTE_DEV_PG_BIN` 指定已安装工具，用
`python3 tools/dev/local.py start --profile <name>` 指定本机 dsh profile，默认 `web`。

| 入口 | 地址／位置 |
| --- | --- |
| Go HTTP／WebSocket | http://127.0.0.1:8080 |
| Go 就绪检查 | http://127.0.0.1:8080/readyz，成功为 204 |
| 本地测试 JWKS | http://127.0.0.1:8082/jwks.json |
| dsh Web | http://127.0.0.1:3000 |
| 插件本机 IPC | `.local/dev/ipc/probe.sock` |
| 开发状态与日志 | `.local/dev/status.json`、`supervisor.log`、`relay.log`、`go-build.log`、`typescript.log`、`dsh.log` |

dsh Web 仍使用自身浏览器授权机制；访问凭据只在私有 `dsh.log` 中，不写进文档、提交或状态
输出。监督进程、全部监听和 PostgreSQL 都限定在本机；数据库只开放私有 Unix socket，
不占用系统 PostgreSQL 的 5432 端口。所有运行数据放在当前用户拥有的 0700 `.local/dev`。

## 修改后如何生效

- `services/remote` 下非测试 Go 源码及 go.mod/go.sum 变化会触发 Go build。构建成功后才
  优雅关闭旧 relay、启动新程序；编译失败保留旧程序。已进入开发状态的 relay 若运行退出，
  dsh 和 watcher 继续运行，等待下一次 Go 源码修改重试。
- TypeScript watch 使用 `--noEmitOnError`，有类型错误时保留之前的输出。dsh 原生 HMR
  监听 `host/remote-control/lib`，批量输出稳定后替换插件；通常不重启整个 dsh。
  插件重载改变 runtime_epoch，旧审批与实时订阅不能跨代复用。
- SQL 新迁移按顺序应用，已应用名称及摘要保存在开发数据库中。修改已经应用的迁移会明确
  失败，保留数据库，不自动清空数据或假装重新应用。迁移失败请查看日志并显式处理。
- 停止会关闭当前监督进程拥有的 dsh、TypeScript watcher、Go relay、JWKS 和 PostgreSQL；
  保留数据库、插件日志和已安装的 profile 链接，以便下次继续。不会停止其它 dsh 或数据库。

插件通过正式 `dsh plugin --profile web add link:<仓库>/host/remote-control` 安装为本机
profile 依赖。开发启动额外传入 `.local/dev/dsh.patch.yml`，启用 HMR 和插件本机 IPC，
只允许本仓库工作区；不覆写原 cordis.patch.yml、账号凭据、profile bundles 或会话。
直接执行不带该 overlay 的 `dsh web` 不会启用这个开发入口。

## 身份与验证范围

学校 OIDC 尚未配置。当前常驻 Go 使用独立的本地 JWKS／issuer 配置，不提供学校登录或
测试 token HTTP 发放接口；只有公钥可从 JWKS 读取。本地 fixture 私钥只保存在私有开发
目录。常驻插件当前使用本机 IPC，尚未登记并连接这个常驻 Relay；status 明确保留
`relay_plugin_connected: false`。健康检查不代表完成绑定或远程鉴权。

本机 dsh 已为 `0.2.1-rc.5`。仓库开发依赖、插件 peer 基线、Host 上报版本及 Host bundle
验证器一起更新到该版本；未使用安装器的版本风险豁免。早期文档中的 rc.2 测试记录保留
其历史版本，不作为 rc.5 或未运行平台的验收证据。

本次证据：

- `.local/dev/rc5-verify.log`：协议检查、类型检查、构建及 Host 495 项全部通过，包含实际本机
  npm dsh rc.5 的 CLI 回归。
- `.local/dev/rc5-native-composition.log`：真实 Go 入口、隔离 PostgreSQL、npm dsh rc.5 联合
  用例通过，覆盖签名登记／凭据、会话、消息与快照、问题、撤权。身份和模型均为本地夹具，
  该临时联合环境与上面的常驻服务分别运行。
- `.local/dev/reload-proof.json`：实际修改再恢复 TypeScript 和 Go 源码，插件 epoch 两次更新、
  dsh PID 不变，Go PID 两次更新且 readyz 恢复；另验证重复启动、停止后重启，以及 relay
  退出后保留 dsh、通过源码变更恢复。源码摘要记录在证明文件中。
- `.local/dev/ipc-smoke-proof.json`：当前常驻插件实际返回 session.list.result，未发送模型请求。

Android APP、模拟器／真机、大负载和厂商推送配置未参与此开发环境启动。真实学校账号、
平台安全存储、生产部署及各平台验收仍需相应证据。

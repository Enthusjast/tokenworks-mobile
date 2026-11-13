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

项目标识已切换，新运行状态为 `.local/tokenworks-mobile-dev`；旧 `.local/dev` 和旧 HOME 私密身份保持原样，不自动复用。首次使用新状态需重新登记，见[切换说明](project-identity-cutover.md)。

| 入口 | 地址／位置 |
| --- | --- |
| Go HTTP／WebSocket | http://127.0.0.1:8080 |
| Go 就绪检查 | http://127.0.0.1:8080/readyz，成功为 204 |
| 本地测试 JWKS | http://127.0.0.1:8082/jwks.json |
| dsh Web | http://127.0.0.1:3000 |
| 插件本机 IPC | `.local/tokenworks-mobile-dev/ipc/probe.sock` |
| 开发状态与日志 | `.local/tokenworks-mobile-dev/status.json`、`supervisor.log`、`relay.log`、`go-build.log`、`typescript.log`、`dsh.log` |

dsh Web 仍使用自身浏览器授权机制；访问凭据只在私有 `dsh.log` 中，不写进文档、提交或状态
输出。监督进程、全部监听和 PostgreSQL 都限定在本机；数据库只开放私有 Unix socket，
不占用系统 PostgreSQL 的 5432 端口。所有运行数据放在当前用户拥有的 0700 `.local/tokenworks-mobile-dev`。

## 修改后如何生效

- `services/remote` 下非测试 Go 源码及 go.mod/go.sum 变化会触发 Go build。构建成功后才
  优雅关闭旧 relay、启动新程序；编译失败保留旧程序。已进入开发状态的 relay 若运行退出，
  dsh 和 watcher 继续运行，等待下一次 Go 源码修改重试。
- TypeScript watch 使用 `--noEmitOnError`，有类型错误时保留之前的输出。dsh 原生 HMR
  监听 `host/remote-control/lib`，批量输出稳定后替换插件；通常不重启整个 dsh。
  插件重载改变 runtime_epoch，旧审批与实时订阅不能跨代复用。
- SQL 新迁移按顺序应用，已应用名称及摘要保存在开发数据库中。修改已经应用的迁移会明确
  失败，保留数据库，不自动清空数据或假装重新应用。迁移失败请查看日志并显式处理。
- 停止会关闭当前监督进程拥有的 dsh、TypeScript watcher、Go relay、JWKS、可选 TLS gateway 和 PostgreSQL；
  保留数据库、私密身份／绑定和插件日志，以便下次继续。不会停止其它 dsh 或数据库。

开发启动额外传入 `.local/tokenworks-mobile-dev/dsh.patch.yml`，从绝对路径加载本仓库开发 adapter，启用 HMR 和
插件本机 IPC，只允许本仓库工作区。不写入用户 profile 的依赖、原 cordis.patch.yml、账号凭据、
bundles 或会话。直接执行不带该 overlay 的 `dsh web` 不会启用这个开发入口。

## 身份与验证范围

学校 OIDC 尚未配置。默认模式使用独立的本地 HTTP JWKS／issuer，不提供浏览器登录或
token HTTP 发放接口。Host 使用明确标记的开发账号及私密 installation key，经过正常 REST
enrollment、双方确认、签名 Host credential 和 WebSocket 协议连接真实 Go Relay。
`relay_plugin_connected` 只有在本机 IPC 的 runtime epoch 与数据库的活跃 dispatcher route、
connection 和 node lease 都匹配时才为 true；单独 readyz 成功不代表 Host 在线。

私钥、受保护 binding/recovery 和 command journal 保存在当前用户的
`~/.local/state/tokenworks-mobile-development/<仓库绝对路径 SHA-256 前16位>/`（0700 目录、0600 文件）。
这是明确的 file-backed 开发保护器，不是生产平台安全存储；不放宽正常 Host 文件保护检查，
也不修改用户 repo/profile 目录权限。HTTP 与 Android 模式分别保留 installation key、binding
和 journal；切换模式前必须先 stop，不能让已运行的另一模式被静默复用。

## Android／手机显式启用

```sh
python3 tools/dev/local.py stop
python3 tools/dev/local.py start --android --profile web
# --mobile 与 --android 等价；省略该参数保留原 HTTP 开发模式。
python3 tools/dev/local.py status
```

该模式增加仅监听 `127.0.0.1:8443` 的 `https://localhost:8443` TLS gateway，HTTP 与 WebSocket
均代理实际 `127.0.0.1:8080` Go 服务，不是 fake relay。Go 的 issuer/public origin 同步改为
`https://localhost:8443/issuer`／`https://localhost:8443`，JWKS 与 Host 都通过受验证的 TLS 使用
开发 CA；Node 使用 `NODE_EXTRA_CA_CERTS`，Go 使用 `SSL_CERT_FILE`，不关闭证书验证。
证书 SAN 包含 localhost 和 127.0.0.1，CA／server 私钥只留在上述私密 state 的 `android/`。

已运行 dsh 可使用 `start --mobile --profile web --attach-pid <PID> --dsh-url http://127.0.0.1:3080`。
附加进程不能靠修改环境变量加载新 CA；仅开发入口会将这个受保护的本地 CA 显式加入当前 Node 的默认信任，系统根证书仍保留，不采用 trust-all。模式切换须先卸载旧开发 entry 释放 IPC，见[附加指南](dsh-desktop-plugin.md)。

启动时生成的两个公开配置文件是：

- `.local/tokenworks-mobile-dev/android/ca.pem`：供 debug Android app 和测试设备浏览器信任的开发 CA，不是私钥。
- `.local/tokenworks-mobile-dev/android/deployment.json`：正常严格部署 schema，客户端 `local-dev-phone`、resource／audience
  `urn:tokenworks-mobile:local-dev`，签名算法 ES256。

Android debug 构建（生产校验不变）使用绝对路径：

```sh
cd android
./gradlew :app:assembleDebug \
  -PdeploymentConfig="$PWD/../.local/tokenworks-mobile-dev/android/deployment.json" \
  -PlocalDevCa="$PWD/../.local/tokenworks-mobile-dev/android/ca.pem"
```

设备端需要将 `tcp:8443` reverse 到运行 supervisor 的主机；WSL 与 Windows adb 所在主机不同时，
还需父级的本机桥接。浏览器不读取 app 的 debug CA：应在**开发测试设备**的系统用户证书存储安装
该公开 CA，再从 app 正常登录入口打开浏览器。不要用 trust-all、HTTP 例外或忽略证书错误代替信任。
安装和设备网络命令由实际设备操作者执行，本 supervisor 不构建、安装或操作 Android。

Discovery 位于 `/issuer/.well-known/openid-configuration`，有明确的 consent 页：
**Local development identity only / NOT USTC or school login**。必须点击
**Sign in as local development phone**；不会索取学校密码。注册客户端仅 `local-dev-phone` 与
`local-dev-host`；phone 仅允许 `cn.edu.ustc.tokenworks:/oauth2redirect`，Host 仅允许本 issuer 的
`/host-callback`。授权码单次使用、120 秒有效，绑定 client、redirect、resource、OIDC nonce、
PKCE S256 和 DPoP key；token endpoint 验证签名 proof、nonce 与 replay。ID/access token 为真实
ES256 签名，access token 绑定实际手机 DPoP key、15 分钟有效，不提供 refresh token，过期后重新登录。
本地 assurance 是 `fixture/local-development`／`amr: fixture`，不冒充 OTP 或学校强认证。

组织固定为 `3ed32392-2684-448d-83e7-d8b5b977035b`；Host／runtime／workspace ID 来自正常
enrollment，并持久化于 `.local/tokenworks-mobile-dev/enrolled-runtime.json`，也会在 Android 模式 status 中公开。
账号 `local-dev-fixture-phone` 的 membership 和 runtime grant 只覆盖这个 runtime 与 `project`
workspace：目录、正文／标题元数据、模型选择、review changes、fork、新建／发送／取消／归档、
question、approval 和 grant 管理。Android 的系统文本分享使用正常已授权 session.read，不新增
虚构的 `session.share` action。没有 file.upload/download grant；blob／scanner／文件导出服务未配置，
不能将文件上传、下载或附件功能声称为已可用。监督进程不会发送模型请求，也不改用户模型配置。

Host 在线检查：status 必须同时显示 `supervisor_alive: true`、`relay_ready: true`、非空
`plugin_epoch`、`relay_plugin_connected: true`。`runtime_id` 必须与 enrolled-runtime 一致；
查故障看私密 `gateway.log`、`relay.log`、`dsh.log`、`supervisor.log`，不把登录 code/token 或 dsh
浏览器凭据复制到公开报告。尚未操作设备／登录／聊天不能作为端到端验收通过。

### Pixel_10 交互式联调记录

已在 Windows SDK 的既有 `Pixel_10`（API 37，`emulator-5554`）完成正常浏览器 PKCE／DPoP 登录、虚拟指纹逐次签名登记、当前权限目录、创建选项与真实会话创建／读取。设备信任的是显式安装的 `DSH LOCAL DEVELOPMENT ONLY CA`，不是跳过证书验证；debug APK 的 CA 仅对 localhost／127.0.0.1 生效。默认 debug 和 release 均构建成功；带 `localDevCa` 的 release APK 仍不包含本地 CA 或 debug 网络配置。

```sh
/mnt/c/Users/CJX/AppData/Local/Android/Sdk/platform-tools/adb.exe \
  -s emulator-5554 reverse tcp:8443 tcp:8443
```

本地 consent 页使用 `Referrer-Policy: same-origin`，保留严格 Origin 与 cookie 检查，并在 CSP 的 `form-action` 中允许已登记的 native callback scheme。开发 HTTP 桥接明确关闭普通请求的连接复用并移除逐跳头，避免 ADB reverse 空闲连接的 EOF；WebSocket 仍保持全双工，不给生产客户端增加请求重放或自动重试。启动仅回收确认拒绝连接且归当前用户所有的旧 IPC socket；活动 socket、非 socket 与替换后的 inode 均不删除。

只向手机显式提供已核对、已启用的 `standard` 原生助手配置，不替换用户模型或 profile。创建请求 `bc3eca1f-cbe5-4f07-8d28-990099af966a` 仅结算一次；手机会话 `2e9e0a02-1334-5a62-b6ce-54396dbc6f0a` 对应真实 native session，并已核对持久化检查点 through-seq `2`。实际验证了后台超过 5 秒后恢复同一会话且仍为实时连接。没有发送模型请求；这不是模型生成、文件服务或真实学校认证验收。无额外 commit，文档仍留在工作区。

公开验证结果保存于 `.local/dev/android/pixel10-smoke.json`；不含 PIN、私钥、code、token 或 dsh 浏览器凭据。临时 Java 调试器与额外 adb forward 已移除，仅保留用户调试所需的 `tcp:8443` reverse。开发登录有效期 15 分钟，过期后用“重新登录此账号”复用现有设备登记。

本机 dsh 已为 `0.2.1-rc.5`。仓库开发依赖、插件 peer 基线、Host 上报版本及 Host bundle
验证器一起更新到该版本；未使用安装器的版本风险豁免。早期文档中的 rc.2 测试记录保留
其历史版本，不作为 rc.5 或未运行平台的验收证据。

以下是较早的独立验证记录，不是上述 Android 模式或当前工作树的验证结论：

- `.local/dev/rc5-verify.log`：协议检查、类型检查、构建及 Host 495 项全部通过，包含实际本机
  npm dsh rc.5 的 CLI 回归。
- `.local/dev/rc5-native-composition.log`：真实 Go 入口、隔离 PostgreSQL、npm dsh rc.5 联合
  用例通过，覆盖签名登记／凭据、会话、消息与快照、问题、撤权。身份和模型均为本地夹具，
  该临时联合环境与上面的常驻服务分别运行。
- `.local/dev/reload-proof.json`：实际修改再恢复 TypeScript 和 Go 源码，插件 epoch 两次更新、
  dsh PID 不变，Go PID 两次更新且 readyz 恢复；另验证重复启动、停止后重启，以及 relay
  退出后保留 dsh、通过源码变更恢复。源码摘要记录在证明文件中。
- `.local/dev/ipc-smoke-proof.json`：当前常驻插件实际返回 session.list.result，未发送模型请求。

默认模式不涉及 Android APP、模拟器／真机、大负载或厂商推送；Android opt-in 也不会自动
完成这些验证。真实学校账号、平台安全存储、生产部署及各平台验收仍需相应证据。

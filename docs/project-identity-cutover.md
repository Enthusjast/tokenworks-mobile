# tokenworks-mobile 标识切换

产品名统一为 **TokenWorks Mobile**，仓库项目名为 `tokenworks-mobile`。这次切换包含包名、应用 ID、签名用途、传输协议、加密域和存储容器，不是兼容性别名更新。

## 新标识

| 部分 | 标识 |
| --- | --- |
| npm workspace | `tokenworks-mobile` |
| Host 包 | `tokenworks-mobile` |
| Cordis 插件 / 桌面状态路径 | `tokenworks-mobile` / `/tokenworks-mobile/status` |
| Go module | `tokenworks-mobile/services/remote` |
| Android applicationId / APP 源码包 | `cn.edu.ustc.tokenworks` |
| core 模块包根 | `io.github.enthusjast.tokenworks` |
| Android 回调 | `cn.edu.ustc.tokenworks:/oauth2redirect` |
| WSS 子协议 | `tokenworks-mobile.remote.v1` |
| 签名 domain / audience | `tokenworks-mobile/...` / `urn:tokenworks-mobile:...` |
| 网页 local issuer | `urn:tokenworks-mobile:local-account:v1` |
| 开发状态 / HOME 私密状态 | `.local/tokenworks-mobile-dev` / `~/.local/state/tokenworks-mobile-development/<checkout-hash>` |

上游 dsh CLI、`@deepseek-ai/*`、`DSH_HOME`、`dsh_version`、`dsh_session_id` 和既有功能配置 `REMOTE_*` 不更名。真实仓库目录不随项目标识切换移动。

## 客户端与本机数据

新 Android applicationId 是独立应用，不能作为旧包原地升级。旧回调 scheme、旧设备/Host 登记、审批签名、浏览器 Cookie、旧加密缓存/日志和旧包 manifest 不由新程序兼容读取。客户端重新安装并登记，Host 重新绑定和确认，再显式授予所需权限。

旧状态目录、日志、备份、CA 和历史包原样保留，不删除、不搜索回退、不自动导入新身份。显式配置旧容器时会拒绝其格式或密码学校验；不能以复制旧文件或改 magic 的方式“迁移”权限。

本机持续开发从新私有数据库和新身份启动，原有 profile 的模型与原生会话不因项目改名迁移。已有 dsh 附加模式须移除旧项目 entry，按新包名链接同一检出，再使用新入口；不要同时加载新旧插件，也不要恢复旧 profile 备份覆盖后来编辑。

## 已有数据库离线升级

SQL 001–054 不回写；新增 055 的格式 guard 只允许新归档写入。已有用户/Host/runtime/本地账号的数据库必须先处于 `quarantined`。空身份数据库可以直接安装全部迁移。

1. 关闭公网入口和旧节点/worker，断开 Host，等待短期授权过期。保存旧固定版本二进制、数据库备份、归档及其独立可信摘要。
2. 在数据库备份之外生成新 epoch 和稳定 recovery UUID；用旧二进制执行已有恢复隔离命令：

   ```sh
   export REMOTE_DATABASE_EPOCH='<新 epoch UUID>'
   # REMOTE_DATABASE_URL 从受控配置注入，不写进命令参数或日志。
   /受控旧版本/remote recovery quarantine \
     --recovery-id '<本次 recovery UUID>' \
     --expected-epoch '<原数据库 epoch>' \
     --epoch "$REMOTE_DATABASE_EPOCH"
   ```

3. 应用 `055_tokenworks_mobile_identity.up.sql`，部署新服务/Host/客户端以及新的 authority、pin、加密 key 和公开部署配置。不要重写历史审批、JWS、归档 payload 或摘要。
4. 按[恢复隔离指南](database-recovery.md)从当前可信来源采集隔离之后的完整身份清单，使用同一 recovery UUID 释放：

   ```sh
   export REMOTE_ISSUER='<当前可信 issuer>'
   /受控新版本/remote recovery release \
     --recovery-id '<同一 recovery UUID>' \
     --authority-file /受控私有目录/current-authority.json
   ```

5. 设备和 Host 重新登记、确认、授权后再开放业务。操作结果不确定时重试原 recovery ID/参数，不能用新 ID 掩盖未知结果。

隔离保留历史记录并撤销旧 authority。旧本地账号保留旧 issuer、暂停状态和唯一用户名，不自动变成新身份；新账号使用未占用用户名。回退 055 仅恢复格式 guard，不能恢复旧会话、撤权时间、grants 或旧 epoch。

旧归档/旧签名只是历史留存，新实时验证器不会为它们增加旧值 fallback。需要核查时使用隔离保存且版本/摘要固定的旧工具，不把它接回新的授权入口。

## 互操作夹具

当前协议夹具必须重新签名生成，不能替换 compact JWS 字符串。显式依次执行：

```sh
npm run contracts:signatures
(cd android && ./gradlew -PjvmOnly=true :core:crypto:generateInteropVectors)
REMOTE_TEST_GO=/opt/go1.26.10/bin/go ./node_modules/.bin/tsx tools/generate-approval-leases.ts
npm run contracts
npm run contracts:identity-vectors
```

生成器只持有临时私钥或已公开的测试专用 fixture 密钥，不读取生产身份。随机签名重生成不作为 `contracts:check` 的稳定校验步骤。

[历史验证与包收据](verification-history.md)保留原项目名、版本、签名、摘要和路径，不能当作此次新名称已经验收的记录。当前学校/SSO/Firebase 登记与目标设备验收没有因改名自动完成。

## 本次验证范围

- 重新生成 42 个 Node/Go 审批场景、4 个 Kotlin/JCA 场景和 58 个授权租约场景，三端校验使用新身份。
- TypeScript 契约检查、类型检查与构建通过；Host 定向行为、发行校验器、服务 runner 守卫和证书夹具通过。真实 Secret Service 用例因环境缺少工具跳过，不构成生产密钥库验收。
- Go auth/approval/accounts/coldarchive/auditarchive/evidencearchive 定向 race 检查，以及真实私有 PostgreSQL 中的 055 隔离升级用例通过；发行选择用例也已通过。
- JVM 核心检查通过。按最终要求将 APP 包名修订为 `cn.edu.ustc.tokenworks` 后，auth/network 检查、debug APK、测试 APK 编译与 lint 通过；直接检查 APK 的 applicationId、回调、Provider 和 DEX 包名，未安装到设备。
- 一次真实双节点 Go/已安装 npm dsh `0.2.1-rc.5` 组合通过新签名、握手、会话、审批和撤权路径，并拒绝旧子协议；不是学校/生产依赖验收。
- 新隔离开发状态已启动；HTTP/IPC 检查新状态接口成功、旧路径 404，Host 源码修改与恢复均产生新 epoch，dsh PID 不变。按最新开发要求，后续不再启动浏览器测试。

本机记录位于 `.local/tokenworks-mobile-rename-evidence`，不随源码分发；旧 `.local/dev` 和旧 HOME 身份不动。没有运行 Windows、设备、负载或厂商推送测试。

# 当前开发状态与验证入口

[文档导航](README.md) · [项目首页](../README.md) · [历史验证与收据](verification-history.md)

文档整理日期：2026-11-05；最近功能提交为 `eaaf00f`（账号网页），之前为 `5a533d4`（dsh 接入与状态 UI）。
本页汇总当前入口与未完成的出口，不是一份本次重新执行的产品测试报告。
旧源码基线、运行快照和测试数量集中保留在[历史验证记录](verification-history.md)，不随本页日期更新。

## 基线与运行入口

| 项目 | 当前依据 |
| --- | --- |
| npm DSH | 根 package/lockfile 和 Host peers 锁定 `0.2.1-rc.5`；使用本机已安装的 dsh |
| Node | 工程要求 `>=24.15.0 <25`；既有记录使用 24.20.0 |
| Go / PostgreSQL | Go 模块为 1.26.0，既有本机验证使用 Go 1.26.10 / PostgreSQL 18；迁移按编号执行至 054 |
| 本机持续开发 | [监督进程](local-development.md)启动私有 PostgreSQL、Go、TypeScript watch 和 dsh HMR |
| 已有 dsh 接入 | [附加模式与状态 UI](dsh-desktop-plugin.md)保留原 dsh 进程，只管理自身后端和 watcher |
| 账号网页 | [用户名密码账号](relay-accounts.md)，默认开发入口 `/account/`；可单独运行而不配置 IdP |
| 身份边界 | 网页 SSO 未启用；原远控 API 的资源 JWT、DPoP、设备证明和 runtime grant 保持独立 |
| 开发节奏 | 完成相关改动后集中运行定向验证；全量与长时间场景按需要另行安排 |

实时运行状态使用 `npm run dev:status` 查询。PID、热重载次数、端口占用和就绪检查是运行时事实，
不从历史日志或本页推断。当前工作区还可能包含未提交的 TLS/手机开发扩展，不能视为干净检出的交付内容。
文件存储、scanner、真实推送与学校身份是否启用，分别检查对应配置，不以进程在线推断业务已验收。

## 已有能力与实现边界

| 范围 | 实现与证据入口 | 边界 |
| --- | --- | --- |
| 本地账号与网页 | [账号指南](relay-accounts.md)，Go `internal/accounts` | 注册、登录、账号/主机概览、改密、退出；新账号没有默认组织或远控权限，Cookie 不授权 `/v1` |
| 原生会话与模型 | [会话读取](session-read-protocol.md)、[创建](session-create-protocol.md)、[归档](session-archive.md)、[模型目录](session-model-catalog.md)、[选择](session-model-selection.md) | Host 复用同进程 Agent；不能将 npm CLI/Loader 组合当成企业桌面既有会话验收 |
| 审批与用户提问 | [原生签名审批](approval-native.md)、[授权租约](approval-leases.md)、[普通问题](question-protocol.md)、[定时提问](timed-question-protocol.md) | 签名决定、原生策略和实际执行边界分别检查；问题回答不授予工具执行权限 |
| 身份、设备与绑定 | [资源认证](m1-authentication.md)、[目录同步](identity-synchronization.md)、[绑定](host-binding-protocol.md)、[已登记启动](host-enrolled-runtime.md) | 有本地身份/私有数据库组合证据；真实学校客户端、目录来源及发行端账号适配未验收 |
| 命令持久性与恢复 | [日志事务](host-journal-transitions.md)、[容量](host-journal-capacity.md)、[I/O 故障](host-journal-io-faults.md) | `host_accepted`、`agent_queued` 和执行完成不同；结果未知时不自动重放副作用 |
| 跨节点与撤权 | [共享 DPoP](shared-dpop-state.md)、[路由](relay-routing.md)、[控制事件](control-fanout.md)、[撤权](revocation-propagation.md) | 多进程及双节点夹具不代表目标集群容量、公网 HA 或撤权 SLO 已验收 |
| 文件与附件 | [文件服务](file-rest-service.md)、[账本](file-transfer-ledger.md)、[Host 交接](file-host-handoffs.md)、[导出](file-export-relay.md) | 有 PG/TLS/Loader、scanner 进程和 CLI 分层证据；真实 S3/KMS、病毒引擎和物理设备仍需验收 |
| 通知 | [FCM 服务](push-service.md)、[OAuth 生命周期](push-oauth-lifecycle.md)、[发送与展示指标](push-observability.md) | 本地 TLS/OAuth/FCM 夹具不证明 Google 接受或设备显示；厂商通道未配置 |
| 应用发行策略 | [发行登记](app-release-registry.md)、[共享策略](app-release-policy.md)、[恢复确认](app-release-recovery.md) | 服务端策略不是 APK 实际签名、分发或目标设备升级验收 |
| 密钥、审计与恢复 | [平台密钥索引](README.md#keys)、[审计归档](audit-archive.md)、[恢复隔离](database-recovery.md)、[PITR 夹具](postgres-pitr-fixture.md) | 已有本地机制和演练不构成生产密钥托管、备份设施或 RPO/RTO 达标 |
| 指标与运行保障 | [指标](operational-metrics.md)、[规则](monitoring-rules.md)、[数据库中断](database-outage-drill.md)、[容量入口](authenticated-capacity.md) | 指标存在及小型故障样本不等于可用性、目标负载或分批放量通过 |

Android 工程及历史专题统一从[Android 导航](README.md#android)查阅，本次不修改或重新验证。
PLAN.md 中 M0–M4 的复选框没有因为已有代码、夹具或本文整理被视为完成。

<a id="verification"></a>

## 按改动范围选择验证

以下用于选择入口，不要求每轮逐项执行。文档整理只检查文档结构和引用，不触发产品全量回归。

| 改动范围 | 入口与注意事项 |
| --- | --- |
| Host 类型/编译 | `npm run check`、`npm run build` |
| 契约 | `npm run contracts:check`，再选择对应 TS/Go 用例 |
| Host 单个行为 | `node --import tsx --test host/remote-control/tests/<目标文件>.test.ts` |
| Go 组件 | 在 `services/remote` 执行 `go test -race -count=1 -run '<目标用例>' ./internal/<目标包>`；数据库场景使用隔离测试库 |
| 账号与网页 | [账号验证说明](relay-accounts.md#验证入口)，目标包为 `./internal/accounts` 及相关命令入口；网页另做真实交互 |
| 文件生命周期组合 | `python3 tools/integration/run_remote_dsh.py --file-lifecycle`；[精确范围](../tools/integration/FILE_LIFECYCLE.md) |
| 已安装 CLI 的文件适配 | 显式设置绝对路径 `REMOTE_TEST_DSH_BIN`，运行 `dsh-cli-files.integration.test.ts`；命令见[专项说明](../tools/integration/FILE_LIFECYCLE.md) |
| 文件依赖配置/scanner | `remote files check --config /absolute/path/to/files.json`；[入口与范围](file-dependency-check.md) |
| 通用 Go/DSH 组合 | `--dsh-only` 跳过独立 Go 全量，但 Node 组合仍覆盖多条链路；`--cross-node` 另启双节点范围 |
| 指定发行包 | [发行包组合入口](packaged-service-composition.md)，核对独立可信摘要及各包源码 commit |

`npm run verify` 是完整 Host/契约入口；不带模式的 `run_remote_dsh.py` 会运行 Go 全量和通用 Node 组合。
`--go-run` 只过滤用例，仍遍历 `./...`，不是单包入口。续期、故障和容量模式按专用指南单独选择。

## 仍开放的完整验收出口

- 学校 issuer/Discovery、client/resource/audience/scope、DPoP、刷新/登出及当前组织目录来源仍未就绪；网页账号不补足学校 SSO。
- TokenWorks/EduWork 发行装配、既有桌面会话和其它终端应答器共存没有相应验收；按当前要求不尝试该组合。
- Android APP、Windows/Wine、真机、大负载及厂商推送配置按当前要求跳过；旧记录不能代替目标平台与版本的验收。
- 生产 S3/KMS、扫描器策略与签名库、FCM、OS 密钥服务、备份/连续日志及可信配置分发仍需实际环境和运维验证。
- 内部试用、目标规模/SLO、分批放量和生产回退未执行；本机演练及指标不能替代。

完整离线历史、独立常驻 Host、端到端加密和 iOS/Web 完整远控客户端属于后续增强；
本地账号网页只提供账号和主机元数据，不表示已经实现 Web 聊天或工具审批客户端。

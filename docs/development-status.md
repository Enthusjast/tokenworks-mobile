# 当前开发状态与验证入口

核对日期：2026-11-02。源码核对基线为 `0fd53d7`，后续文档提交不改变这次代码验证范围。
本页汇总当前入口、已有证据和仍开放的阶段出口；早期文档中的日期、版本及测试数量保留为历史记录。
代码存在、构建通过、组件用例通过和实际发行验收分别记录，不能相互替代。

## 当前基线和工作范围

| 项目 | 当前依据 |
| --- | --- |
| npm DSH | `0.2.1-rc.5`；根 `package.json`、lockfile、Host peers、CLI 测试和 Host bundle 工具均锁定此版本 |
| 实际 CLI | 使用本机 npm 安装的 `dsh`；集成时通过绝对路径 `REMOTE_TEST_DSH_BIN` 选择并在启动前校验版本 |
| Node / Go / PostgreSQL | 本地已用 Node 24.20.0、Go 1.26.10、PostgreSQL 18；工程 Node 范围为 `>=24.15.0 <25` |
| Host / 服务 | 当前 DSH 进程内 TypeScript 插件、Go `services/remote`；不另起会话 writer |
| 身份、模型、对象与 scanner | 本地测试夹具；学校 issuer/client/resource 配置尚未就绪 |
| 本轮开发约束 | 不尝试 TokenWorks，不修改 Android APP，不运行 Windows/Wine、真机、大负载或厂商通道配置 |
| 开发节奏 | 完成一批相关改动后做针对性验证；全量验证和较长演练单独按需要运行 |

常驻 Go、TypeScript watch 和已安装 dsh HMR 使用[持续开发入口](local-development.md)。
本次核对时 supervisor、Relay readiness、插件路由连接正常，Go 热重载次数为 19。
这是一条本地运行观察，不是生产可用性或长期稳定性测量。
当前启动配置还包含未提交的本地 TLS/登录扩展；本页没有修改、提交或重新启动该配置。
开发环境尚未启用文件存储/scanner，也没有启用真实推送 provider；定向文件与推送测试使用自己的夹具。

## 服务端和插件交付映射

| 计划要求 | 代码入口与已有证据 | 阶段状态及边界 |
| --- | --- | --- |
| M0：进程内会话、queue/steer、中断、模型、历史和流 | Host `dsh-client.ts`、`runtime.ts`、`session-sync.ts`；[Go/DSH 组合记录](go-dsh-integration.md)、[发行包组合](packaged-service-composition.md) | 有临时 CLI/profile 和真实 Loader 证据；不证明现有企业桌面 profile 的原有会话已接入 |
| M0：审批、普通与定时提问、终端归属及最终 guard | `signed-approvals.ts`、`interaction-router.ts`、`timed-questions.ts`；[原生审批](approval-native.md)、[timed question](timed-question-adapter.md) | 有真实工具调用与本地身份/模型证据；TokenWorks/微信组合及企业核心出口未验收 |
| M0：卸载、账号结束、重连和资源所有权 | `index.ts`、`relay-client.ts`；[Host 生命周期](host-relay-lifecycle.md)、[已登记入口](host-enrolled-runtime.md) | 有夹具账号取消、插件清理及原生 Agent 保留证据；真实桌面窗口、应用与学校登出仍需发行集成 |
| M1：JWT/JWKS、DPoP、设备、安装、绑定、组织及 grant | Go `internal/auth`、`internal/store`；[身份基础](m1-authentication.md)、[身份同步](identity-synchronization.md)、[绑定](host-binding-protocol.md) | 代码和私有 PG/HTTP/DSH 组合已有；真实学校配置、目录来源及客户端注册未验收 |
| M1：host_accepted/agent_queued、去重、未知结果和故障 | Host `command-journal.ts`、`session-message-actions.ts`，Go `relay_commands.go`；[磁盘故障](host-journal-io-faults.md)、[日志容量](host-journal-capacity.md) | 有持久记录、单次执行和故障夹具证据；不会自动重放未知副作用，生产磁盘与恢复仍需配置 |
| M1/M4：共享 proof、双节点路由、租约和可靠撤权 | Go `proof_state.go`、`relay_directory.go`、control consumer；[共享 DPoP](shared-dpop-state.md)、[路由](relay-routing.md)、[控制事件](control-fanout.md) | 有多进程/重启及双节点组合证据；不代表目标集群容量和公网 HA 已验收 |
| M2 的 Host/Go 配套：目录、创建、归档、问题与多 scope | Host/Go 对应协议处理器；[目录](session-directory.md)、[创建](session-create-protocol.md)、[归档](session-archive.md)、[问题](question-protocol.md) | 配套接口已有；本轮不修改或补验 Android，M2 的真机、完整前台与试用出口保持开放 |
| M3：上传、扫描、receipt、消息引用、导出与撤权 | Go `internal/files`，Host `file-registry.ts`、`export-catalog.ts`；[文件账本](file-transfer-ledger.md)、[交接](file-host-handoffs.md)、[导出](file-export-relay.md) | 有 PG/TLS/Loader/生产进程及安装 CLI 的不同层级证据；真实 S3/KMS、病毒引擎和物理设备未验收 |
| M3：FCM、交互引用、发送结果与客户端回执 | Go `internal/push`、`store/push*.go`；[推送服务](push-service.md)、[结果指标](push-observability.md)、[OAuth 生命周期](push-oauth-lifecycle.md) | 有本地 TLS OAuth/FCM 和持久队列证据；未连接真实 Google，厂商通道按要求跳过 |
| M3/M4：APK 目录、不可变身份、共享 publication 策略和恢复确认 | Go `internal/releases`、`store/release*.go`；[发行身份](app-release-registry.md)、[共享策略](app-release-policy.md)、[恢复确认](app-release-recovery.md) | 服务端能力和夹具证据已有；不等于 APK 实际发布、签名分发或目标设备安装验收 |
| M4：审计、归档、恢复、密钥和发行来源 | [审计归档](audit-archive.md)、[恢复隔离](database-recovery.md)、[Linux 密钥](linux-installation-key.md)、[Host 包](host-bundle.md)、[Go 包](go-service-bundle.md) | 有应用机制、受控工具和本地演练；生产备份/连续日志、OS 密钥服务、证书与独立可信配置仍需部署验收 |
| M4：监控、故障、容量、SLO 和放量 | [指标](operational-metrics.md)、[规则](monitoring-rules.md)、[故障演练](database-outage-drill.md)、[容量入口](authenticated-capacity.md) | 已有指标、规则和小型故障证据；本轮不运行目标负载、配置通知接收方或执行试点/放量 |

源码入口用于定位实现，链接记录用于核对各自测试范围。表中“已有证据”不是当前 HEAD 的全量回归声明。
`PLAN.md` 中 M0–M4 的复选框没有因为这些代码或夹具结果被自动视为完成。

## 最近批次的可复核范围

以下日志仅保存在本机 `.local`，不会随源码提交；commit 和测试入口用于重现对应范围。

| 源码 commit | 已完成验证 | 本机证据 |
| --- | --- | --- |
| `cc78574` | 三个文件组合主用例及五个交接子场景，包含 PG/TLS、真实 npm Loader、跨节点与生产 scanner worker | `.local/file-lifecycle-evidence/composition.log` |
| `8362f3e` | 12 个 Host 文件用例，包含已安装 dsh 的 receipt/附件/工作区导出、同名类/子类拒绝、provider 替换；类型检查与构建 | `.local/cli-files-evidence/focused-files.log` |
| `cdaef91` | 18 个 Go 主用例和 27 个子用例；实际 `remote files check` 分派、scanner 失败/超时、配置与健康代次 | `.local/file-check-evidence/focused.log` |
| `0fd53d7` | 8 个主用例和 2 个子用例；OAuth 取消、共享失败、失效与迟到响应，既有 FCM 发送与受保护配置 | `.local/push-auth-evidence/focused.log` |

`db58f6f` 仅纠正文件进程测试注释：`/readyz` 已包含配置 scanner 的健康检查，额外等待用于核查成功 probe 的指标发布。

较早的[Go/Host 包组合](packaged-service-composition.md)使用 dsh `0.2.1-rc.5`，但 Go 包源为
`cfd06c0`，Host 包源为 `d4b94c2`。本次按原 receipt 的固定清单摘要重新离线校验，两包仍通过，
分别为 104/239 个文件。它们未包含最近几批修复，不能当作当前代码的发行产物。
上述旧包检查仅离线核对，未重建或发布。交付当前代码需要在明确的干净 commit 上重新构建、校验并按相关范围验证。

随后已在干净检出 `9fa43c4` 构建包含近期修复的 Go/Host 包，并集中通过包双节点组合及两个文件专项。
对应来源、摘要和范围收据见[包验证记录](package-verification-9fa43c4.md)。该后续记录没有补跑 Go 全量或声明生产验收。

Linux [真实 Secret Service 专项](linux-native-secret-service.md)随后使用同一 Host 包，通过安装签名、
日志保护、collection 锁定拒绝、密码解锁和正常 daemon 重启后的重开；该结果仍属于隔离密钥库，不是用户生产会话验收。

## 按改动范围选择验证

先完成相关改动，再集中运行适用入口。下面是选择说明，不是要求每轮逐项执行。

| 改动范围 | 对应入口 |
| --- | --- |
| Host 类型/编译 | `npm run check`、`npm run build` |
| 契约 | `npm run contracts:check` 及对应 TS/Go 用例 |
| 文件交接/导出/生产扫描进程 | `python3 tools/integration/run_remote_dsh.py --file-lifecycle`；范围见[专项说明](../tools/integration/FILE_LIFECYCLE.md) |
| 已安装 CLI 文件适配 | 构建后显式设置 `REMOTE_TEST_DSH_BIN`，只运行 `dsh-cli-files.integration.test.ts`；[命令与边界](../tools/integration/FILE_LIFECYCLE.md) |
| Go 文件依赖配置或 scanner | `remote files check --config /absolute/path/to/files.json`；[命令边界](file-dependency-check.md) |
| Go 组件改动 | `go test -race -run '<目标用例>' ./internal/<目标包>`，需要数据库的用例另准备私有 PG 夹具 |
| 通用 Go/DSH 组合 | `--dsh-only` 可跳过独立 Go 全量测试；可配合 `--cross-node`，但 Node 组合本身仍覆盖多条业务链路 |
| 指定包组合 | [发行包组合入口](packaged-service-composition.md)，核对独立可信摘要及各包源码 commit |

未指定模式的 `run_remote_dsh.py` 会运行 Go 全量及通用 Node 组合；`--go-run` 虽过滤用例，
仍遍历 `./...`，不是单包入口。较长的换证、故障和容量模式分别按其专用文档选择，不随普通修复自动执行。
`npm run verify` 是完整 Host/契约回归入口，按实际需要安排。

## 仍开放的完整验收出口

- 学校 OIDC 的公开 issuer/Discovery、客户端、资源/audience/scope、DPoP、刷新/登出和当前组织目录来源尚未就绪；继续夹具开发，不静默放宽认证。
- TokenWorks/EduWork 发行装配、现有桌面会话和其他终端应答器共存没有本轮证据；按要求不尝试该组合。
- Android APP、真实设备、Windows/Wine、厂商推送和大负载按当前指令跳过；已有历史记录不能补足目标平台与版本的验收。
- 生产对象存储/KMS、扫描器策略与签名库、FCM、OS 密钥服务、加密备份/连续日志及可信配置分发仍需实际环境与运维验证。
- M2/M3 试用、目标规模/SLO 测量、分批放量和生产回退没有执行；本地指标与小型故障演练不作为这些出口的通过依据。

完整离线历史同步、独立常驻 Host、端到端加密和 iOS/Web 属于计划的后续增强，不因当前预留接口而宣称实现。

# 文档导航

[项目首页](../README.md) · [当前状态](development-status.md) · [验证记录索引](verification-history.md)

按主题查找文档；文件保留原路径，避免破坏已有引用。这里索引已提交的专题及公开收据，
不将本机 `.local/` 运行文件、未提交设计稿或 PLAN.md 作为可移交的文档依赖。

## 从哪里开始

| 目标 | 阅读顺序 |
| --- | --- |
| 首次运行 | [项目首页](../README.md) → [本机持续开发](local-development.md) → [账号与网页](relay-accounts.md) |
| 接入已经运行的 dsh | [现有 Web profile 接入与 UI](dsh-desktop-plugin.md) → [Host 登记启动](host-enrolled-runtime.md) |
| 理解项目完成程度 | [当前能力与开放出口](development-status.md) → [对应版本的验证记录](verification-history.md) |
| 开发服务端 API | [Go 服务入口](../services/remote/README.md) → [OpenAPI](../contracts/openapi/remote.yaml) → 下方身份、Relay 或业务专题 |
| 开发 Host 插件 | [Host 入口](../host/remote-control/README.md) → 下方登记、会话、审批及日志专题 |
| 部署或恢复 | [受保护配置](trusted-service-configuration.md) → [Go 发行包](go-service-bundle.md) → [数据库恢复隔离](database-recovery.md) |
| 检查 Android 历史实现 | [Android 工程说明](../android/README.md) → [Android 专题](#android)；本轮不修改或补验 Android |

## 文档层级与维护约定

- **操作指南**说明如何使用，**协议与实现专题**说明边界，**历史证据**只证明所记录的版本、环境和范围。
- 当前依赖和运行入口以仓库配置、[当前状态](development-status.md)及各操作指南为准；不要把早期 rc.2 记录改成 rc.5 证据。
- 组件 README 保留较长的增量实现说明；遇到早期“尚未实现”描述，先核对对应专题和当前状态。
- 新功能更新对应专题与本索引；新的验证记录注明源码 commit、工具版本、外部夹具及未验证项。
- 动态 PID、端口占用和就绪状态通过 `npm run dev:status` 查询，不把某次运行快照写成长期保证。
- `.local/` 中的日志、备份和密钥不提交；公开 JSON 收据与原验证记录保留来源，不能充当当前 HEAD 的发行产物。
- 仓库内链接使用相对路径。Android 参考研究中的仓库外路径只适用于原作者环境，不属于移交依赖。

## 主题目录

- [入门与开发](#start)
- [账号、身份与权限](#identity)
- [Host 登记与连接](#host)
- [会话、模型与用户提问](#sessions)
- [平台密钥与本机保护](#keys)
- [审计、归档与恢复](#recovery)
- [审批签名、授权与结果](#approvals)
- [文件中转、附件与受限导出](#files)
- [通知与推送](#push)
- [中继路由、配额与事务准入](#relay)
- [指标、故障演练与容量](#operations)
- [Host 命令日志与持久性](#journal)
- [发行构建与 APK 策略](#release)
- [历史验证与包收据](#evidence)
- [Android 专题参考](#android)

<a id="start"></a>

## 入门与开发

本地启动、账号页面及已运行的 dsh 接入；运行命令的范围见相应指南。

- [当前开发状态与验证入口](development-status.md)
- [本机 Go 与 dsh 持续开发](local-development.md)
- [在已有 dsh Web 中开发远控插件](dsh-desktop-plugin.md)
- [常驻开发 Relay 的身份夹具](local-relay-fixtures.md)

<a id="identity"></a>

## 账号、身份与权限

- [中转账号与网页入口](relay-accounts.md)
- [资源 JWT、DPoP 与身份映射](m1-authentication.md)
- [审批所需的认证强度](authentication-assurance.md)
- [JWKS 缓存与轮换](jwks-cache.md)
- [跨进程 DPoP nonce 与 replay 状态](shared-dpop-state.md)
- [Go 服务的受保护配置输入](trusted-service-configuration.md)
- [学校身份与组织映射同步](identity-synchronization.md)
- [本人设备目录与遗失设备撤销](device-directory.md)
- [设备登记状态对账](device-enrollment-recovery.md)
- [主机安装发现与撤销](installation-management.md)
- [安装下的 profile 管理](installation-profile-management.md)
- [Runtime 授权目录](runtime-grant-directory.md)
- [Runtime 授权写入与手机撤销](runtime-grant-writes.md)
- [主机绑定核对与激活](host-binding-protocol.md)
- [主机登记的组织管理员审批](enrollment-admin-approval.md)
- [绑定确认的配对期限](enrollment-expiry.md)
- [主机与 runtime 分页目录](resource-directories.md)

<a id="host"></a>

## Host 登记与连接

- [主机绑定客户端](host-enrollment-client.md)
- [Linux 终端主机绑定确认](host-local-enrollment.md)
- [Host 终态对账与本地收尾](host-enrollment-terminal.md)
- [Linux 主机登记恢复存储](host-enrollment-recovery.md)
- [主机绑定定位信息持久化](host-enrollment-binding.md)
- [已登记主机的插件启动](host-enrolled-runtime.md)
- [Host 资源 JWT 本地验证](host-resource-jwt.md)
- [Host 资源身份到连接凭据的 REST provider](host-credential-provider.md)
- [Host 挑战与凭据签发的期限检查](host-credential-expiry.md)
- [主机凭据续期的定向验证](host-credential-renewal.md)
- [Host 连接队列、断线与原命令对账](host-relay-lifecycle.md)

<a id="sessions"></a>

## 会话、模型与用户提问

- [已知会话的最小授权](known-session-access.md)
- [普通问题与计划确认协议](question-protocol.md)
- [会话归档](session-archive.md)
- [远程中断协议](session-cancel-protocol.md)
- [远程创建会话协议](session-create-protocol.md)
- [有界会话目录](session-directory.md)
- [会话授权下的模型目录](session-model-catalog.md)
- [远程会话模型选择](session-model-selection.md)
- [会话快照、历史与实时读取](session-read-protocol.md)
- [原生定时提问适配](timed-question-adapter.md)
- [Relay 定时提问协议](timed-question-protocol.md)

<a id="keys"></a>

## 平台密钥与本机保护

- [主机日志密钥的可插拔封装](host-journal-key-protection.md)
- [Linux 安装密钥生成与重开](linux-installation-key.md)
- [Linux Secret Service 安装密钥签名器](linux-secret-service-installation-signer.md)
- [Linux Secret Service 日志密钥封装器](linux-secret-service-journal-key.md)
- [Linux 原生 Secret Service：锁定与重开](linux-native-secret-service.md)
- [macOS Keychain 安装密钥签名适配](macos-keychain-installation-signer.md)
- [macOS Keychain 日志密钥命令适配](macos-keychain-journal-key.md)
- [Windows 当前用户 DPAPI 原生协议基础](windows-dpapi-primitive.md)
- [Windows DPAPI helper 安装文件自检](windows-helper-file-guard.md)
- [Windows DPAPI 摘要固定启动器](windows-pinned-launcher.md)

<a id="recovery"></a>

## 审计、归档与恢复

- [审计日志的数据库归档](audit-archive.md)
- [审计历史的加密文件导出](audit-cold-export.md)
- [审批签名证据的事务内保留](approval-evidence.md)
- [审批证据的加密文件导出](approval-evidence-export.md)
- [审批证据材料的保留延期](approval-evidence-retention.md)
- [控制事件归档与恢复代次](control-archive.md)
- [控制事件历史的加密导出](control-cold-export.md)
- [审计和审批证明的历史导入](cold-history-import.md)
- [命令元数据保留与归档](command-archive.md)
- [数据库恢复后的隔离与授权重建](database-recovery.md)
- [加密基础备份、WAL 与指定恢复点夹具](postgres-pitr-fixture.md)

<a id="approvals"></a>

## 审批签名、授权与结果

- [审批登记与决定的提交前期限检查](approval-admission-expiry.md)
- [审批 authority 描述与轮换](approval-authority-rotation.md)
- [审批的事务控制](approval-control.md)
- [审批过期的后台终结](approval-expiry-worker.md)
- [HTTP 审批状态核对](approval-http-status.md)
- [一次性审批日志](approval-journal.md)
- [签名授权租约与本机期限](approval-leases.md)
- [原生审批归属与最终执行检查](approval-native.md)
- [审批状态、执行结果与租约指标](approval-observability.md)
- [Host 结果持久补报](approval-outbox.md)
- [签名审批服务端协议](approval-protocol.md)
- [审批详情与执行结果](approval-results.md)
- [审批决定签名格式](approval-signatures.md)

<a id="files"></a>

## 文件中转、附件与受限导出

- [导出下载的结束结果](export-download-settlement.md)
- [文件删除领取、回执与配额证据](file-cleanup-proof.md)
- [文件服务依赖检查](file-dependency-check.md)
- [受限来源经 Relay 导出](file-export-relay.md)
- [导出登记、扫描与手机鉴权下载](file-export-service.md)
- [受限导出的 Host 来源层](file-export-sources.md)
- [文件交接许可的提交前期限检查](file-handoff-admission-expiry.md)
- [扫描对象到 Host 会话的交接](file-host-handoffs.md)
- [Host 消息附件转换与恢复](file-message-native.md)
- [Relay 消息附件授权与消费](file-message-relay.md)
- [文件扫描与交接指标](file-observability.md)
- [上传、导出与下载准入的期限边界](file-rest-admission-expiry.md)
- [文件 REST 与后台服务](file-rest-service.md)
- [文件扫描租约与发布的提交前检查](file-scan-admission-expiry.md)
- [文件隔离存储与扫描准入](file-storage-admission.md)
- [文件登记、扫描租约与清理账本](file-transfer-ledger.md)
- [Go Host 文件流的独立取消](go-host-file-cancellation.md)
- [Host 导出源的关闭等待与容量](host-export-cleanup.md)
- [Host 导出目录游标的并发与关闭](host-export-cursors.md)
- [Host 导出操作的期限与关闭](host-export-operation-leases.md)
- [Host 文件交接的期限约束](host-file-handoff-leases.md)
- [Host 暂存 receipt 的关闭与竞争](host-file-registry-lifecycle.md)
- [原生文件准入](native-file-admission.md)
- [扫描健康状态与任务发布](scanner-health-generations.md)

定向组合入口：[文件生命周期验证说明](../tools/integration/FILE_LIFECYCLE.md)。

<a id="push"></a>

## 通知与推送

- [通知登记与 FCM 后端](push-service.md)
- [FCM 凭据刷新与任务取消](push-oauth-lifecycle.md)
- [推送发送结果与客户端展示回执](push-observability.md)
- [普通与定时提问的后台引用](question-notifications.md)

<a id="relay"></a>

## 中继路由、配额与事务准入

- [Relay 连接目录与代次](relay-directory.md)
- [节点间内容与请求路由](relay-routing.md)
- [Relay 关闭连接与回执隔离](relay-connection-closing.md)
- [Relay 控制通知优先队列](relay-control-priority.md)
- [Relay 节点发送字节预算](relay-node-send-budgets.md)
- [控制事件的逐节点消费](control-fanout.md)
- [活跃连接撤权传播](revocation-propagation.md)
- [Ticket 准入的实时期限检查](realtime-ticket-expiry.md)
- [共享连接配额](shared-connection-limits.md)
- [连接配额策略的离线变更](connection-policy-operations.md)
- [共享命令准入预算](shared-command-limits.md)
- [命令限流策略的离线变更](command-limit-policy-operations.md)
- [命令准入的 grant 实时期限](command-admission-expiry.md)
- [并发准入的完整事务重试](serializable-admission.md)
- [批量租约的精确连接查询](lease-validation-capacity.md)

<a id="operations"></a>

## 指标、故障演练与容量

- [Go 服务运行指标](operational-metrics.md)
- [私有指标采集与规则包](monitoring-rules.md)
- [HTTP 请求关联](http-request-correlation.md)
- [命令状态与存储指标](command-observability.md)
- [当前未知结果积压快照](unknown-results-metrics.md)
- [Host 原生操作与 checkpoint 观测](host-native-observations.md)
- [Host 发布、消费与投影计数](host-flow-observations.md)
- [Host 本地日志事务观测](host-journal-observations.md)
- [小型数据库中断与恢复演练](database-outage-drill.md)
- [原生 DSH 的 Relay 崩溃与恢复演练](native-relay-crash-drill.md)
- [节点间 mTLS 叶证书和私钥续期演练](relay-certificate-renewal-drill.md)
- [认证 TLS / WSS 容量夹具](authenticated-capacity.md)

<a id="journal"></a>

## Host 命令日志与持久性

- [Host 日志主库容量与 SQLite FULL](host-journal-capacity.md)
- [Host journal 的 WAL 写入与同步故障](host-journal-io-faults.md)
- [Host 日志状态转换的事务边界](host-journal-transitions.md)

<a id="release"></a>

## 发行构建与 APK 策略

- [Host npm 包、输入清单与离线校验](host-bundle.md)
- [Linux Go 服务发行包](go-service-bundle.md)
- [Go 服务离线版本信息](go-service-version.md)
- [APK 发行元数据与灰度选择](app-release-metadata.md)
- [APK 发行身份持久登记](app-release-registry.md)
- [跨节点发行策略核对](app-release-policy.md)
- [恢复后重新核对发行策略](app-release-recovery.md)

<a id="evidence"></a>

## 历史验证与包收据

先读[验证记录索引](verification-history.md)，再选择与源码及包版本相符的证据。历史记录不代表当前全量验收。

- [早期 M0 兼容与验证记录](m0-compatibility.md)
- [早期 Go／DSH 组合证据](go-dsh-integration.md)
- [使用 Go／Host 发行包进行联合验证](packaged-service-composition.md)
- [Go／Host 本地包验证：9fa43c4](package-verification-9fa43c4.md)
- [Host 包验证：1a40092](host-package-verification-1a40092.md)
- [Host 包验证：365b025](host-package-verification-365b025.md)

公开收据：

- [host-package-verification-1a40092.json](host-package-verification-1a40092.json)
- [host-package-verification-365b025.json](host-package-verification-365b025.json)
- [package-verification-9fa43c4.json](package-verification-9fa43c4.json)
- [postgres-pitr-verification.json](postgres-pitr-verification.json)

<a id="android"></a>

## Android 专题参考

以下仅提供导航，保留原有实现及验收边界；未提交的 UI/UX 设计稿不纳入本索引。

- [Android 账号恢复与刷新](android-account-recovery.md)
- [APK 安装会话与持久记录](android-apk-installation.md)
- [APK 私有下载与安装前校验](android-apk-preflight.md)
- [Android 应用更新页面](android-app-updates.md)
- [完整应用升级夹具构建](android-app-upgrade-fixtures.md)
- [Android 签名审批客户端](android-approvals.md)
- [Android 登录与设备登记](android-authentication.md)
- [Android 命令投递与加密 outbox](android-command-delivery.md)
- [加密数据库首次打开的并发约束](android-database-setup.md)
- [APK、Go 与 DSH 设备组合](android-device-composition.md)
- [Android 文件协议、传输与附件命令](android-files.md)
- [Android 主机绑定](android-host-binding.md)
- [Android Keystore 验证证据](android-keystore.md)
- [Android 通知与当前状态核对](android-notifications.md)
- [Android 用户提问与计划回答](android-questions.md)
- [Android 前台会话读取](android-realtime.md)
- [真实自升级的两阶段验收](android-self-upgrade-acceptance.md)
- [Android 前台会话创建](android-session-creation.md)
- [Android 加密历史缓存](android-transcript-cache.md)
- [Android 任务导航](android-ui-navigation.md)
- [Android APK 更新：API 与签名信任依据](android-updates-research.md)

## 协议、配置与源码入口

| 位置 | 用途 |
| --- | --- |
| [contracts/openapi](../contracts/openapi/) | REST API 契约 |
| [contracts/ws/v1](../contracts/ws/v1/) | Relay 消息 Schema |
| [contracts/m0](../contracts/m0/) | 本机调试协议；不能当作公网 v1 协议 |
| [contracts/security](../contracts/security/) | 签名与安全互操作样例 |
| [contracts/files](../contracts/files/)、[contracts/push](../contracts/push/) | 文件、推送共享契约 |
| [deploy/sql](../deploy/sql/) | 按编号配对的数据库迁移 |
| [deploy/files](../deploy/files/)、[deploy/monitoring](../deploy/monitoring/) | 文件服务配置示例和监控规则 |
| [tools/dev](../tools/dev/) | 本机开发监督、profile 接入和夹具 |
| [tools/integration](../tools/integration/)、[tools/release](../tools/release/) | 定向联调、发行构建和离线校验工具 |

核对范围：本次只整理文档与引用，不重新运行产品测试、不变更业务实现，也不据此补齐阶段验收。

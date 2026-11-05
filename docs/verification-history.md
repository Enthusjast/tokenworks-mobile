# 历史验证记录与发行收据

[文档导航](README.md) · [当前状态](development-status.md)

此页按来源定位已记录的验证，不声明当前 HEAD 已通过全量测试或阶段验收。
旧版本、测试数量、源码 commit、包摘要和未验证边界保留原含义；重新编写导航不会更新证据。

## 最近功能的验证入口

| 源码提交 | 功能与已有记录 | 范围 |
| --- | --- | --- |
| `eaaf00f` | [中转账号与网页](relay-accounts.md#验证入口) | 用户名密码、真实 PostgreSQL、浏览器流程与不带 IdP 的服务启动；没有实现网页 SSO |
| `5a533d4` | [已有 dsh 接入及状态 UI](dsh-desktop-plugin.md) | 在运行的 Web profile 内加载、HMR、owner-only 状态接口；不是企业桌面发行组合 |
| `19f3936` / 源 `365b025` | [审批时钟修复的包内验证](host-package-verification-365b025.md) | 原生正文边界与缓存期限定向验证；没有重新运行 CLI 或双节点组合 |
| `0fc8af8` | [加密 PostgreSQL WAL/PITR 夹具](postgres-pitr-fixture.md) | 指定恢复点及权限围栏；不是生产备份设施或 RPO/RTO 测量 |

账号与桌面 UI 的当次运行日志分别位于本机 `.local/accounts-evidence` 和 `.local/dsh-attach-evidence`。
这些路径不是源码随附附件；不要向外部读者承诺它们可以下载或仍存在。

## 固定版本的公开记录

| 记录 | 固定来源及用途 |
| --- | --- |
| [M0 兼容记录](m0-compatibility.md) | 早期 rc.2 原型接口与 npm Loader 证据，不证明当前企业发行装配 |
| [Go／DSH 联合记录](go-dsh-integration.md) | 按各节记录的版本与夹具组合；不要将 rc.2 结果改成 rc.5 结果 |
| [发行包联合入口](packaged-service-composition.md) | 复核已校验 Go／Host 包的方法，以及旧版组合边界 |
| [Go／Host 源 9fa43c4](package-verification-9fa43c4.md) · [JSON 收据](package-verification-9fa43c4.json) | 一对固定来源的包及其双节点／文件验证范围 |
| [Host 源 1a40092](host-package-verification-1a40092.md) · [JSON 收据](host-package-verification-1a40092.json) | 日志事务、审批额度与 Secret Service writer 的包内验证 |
| [Host 源 365b025](host-package-verification-365b025.md) · [JSON 收据](host-package-verification-365b025.json) | 包含单调时钟修复的 Host 包；不包含之后的桌面 UI 功能 |
| [PostgreSQL PITR 夹具](postgres-pitr-fixture.md) · [JSON 收据](postgres-pitr-verification.json) | 临时测试集群的物理恢复，不是可用于生产恢复的备份 |

## 如何解释下方记录

以下段落从 2026-11-02 的状态页归档；“本次”“随后”等措辞指当时的核对与开发批次。
本轮文档整理未重复运行这些场景、重建发行包或重新核验私有日志。
源码后续变化、可读的清单或构建成功，都不能把旧包变成当前代码的验收产物。

## 2026-11-02 状态页归档

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

后续源码还修复了[日志状态转换的跨进程事务边界](host-journal-transitions.md)，防止等待写锁前读取的旧状态
覆盖另一进程已经提交的终态与结果。此修复不在 `9fa43c4` 包内，交付时须选择包含修复的新构建。

[审批日志](approval-journal.md)的准入额度也已修复：超时/关闭的调用仍占用槽，直到未结束的验证或授权任务真正完成，
防止反复超时绕过 64 项工作上限；调用方仍及时返回。此后续修复同样不在旧包内。

[安装密钥写入](linux-installation-key.md)随后补齐每 writer 实例八个存活子进程的预算，取消后仍待实际退出再释放。
该源码修复也需通过后续新构建交付，不包含在 `9fa43c4` 包内。

上述三项 Host 修复现已进入源 `1a40092` 的[新 Host 包](host-package-verification-1a40092.md)，
包内定向回归、安装 CLI 文件与原生密钥库验证通过。该批未重跑 Go 全量或通用双节点组合，旧包记录保持原范围。

[PostgreSQL 加密 WAL/PITR 专项](postgres-pitr-fixture.md)又补齐具名恢复点的本地物理恢复证据，
核对旧权限行回退后的代次阻断和幂等隔离/释放；它不构成生产备份设施或 RPO/RTO 通过。

审批授权缓存随后加入[单调时钟约束](approval-leases.md)，防止墙钟停滞或未被观察到的回拨拉长签名窗口。
该修复已进入源 `365b025` 的[Host 新包](host-package-verification-365b025.md)：
旧包复现墙钟暂停/事件循环阻塞后的过期执行，新包在正文边界拒绝，42 项包内定向结果通过。
这批没有重跑 CLI、双节点组合或全量测试。

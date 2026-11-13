# TokenWorks / DSH 移动远程控制平台 · 详细开发方案

| 项目 | 内容 |
| --- | --- |
| 文档版本 | v1.2 |
| 日期 | 2026-10-23 |
| 状态 | 已按源码修订；待完成 M0 真实发行版接入验证 |
| 最终规模 | 5000 台主机 / 5000 部手机；客户端先做 Android，公网部署 |
| 运行时基线 | DSH 0.2.1-rc.2（`687f282`）；TokenWorks 0.2.103；EduWork 0.4.7 本校核心（`eb2d643`） |
| 身份方案 | 复用 TokenWorks 现有学校 SSO/OIDC（oidc-proxy）；远控设备凭据、远控权限与模型凭据分别管理 |
| 读者 | 架构、后端、Android、TokenWorks 插件、SRE、安全、QA |

本文描述计划实现的系统。源码已经支持的能力见 0.3；本文提出的移动协议、远控插件、服务端和 APK 均不代表已经实现或通过实机验收。

## 0. 决策基线与接入依据

### 0.1 已确定

| 编号 | 决策 | 内容 |
| --- | --- | --- |
| D1 | 规模 | 最终支持 5000 台主机、5000 部手机，按试点数据逐级扩容 |
| D2 | 客户端 | 先做 Android APK，保留后续其他客户端接入空间 |
| D3 | 完整交付范围 | 聊天、审批、用户提问、文件、多主机、多会话、多设备授权 |
| D4 | 网络 | 企业自建公网中继；手机和主机均主动发起出站连接 |
| D5 | 审计 | 身份、授权、审批和文件操作可追溯；日志默认不记录正文和完整参数 |
| D6 | 用户身份 | 复用 TokenWorks 现有学校 SSO/OIDC（oidc-proxy）作为用户身份源；电脑插件复用现有登录状态，手机和中转服务接入同一身份体系，不新建 SSO 或用户密码体系 |
| D7 | 主机接入 | 插件加载进当前 TokenWorks 运行进程，复用该进程的会话和模型服务 |
| D8 | 首期进程模型 | 连接、协议转换和本地可靠性模块位于插件内部；不部署独立 Host Bridge 服务 |
| D9 | 历史依据 | DSH 本机会话日志负责历史；服务端首期保存元数据，手机保留授权期间的本地缓存 |
| D10 | 审批 | 指定高风险调用必须取得手机端有效的单次批准；远程端不能放宽全局权限 |
| D11 | 实施顺序 | 先验证真实 TokenWorks 插件接入，再建设身份、传输和 Android 功能 |

### 0.2 必须在相应阶段前解决的事项

| 编号 | 事项 | 建议方案与验证要求 | 截止阶段 |
| --- | --- | --- | --- |
| P1 | 现有 SSO 的远控接入配置 | 在已有 oidc-proxy 上登记 Android 客户端及远控资源的 audience / scope，核对回调、插件请求目标、刷新、登出和身份声明；补齐接入配置与客户端适配 | M1 上线前 |
| P2 | DPoP | 验证现有 oidc-proxy、客户端和资源服务器的实际配置及完整链路；若不具备，另行评审凭据绑定方案，不能静默降级 | M1 上线前 |
| P3 | 主机绑定 | 手机确认身份、主机使用者本机确认；按组织策略增加管理员审批 | M1 |
| P4 | 审批应答器组合 | 验证与现有桌面及微信入口的交互归属；一个请求只有一个终端应答器，指定高风险调用以手机批准为准 | M0 |
| P5 | 企业核心兼容性 | 获取锁定的 EduWork 本校核心和 TokenWorks dev 包，验证插件装配、权限策略和退出生命周期 | M0 |
| P6 | 离线历史 | 首期展示手机已有缓存；如需主机离线时读取全部历史，新增服务端正文同步方案并重新评审数据边界 | M2 产品验收前 |
| P7 | 应用完全退出后继续运行 | 首期要求 TokenWorks 运行时在线；若需要独立常驻运行时，单独设计其所有权、桌面连接和升级 | 后续增强 |
| P8 | 端到端加密 | 首期采用 TLS 和静态加密；后续独立评审密钥分发、轮换、设备撤销与文件加密协议 | 后续增强 |

未验证的 IdP 功能、企业核心接口和性能参数不能作为已经成立的前提。任何阻塞项必须有负责人、验证记录和解决路径。

### 0.3 当前源码依据与验证边界

| 组件 | 本次审读结果 | 实现依据 |
| --- | --- | --- |
| DSH | 当前检出 `dsh-v0.2.1-rc.2`，提交 `687f282e8625c6b8d11ff58211a90524be2e4317` | [CLI 版本](/home/Enthusjast/reference/deepseek-harness/apps/cli/package.json:4) |
| TokenWorks | 当前源码产品版本 0.2.103，组件表声明同一 DSH 基线 | [组件基线](/home/Enthusjast/reference/tokenworks/README.md:87) |
| EduWork 本校核心 | 锁定 0.4.7、`eb2d6433e2e9afac407d6d82030753889207c068`；本次未取得该核心的完整配套检出 | [core.lock.json](/home/Enthusjast/reference/tokenworks/core.lock.json:4) |
| 插件装配 | 发行清单已支持自有插件及配置补丁 | [发行清单](/home/Enthusjast/reference/tokenworks/edition/distribution.json:24) |
| 会话操作 | `sessionController` 提供 list、create、prompt、cancel、page、follow、selectModel 等接口 | [Session Controller](/home/Enthusjast/reference/deepseek-harness/packages/api/session-controller/src/index.ts:275) |
| 提交去重 | prompt 用 requestId 检查队列和已记录用户消息；插件仍需处理并发提交和崩溃状态 | [消息接收](/home/Enthusjast/reference/deepseek-harness/packages/api/session-controller/src/commands.ts:319) |
| 审批 | `approval/request` 提供单次决策；请求不直接包含完整工具参数，callId 可选 | [审批服务](/home/Enthusjast/reference/deepseek-harness/packages/interaction/user-approval/src/index.ts:108) |
| 远程权限 | Remote Access 支持非 owner 策略；Gateway 当前仅允许 owner Peer 回答转发的交互事件 | [访问策略](/home/Enthusjast/reference/deepseek-harness/packages/api/remote-access/src/index.ts:153)、[交互限制](/home/Enthusjast/reference/deepseek-harness/packages/api/gateway/src/index.ts:486) |
| 文件 | uploadStream 接收流并生成会话所属 receipt，供 prompt 引用 | [文件接收](/home/Enthusjast/reference/deepseek-harness/packages/client/file-upload/src/index.ts:118) |
| 学校登录与模型 | bootstrap 复用 oidcAccounts.authorizedFetch，模型凭据由 Host 使用 | [bootstrap 插件](/home/Enthusjast/reference/tokenworks/edition/plugins/tokenworks-bootstrap/lib/index.js:212) |
| 现有 SSO 服务 | TokenWorks 文档记载学校登录经 oidc-proxy 接入 CAS；讨论稿记载资源 audience、JWT / JWKS 和可选 DPoP 能力，生产配置和远控接入尚需验证 | [SSO 与资源服务记录](/home/Enthusjast/reference/tokenworks/docs/MCP-DISCUSSION.md:7) |
| 已有远程入口 | 微信文档说明共用桌面会话，但定位单个微信用户；配套核心中的完整实现不在本次已读源码内 | [微信入口](/home/Enthusjast/reference/tokenworks/docs/WECHAT.md:41) |

以上是静态源码审读结论。尚未运行本项目的新插件、Android APK、真实模型链路、企业核心组合测试或容量测试。M0 必须补齐真实组合证据，不能用模拟测试代替。

## 1. 目标、范围与运行边界

### 1.1 业务目标

1. 在手机上查看并继续电脑上正在使用的 TokenWorks 会话。
2. 对企业策略指定的高风险调用，在执行前取得手机端人工批准。
3. 回答 Agent 的普通提问和计划确认，避免任务停在电脑端等待。
4. 安全传递会话附件和允许导出的文件。
5. 一台手机管理多台主机，一台主机允许多个获授权用户操作。
6. 全部远程通信经企业自建服务，保持桌面和手机会话状态一致。

### 1.2 功能范围与交付阶段

| 功能 | 完整交付要求 | 首次交付 |
| --- | --- | --- |
| 插件装配与会话接入 | 接入真实 TokenWorks 当前进程 | M0 |
| 登录、设备注册、绑定与授权 | 复用 TokenWorks 学校 SSO/OIDC，增加设备密钥、撤销和运行实例授权 | M1 |
| 主机和会话列表 | 只展示有权访问的运行实例和会话 | M2 |
| 创建与继续会话 | 使用已允许的工作区和 Agent preset | M2 |
| 聊天与中断 | 流式展示、queue / steer、中断当前任务 | M2 |
| 工具审批 | 参数绑定、手机单次签名、拒绝和超时处理 | M2 |
| 用户提问与计划确认 | 独立问题类型、结构化答案、过期状态 | M2 |
| 重连与本地缓存 | 快照、历史分页、命令状态对账 | M1 / M2 |
| 会话归档 | 明确与中断、取消订阅、释放 Agent 的区别 | M2 |
| 文件上传和下载 | 对象存储中转、扫描、会话 receipt、导出授权 | M3 |
| 后台通知 | FCM 与目标设备所需的厂商推送 | M3 |
| 审计与应用更新 | 元数据审计、APK 签名校验、灰度更新 | M1 起逐步交付，M4 完整验收 |
| 目标规模 | 5000 台主机 / 5000 部手机 | M4 分批放量 |

### 1.3 非功能目标与测量口径

| 指标 | 目标 | 口径 |
| --- | --- | --- |
| 远控服务可用性 | 99.9% / 月 | 单独统计主机离线与公网服务故障 |
| 消息路由延迟 p95 | < 300 ms，同地域 | 两端已连接时，从入口接收到目标连接写出；不含模型和人工时间 |
| 压测端到端投递 p95 | < 500 ms | 指定负载、网络条件和消息尺寸下测量 |
| 审批通知延迟 p95 | 前台 < 2 s；后台目标 < 10 s | 从插件登记请求到界面或系统通知出现；按推送通道分别统计 |
| 断线重连成功率 | > 99.5% | 网络恢复且主机、服务端均可用 |
| 集群连接能力 | 验证 12000 条连接 | 覆盖 10000 条目标连接及额外容量 |
| 单节点能力 | 以实测结果确定 | 3000 条连接作为基准测试点，不提前据此承诺规格 |
| 确认后消息保留 | host_accepted 对应命令已在主机持久保存 | 边界见 4.5；不把临时传输回执当成持久接收 |
| 崩溃恢复 | 恢复已持久化会话和命令状态 | 不承诺恢复未提交的 assistant 增量或重复执行外部副作用 |
| 客户端稳定性 | 无崩溃会话比例 > 99.5% | Android 试点和正式版本分别统计 |
| 服务端审计保留 | 热 90 天、冷 1 年 | 审批决定的证明记录同样执行明确保留策略 |

### 1.4 数据与生命周期边界

- 首期要求 TokenWorks 运行时在线；电脑休眠、断网或应用完全退出时，主机显示离线。
- 关闭窗口是否仍在托盘运行，以实际发行版生命周期为准，在 M0 验证。
- 服务端处理路由所需的协议字段，可在内存中接触业务数据；首期不持久化聊天正文、完整工具参数、问题正文和工作区文件内容。
- 文件中转是明确的数据存储例外，按文件服务策略保存并到期清理。
- 主机离线时只显示手机已有缓存；没有缓存的历史显示“需要主机上线”，不伪造空历史。
- 不做远程桌面、屏幕串流、手机直接 shell 接口、任意路径读写或跨企业身份联邦。
- 远程插件不导出桌面模型 Key、OIDC refresh token、原始设置或通用管理 RPC。

## 2. 总体架构

### 2.1 首期逻辑架构

```text
Android App
  ├─ HTTPS：登录后的设备、授权、元数据、ticket、文件
  └─ WSS：会话、消息、审批、问题、在线状态
             │
             ▼
企业公网入口（TLS、连接配额、限流）
             │
             ▼
Go 远控服务
  ├─ Control Plane：身份校验、设备与实例绑定、权限、审计
  ├─ Relay：连接、逐操作授权、路由、背压、撤权
  └─ Push / Files：通知、文件元数据与对象存储协调
             ▲
             │ 插件主动建立出站 WSS
             │
TokenWorks 当前运行进程（当前 OS 用户 / 当前 profile）
  └─ Remote Control 插件
       ├─ 连接、设备证明、受限协议、命令状态
       ├─ Session Controller、会话日志与持久化检查点
       ├─ 审批策略、最终执行检查、问题应答
       └─ 附件接收、允许导出文件
```

首期 Go 服务按模块开发，可作为同一进程部署。需要多节点或独立扩容时再拆部署单元，网络协议不依赖模块是否同进程。

### 2.2 组件职责

| 组件 | 拥有的状态与职责 |
| --- | --- |
| Android | 用户登录、本机密钥、本地消息缓存、待发送命令、交互和通知 |
| Control Plane | 用户与设备身份、安装实例登记、授权版本、会话元数据、审计、发行元数据 |
| Relay | 活跃连接、路由、每个操作的授权检查、短期发送队列、连接代次 |
| Remote Control 插件 | 当前运行实例的协议入口、命令日志、DSH 接口适配、审批生命周期、文件交接 |
| DSH | Agent 执行、会话日志、队列、模型路由、工具与附件能力 |
| PostgreSQL | 控制面持久元数据和审计；不保存聊天正文 |
| Redis | 多节点需要时保存路由、ticket、授权失效信息和限流缓存；不作为持久命令唯一来源 |
| 对象存储 | 上传隔离区和允许下载的文件，按配额与保留期限清理 |
| NATS / JetStream | 多节点阶段的消息转发与可靠控制事件；启用条件见 5.5 |

### 2.3 主机、安装实例与运行实例

物理电脑、OS 用户、TokenWorks profile 和当前进程不是同一个授权主体：

| 标识 | 含义 | 生命周期 |
| --- | --- | --- |
| host_id | 控制面登记的一台主机 | 管理员撤销前有效 |
| installation_id | 某 OS 用户下的一次插件安装和设备密钥登记 | 重装、密钥轮换或撤销时变化 |
| runtime_id | 某安装实例下的一个明确 TokenWorks profile | 稳定保存，不能由硬件指纹推导授权 |
| runtime_epoch | 当前运行时的启动 / 插件重新接入代次 | 重新启动或失去交互所有权后变化 |
| session_id | 控制面对外会话 UUID | 映射到 runtime_id + DSH 会话 ID |
| dsh_session_id | DSH 原生不透明字符串 | 原样保留，不要求 UUID 或固定前缀 |

授权默认落在 runtime_id，可进一步限制工作区和会话。一台电脑中的另一个用户或 profile 不自动继承授权。硬件指纹只用于展示和辅助排查。

### 2.4 关键数据流

**绑定**：插件生成安装密钥 → 控制面创建短期绑定申请 → 手机学校登录后核对主机、profile、公钥指纹 → 主机使用者本机确认 → 必要的管理员审批 → 控制面激活实例与授权。

**连接**：手机带用户身份和设备证明申请一次性 ticket；插件使用已登记安装密钥取得短期主机连接凭据。两端接入 Relay，连接绑定到确定的设备 / 实例和连接代次。

**发消息**：手机保留 request_id 和正文 → Relay 授权并转发 → 插件持久登记命令 → 回 host_accepted → 按会话串行提交 DSH prompt → 等待会话持久化检查点 → 回 agent_queued → 后续通过会话事件反映执行状态。

**同步**：插件读取原生会话快照并订阅后续事件 → 协议携带同步代次、原生事件位置和实时 assistant 状态 → 手机原子替换快照并合并后续事件 → 更早历史按固定快照位置分页。

**审批**：执行策略识别需人工确认的调用 → 插件取得精确执行上下文并登记挂起请求 → 手机拉取当前详情并签署决定 → 服务端验证身份、授权并持久审计 → 插件验签、校验上下文并原子决议 → DSH 继续或拒绝，最终执行检查约束该次调用。

**文件**：手机申请上传 → 直传隔离区 → 服务端校验及扫描 → 插件下载并调用 fileUploads.uploadStream → 得到目标会话 receipt → 随 prompt 提交。下载通过已授权导出对象完成。

### 2.5 技术选型与仓库结构

| 层 | 方案 | 决策边界 |
| --- | --- | --- |
| 插件 | TypeScript、ESM、Cordis 插件；输出构建后的 JS | 精确锁定 DSH 基线和 peer 依赖；不复制一套 DSH 核心 |
| 服务端 | Go，控制面 / 中继 / 推送 / 文件模块 | 先模块化部署，后按实测拆分 |
| Android | Kotlin、Compose、Hilt、Room、OkHttp | 加密库、SDK 和 targetSdk 在实现 / 发布时锁定并验证 |
| 身份 | 复用 TokenWorks 现有学校 SSO/OIDC（oidc-proxy），Android 使用授权码 + PKCE | 电脑复用 oidcAccounts；手机登记客户端；中转登记资源并验签；不新增 SSO 部署 |
| 数据 | PostgreSQL；手机本地库；插件本地命令日志 | 各自拥有状态，跨库通过稳定 ID 对账 |
| 文件 | S3 兼容对象存储、扫描任务 | 具体产品和部署规格按组织现有设施选择 |
| 扩容 | Redis、NATS / JetStream、必要时 Kubernetes | 以多节点需求与压测数据作为启用依据 |

```text
dsh-mobile-ustc/
├── contracts/
│   ├── ws/v1/                 # 移动协议 JSON Schema
│   ├── openapi/               # REST API
│   └── golden/                # 签名、JCS、恢复、状态样例
├── host/
│   └── remote-control/        # TokenWorks 进程内插件
│       ├── src/               # DSH 适配、协议、连接、审批、命令日志
│       └── tests/             # Loader 真实组合及生命周期验证
├── services/
│   └── remote/                # Go 入口及 control / relay / push / files 模块
├── android/
├── deploy/
│   ├── compose/               # 本地依赖
│   └── sql/                   # PostgreSQL 迁移
├── tools/loadtest/
└── docs/                      # 接入、实机验收、运维、兼容矩阵
```

插件在本仓开发，发行时按 TokenWorks 的插件装配、版本锁和来源审计流程集成。插件包的构建产物、发行配置和实际 dev 包都要验证。

## 3. 身份、设备与授权

### 3.1 三类凭据分别管理

本项目复用 TokenWorks 已接入的学校 SSO/OIDC。学校账号和用户身份由现有 oidc-proxy 及其上游认证服务负责；新增的中转服务负责远控设备、主机、会话和审批权限。新增远控短期凭据用于业务连接，不形成第二套用户账号或密码体系。

| 类型 | 用途 | 保存位置与限制 |
| --- | --- | --- |
| 用户 OIDC 凭据 | 证明学校用户身份 | 手机或桌面各自客户端持有，不能直接跨 audience 使用 |
| 远控设备 / 安装凭据 | 证明手机设备或主机安装实例，建立远控连接 | 本机密钥存储；绑定明确用户、实例、用途和有效期 |
| 桌面模型凭据 | 调用学校模型网关 | 保留在现有桌面模型服务，远控协议不传递 |

用户主键按已验证的 issuer + subject 映射。若不同客户端使用 pairwise subject，需由身份服务提供受信任关联，不能按邮箱自动合并。org_id、角色和授权从可信服务端映射产生。

### 3.2 复用 TokenWorks 现有 SSO/OIDC

| 部分 | 复用方式 | 本项目新增工作 |
| --- | --- | --- |
| 电脑插件 | 注入现有 oidcAccounts，复用当前学校账户、登录状态、令牌管理及 oidc/accounts-changed 事件 | 远控资源授权、主机登记、安装密钥与远控连接 |
| Android | 接入同一个学校 oidc-proxy，使用同一套学校账号 | 独立登记原生 public client，配置手机回调，使用授权码 + PKCE，并在手机保存自己的凭据 |
| 中转服务 | 作为现有 oidc-proxy 的资源服务器，验证适用于远控 API 的身份凭据 | 登记独立 resource / audience / scope，验证签名、issuer、有效期、scope 和必要声明，维护设备及远控访问权限 |

电脑插件沿用现有公开接口发送获授权的身份请求：

```ts
ctx.oidcAccounts.authorizedFetch(profileId, remoteAuthUrl, requestOptions)
```

令牌由现有 dsh-oidc 服务附加和刷新，插件不导出桌面 refresh token，也不另写一套学校登录流程。profileId 来自现有学校账户配置；通过 oidc/accounts-changed 同步退出、登录和账号切换，并执行 3.7 的远控暂停与撤权规则。实际调用方式可参照 [bootstrap 与用量请求](/home/Enthusjast/reference/tokenworks/edition/plugins/tokenworks-bootstrap/lib/index.js:212)。

现有接口文档说明纯身份登录允许访问 issuer 同源地址。远控地址采用其他域名时，必须核对 dsh-oidc 的请求目标限制及取得远控 audience 令牌的能力；同源允许发送请求也不等于已有 token 自动具备远控权限。对跨域或新增资源所需的授权，在现有 SSO / 客户端上完成受支持的配置或适配，不把模型网关 Key 或 bootstrap 专用凭据当成远控凭据。[接口范围说明](/home/Enthusjast/reference/tokenworks/edition/plugins/tokenworks-bootstrap/README.md:24)

TokenWorks 的讨论稿已记载 oidc-proxy 支持 PKCE、资源 audience、JWT / JWKS 与可选 DPoP；这是沿用现有身份设施的接入依据，不代表远控资源、Android 回调或生产 DPoP 配置已经完成。[现有能力记录](/home/Enthusjast/reference/tokenworks/docs/MCP-DISCUSSION.md:29)

M1 前确认并记录当前生效的 Discovery / issuer、Android client、精确 redirect URI、PKCE S256、state、nonce、远控 resource / audience / scope、签名算法与 JWKS 轮换、刷新、账号停用、登出、MFA / acr 及 DPoP 实际行为。issuer 取自当前受信任配置；文档中的地址迁移提案不作为当前生产地址。

身份设施继续使用现有 oidc-proxy；本项目不部署新的 SSO 或 Keycloak。接入缺口在现有身份服务、客户端适配或中转服务的受限鉴权入口解决，所有用户身份仍追溯到同一学校 SSO。

### 3.3 Android 授权码 + PKCE

Android 使用与 TokenWorks 相同的学校 SSO，登记适用于手机的独立 public client 和回调地址。手机自行完成授权并持有本设备凭据；电脑的登录状态和 refresh token 不复制到手机。

1. 先创建独立的连接证明密钥 proof_key（采用 DPoP 时作为 dpop_key），然后生成 PKCE verifier / challenge、state、nonce，并保存短期登录上下文。
2. 通过 Custom Tabs 打开授权端点，使用精确注册的 App Link 或回调 URI。
3. 回调校验 state，用 code + verifier 换令牌；需要 DPoP 时，在令牌请求阶段即使用该密钥生成 proof。
4. 验证 ID Token 的签名、issuer、audience、有效期和 nonce；API 使用适用 audience 的 access token。
5. 另行生成审批用 approval_key，向控制面登记设备公钥，完成公钥持有证明；不能只上传一把未经证明的公钥。
6. 进入主机绑定和实例列表。客户端不获取桌面模型 Key。

本章是流程要求，实际 AppAuth 配置从 Discovery 构造并按 SDK API 实现，不保留未经编译验证的构造函数示例。

### 3.4 主机安装实例登记与连接凭据

首期采用控制面管理安装公钥的登记协议，不默认把每台电脑变成一个 IdP confidential client：

登记申请的用户身份复用电脑端已登录的学校账户，通过 3.2 所述受支持的身份请求接入远控服务；安装私钥证明机器安装身份。两者分别校验，不能只凭硬件指纹或安装公钥判断学校用户。

1. 插件在当前 OS 用户身份下生成安装密钥，私钥不离机。
2. 创建短期 enrollment，包含安装公钥、主机展示信息、profile、版本及唯一申请 ID；未绑定申请只能查询自身状态，受严格配额保护。
3. 手机用户登录后确认 enrollment；本机用户核对发起者并确认；组织策略要求时再管理员批准。
4. 控制面登记 installation_id、runtime_id 与授权，并要求安装私钥完成一次性 challenge 签名。
5. 安装实例用带用途、nonce、audience、短有效期的签名证明换取远控服务专用的短期连接凭据。挑战只能成功消费一次。
6. 连接凭据绑定该安装公钥并在接入时证明持有；需要时采用经验证的 DPoP 或 mTLS 方案。裸凭据复制不能完成接入。

`private_key_jwt` 是 OAuth 客户端认证机制。若未来采用它，需要明确 IdP 客户端、公钥注册 / JWKS、每安装密钥隔离和轮换；不能认为调用控制面的 enroll 就完成了 IdP 密钥登记。[JWT 客户端认证规范](https://www.rfc-editor.org/rfc/rfc7523.html)

### 3.5 DPoP、ticket 与长连接

需要 DPoP 的受保护 HTTP 请求使用 `Authorization: DPoP <access_token>` 和独立 proof。校验至少包括允许的非对称算法、typ、签名、公钥指纹、htm、规范化后的 htu、iat、jti、ath 和服务端要求的 nonce；token 请求与资源请求的 proof 按各自规则生成。

DPoP 不签署任意业务请求正文；审批仍使用第 8 章的内容签名。[RFC 9449](https://www.rfc-editor.org/rfc/rfc9449.html)

手机通过受保护 REST 申请 30 秒有效的一次性 ticket，绑定 user_id、device_id、远控用途、允许访问范围、授权版本和当前密钥。接入 WSS 时额外完成绑定设备密钥的 challenge 证明，防止仅盗取 ticket 后抢先接入。

ticket、绑定申请秘密、nonce 和 challenge 使用至少 256 bit 的密码学安全随机数。ticket 存储为随机 token 的摘要对应的字符串值；使用原子取出并删除操作。不得把 Redis hash 与仅用于字符串值的消费方式混用。访问日志必须屏蔽 ticket，服务端验证失败不泄露其内容。

连接建立后继续检查令牌 / 会话租约的有效期并按协议续期。一次握手不会赋予连接永久权限；每个业务操作仍检查当前授权。

### 3.6 授权粒度与执行规则

| 动作 | viewer | operator | owner |
| --- | --- | --- | --- |
| 列表、历史、会话状态 | 允许 | 允许 | 允许 |
| 创建会话、发消息、中断、普通提问 | 拒绝 | 按授权允许 | 允许 |
| 单次审批 | 拒绝 | 仅组织策略允许的低风险请求 | 可批准授权范围内的请求 |
| 文件上传、下载 | 拒绝 | 按文件范围允许 | 按文件范围允许 |
| 授权他人、撤销实例 | 拒绝 | 拒绝 | 按组织管理规则允许 |
| 放宽桌面全局权限、插件管理、任意 shell RPC | 拒绝 | 拒绝 | 远控入口同样不开放 |

高风险审批要求 owner、所需认证强度和当前审批密钥证明；特别高风险动作可要求额外确认。不能仅凭工具名或命令正则准确识别所有风险，具体规则必须来自当前工具 / capability 注册和企业策略，并包含脚本、PTC、子 Agent 等路径。

服务端和插件均验证完整资源归属链：用户 / 设备 → 组织 → 主机安装 → runtime → session → approval / file。仅有 session_id、approval_id 或 file_id 的请求，必须先解析资源归属；解析失败即拒绝，不能因 URL 中没有 host_id 而跳过鉴权。

调用 DSH Gateway 的路径可注册独立的非 owner principal 策略；直接调用同进程服务时，由插件的受限分发器完成等价检查，不假设服务方法会自动补上公网授权。

### 3.7 撤权、切换账户与租约

- 控制面授权修改与撤销事件在同一数据库事务登记，通过持久 outbox 发布。
- 在线写操作和审批只在当前授权版本、有效连接及受信任授权租约下接受。主机验证由控制面签发的动作范围与资源范围。
- 撤权目标窗口小于 5 秒；租约寿命、缓存、时钟容差和传播时间全部计入该窗口。授权状态无法及时验证时，拒绝新的敏感操作。
- 失效事件可靠广播，Relay 中断相关调用和订阅，插件取消受影响的挂起审批、问题或文件交接。
- 丢失手机先撤销该设备及其密钥；是否注销全部用户会话按事件范围决定，不能把所有设备默认同时踢下线。
- 桌面账号切换时，暂停旧身份的远控授权和请求，清理旧路由，重新确认实例身份；不能让晚返回的旧请求恢复原授权。
- 重装或密钥丢失重新登记 installation；旧安装撤销。新 runtime 不自动承接旧实例权限。
- 轮换、登出和账号停用分别定义，不能把清除本地 token 等同于服务端撤权。

### 3.8 本机密钥保护

| 密钥 | 用途 | 保护 |
| --- | --- | --- |
| 手机 dpop_key / proof_key | HTTP / 连接持有证明 | Keystore，允许必要的后台证明，不要求每次人工解锁 |
| 手机 approval_key | 人工审批签名 | Keystore，按策略要求生物识别或设备凭据 |
| 手机 token_key / db_key | 凭据及缓存加密 | 平台支持的密钥封装；设备锁和恢复规则需实机验证 |
| 主机安装密钥 | 实例登记与连接证明 | 当前用户的 OS 安全存储；受限文件方案需明确 ACL、备份和轮换 |

Windows 避免首期使用 SYSTEM / 机器范围凭据承载桌面用户会话；macOS、Linux 使用实际发行版支持的用户密钥存储。私钥不写入发行清单、日志、共享目录或协议。登录配置、服务地址及公开验证密钥沿现有签名配置流程分发。

### 3.9 身份验收

- [ ] 电脑插件复用 TokenWorks 已有学校账户，登录恢复、令牌刷新、退出及账号切换与原账户一致。
- [ ] Android 和中转服务接入同一学校 oidc-proxy；独立客户端、资源 audience / scope 及插件请求目标配置正确。
- [ ] 学校真实身份可完成手机登录，错误 issuer / audience / nonce 被拒绝。
- [ ] 设备和安装私钥的持有证明、过期与重放检查有效。
- [ ] DPoP 覆盖实际采用的授权、刷新和资源请求；未验证能力没有标为已支持。
- [ ] 两个用户、两个设备、两个 profile 的隔离成立。
- [ ] 账号切换、停用、丢失设备和主机重装可在规定窗口内撤权。
- [ ] 模型 Key、桌面 refresh token 不出现在手机、远控日志和中继持久数据中。

## 4. 通信、同步与可靠性协议

### 4.1 通道和版本

| 通道 | 用途 |
| --- | --- |
| HTTPS REST | 身份后的设备、登记、授权、元数据、ticket、文件中转与发行信息 |
| WSS | 会话读写、实时状态、审批、问题、控制事件 |
| HTTPS 对象传输 | 文件直传和下载，不把大文件塞进 WSS |

首期没有 Bridge ↔ Adapter 回环端口。移动协议 `protocol_version`、插件版本、DSH 版本、TokenWorks 版本、会话格式和支持的 capability 分别报告，不混用成一个版本号。

### 4.2 握手与接入状态

```json
{
  "v": 1,
  "type": "hello",
  "payload": {
    "client": "android",
    "device_id": "22222222-2222-4222-8222-222222222222",
    "protocols": [1],
    "capabilities": ["session.snapshot.v1", "assistant.stream.v1", "approval.once.v1", "questions.v1"],
    "resume": [{
      "session_id": "55555555-5555-4555-8555-555555555555",
      "last_persisted_seq": 128
    }]
  }
}
```

服务端只接受与已验证 ticket 和设备证明一致的身份；客户端提交的 ID 不是身份来源。主机握手额外报告 installation_id、runtime_id、runtime_epoch、DSH / TokenWorks / 插件版本和经过探测的功能集。

握手完成回复协议交集、连接 ID、服务时间、帧和队列限制及心跳参数。首期建议心跳 20 秒，关闭阈值按测量配置。断线采用有抖动的 1～60 秒退避。

### 4.3 信封、ID 与位置

```json
{
  "v": 1,
  "type": "message.submit",
  "request_id": "66666666-6666-4666-8666-666666666666",
  "runtime_id": "44444444-4444-4444-8444-444444444444",
  "session_id": "55555555-5555-4555-8555-555555555555",
  "payload": {
    "mode": "queue",
    "content": [{"type": "text", "text": "请继续当前任务"}]
  }
}
```

| 字段 | 语义 |
| --- | --- |
| request_id | 客户端生成并在重试时保持不变的命令 UUID；与内容摘要共同校验 |
| session_id | 控制面 UUID；原生 DSH ID 在映射层作为不透明文本保存 |
| runtime_epoch | 当前交互 / 实时状态所属的运行时代次；签名审批必须包含 |
| native_seq | 原生 Session 的零起始事件位置；只有会话日志事件携带 |
| persisted_through_seq | 已经过持久化检查点的原生事件位置；空日志为 -1 |
| sync_id | 每次快照与后续订阅所属的同步代次 |
| attempt_id / revision / index | 进程内 assistant 流的位置，不是历史恢复游标 |

不设置一个覆盖所有业务帧的“全局持久 seq”。如增加传输序号，仅用于当前连接丢帧诊断。

### 4.4 消息清单

| 请求 / 事件 | 主要用途 |
| --- | --- |
| hello / hello.ok、ping / pong | 握手、参数与心跳 |
| session.list / session.create | 列出和创建允许范围内的会话 |
| session.subscribe / session.unsubscribe | 打开或取消一代订阅；取消订阅不终止任务 |
| session.snapshot / session.event / session.checkpoint | 开场快照、原生日志事件、持久化进度 |
| session.page | 读取固定 snapshot cursor 以前的历史页 |
| assistant.start / assistant.chunk / assistant.end | 当前 attempt 的增量展示 |
| message.submit / command.receipt / command.status | 命令提交、分阶段回执、状态对账 |
| session.cancel / session.archive | 取消活动或归档；归档语义由 6.3 定义 |
| approval.request / approval.details / approval.decision / approval.result | 审批引用、详情、签名决定、最终结果 |
| question.request / question.answer / question.result | 普通问题及计划确认；不能当成工具执行授权 |
| file.offer / file.status | 已经扫描并获授权文件的交接状态 |
| access.revoked / runtime.offline / error | 撤权、离线与可操作错误 |

模型选择可作为独立 capability，通过现有 modelCatalog / selectModel 接入；不能让手机提交任意 provider 配置或凭据。

### 4.5 命令接收、去重与确认

```text
client_pending
  → received          Relay 临时收到；不代表持久接收
  → host_accepted     插件命令日志及内容已持久保存
  → dispatching       已持久记录即将交给 DSH
  → agent_queued      DSH 已接收，且对应会话持久化检查点成功
  → observed          已能关联到队列或用户消息事实
  → settled           已记录后续活动结果，或明确终止 / 拒绝
```

执行结果不明时进入 outcome_unknown，保留记录并提示人工确认，不把它显示成“肯定未执行”。命令入队、当前任务完成和某条 prompt 独占生成一条回复是不同事实；queue、steer 和其他输入可能共同影响一个活动区间。

插件在本地 SQLite WAL 等持久存储中保存 request_id、提交者 / 设备、runtime、session、规范化内容摘要、加密正文、状态和原生关联位置。host_accepted 必须在该事务达到约定的磁盘持久性后发送。

服务端只保存命令状态和内容摘要。主机离线时拒绝新的正文转发，手机保留待发送内容；Relay 的 received 不是断线后的可靠队列。用户离线期间的提交是否继续重试，由明确的客户端待发送状态控制。

同一设备的 request_id 不得代表不同内容；冲突返回 command.request_conflict。插件对同一会话的准入串行化，并复用原生 prompt 的 requestId 去重能力，避免两个并发请求同时通过原生重复检查。

agent_queued 必须等待 `ctx.sessions.flush(agent.session)` 的持久化检查点，并确认实际有持久化参与者。失败时保留本地命令日志并报告对应阶段，不能回“已安全入队”。

恢复时先用原生 inbox / 用户消息和命令日志对账。只有能够证明尚未交给 DSH 的命令才可自动提交；可能已执行但无法确认的操作进入 outcome_unknown。传输采用至少一次重试与幂等准入，不承诺任意外部工具副作用的恰好一次执行。

“确认后 0 丢失”的边界是主机持久磁盘可恢复情况下的网络和进程故障。主机磁盘毁坏、整机遗失和备份恢复按另行定义的 RPO 处理；服务端元数据不能恢复未上传的正文。

### 4.6 历史、快照与实时流恢复

DSH 当前 `follow` 的请求接口没有移动协议的 last_seq 参数。它提供开场快照、固定 cursor、历史窗口和后续事件；`page` 提供该 cursor 以前的分页。插件须按此真实接口适配，不能把移动 resume 字段原样传入并假定原生已经完成补发。

1. 为本次订阅创建 sync_id，使用原生 follow 取得开场快照和 gap-free 后续事件。
2. 将快照 header、窗口、cursor、投影、assistant baseline 和持久化位置发送给手机；大快照分片并在接收完整后原子切换。
3. 手机按 sync_id 和 native_seq 合并事件；先去重，再更新投影和缓存。更早历史通过 page 逐页读取。
4. 同一 runtime_epoch 下，assistant 流按 attempt、revision、index 校验连续性，发现断档请求替换快照。
5. runtime_epoch 变化后，旧 assistant 暂态和所有旧交互请求失效，重新读取当前快照。
6. 没有达到 persisted_through_seq 的事件属于尚待持久化的观察状态；重启后按恢复快照撤回或替换，不继续用旧的暂态位置追尾。

已提交 assistant/message、assistant/attempt 和工具结果从日志恢复。尚未提交的 assistant 增量只保证同一进程存活时的重连展示；进程崩溃后可能消失。最终事件到达后替换对应实时 attempt，避免重复追加文本。

### 4.7 背压与限流

- 单帧初始上限 64 KiB，按 UTF-8 编码后的完整信封计算。快照、历史和大详情分片或分页；原始文件不经 WSS。
- 发送队列按总字节数设置上限，同时限制单用户、设备、安装实例和会话的速率；参数由部署配置给出。
- 实时增量可按约 100 ms 的窗口合并，但保持 attempt 位置与替换快照语义；不能静默丢帧后继续声称连续。
- 审批和撤权等控制事件优先发送。队列无法保持必需事实时终止该订阅并要求重同步，不用无限队列阻塞模型执行。
- 事件读取与慢客户端网络写入分离；插件保留有限缓冲，必要时取消慢订阅，防止原生 follower 队列被慢手机持续拖住。
- 网关按身份和来源联合限流；公网 NAT 下大量合法用户可能共用 IP，不用低固定 IP 连接数上限替代设备配额。

### 4.8 错误与版本协商

| code | 含义 | 客户端动作 |
| --- | --- | --- |
| auth.expired / auth.proof_invalid | 身份或持有证明失效 | 按协议刷新或重新登录 |
| access.denied / access.revoked | 无授权或授权已撤销 | 停止请求、关闭对应视图并处理缓存 |
| runtime.offline / runtime.changed | 主机离线或代次变化 | 保留未确认命令，重新获取状态 |
| session.not_found / session.writer_held | 会话不存在或被其他运行时持有 | 刷新列表，不能启动第二个 writer 抢占 |
| command.request_conflict / command.outcome_unknown | request_id 内容冲突或执行结果不明 | 停止自动重发并展示状态 |
| approval.stale / approval.expired / approval.conflict | 旧代次、过期或已决议 | 更新审批视图，禁止重新使用签名 |
| protocol.resync_required / protocol.unsupported | 需要新快照或版本不兼容 | 重同步或升级 |
| rate.limited / internal | 配额或服务错误 | 按 retry_after 和幂等规则处理 |

协议按主版本协商，可选能力按双方交集开放。兼容矩阵分别锁定移动协议、插件、DSH、TokenWorks 与企业核心版本。DSH 升级先做真实组合和故障回放，再灰度发行。

## 5. 数据模型与保留

### 5.1 数据所有权

| 位置 | 持久保存 | 不保存 |
| --- | --- | --- |
| 主机 DSH | 原生会话、队列、工具结果与附件 | 远控服务的用户密码 |
| 插件本地库 | 加密命令日志、审批上下文、一次性消费状态、交互映射、控制面待同步状态 | 导出的桌面模型凭据 |
| 手机 | 授权期间的会话缓存、待发送命令、设备密钥引用 | 桌面凭据 |
| 控制面 PostgreSQL | 身份映射、设备、实例、授权、会话元数据、命令 / 审批状态与审计 | 聊天正文、工具完整参数、问题正文 |
| 对象存储 | 获授权中转文件和 APK 等发行物 | 未经登记的工作区文件 |

server session 元数据是目录和状态镜像，历史内容以主机日志为依据。数据库、Redis、推送或审计中不另建第二份正文库。

### 5.2 PostgreSQL 初始 DDL

以下是初始迁移基线，所有业务 ID 使用 UUID，DSH ID 使用 text。首期业务表不按时间分区，避免为时间清理破坏会话唯一性；后续如分区，必须单独迁移和验证全局去重方式。

```sql
create extension if not exists pgcrypto;

create table orgs (
  id uuid primary key default gen_random_uuid(),
  slug text not null unique,
  name text not null,
  created_at timestamptz not null default now()
);

create table users (
  id uuid primary key default gen_random_uuid(),
  issuer text not null,
  subject text not null,
  status text not null default 'active'
    check (status in ('active', 'suspended', 'deleted')),
  created_at timestamptz not null default now(),
  unique (issuer, subject)
);

create table org_memberships (
  org_id uuid not null references orgs(id),
  user_id uuid not null references users(id),
  role text not null check (role in ('member', 'admin')),
  primary key (org_id, user_id)
);

create table devices (
  id uuid primary key default gen_random_uuid(),
  user_id uuid not null references users(id),
  name text not null,
  approval_public_jwk jsonb not null,
  proof_public_jwk jsonb not null,
  key_version bigint not null default 1,
  push_provider text,
  push_token_ciphertext bytea,
  last_seen_at timestamptz,
  revoked_at timestamptz
);

create table hosts (
  id uuid primary key default gen_random_uuid(),
  org_id uuid not null references orgs(id),
  alias text not null,
  state text not null default 'pending'
    check (state in ('pending', 'active', 'revoked')),
  created_at timestamptz not null default now()
);

create table installations (
  id uuid primary key default gen_random_uuid(),
  host_id uuid not null references hosts(id),
  owner_user_id uuid not null references users(id),
  public_jwk jsonb not null,
  key_version bigint not null default 1,
  public_key_thumbprint text not null unique,
  revoked_at timestamptz
);

create table runtimes (
  id uuid primary key default gen_random_uuid(),
  installation_id uuid not null references installations(id),
  profile_ref text not null,
  epoch uuid,
  dsh_version text,
  tokenworks_version text,
  plugin_version text,
  protocol_version integer not null default 1,
  last_seen_at timestamptz,
  revoked_at timestamptz,
  unique (installation_id, profile_ref)
);

create table runtime_grants (
  runtime_id uuid not null references runtimes(id),
  user_id uuid not null references users(id),
  role text not null check (role in ('viewer', 'operator', 'owner')),
  scope jsonb not null default '{}',
  grant_version bigint not null default 1,
  granted_by uuid not null references users(id),
  expires_at timestamptz,
  revoked_at timestamptz,
  primary key (runtime_id, user_id)
);

create table sessions (
  id uuid primary key default gen_random_uuid(),
  runtime_id uuid not null references runtimes(id),
  dsh_session_id text not null,
  state text not null default 'available'
    check (state in ('available', 'running', 'archived', 'unavailable')),
  last_persisted_seq bigint not null default -1 check (last_persisted_seq >= -1),
  updated_at timestamptz not null default now(),
  unique (runtime_id, dsh_session_id)
);

create table commands (
  device_id uuid not null references devices(id),
  request_id uuid not null,
  user_id uuid not null references users(id),
  session_id uuid not null references sessions(id),
  payload_sha256 text not null check (payload_sha256 ~ '^[0-9a-f]{64}$'),
  state text not null check (state in (
    'received', 'host_accepted', 'dispatching', 'agent_queued', 'observed',
    'settled', 'rejected', 'outcome_unknown'
  )),
  updated_at timestamptz not null default now(),
  primary key (device_id, request_id)
);

create table approvals (
  id uuid primary key,
  session_id uuid not null references sessions(id),
  runtime_epoch uuid not null,
  execution_id uuid not null,
  call_id text,
  binding_sha256 text not null check (binding_sha256 ~ '^[0-9a-f]{64}$'),
  policy_version bigint not null,
  state text not null check (state in (
    'pending', 'decision_received', 'allowed', 'denied',
    'expired', 'cancelled', 'invalidated'
  )),
  decided_by uuid references users(id),
  decided_device_id uuid references devices(id),
  decision_jws text,
  expires_at timestamptz not null,
  created_at timestamptz not null default now(),
  decided_at timestamptz,
  unique (session_id, runtime_epoch, execution_id)
);
create index approvals_pending on approvals (expires_at) where state = 'pending';

create table files (
  id uuid primary key default gen_random_uuid(),
  session_id uuid not null references sessions(id),
  uploader_id uuid not null references users(id),
  direction text not null check (direction in ('to_host', 'from_host')),
  name text not null,
  size_bytes bigint not null check (size_bytes >= 0),
  sha256 text not null check (sha256 ~ '^[0-9a-f]{64}$'),
  object_key text not null unique,
  state text not null check (state in (
    'uploading', 'scanning', 'clean', 'rejected', 'expired'
  )),
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);

create table audit_log (
  id bigint generated always as identity primary key,
  ts timestamptz not null default now(),
  org_id uuid references orgs(id),
  actor_type text not null,
  actor_id text not null,
  device_id uuid,
  runtime_id uuid,
  session_id uuid,
  action text not null,
  result text not null,
  metadata jsonb not null default '{}'
);
create index audit_org_time on audit_log (org_id, ts desc);

create table control_outbox (
  id uuid primary key default gen_random_uuid(),
  created_at timestamptz not null default now(),
  topic text not null,
  payload jsonb not null,
  published_at timestamptz
);

create table app_releases (
  id uuid primary key default gen_random_uuid(),
  platform text not null,
  version text not null,
  version_code bigint not null,
  sha256 text not null,
  object_key text not null,
  rollout integer not null default 0 check (rollout between 0 and 100),
  mandatory boolean not null default false,
  notes text not null default '',
  created_at timestamptz not null default now(),
  unique (platform, version_code)
);
```

scope 使用明确 Schema 定义允许的工作区、会话和动作，空对象不表示任意授权。审批正文和参数只在主机及授权手机存储，decision_jws 只包含签名元数据与摘要。

服务层在事务内完成资源归属、组织成员关系及状态校验。必要的复合外键或行级安全策略在迁移实现时补充；存在单列外键不等于已经完成租户隔离。

PostgreSQL 分区表的主键 / 唯一约束必须包含分区键；不能恢复旧方案中“按 ts 分区、只以 session_id + seq 为主键”的定义。[PostgreSQL 16 分区限制](https://www.postgresql.org/docs/16/ddl-partitioning.html)

### 5.3 本地日志与保留

| 数据 | 建议保留 | 条件 |
| --- | --- | --- |
| 手机会话缓存 | 最近 30 天、每会话近期窗口，按容量淘汰 | 授权撤销和登出时按策略清理；更早历史需主机在线 |
| 主机命令日志 | 未完成、未知结果保留至人工处理；已完成摘要按策略归档 | 清理正文前确认 DSH 已持久接收且有可对账位置 |
| 服务端命令元数据 | 初始 30 天，可配置 | 保留期届满后的重复提交仍不能绕过主机去重 |
| 审批决定及审计 | 热 90 天、冷 1 年 | 法务或组织策略要求更长时单独配置 |
| 上传隔离文件 | 24 小时内清理失败 / 未提交上传 | 只允许 clean 文件进入后续交接 |
| 已提交中转文件 | 初始最长 90 天，支持更短 TTL 和交接后删除 | 不等同于工作区文件备份 |

Redis 和推送不承载正文恢复责任。主机 SQLite 的持久性参数、加密和磁盘空间上限必须在故障测试中明确。

### 5.4 Redis 可选键设计

| 键 | 类型 | 用途 |
| --- | --- | --- |
| ticket:{digest} | string | 一次性 ticket；原子消费；30 秒有效 |
| route:runtime:{id} | string | node、conn_id、epoch；带租约 |
| presence:runtime:{id} | string | 在线状态缓存；带 TTL |
| grant-version:{runtime}:{user} | string | 授权失效辅助；数据库为依据 |
| proof:{key-thumbprint}:{jti} | string | proof 防重放；TTL 覆盖完整可接受时间窗口及容差 |
| rate:{subject}:{operation} | counter / bucket | 身份和动作限流 |

路由写入、续租和删除使用连接代次比较。旧连接关闭时不得删除新连接路由；节点崩溃后通过租约到期回收。

### 5.5 多节点消息与可靠控制事件

单节点阶段在模块内分发，多节点阶段通过连接目录路由。NATS 可承载可重建的实时流；丢失后必须显式重同步。不能因为 NATS Publish 成功就确认命令已经到达主机。

授权撤销、设备撤销和发行控制等事件通过 PostgreSQL control_outbox 与可靠消费处理；启用 JetStream 时也保留幂等、重试、离线节点重新拉取当前状态的机制。连接路由与 transient assistant 流不需要伪装为永久消息队列。

## 6. 插件、服务端与文件实现

### 6.1 Go 服务与 Relay

Control Plane、Relay、Files 和 Push 保持模块边界，可先同进程部署。REST 和 WSS 共用身份、资源解析和授权规则。

Relay 建连后绑定用户 / 设备或安装 / runtime，后续提交的 session_id 只能引用该主体获授权的资源。每个操作检查当前权限，已有订阅在权限撤销后取消。Origin、CORS、帧大小和限流属于附加限制，不作为身份认证。

读循环负责验证、配额和调度，写循环独占连接写入；队列有字节预算和优先级。慢消费者重同步、空闲超时、最大连接寿命和续期均可配置。

优雅关闭停止新连接，发 drain 提示并在有界时间内关闭。迁移节点时，手机按 request_id 查询 / 重试，插件按连接代次重接；旧节点无权清除新路由。

首期无服务端正文持久队列，主机不在线时返回 runtime.offline。后续如引入离线命令执行，必须同时设计正文存储、加密、撤权和命令过期策略。

### 6.2 REST API 范围

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | /v1/devices | 登记手机公钥并证明持有 |
| DELETE | /v1/devices/{id} | 撤销设备和密钥 |
| POST | /v1/enrollments | 插件发起短期绑定申请 |
| POST | /v1/enrollments/{id}/confirm | 手机用户确认，受本机 / 管理员后续确认约束 |
| POST | /v1/installations/challenge、/v1/installations/token | 主机密钥证明与短期连接凭据 |
| GET | /v1/hosts、/v1/runtimes | 已授权主机和运行实例 |
| PUT / DELETE | /v1/runtimes/{id}/grants/{user_id} | 修改或撤销运行实例授权 |
| GET | /v1/runtimes/{id}/sessions | 会话元数据；新鲜列表需要主机在线 |
| POST | /v1/realtime/tickets | 手机 WSS 一次性接入凭据 |
| GET | /v1/commands/{request_id}?device_id=... | 查询命令状态，不返回正文 |
| GET | /v1/approvals/{id} | 状态元数据；详情转发到当前在线主机 |
| POST | /v1/approvals/{id}/decision | 与 WSS 相同的签名决定处理入口 |
| POST | /v1/files | 申请已授权会话的上传 |
| POST | /v1/files/{id}/commit | 验证、扫描并更新状态 |
| GET | /v1/files/{id}/download | 重新授权后签发短期下载地址 |
| GET | /v1/app/releases/latest | 当前用户 / 设备适用的 APK 元数据 |

会话正文读取、创建、归档、提问等操作由统一命令 / 订阅层转发；如暴露对应 REST 入口，必须复用相同处理器，不能产生两套去重或审批逻辑。

统一响应包含 ok、data 或 error，以及 request_id。错误的 HTTP 状态码和业务 code 一致；日志只记录安全的元数据，不记录令牌、签名原始上下文或消息正文。

### 6.3 TokenWorks Remote Control 插件

建议模块：

```text
remote-control/
  dsh-client          封装当前 DSH 版本的 Session / Agent / 附件调用
  session-sync        快照、分页、投影、实时流与持久化位置
  command-journal     本地可靠接收、准入串行化、状态对账
  interaction-router  审批及用户提问的归属和挂起生命周期
  execution-policy    高风险规则、单次批准、最终工具 guard
  access              主体、资源、短期授权与撤销检查
  relay-connection    安装证明、出站连接、重连、限流
  file-transfer       对象下载 / 导出、校验和会话 receipt
```

插件使用实际 DSH 服务依赖，函数插件导出 name / inject / Config / apply，不混合默认导出。监听、注册、连接、定时器和挂起请求通过 ctx.effect / ctx.on 等受控生命周期清理。

| 移动动作 | DSH 接入 | 限制 |
| --- | --- | --- |
| list / create | sessionController.list / create | 创建限于已授权工作区和 preset；不能远程选择任意 cwd |
| submit | sessionController.prompt | 使用稳定 requestId、queue / steer；不绕过附件和队列准入 |
| history / subscribe | page / follow / projections | 转换为移动端明确支持的投影；不开放所有原始 Host 数据 |
| cancel | sessionController.cancel | 取消活动，与取消订阅不同；保留队列语义按原生接口处理 |
| model selection | modelCatalog / selectModel | 仅选择当前桌面可用目录中的模型 |
| upload | fileUploads.uploadStream → receipt → prompt | receipt 隶属目标会话，运行时重启后重新交接未消费 receipt |
| approval | Host approval/request 应答器 + 执行策略 | 远程 principal 不能直接绕过 owner 交互限制 |
| question | user-questions/request 及问题状态投影 | 区分仍可应答、已继续和过期；按原生接口提交答案 |

**会话生命周期**：协议不提供含糊的 session.close。unsubscribe 只取消手机订阅；cancel 请求停止活动；archive 按已验证的工作区 / 会话归档能力实现；释放 Agent 必须等待执行和后代清理。当前基线没有完全等价的通用 close API，M0 / M2 分别确认可用能力后才在握手中宣告。

**进程与所有权**：复用当前运行时，不为同一活动会话启动 SDK / ACP 子进程。对其他 writer 持有的会话报告不可用。子 Agent 按原生父子关系读取，不把任意子会话当作可独立接管的根会话。

**交互路由**：为远控管理的会话定义明确归属，默认每个请求只有一个终端应答器。不能用同级 listener 的偶然注册顺序决定手机、桌面或微信谁拥有最终权限。M0 验证 scoped listener / 组合配置能否满足要求；若公开扩展点不足，记录所需的最小企业核心适配，不能提前宣称严格审批完全由现有接口满足。

**退出与重载**：插件卸载、账号切换、失去运行实例所有权时，停止接收新命令、使挂起交互失效、终止订阅并关闭连接。重新接入生成新 epoch；普通网络重连保持当前活跃交互的状态规则，不能恢复已经失效的 Promise。

### 6.4 审批与普通问题的运行状态

审批的精确签名和最终执行约束见第 8 章。本节定义应用状态：

```text
审批：pending → allowed | denied | expired | cancelled | invalidated
问题：按原生投影区分 open / continued / settled，并记录移动端请求代次
```

挂起审批的回调由当前插件持有，本地数据库记录只用于审计和状态对账。运行时 / 插件重启后将旧 pending 置 invalidated；不能根据一条恢复记录重新执行原工具。

手机断网或进入后台时，请求可在有效期内保持等待，尚未取得合法决定就不会执行。主机失去控制连接、授权租约失效、调用 signal 取消或请求到期时，按策略取消该次审批并反馈不可用 / 拒绝；重连后不能复用已经结束的请求。

对于有原生 timed question 的场景，继续后的迟到答案必须走原生支持的后续消息路径，不能再次完成旧工具调用。计划确认和普通问答不产生任何工具批准令牌。

### 6.5 文件中转与附件交接

1. 用户为已授权会话登记文件名、大小、SHA-256，获取隔离区短期 PUT 地址。
2. 对象服务接收文件，commit 校验实际大小及内容 SHA-256，并执行扫描；HEAD 元数据不能代替实际内容哈希。
3. clean 状态后向当前 runtime 发送 file.offer；插件重新确认身份、会话和文件授权。
4. 插件流式下载、校验大小和摘要，交给 fileUploads.uploadStream，保存返回 receipt 与目标会话的临时映射。
5. 用户消息通过 receipt 引用文件；不能将任意本地路径当成已完成附件准入。
6. 运行时重启使旧 staged receipt 不可用；文件中转对象仍有效时重新执行附件交接，避免使用已过期 receipt。

从主机导出时，先验证该文件属于允许的会话 / 交付物 / 工作区范围，再通过底层文件能力读取；验证实际路径、符号链接和读取时的一致性。协议不开放任意 file.get / file.put，也不默认把上传内容覆盖进工作区。

| 项 | 初始配置建议 |
| --- | --- |
| 手机单文件上限 | 200 MB |
| 主机导出上限 | 2 GB；同时受会话和用户配额限制 |
| 地址有效期 | 上传 15 分钟、下载不超过 5 分钟 |
| 对象键 | 服务端生成的组织 / 会话 / 文件 UUID 路径，禁止客户端控制存储键 |
| 扫描失败 | 保持不可下载和不可交接 |
| 加密 | 对象静态加密，明确 KMS / 密钥配置；不能把产品默认能力等同于已配置密钥管理 |

预签名地址在到期前可能仍可用，敏感文件使用更短 TTL；需要即时撤销的下载必须经过鉴权代理或可撤销网关，不能承诺所有已签发 URL 都能立即收回。

### 6.6 推送与状态获取

推送只携带类型、approval_id / question_id、runtime_id 和有效期等最小引用，不携带参数、正文或文件内容。主机别名也按通知隐私设置决定是否展示。

收到推送后 App 重新验证身份和授权，向当前主机获取详情。主机离线、epoch 变化或请求结束时展示相应状态并禁用操作。服务端只有元数据时不能返回伪造的完整审批详情。

推送入队、厂商接受和用户设备已展示是不同状态。按设备支持的通道选择并重试，同一请求按 ID 去重；通知合并保留全部未处理请求的入口，不能按 session collapse 后漏掉多个审批。

## 7. Android 客户端

### 7.1 模块与状态所有权

```text
:app
:core:auth         OIDC、设备登记、凭据更新、账号切换
:core:crypto       Keystore、DPoP、审批签名、密钥轮换
:core:network      REST、WSS、续期、重连、帧与队列限制
:core:data         加密缓存、待发送命令、同步代次与对账
:core:ui           通用组件、主题、可访问性
:feature:hosts     主机 / runtime、绑定、授权、在线状态
:feature:sessions  会话列表、创建、归档
:feature:chat      历史、增量、queue / steer、中断
:feature:interaction  审批、普通提问、计划确认
:feature:files     文件上传、下载、交接进度
:feature:settings  设备、通知、隐私、日志导出、更新
:sync              推送和受系统约束的后台恢复
```

使用 Compose、MVVM 与单向数据流。会话缓存、实时 attempt、待发送命令和交互请求分别建模，不把屏幕上暂态文本当成已持久化历史。

minSdk 初始按 26 评估；targetSdk 与分发渠道政策在发布前确认。Photo Picker、SAF、BiometricPrompt 和密钥认证参数都要覆盖最低系统版本的兼容实现，不能直接复制高版本 API 调用。

### 7.2 连接与同步状态

```text
DISCONNECTED → CONNECTING → AUTHENTICATING → SYNCING → READY
      ▲              │               │          │        │
      └────────── BACKOFF / AUTH_REQUIRED / REVOKED ───────┘
```

READY 只表示当前连接已可处理授权操作，不表示所有会话历史已经加载。每个会话独立保存 runtime_epoch、sync_id、快照 cursor、persisted_through_seq、历史分页状态和 assistant attempt。

重连先完成身份、租约和同步，再恢复新的业务操作。未确认命令保持原 request_id；先查主机状态，不能因为界面看不到回复就生成新 ID 自动重发。

### 7.3 界面与命令反馈

| 页面 | 必需行为 |
| --- | --- |
| 登录 / 绑定 | 学校身份、本机和手机双方确认、密钥指纹、退出和重新登录 |
| 主机 / runtime | 展示具体安装实例、profile、在线状态、授权范围；不能把同机所有 profile 合并成同一权限 |
| 会话列表 | 显示缓存 / 在线数据区别、运行状态、可用操作和工作区范围 |
| 聊天 | 流式内容、工具状态、历史分页、排队和 steer；最终消息替换暂态 attempt |
| 命令状态 | 明确“手机待发送”“主机已保存”“Agent 已入队”“结果待确认”，提供对账和人工重试入口 |
| 审批 | 显示完整可核对的动作、实际参数、资源、有效期和风险；只提供拒绝 / 仅本次允许 |
| 提问 / 计划 | 展示原生选项、自由文本或计划内容；继续后的迟到答案采用对应原生语义 |
| 文件 | 上传、扫描、主机接收和附件可用分别展示；失败可恢复，不把上传完成显示成 Agent 已收到 |
| 设置 | 账号、设备撤销、通知渠道、缓存策略、脱敏日志、版本与更新 |

审批请求缺少完整上下文、详情已过期、主机离线或代次不匹配时禁用批准。完整参数过大时可分页查看，但签名必须对应完整内容摘要；不能批准界面无法核验的隐藏部分。

### 7.4 密钥、缓存与隐私

DPoP 和审批使用不同密钥。审批签名前按组织策略进行本地身份确认；本地生物识别不能替代 IdP 的 MFA / acr，服务端仍检查声明是否满足具体请求要求。

数据库使用经过验证的加密实现，token 和数据库密钥由 Keystore 保护。私钥不可导出、凭据和敏感缓存不进入系统备份；生物识别变更、密钥失效、重装和锁屏凭据变化均有明确的重新登记路径。

缓存按账号、组织、runtime 和会话隔离。登出或撤权后关闭订阅并清理受影响缓存；未联网设备无法即时接收删除指令，敏感缓存使用有限的离线访问租约并在恢复联网时重新检查。不能承诺对长期离线设备远程立即擦除。

按组织隐私策略启用 FLAG_SECURE、通知隐藏内容和脱敏日志；错误上报不携带正文、原始参数、token、JWS 或预签名 URL。

### 7.5 后台、推送与恢复

前台保持 WSS；无前台操作时按系统限制断开实时连接，依赖推送和恢复同步。只有用户明确启用且符合系统 / 分发政策时，才使用适当的前台服务。

WorkManager 和前台服务都不能保证设备无限期保持实时连接。针对目标 Android 版本和厂商验证后台限额、通知权限、Doze、进程回收和自动启动行为；不把固定 20 秒心跳当成后台可靠性承诺。

点击通知后重新获取当前交互状态，检查身份、授权、epoch 和有效期。系统或厂商没有送达能力时向用户明确展示通知能力状态。

### 7.6 APK 更新

发布元数据包含 version_code、SHA-256、包名、签名身份、下载地址、最低协议版本、灰度与更新说明。下载后验证内容和安装包签名身份，再通过系统安装流程更新。

APK 更新与桌面插件升级独立，服务端支持明确公布的客户端版本窗口。强制更新仅用于已有兼容政策规定的情形；必须先验证签名、回滚和失败恢复。企业 MDM 可作为组织分发渠道。

### 7.7 Android 验收

- [ ] 最低系统和主要目标系统上完成真实登录、绑定、聊天、审批、提问和文件交接。
- [ ] Wi-Fi / 移动网络切换、飞行模式、进程回收和重启后不会重复提交已经确认的命令。
- [ ] 缓存、实时增量和持久事件的展示符合 4.6；发生回退时无重复文本和错误完成标记。
- [ ] 多手机竞争审批只产生一个最终决定；迟到签名和旧通知不能操作新请求。
- [ ] 后台通知按目标机型分别验证，记录真实到达结果和系统限制。
- [ ] 生物识别取消 / 失败、密钥失效、撤权和切换账号均正确处理。
- [ ] 篡改 APK、错误签名及不适用的协议版本不能被作为正常更新接受。

## 8. 执行策略与安全设计

### 8.1 信任与执行范围

服务器端身份和设备证明用于防止未经授权的远程调用；内容签名用于防止中继改写审批决定。信任经过审核的 TokenWorks 运行时及已安装插件，不把本方案当成本机恶意插件或已失陷操作系统的隔离机制。

企业启用远控管理的会话及其受管后代，必须遵守明确的高风险规则。规则覆盖实际工具 / capability 执行路径；M0 核对 bash、PTC、脚本、嵌套工具和子 Agent 的执行范围。签名参数本身不冻结文件、仓库或环境状态；如规则依赖文件内容或 diff，需将相关版本 / 摘要纳入批准并在执行前核验。

### 8.2 审批请求的精确绑定

DSH 的 approval/request 提供 Agent、toolName、可选 callId、reason 和取消信号，没有保证完整 arguments。插件通过 tools/pre-execute 的执行上下文、tool/call 等受信任事实建立映射，不能用展示用 reason 还原参数。

缺少 callId 或精确参数的请求，必须有专门的 capability 适配提供实际执行单元及上下文；没有适配时返回不可用 / 拒绝，并明确记录能力缺口。

每次待批准的执行单元创建 execution_id；嵌套调用或重新发起的独立审批使用新 ID。上下文采用确定的类型与编码，至少包含：

| 字段 | 用途 |
| --- | --- |
| domain / protocol_version | 固定用途，防止跨协议复用 |
| host_id / runtime_id / runtime_epoch | 绑定主机、profile 和当前运行代次 |
| session_id / dsh_session_id | 绑定目标会话及其受信任映射 |
| approval_id / execution_id / call_id（可用时） | 绑定当前挂起请求和实际执行单元 |
| tool / capability / arguments | 描述真实动作，不使用虚构工具名或未经适配的参数格式 |
| workspace / resource facts | 已解析资源和必要的内容版本，按具体动作定义 |
| nonce / expires_at | 单次性和有效期 |
| policy_version / required_assurance | 固定适用规则及认证强度 |

上下文按 RFC 8785 JCS 编码并计算 SHA-256。Go、TypeScript、Kotlin 对字符串、数字、嵌套键和 Unicode 使用同一规范及同一份测试向量；输入数值范围和不支持类型在 Schema 中限制。普通 json.Marshal 或 stringify 不能作为完整规范的替代。[RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html)

### 8.3 审批决定的签名

手机先核对完整上下文与 binding，再用 approval_key 签署决定。签名载荷至少包含以下字段，decision 必须在签名覆盖范围内：

```json
{
  "domain": "dsh-mobile-ustc/approval-decision/v1",
  "approval_id": "77777777-7777-4777-8777-777777777777",
  "execution_id": "88888888-8888-4888-8888-888888888888",
  "runtime_id": "44444444-4444-4444-8444-444444444444",
  "runtime_epoch": "99999999-9999-4999-8999-999999999999",
  "session_id": "55555555-5555-4555-8555-555555555555",
  "binding_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "decision": "allow_once",
  "user_id": "11111111-1111-4111-8111-111111111111",
  "device_id": "22222222-2222-4222-8222-222222222222",
  "key_version": 1,
  "policy_version": 1,
  "nonce": "request-specific-random-nonce",
  "issued_at": "2026-10-23T08:00:00Z",
  "expires_at": "2026-10-23T08:03:00Z"
}
```

采用标准 JWS ES256 库，固定允许的算法、用途和 key ID。JWS payload 为上述规范化字节，signature 使用 JOSE 规定的编码；Android 平台产生的 ECDSA 编码经合适的库转换，不能把 DER / ASN.1 与 JOSE signature 混用。

批准与拒绝使用同一签名格式，decision 只允许 allow_once / reject。nonce 必须与原请求一致，决定的 expires_at 不得晚于请求截止时间。服务端和主机根据已登记且未撤销的密钥验签，不接受请求自行提供的任意验证公钥。时间比较采用明确的 UTC 格式和有限容差，认证强度按 IdP 的已配置映射判断，不对 acr 字符串做大小比较。

### 8.4 校验、一次性消费与执行顺序

1. 服务端验证当前用户、设备、公钥版本、资源归属、授权和认证强度，检查请求对应当前 pending 审批。
2. 服务端验证签名和有效期，在事务内持久保存决定接收审计；失败时不向主机转发可批准结果。
3. 插件核对当前 runtime_epoch、实际挂起回调、执行上下文摘要、当前策略和有效授权租约，再独立验签。
4. 插件在本地事务中比较 pending 状态并提交唯一决定；nonce 只在全部校验成功后消费。重复的同一决定返回原结果，冲突决定不能覆盖。
5. 插件将批准绑定到该执行单元，通过终端应答器返回 DSH 的 allowed-once；拒绝、取消、超时、不可用分别映射原生结果。
6. 工具执行前由 tools.guard 等适用的最终执行检查确认精确调用已经取得手机单次批准且未被撤销。guard 只能拒绝，不能把其他策略的拒绝改成允许。
7. 状态变化、取消信号、授权版本改变或上下文不一致时拒绝执行，记录结果并更新手机和控制面镜像。

前置 waterfall 用于挂起请求并取得决定，最终 guard 用于防止其他应答通道或 listener 顺序绕过手机批准。对不经过工具 guard 的底层 capability 路径，必须提供等价执行约束并在 M0 / 安全测试中证明；仅监听审批事件不算完成强制审批。

M0 必须验证现有桌面 / 微信 / 自动应答器无法让受管理的高风险调用在缺少手机批准时执行。若公开扩展点不足，明确列出企业核心所需改动并验证后才能宣布该能力可用。

### 8.5 超时、竞争和重启

审批有效期初始建议普通请求 10 分钟、高风险 3 分钟；实际时间受调用 signal 和任务生命周期约束，以较早截止为准。

多个获授权手机可以看到同一请求，只有一个合法决定能完成原子状态迁移。若策略要求多方审批，需另定义聚合规则，不能用多个并发 Promise 完成代替。

主机 / 插件重启、会话释放、执行取消、授权失效或上下文改变后，旧请求进入终态。恢复本地日志只用于清理和审计，不恢复旧执行回调，不自动重放工具。

### 8.6 审计与数据保护

| 事件 | 可记录的内容 |
| --- | --- |
| 登录、设备与绑定 | 身份内部 ID、设备、安装实例、时间、结果 |
| 授权与撤权 | 操作者、资源、动作范围、版本和变化结果 |
| 命令 | request_id、会话、长度、摘要、阶段、错误类别 |
| 审批 | approval_id、执行单元、binding、签名决定、签名密钥版本、最终结果 |
| 文件 | 文件 ID、会话、大小、摘要、扫描和交接结果 |
| 管理操作 | 操作者、对象、结果及必要元数据 |

服务端持久审计失败时，审批和授权类操作失败。主机在完成决定或发出持久接收回执前，先持久登记相应本地事实；后续结果通过 outbox 重试同步。外部副作用已经发生后的审计同步错误不能显示成“操作未执行”。

默认不记录聊天正文、完整参数、问题正文、token、预签名 URL 和密钥。审计 metadata 使用字段白名单，禁止把完整请求或异常对象直接序列化。审计访问、导出、归档和删除本身受权限管理。

传输使用受支持的 TLS 配置和系统证书验证；数据库、对象与备份启用明确配置的静态加密。若使用证书固定，需要备份 pin、轮换和恢复方案。

### 8.7 后续端到端加密

首期传输经过 TLS，但中继所在服务可以接触传输中的内容，不声明端到端加密。后续启用前，需要独立设计并验证设备认证、公钥替换防护、会话密钥分发、设备加入 / 撤销、重放、nonce 管理、历史共享、文件和恢复机制。

协议可预留新的加密 capability，但不能仅增加 payload.enc 字段就宣称具备 E2E，也不能把未经完整设计的 ECDH 片段当作可实施协议。启用 E2E 会改变调试、扫描和文件服务的能力，需要同步调整产品和运维要求。

## 9. 部署、容量与运维

### 9.1 按阶段部署

| 阶段 | 部署方案 | 扩容依据 |
| --- | --- | --- |
| M0 | 真实 TokenWorks dev 包、隔离的本地调试客户端 / 测试服务 | 验证接口及组合，无生产公网接入 |
| M1 / M2 | 模块化 Go 服务、PostgreSQL；接入 TokenWorks 现有学校 oidc-proxy；必要的对象存储稍后接入 | 少量真实设备和可恢复性数据 |
| M3 | 小规模高可用部署、对象存储、目标厂商推送 | 试点并发、文件和后台通知实测 |
| M4 | 多 Relay、共享连接目录、可靠控制事件、数据库及对象存储高可用 | 目标负载和故障演练通过 |

不预先固定 6 台 Relay、Redis Cluster、NATS 集群或 Kubernetes 规格。组织已有设施满足需求时可以复用；引入每个组件必须说明它解决的容量或故障问题、维护责任和成本。

### 9.2 容量模型

| 工作负载 | 初始估算 | 验证方法 |
| --- | --- | --- |
| 目标连接 | 5000 主机 + 5000 手机，额外验证至 12000 | 分别测试空闲、活跃和重连风暴 |
| 活跃会话 | 2000 作为压力场景 | 不代表长期平均在线活跃量 |
| 实时事件 | 每活跃会话每秒 20 个源帧时，源侧约 40000 帧 / 秒 | 分别记录源帧、合并后帧和客户端扇出 |
| 下行字节 | 由实际消息编码大小 × 合并后速率 × 订阅数计算 | 同时测峰值和稳定时段 |
| 数据库写入 | 命令、审批、授权、审计和状态检查点 | 实时 token 增量不逐帧写 PostgreSQL |
| 本地历史 | DSH 日志与附件留在主机 | 对长会话查询、缓存和磁盘占用单独测量 |
| 文件容量 | 5000 人 × 5 MB / 天 × 90 天约 2.25 TB 十进制原始数据 | 另外计入副本、纠删码、版本、扫描暂存和余量 |

历史、实时帧、审计和文件分别估算，不再用“每会话每天 300 条”推导整个系统的 token 流存储。每项估算都记录消息尺寸、活跃时长、扇出、保留期和测试版本。

节点数量由实测的连接、CPU、内存、带宽和故障余量确定。单个手机可以订阅多个会话，多设备也会增加扇出；不能把一条主机连接当成固定一条下行流量。

### 9.3 配置与密钥

服务地址、限流、队列字节预算、心跳、缓存、文件大小与 TTL 通过已验证配置管理。安全用途、签名算法白名单和协议不变量在协议中固定。

主机端配置随 TokenWorks 的签名配置和插件发行流程分发，受已有版本及防回滚规则约束。学校实际客户端配置和私钥不写入仓库。部署凭据由组织现有 secret 管理设施提供，证书和密钥轮换必须演练。

只有采用 Kubernetes 时才引入其 Secret / ConfigMap、cert-manager 和 GitOps 配置；这些不是应用协议或 M0 的前置依赖。

### 9.4 可观测性与 SLO

至少采集连接数、每主体队列字节、路由时延、重同步次数、命令各阶段延迟、未知结果数、持久化检查点失败、审批终态分布、授权租约和撤权传播延迟、文件扫描 / 交接延迟、推送展示结果。

关联 ID 贯穿手机、服务端和插件，记录 request_id、runtime、epoch、session、sync_id；日志避免记录正文和完整上下文。指标标签不直接使用无限基数的会话 / 用户 ID。

按 1.3 定义测量 SLO。网络不可达、主机休眠、用户等待和模型处理时间单独归因，不能混入路由时延。告警阈值以试点基线确定，不把设计目标当作已经达到的性能。

### 9.5 备份与恢复

| 数据 | 策略 | 初始恢复目标 |
| --- | --- | --- |
| 控制面 PostgreSQL | 加密备份、连续日志和恢复演练 | RPO 5 分钟、RTO 30 分钟；以演练确认 |
| 对象存储 | 按文件价值选择版本、冗余和备份 | 中转与长期文件分别定义，不默认双活 |
| Redis | 缓存 / 路由重建 | 不能依靠它恢复正文或命令 |
| 插件本地库和 DSH 日志 | 进程崩溃通过本地持久存储恢复；整机丢失另有用户备份方案 | 不声称控制面能重建全部会话 |
| 密钥与配置 | 组织设施保存服务密钥；设备私钥按重新登记路径恢复身份 | 备份恢复后先校验撤权和密钥版本 |

恢复旧数据库可能恢复曾经有效的授权。恢复后先同步身份状态、最新撤销记录并使旧连接凭据失效，不能直接把旧 grants 重新用于公网访问。

### 9.6 发行、灰度与回滚

插件代码、构建产物、实际 DSH / 企业核心版本和 TokenWorks 装配回执一起锁定。遵守目标仓库自己的提交、来源审计和发行流程；本计划不授权自动提交、推送、签名或发布。

首先验证 dev 包中的组合，再内部少量设备、20 人、200 人逐级试点，最后分批扩展到目标规模。每级至少检查身份、撤权、待确认命令、审批和升级兼容性。移动端、服务端和桌面插件各自独立灰度。

数据库和本地命令日志迁移采用可升级且已验证的方案。DSH 历史会话格式遵循原生迁移规则，不能为回滚覆盖旧代日志或让旧 writer 解释不兼容数据。回滚前检查数据和插件协议兼容性，存在不兼容时采用前向修复。

### 9.7 Runbook 摘要

| 现象 | 首先检查 | 处理原则 |
| --- | --- | --- |
| 主机离线 | TokenWorks 运行状态、账号、安装证明、租约和网络 | 不另起 SDK 进程抢占活动会话 |
| 手机卡在发送 | request_id 的主机日志和服务端状态 | 先对账，不以新 ID 自动重放 |
| 增量缺失 / 重复 | epoch、sync_id、attempt、revision 和持久 cursor | 获取替换快照，保留已持久历史 |
| 审批无效 | 回调是否仍挂起、授权版本、签名上下文和期限 | 旧审批失效，不恢复执行 |
| 撤权延迟 | outbox、控制广播、租约寿命和缓存 | 暂停敏感操作，修复传播和重新同步 |
| 上传后 Agent 未收到 | 扫描、主机下载、receipt 及消息引用 | 区分中转完成和附件已准入 |
| 大量连接重建 | 服务故障、续期、退避、路由代次和队列预算 | 控制重连风暴、保护身份与数据库服务 |

## 10. 测试与验证计划

### 10.1 证据分层

| 层级 | 能证明什么 | 不能代替什么 |
| --- | --- | --- |
| 静态源码审读 | 当前接口、调用链、限制和待适配点 | 可运行的发行组合 |
| 单元 / 模拟测试 | 编码、状态机、签名、取消和故障分支 | 真实 OIDC、模型、系统密钥和推送 |
| Loader 组合测试 | 插件在实际 cordis.yml / profile 下装配与卸载 | 企业打包产物和实机生命周期 |
| TokenWorks dev 包 | 插件与锁定企业核心、桌面会话、账户和其他插件共存 | 真实手机后台和大规模负载 |
| 真实端到端 | 手机、主机、模型服务和公网链路的实际结果 | 超出已测版本、机型和负载的能力 |
| 容量 / 故障测试 | 指定负载、拓扑和故障模型下的性能与恢复 | 未运行的生产可用性指标 |

所有报告记录实际命令、版本、设备、外部依赖、跳过项和未通过项。构建成功、mock 通过和代码中存在某能力不能写成实际交付验收通过。

### 10.2 M0 插件接入验证

- [ ] 在锁定的 TokenWorks dev 包中加载插件，确认依赖、peer 版本及 profile 配置。
- [ ] 列出并继续电脑上已有会话，远端调试入口与桌面观察到同一个 Agent。
- [ ] 创建会话、queue、steer、中断和模型选择符合实际原生行为。
- [ ] 快照、历史页、实时 attempt 与最终消息不重复，断线恢复可用。
- [ ] 一次真实工具审批可经远端完成；不批准、超时和取消时调用不执行。
- [ ] 指定高风险调用不能经桌面、微信、自动应答器或 listener 顺序绕过手机批准。
- [ ] 普通提问和计划确认能应答，timed question 的迟到答案正确处理。
- [ ] 插件卸载、重载、窗口关闭、应用退出和账号切换均完成资源清理。
- [ ] 记录需要的最小企业核心改动；未验证的接入点不标为已完成。

### 10.3 协议、签名与恢复

共享 golden 样例覆盖规范化 JSON、Unicode、数字边界、签名编码、帧限制、快照分片和恢复。TypeScript、Go、Kotlin 校验同一份输入输出，兼容检查覆盖旧客户端安全拒绝未知必需行为。

必须覆盖：decision 被修改、错误用途 / 主机 / runtime / epoch / session / 设备 / 密钥版本 / 策略、参数摘要变化、过期、重放、撤权、缺少精确调用上下文、多设备竞争、无效签名不消费 nonce。

命令故障点至少包括：Relay received 后断开、本地登记后断开、dispatching 前后崩溃、原生入队后回执丢失、flush 失败、重复并发提交、同 ID 不同内容、运行时重启和磁盘满。检查原命令保留、阶段正确、未知结果可见以及外部副作用不会被自动重放。

### 10.4 规模测试

| 场景 | 验证内容 |
| --- | --- |
| 单节点基准 | 从小负载到 3000 连接，记录空闲与活跃资源、TLS / 证明开销 |
| 集群目标 | 10000 连接并额外验证至 12000；检查路由、租约和授权 |
| 活跃会话 | 2000 个活跃会话、每会话每秒 20 个源帧，即约 40000 源帧 / 秒；分别统计合并后和扇出后的消息量 |
| 慢消费者 | 队列字节受控，控制事件和重同步语义有效，主机内存不随积压无限增长 |
| 节点故障 | Relay 故障、连接迁移、旧连接关闭、持久控制事件重放 |
| 重连与续期风暴 | 大量手机和主机恢复时，IdP、证明校验和数据库不过载 |
| 文件并发 | 上传、内容哈希、扫描、主机交接、配额和对象过期 |
| 混合业务 | 聊天、审批、撤权和文件同时进行时仍符合延迟与一致性目标 |

持续测试窗口、消息大小、合并窗口、扇出分布和完成阈值在测试报告中固定。隔离吞吐测试可使用测试签名和模拟模型，但需另运行真实身份和设备证明的负载样本；不能将绕过认证的结果当作全链路性能。

### 10.5 实机、故障与灰度验收

真实设备覆盖目标 Android 系统 / 厂商，主机覆盖 TokenWorks 目标发行的 Windows、macOS 和 Linux。至少验证休眠唤醒、账号切换、两台手机、多 profile、后台通知、主机 / 手机进程回收、安装升级和授权撤销。

放量前无未解决的越权、审批绕过、已确认命令丢失或错误自动重放。试点记录实际稳定性、路由和通知延迟；未满足目标时先修复或明确调整范围，再扩大规模。

## 11. 实施计划

按出口条件推进，不预设所有团队都必须先完成完整基础设施。各阶段交付包含代码、协议、验证记录和运行说明；下列复选框均为待完成事项。

### M0 · 真实 TokenWorks 接入原型

交付进程内插件骨架、隔离调试入口、源码 / 发行兼容矩阵、会话同步最小协议，以及审批和问题接入验证。使用真实发行组合，外部模型和身份条件未齐备时明确分别记录模拟与真实结果，不向生产公网开放调试入口。

出口：

- [ ] 当前桌面会话可从调试入口继续，桌面与远端共用同一个 Agent。
- [ ] 会话列表、发送、流式输出、历史、中断可用。
- [ ] 一次审批与一次普通提问走通；高风险执行约束和现有终端应答器共存方式已明确。
- [ ] 文件 receipt、归档、子 Agent 等后续接口的实际支持情况已记录。
- [ ] 企业核心的缺口、必要改动和 dev 包装配方式有可复核记录。
- [ ] 插件重载和应用退出能清理连接、监听器、定时器和挂起请求。

### M1 · 复用 SSO/OIDC、授权与可靠传输

交付 TokenWorks 现有学校 SSO/OIDC 的复用接入：电脑插件复用 oidcAccounts，Android 登记独立 public client，中转服务登记远控 resource / audience / scope。同步交付手机 / 安装实例登记、短期连接证明、资源授权、撤权、Go 服务及插件命令日志。所需配置和适配在现有 oidc-proxy 上验证，不新增 SSO 部署，不带未说明的降级上线。

出口：

- [ ] 现有 TokenWorks 登录状态可用于远控绑定；手机可使用同一学校 SSO 登录，中转只接受适用于自身资源的身份凭据。
- [ ] 两个用户 / 设备 / profile 的隔离测试通过。
- [ ] DPoP 或经明确评审的凭据绑定方案验证通过。
- [ ] request_id 去重、内容冲突、host_accepted 和 agent_queued 的持久性成立。
- [ ] 断网、崩溃、回执丢失和未知结果对账通过。
- [ ] 撤权窗口满足目标，控制面不可用时敏感操作失败关闭。

### M2 · Android 前台 MVP

交付登录、绑定、主机 / 会话列表、聊天、快照与分页、queue / steer、中断、签名审批、普通提问与计划确认、缓存和命令状态。

出口：

- [ ] 真机与真实 TokenWorks 完成完整前台链路。
- [ ] 原生 seq、持久 cursor 与实时 attempt 的恢复语义正确。
- [ ] 多设备审批、迟到请求、重启、撤权和账号切换均正确处理。
- [ ] 首期主机离线和离线历史边界获得产品验收。
- [ ] 20 人以内的内部前台试用完成，问题有跟踪记录。

### M3 · 文件、后台通知与试点

交付扫描后的文件中转、receipt 交接、受限导出、目标厂商推送、后台恢复、APK 更新和必要的高可用部署。

出口：

- [ ] 上传、扫描、主机接收、消息引用与下载全链路实机通过。
- [ ] 后台和主机离线时审批 / 提问的通知及状态展示正确。
- [ ] 200 人级试点覆盖目标机型、主机平台和多设备场景。
- [ ] 通知延迟、路由时延、资源占用、存储和成本有实测数据。
- [ ] 已确定多节点和容量组件的启用方案。

### M4 · 目标规模、硬化与分批放量

按测量结果部署多节点，完成混合负载、12000 连接、故障演练、安全测试、备份恢复、监控、发行和回滚验证。

出口：

- [ ] 10.4 的目标负载与故障场景通过，瓶颈和资源规格有记录。
- [ ] 审批、授权、签名和恢复无未解决的高严重性问题。
- [ ] 三平台桌面 dev 包和目标 Android 机型完成必要验收。
- [ ] 备份恢复、撤权与升级失败恢复演练通过。
- [ ] 从试点分批放量至 5000 台主机 / 5000 部手机，保持监控并设置回退阈值。

### 后续增强

完整离线历史、独立常驻运行时、端到端加密、iOS / Web、多组织身份联邦分别作为新能力评估。每项明确新增的数据、身份、生命周期和测试责任，不把预留字段等同于实现。

### 11.1 工作分解

| 工作包 | 责任 | 主要依赖 |
| --- | --- | --- |
| 真实发行组合、DSH 接口和企业核心适配 | 插件 / 桌面、Agent 团队 | 锁定核心和 dev 包 |
| 移动协议、状态、签名与 golden 样例 | 架构、插件、后端、Android | M0 接入结果 |
| 现有学校 SSO/OIDC 接入、设备 / 安装登记、授权与撤权 | 后端、安全、身份服务团队 | 现有 oidc-proxy 的客户端、资源配置与实际链路验证 |
| 插件命令日志、同步、执行约束 | 插件 / 桌面 | 实际会话和工具扩展点 |
| Go 控制面与中继 | 后端 | 身份方案与协议 |
| Android 前台与密钥 | Android | M1 协议及模拟服务 |
| 文件与推送 | 后端、Android、插件 | M2 稳定链路 |
| 来源审计与三平台发行 | 桌面、SRE、负责人 | 企业发行装配流程 |
| 安全、性能、故障和实机验收 | QA、安全、SRE、各端 | 对应阶段可运行交付 |

人员可兼任，按阶段安排，不用预定的大型平台运维配置阻塞 M0。身份、企业核心和真实设备资源需指定负责人。

### 11.2 完成定义

1. 操作在约定的实际组合中可用，有对应协议 Schema 和样例。
2. 有必要的行为、取消、恢复和负面测试，覆盖适用的越权和审批路径。
3. 有 Loader 组合、相关 dev 包和实机证据；模拟结果单独说明。
4. 数据、密钥、授权和错误的处理责任明确，指标及日志不泄露内容。
5. 升级、版本兼容、撤权和故障处理有可操作说明。
6. 来源审计和相关仓库规定的构建 / 检查通过。
7. 未验证项写入验收记录，不以文档中的计划或源码中的接口存在代替完成。

## 12. 风险登记册

| ID | 风险 | 影响 | 缓解与验证 | 负责人 |
| --- | --- | --- | --- | --- |
| R1 | 企业核心与上游接口不同 | 插件不能按原设想装配 | M0 使用锁定核心和实际 dev 包，记录最小适配 | 插件 / 桌面 |
| R2 | 多个终端或扩展路径绕过审批 | 未批准的高风险调用执行 | 明确交互归属、精确绑定、最终执行检查及实际绕过测试 | 插件、安全 |
| R3 | 接收回执被误认为持久或工具结果 | 消息丢失或重复执行 | 分阶段回执、flush、稳定 request_id、未知结果处理 | 插件、后端 |
| R4 | 重启后复用旧审批或实时位置 | 错误执行、重复内容 | runtime_epoch、终态、替换快照；不恢复旧回调 | 插件、Android |
| R5 | 现有 SSO 的资源配置或客户端适配未完成 | 身份不能直接用于远控 | M1 核对 oidc-proxy 的 client / resource / audience / scope、插件请求范围及生产配置，补齐接入 | 后端、身份团队 |
| R6 | Android 后台受限 | 通知迟到或不可达 | 目标机型实测、厂商推送、正确恢复和能力展示 | Android |
| R7 | 原始事件或日志意外包含正文 | 服务端数据边界失效 | 明确移动投影、字段白名单、日志与数据库抽查 | 各端、安全 |
| R8 | 慢消费者 / 重连风暴 | 内存和身份服务过载 | 字节预算、退避、取消订阅、混合负载测试 | 后端、插件 |
| R9 | 文件授权与实际路径脱节 | 越权导出或写入 | 会话 receipt、允许导出范围、实际路径与内容校验 | 插件、后端 |
| R10 | 单机磁盘损坏与备份缺失 | 主机历史不可恢复 | 明确本地数据边界、用户备份和恢复目标 | 桌面、运维 |
| R11 | 容量估算口径不一致 | 规格与成本失真 | 分开源帧、合并、扇出、持久写入和文件冗余 | SRE、后端 |
| R12 | 更新与会话格式不兼容 | 旧版本无法安全恢复 | 原生迁移规则、兼容矩阵、dev 包与升级演练 | 桌面、QA |

## 13. 附录

### A. 命令回执示例与 Schema

以下为计划中的移动协议示例，不是 DSH 原生接口返回值。

```json
{
  "v": 1,
  "type": "command.receipt",
  "request_id": "66666666-6666-4666-8666-666666666666",
  "session_id": "55555555-5555-4555-8555-555555555555",
  "runtime_epoch": "99999999-9999-4999-8999-999999999999",
  "payload": {
    "state": "host_accepted",
    "payload_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  }
}
```

```json
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "title": "CommandReceiptV1",
  "type": "object",
  "required": ["v", "type", "request_id", "session_id", "runtime_epoch", "payload"],
  "properties": {
    "v": {"const": 1},
    "type": {"const": "command.receipt"},
    "request_id": {"type": "string", "format": "uuid"},
    "session_id": {"type": "string", "format": "uuid"},
    "runtime_epoch": {"type": "string", "format": "uuid"},
    "payload": {
      "type": "object",
      "required": ["state", "payload_sha256"],
      "properties": {
        "state": {"enum": ["host_accepted", "agent_queued"]},
        "payload_sha256": {"type": "string", "pattern": "^[0-9a-f]{64}$"}
      },
      "additionalProperties": false
    }
  },
  "additionalProperties": false
}
```

协议实现使用启用 format 断言的校验器，并为每种回执和事件提供独立 Schema。host_accepted 仅表示插件本地持久接收；agent_queued 才表示 DSH 准入和对应持久化检查点已经成功。

### B. 安全与资源解析检查清单

| 操作 | 必须解析的归属 | 必须验证 |
| --- | --- | --- |
| 会话列表 | runtime → installation → host → org | 当前用户、设备、runtime grant |
| 会话读写 / 归档 | session → runtime → installation → host → org | 动作、范围、状态和授权版本 |
| 审批详情 / 决定 | approval → session → runtime → installation → host → org | 当前 epoch、精确上下文、设备密钥、签名、有效期、决策状态 |
| 文件交接 / 下载 | file → session → runtime → installation → host → org | 上传者 / 接收者权限、对象状态、大小、摘要、期限 |
| 安装连接 | runtime → installation → host → org | 安装密钥证明、撤销状态、profile 和连接代次 |
| 授权修改 | runtime → installation → host → org | 操作者管理权限、组织成员关系、审计和撤权传播 |

### C. 本地开发依赖建议

```yaml
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: dsh_remote
      POSTGRES_USER: dsh_remote
      POSTGRES_PASSWORD: local-development-only
    ports:
      - "127.0.0.1:5432:5432"
    volumes:
      - remote_pg:/var/lib/postgresql/data

volumes:
  remote_pg:
```

该示例仅用于本地隔离开发，不是生产凭据或完整部署清单。实现时锁定实际镜像版本 / 摘要，迁移通过独立步骤应用；测试身份服务或 Redis、对象存储随对应阶段加入。学校真实 token 和配置不写入 compose。

### D. 配置项类别

| 类别 | 内容 |
| --- | --- |
| 身份 | 可信 issuer、audience、客户端注册、DPoP / 设备证明方案、允许的回调 |
| 连接 | 服务入口、心跳、连接租约、重连、帧大小、字节队列预算 |
| 授权 | 动作与资源范围、租约有效期、撤权窗口、工作区 / 会话映射 |
| 交互 | 请求有效期、必要认证强度、风险策略版本、详情限制 |
| 文件 | 上传 / 下载上限、配额、扫描、TTL、对象和密钥管理 |
| 数据 | 本地缓存和命令日志限额、加密、清理、审计归档 |
| 发行 | 精确基线、插件版本、协议兼容窗口、灰度和签名身份 |

### E. 术语

| 术语 | 说明 |
| --- | --- |
| DSH | DeepSeek Harness；本方案接入 0.2.1-rc.2 基线 |
| TokenWorks | 学校机构发行版；现有桌面会话、身份和模型所在应用 |
| Remote Control 插件 | 加载进当前 TokenWorks 进程的远控接入组件 |
| Host Bridge | 旧方案中的独立进程；首期职责已并入插件内部模块 |
| installation / runtime | OS 用户下的安装身份，以及某 profile 的稳定运行实例身份 |
| runtime_epoch | 当前运行 / 交互代次，用于使旧请求和暂态失效 |
| native_seq | 原生会话日志事件位置，从 0 开始 |
| persisted_through_seq | 已达到持久化检查点的事件位置，空日志为 -1 |
| sync_id | 一次快照和后续订阅的身份 |
| request_id | 手机命令在重试和对账期间保持不变的身份 |
| receipt | 文件准入后由 DSH 返回、绑定目标会话的临时引用 |
| binding | 精确审批上下文的规范化摘要 |
| DPoP | 将 OAuth token 的使用约束到客户端密钥持有者的机制 |
| JWS | 承载审批决定签名的标准格式 |

### F. 实施参考

- [DSH 架构](/home/Enthusjast/reference/deepseek-harness/docs/architecture.md)：profile、插件、会话和事件扩展点。
- [DSH 工具执行](/home/Enthusjast/reference/deepseek-harness/packages/core/tools/src/index.ts:1135)：最终 guard 与执行策略。
- [DSH 历史与实时流](/home/Enthusjast/reference/deepseek-harness/packages/api/session-controller/src/history.ts:120)：快照与后续流适配。
- [DSH 持久化检查点](/home/Enthusjast/reference/deepseek-harness/packages/core/session/src/index.ts:1307)：flush 的参与者和完成语义。
- [TokenWorks 插件装配](/home/Enthusjast/reference/tokenworks/edition/distribution.json:24)：发行清单中的本校插件。
- [RFC 9449](https://www.rfc-editor.org/rfc/rfc9449.html)：DPoP。
- [RFC 8785](https://www.rfc-editor.org/rfc/rfc8785.html)：JSON 规范化。
- [RFC 7515](https://www.rfc-editor.org/rfc/rfc7515.html)、[RFC 7518](https://www.rfc-editor.org/rfc/rfc7518.html)：JWS 与算法编码。
- [PostgreSQL 16 分区文档](https://www.postgresql.org/docs/16/ddl-partitioning.html)：分区唯一约束的限制。

源码链接指向本次审读的本机检出；移交其他环境时，应同时提供对应仓库和本节基线提交。版本变更后重新核对接口、限制与引用。

## 14. 执行摘要

1. 插件进入当前 TokenWorks 进程，复用已有会话、模型与工具；M0 用真实发行组合确认接入，记录企业核心适配缺口。
2. 首期把连接和可靠性职责放在插件内部，Go 服务先模块化部署；目标规模仍为 5000 台主机 / 5000 部手机。
3. 复用 TokenWorks 现有学校 SSO/OIDC（oidc-proxy）：电脑插件复用登录服务，Android 和中转接入同一身份体系；不新增 SSO，远控设备凭据、业务权限与模型 Key 分别管理。
4. 会话历史留在主机，服务端保存元数据；手机离线缓存与文件中转有明确的数据和保留策略。
5. 命令回执、持久会话位置和实时 assistant 增量分别建模；host_accepted、agent_queued 和执行完成具有不同含义。
6. 审批签名覆盖决定和精确执行上下文，校验后原子消费；运行时重启使旧审批失效，指定高风险调用受最终执行检查约束。
7. 文件通过扫描后的对象中转和 DSH 会话 receipt 交接；普通提问、计划确认与工具审批分别处理。
8. 按 M0 接入、M1 身份和可靠性、M2 Android 前台、M3 文件和后台、M4 规模放量推进，依据实测选择基础设施和资源规格。

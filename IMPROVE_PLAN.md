# TokenWorks Mobile 架构方案: 三项目对比、v1 审查与 v2 复用 dsh 协议

> 审查对象: `Enthusjast/tokenworks-mobile`, 分支 `codex/m0-host-prototype`, HEAD `c0948f9`.

> 参考项目: `saya-ch/dsh-mobile`, `liguobao/ds-harness-remote`, `ccch1mneyyy/dsh-Go`.

> 审查方式: 静态阅读源码、协议、文档与测试; 未运行构建、Go 测试或 Android instrumentation.

## 1. 执行摘要

### 1.1 一句话定位

| 项目 | 一句话定位 |
| --- | --- |
| TokenWorks Mobile | 原生 Android 客户端 + Go Relay + 进程内 dsh Host 插件, 目标是可审计、可撤销、带签名审批的移动远控平台 |
| dsh-mobile | Host 插件把 DSH Web UI 反向代理成移动 HTTPS 页面, Android 用 WebView 打开, 重在低成本和完整 UI 复用 |
| ds-harness-remote | Host 插件把 DSH 的 Typert RPC/流/事件封装进 Noise 端到端加密通道, 走 LAN → P2P → TURN → Relay, 重在网络与隐私 |
| dsh-Go | Flutter 原生 Android App, 可在手机本地跑 PRoot + Node + DSH, 也可通过 SSH 或 DSH Remote 控制电脑, 重在移动体验和本地运行 |

### 1.2 总体判断

**TokenWorks 的 v1 在身份、审批、命令幂等和同步状态机上比三个参考项目更完整, 但它自己写了一套协议, 导致 Host, Relay, Android 三端重复维护会话/命令/审批/文件语义.**

2026-11-07 的方案变更是: **复用 dsh 的 Typert 协议, 不再维护自定义 v1.** 手机直接说 dsh 的 `/api`, `/api/remote.mux` 和 `$events`; Host 插件退化为认证 + 传输适配器; Go Relay 退化为通用 HTTP/WS 隧道. 这样保留原生 UI 和自有安全能力, 同时消除协议漂移.

v2 仍需补的四个维度:

1. **网络拓扑**: 没有局域网直连、P2P/TURN 或隧道回退; 当前远程路径依赖 Relay + PostgreSQL.
2. **隐私边界**: 没有端到端加密; Relay 在路由时必须持有明文帧.
3. **生态 UI**: 原生 App 只渲染自有页面, 第三方 dsh 插件的自定义 UI 无法出现.
4. **多端与产品完整度**: 只有 Android; 没有终端、通用文件预览、语音、Web/PWA/iOS.

**建议: 先做 v2 核心 parity, 再补 LAN/E2E/终端/插件 UI.** 如果目标是尽快上线个人工具, 直接用 dsh-mobile 或 ds-harness-remote 更划算; 如果目标是做差异化的安全原生远控产品, TokenWorks 值得继续, 但必须收窄范围、明确验收.

### 1.3 如果只做六件事

1. **v2 contract + codegen**: 定义方法/流白名单, 生成 Kotlin/TS 类型, 锁住 dsh 版本.
2. **Host 适配器**: `createPeer` + `sharedFetch` + `wireStream` + `$events`, 不再写会话/命令/审批语义.
3. **Android 核心 dsh 客户端**: unary/stream/events; 先跑通 list/follow/prompt/cancel/create/model.
4. **审批与提问桥**: `$events` waterfall + `$events/result`; 可选外层签名.
5. **Relay 通用隧道**: 或先用 Tailscale/Cloudflare/FRP; Relay 不再解析 dsh 帧.
6. **删除 v1**: parity 后清理自定义协议、Relay 解析器和 Android v1 客户端.

这六件事做完, 项目会从"有两套协议的原型"变成"协议统一、可日常使用的产品". 许可证、LAN、E2E、终端/预览、Web/PWA 可以并行或随后做.

---

## 2. 四个项目分别是什么

### 2.1 TokenWorks Mobile (v1 现状 + v2 目标)

v1 拓扑:

```text
Android (Compose) ──WSS/HTTPS──▶ Go Relay + PostgreSQL + 账号网页
                                      │  (元数据/授权/路由)
                                      ▼
                              dsh Host 插件 (Cordis)
                                      │
                                      ▼
                         sessionController / agents / tools / approvals
```

v2 目标拓扑:

```text
Android (Compose) ──dsh Typert /api + /api/remote.mux + $events──▶
   LAN 网关 或 Relay 通用隧道
        │
        ▼
Host 插件 (认证 + sharedFetch/wireStream 适配)
        │
        ▼
dsh Connection / Typert Gateway / Agent / Tools
```

特点:

- Host 插件与 dsh 同进程, 不启动第二个 Agent 或第二个 writer.
- v1 手机说自定义 v1 协议; **v2 改为直接说 dsh 的 Typert 协议**, 见第 6 节.
- v1 Relay 负责身份、授权、路由、命令回执; v2 Relay 只做通用 HTTP/WS 隧道.
- Android 原生 Compose, 有会话、创建、fork、归档、模型、审批、提问、文件、变更、通知、应用更新等页面.
- 文档和测试覆盖很广, 但 `docs/development-status.md` 明确说 M0–M4 的复选框没有因为代码存在而完成, Android 也未按目标设备重新验收.
- v2 的核心取舍: 协议复用 dsh, 原生 UI 保留; 设备配对/签名审批/审计保留; 自定义会话/命令/审批协议删除.

### 2.2 dsh-mobile

拓扑:

```text
Android WebView / 手机浏览器 / iOS PWA
              │ HTTPS + WSS
              ▼
       dsh-mobile 插件自带 HTTPS 网关
       (配对/设备令牌/隧道/移动页面模块)
              │ 反向代理
              ▼
          dsh Web UI + dsh Host
```

特点:

- 插件把 DSH 的 HTTP 与 WebSocket 反向代理成独立的移动 HTTPS 入口.
- Android 是 WebView App, iOS/iPadOS 用 Safari 或主屏幕 Web App.
- 支持局域网、Tailscale、cpolar、cloudflared、自建 FRP、自有反代、托管 Caddy, 以及实验性 iroh.
- 复用 DSH Web UI, 所以第三方插件 UI 基本免费获得; 但小屏布局仍取决于插件自身, 插件还提供移动页面模块、WebSocket 路径放行和 `/mobile` 对话式定制.
- 有设备配对、配对记录、可达性检测、撤销、Keystore、私有 CA、Web Push.
- 安全模型是"配对设备视为完全受信任"; 没有应用层 E2E, 网关能看到明文.
- 许可证 Apache-2.0, 代码可复用.

### 2.3 ds-harness-remote

拓扑:

```text
Android (RN) / iOS / Web / DSH Desktop
              │ Noise IK E2E
              ▼
     LAN → P2P → TURN → Relay (同一份密文)
              │
              ▼
       Host 插件 (Typert Remote tunnel)
              │
              ▼
       DSH / Codex / ACP workspaces
```

特点:

- Host 只出站, 不需要公网端口或端口映射.
- 业务流量用 `Noise_IK_25519_ChaChaPoly_SHA256` 在客户端与 Host 之间加密, 服务端只协调连接和看必要的网络元数据.
- 传输顺序 `LAN → P2P → TURN → Relay`, 失败自动回退, 每条路径都保持同一份 Noise 密文.
- 隧道的是 Typert 的 unary、stream 和 `$events`, 所以能复用 DSH 原生权限流和原生界面.
- 客户端覆盖 Android 原生、iOS TestFlight、Web、DSH Desktop; 还有 Codex/Cursor/Claude 等 ACP 后端.
- 支持远程终端、只读文件预览、文件传输、远程工具注入.
- 插件包 MIT; 仓库根目录未标注统一许可证, 复用代码前要确认.

### 2.4 dsh-Go

拓扑:

```text
Flutter Android App
  ├─ 本机模式: PRoot Ubuntu + Node.js + DSH
  └─ 远程模式: SSH 或 DSH Remote (Dart 客户端)
```

特点:

- 把整套 DSH 运行环境塞进 Android, 不需要 Root/Termux.
- 远程模式通过 SSH 或 DSH Remote 连电脑; 远程协议参考 ds-harness-remote.
- 原生 Flutter UI, 支持聊天、图片、语音输入、工具审批、提问、网页模式、分享/导出、主题/桌宠.
- 提供原生插件 SDK、教程和示例, 第三方插件可以给手机做原生工具卡、表单和面板.
- 开启无障碍权限后可以读取屏幕、点按和输入, 让 Agent 操控手机.
- 许可证 AGPL-3.0, 改造和再发布有明确约束.

---

## 3. 架构对比

### 3.1 维度矩阵

| 维度 | TokenWorks v1 | dsh-mobile | ds-harness-remote | dsh-Go |
| --- | --- | --- | --- | --- |
| 产品形态 | 原生 Android + Go Relay + Host 插件 | Host 插件 + WebView/PWA | Host 插件 + RN 原生/多端 | Flutter + 可选本地 runtime |
| Agent 位置 | 电脑 | 电脑 | 电脑 | 手机本地或电脑 |
| 截流点 | `sessionController.follow` | 反向代理整个 Web UI | Typert unary/stream/`$events` | DSH Web API / DSH Remote |
| 手机协议 | 自定义 v1 | DSH Web HTTP/WS | Typert over Noise | DSH Web API / Remote Protocol |
| 网络路径 | 必须有可达 Relay | LAN + 多种隧道 | LAN → P2P → TURN → Relay | SSH / DSH Remote |
| 端到端加密 | 无 | 无 (HTTPS 网关) | 有 (Noise IK) | DSH Remote 有; SSH 仅传输层 |
| 审批 | 签名审批 + 原生 guard | DSH 原生审批 | DSH 原生审批 | 原生 UI 审批 |
| UI 策略 | 原生 Compose | WebView 复用 Web | 原生 RN + 复用 Web | Flutter 原生 |
| 第三方插件 UI | 无 | 完整 Web 插件 | 完整 Web/原生 | 原生插件 SDK |
| 终端 | 无 | 有 (Web) | 有 | 未明确 (网页模式) |
| 文件预览 | 图片预览 + 上传/导出; 无通用文件/PDF 预览 | 有 (Web) | 只读预览 + 文件传输 | 部分 (网页/分享) |
| 语音输入 | 无 | 无 | 无 | 有 |
| 多端 | Android | Android + iOS PWA | Android/iOS/Web/Desktop | Android |
| 部署成本 | Go + PG + OIDC + TLS | npm 插件 | npm 插件 + 可选 Relay | APK + 可选远程 |
| 许可证 | 无 | Apache-2.0 | 插件 MIT | AGPL-3.0 |
| 主要优势 | 身份/审批/幂等/同步 | UI 完整/部署最简单 | 隐私/网络/多端 | 移动体验/本地运行 |
| 主要短板 | 网络/隐私/生态 UI | 非原生/无 E2E | 代码不能随意复用 | AGPL/Android only |

TokenWorks 列描述 v1 现状. v2 目标: 手机协议改为 dsh Typert; 截流点改为 Host 适配器 + `typertGateway`; Relay 改为通用隧道; 网络仍可选 LAN/隧道; 部署成本可降到"插件 + 可选隧道". 详见第 6 节.

### 3.2 通信与截流点

**TokenWorks v1** 的截流点是正确的: `sessionController.follow({ address, assistantStream: true })` 拿到的是 Web UI 同源的会话流, 包含 opening snapshot、持久事件和逐 token assistant chunk. 这比监听 `session/event` 再自己拼流可靠得多.

但 v1 没有复用 dsh 的 Typert 协议和 `/api/remote.mux`, 而是自定义 v1. 好处是手机 DTO 更紧凑、可控; 代价是重复实现了会话、分页、增量、审批、文件、导出、变更等一大套语义, 且这些语义会随 dsh 版本漂移.

**v2 的改法**: 手机直接说 dsh 的 Typert 协议; Host 适配器用 `sharedFetch`/`wireStream` 转发; 不再自定义会话/命令/审批/文件协议. 截流点从"插件自己打包"变成"dsh Gateway 原生输出".

**dsh-mobile** 不截流, 直接反向代理整个 Web UI; 协议永远和 Web UI 一致, 但手机端只是 WebView, 无法做原生交互.

**ds-harness-remote** 把 Typert 的 unary、stream、`$events` 整体隧道化; 手机说的是 dsh 协议, 但外面套了 Noise 和 P2P/Relay. 这是"协议复用 + 网络增强"的路线.

**dsh-Go** 在本机模式直接跑 DSH, 远程模式用 DSH Web API 或 DSH Remote; 它更像一个完整的移动客户端, 而不是 Host 插件 + BFF.

### 3.3 网络与信任边界

| 项目 | 信任边界 | 谁能看到正文 | 谁能发起连接 |
| --- | --- | --- | --- |
| TokenWorks v1 | Relay 是可信中间人; 设备/主机双向认证; 签名审批 | Relay 可以看到明文帧 | Host 出站到 Relay; 手机连 Relay |
| TokenWorks v2 | 设备/主机认证; Relay 可选 E2E | Relay 默认可见; E2E 后不可见 | Host 出站; 手机连 LAN/Relay |
| dsh-mobile | 配对设备视为完全可信; 网关是本机插件 | 本机网关可以看到明文 | 手机连电脑网关或隧道 |
| ds-harness-remote | 设备身份 + 同账号成员 + Noise E2E | 只有两端; 服务端只看密文 | Host 只出站; P2P/TURN/Relay |
| dsh-Go | 本机模式全在本机; 远程取决于 SSH/DSH Remote | 本机模式仅本机; DSH Remote 为 E2E | SSH 或 DSH Remote |

TokenWorks 的认证和审批最强, 但隐私边界最弱; ds-harness-remote 正好相反, 网络和隐私最强, 审批复用 DSH 原生流程.

### 3.4 UI 与扩展

| 项目 | 主 UI | 第三方插件 UI | 移动定制 |
| --- | --- | --- | --- |
| TokenWorks | 原生 Compose, 自有页面 | 无 | 无 |
| dsh-mobile | WebView 复用 Web UI | 基本免费获得 | `/mobile` + 移动页面模块 + CSS/JS |
| ds-harness-remote | RN 原生 + Web | 基本免费获得 | 原生 + Web 混合 |
| dsh-Go | Flutter 原生 | 原生插件 SDK | 原生工具卡/表单/面板 |

TokenWorks 的原生 UI 对核心会话体验更可控, 但 dsh 生态的插件 UI 完全进不来. 这是一个战略性 gap: 随着 dsh 插件生态增长, 用户会希望在手机上看到插件提供的工具卡、设置页、面板和可视化.

### 3.5 部署与运维

| 项目 | 最小部署 | 远程部署 | 运维复杂度 |
| --- | --- | --- | --- |
| TokenWorks | Host 插件 + 本机 socket (仅调试) | Go Relay + PostgreSQL + 账号服务 + TLS | 高 |
| dsh-mobile | npm 插件 | 插件 + 可选隧道/Caddy/FRP | 低到中 |
| ds-harness-remote | npm 插件 | 插件 + 可选 Relay 账号 | 低到中 |
| dsh-Go | APK | 可选 SSH/DSH Remote | 低 |

TokenWorks 的最小可用远程路径需要一整套服务端. 这对个人用户和学校小规模部署都是门槛; 也是它和三个参考项目最大的产品差异.

### 3.6 许可证

| 项目 | 许可证 | 影响 |
| --- | --- | --- |
| TokenWorks | 未标注 | 默认保留所有权利; 别人不能合法复用, 你也不能安心复用别人的代码 |
| dsh-mobile | Apache-2.0 | 可商用/修改/分发, 需保留声明 |
| ds-harness-remote | 插件 MIT; 仓库根未统一标注 | 插件代码可复用, 但要逐目录确认 |
| dsh-Go | AGPL-3.0 | 衍生分发需 AGPL, 名称/素材另有约束 |

v2 后 TokenWorks 的部署可以降为"Host 插件 + 可选 LAN 网关/隧道"; Go Relay + PostgreSQL 不再是唯一路径. 详见第 6 节.

---

## 4. 你的项目做对了什么

> 以下是 v1 的优点. v2 保留原生 UI、设备配对、撤销、加密缓存和审计思路; 命令日志和签名审批改为可选的 Typert 扩展或 `$events/result` 外层签名.

在列问题之前, 先明确哪些东西不要动:

1. **截流点正确**: `sessionController.follow(..., assistantStream: true)`, 不是手工拼事件.
2. **同步状态机正确**: `sync_id` + opening cursor + 固定切点分页 + `checkpoint`, 分页只能引用仍打开的订阅; fragment 有顺序、字节和节点数上限.
3. **命令幂等与对账正确**: `host_accepted` / `dispatching` / `agent_queued` / `outcome_unknown` 分层, 不明确的结果不盲目重放; Android 用 SQLCipher 持久化 outbox.
4. **审批链路强**: 手机签名 + 原生 allowed-once + 一次性消费 + 执行 fence, 比"手机点允许就执行"强很多.
5. **Android 缓存与撤销正确**: 加密 transcript cache、lease 过期、运行时撤销后清理本地数据.
6. **Relay 授权严格**: 每个订阅、分页、内容帧都做授权检查; 这是安全优点, 也是性能问题的来源.
7. **文档与测试意识强**: 文档、契约、golden、集成脚本都很完整, 这在社区项目中很少见.

下面所有建议都建立在"保留这些核心"的前提上.

---

## 5. v1 审查: 问题与教训

> 本节保留 v1 的代码级审查, 用于记录问题和教训. 5.0 表新增 "v2 状态" 列: 标为"消失"的问题会随 v1 协议层删除而消失, 标为"保留"的问题在 v2 中仍需处理. v2 的具体方案见第 6 节.

### 5.0 问题总表

| 编号 | 级别 | 问题 | 主要修复 | v2 状态 | 阶段 |
| --- | --- | --- | --- | --- | --- |
| P0-1 | 高 | Relay 每帧查 DB | 授权缓存 + touch 降频 | 消失 (Relay 变隧道) | Phase 0 |
| P0-2 | 高 | 没有 E2E | Noise IK + 帧加密 | 保留 | Phase 2 |
| P0-3 | 高 | 未签名模式只拦 3 个工具 | 工具风险策略默认拒绝 | 转为 dsh 原生策略 | Phase 0 |
| P0-4 | 高 | dsh 内部耦合与版本锁 | adapter + 版本矩阵 | 保留 (改用公共面) | Phase 1 |
| P1-1 | 中 | 每次操作全量扫描会话 | Sync cut 保存 native ID | 消失 | Phase 1 |
| P1-2 | 中 | `ids` 全局 Map 竞态 | SessionResolver | 消失 | Phase 1 |
| P1-3 | 中 | 3 秒超时报成离线 | 独立 timeout 错误码 | 消失 | Phase 0 |
| P1-4 | 中 | 大记录无法同步 | projection 截断/分页 | 保留 (手机侧截断) | Phase 0 |
| P1-5 | 中 | 没有 LAN/P2P | LAN 网关 + 多路径 | 保留 | Phase 1 |
| P1-6 | 中 | 第三方插件 UI 缺失 | WebView 插件槽 | 保留 | Phase 2 |
| P1-7 | 中 | 缺终端/文件预览/语音 | PTY + 沙箱预览 | 保留 | Phase 2 |
| P1-8 | 中 | 只有 Android | Web/PWA 先行 | 保留 | Phase 3 |
| P1-9 | 中 | 没有许可证 | 选择并补齐 LICENSE | 保留 | Phase 0 |
| P2-1 | 低 | 主机重连无终止认证状态 | 状态机 + 终止分类 | 保留 | Phase 0 |
| P2-2 | 低 | Android 不支持系统代理 | OkHttp WebSocket | 保留 | Phase 1 |
| P2-3 | 低 | 固定 10 秒 settlement | 可配置宽限 | 消失 | Phase 1 |
| P2-4 | 低 | 范围膨胀/验收滞后 | 冻结 M0 + feature flag | 保留 | Phase 2 |

### P0-1 Relay 每帧都查数据库

**现象**

Relay 在转发每一个 `assistant.chunk` 之前, 都会调用:

- `TouchRuntimeConnection`: 一次 `UPDATE runtimes SET last_seen_at = ...`.
- `AuthorizeRealtimeSessionRead`: 一次多表 JOIN 的 `SELECT`.

证据:

- [`services/remote/internal/relay/sessions.go:233`](../services/remote/internal/relay/sessions.go#L233)
- [`services/remote/internal/relay/sessions.go:237`](../services/remote/internal/relay/sessions.go#L237)
- [`services/remote/internal/store/session_access.go:33`](../services/remote/internal/store/session_access.go#L33)
- [`services/remote/internal/store/runtime_connections.go:170`](../services/remote/internal/store/runtime_connections.go#L170)

`assistant.chunk` 是 `isSessionContent` 中的内容帧, 所以每产生一个 token chunk, 就会走一次这条路径. 按每秒 20–50 个 chunk 计算, 单会话就是每秒 40–100 次 DB 往返.

**影响**

- 数据库 CPU/IO 和连接池成为第一瓶颈.
- 每个 token 的转发延迟被 DB 往返放大.
- 多会话并发时, Relay 很难达到目标规模.
- 现有容量文档也明确说目标负载未验收.

**改进方案**

1. **订阅时授权, 帧级只做内存检查.**
   - 在 `sessionSubscription` 中保存 `grantVersion`, `workspaceID`, `sessionID`, `deviceID`.
   - 订阅、分页时调用 `AuthorizeRealtimeSessionRead`; 每帧只检查订阅仍存在、Host 连接匹配、`sync_id` 匹配、序号连续.
   - 授权结果加短 TTL 缓存 (例如 30 秒), key 为 `(deviceID, grantVersion, sessionID)`.
2. **撤销事件驱动失效.**
   - 复用现有 `revocation.go` / `control/consumer.go`: 设备撤销、grant version 变化时, 删除缓存并 `dropSubscription`.
   - 不要让每帧 DB 查询承担撤销发现.
3. **Touch 降频.**
   - `liveConnection` 增加 `lastTouch` 和 `touchInterval` (建议 10–20 秒).
   - 用后台 ticker 批量 touch 活跃连接, 或只在心跳时 touch.
   - touch 失败仍然要关闭连接, 但不要每帧写.
4. **保留必要的内存不变量.**
   - 订阅存在、Host 连接是当前连接、`sync_id` 一致、`request_id` 匹配、序号连续; 这些检查很便宜, 继续每帧做.
5. **指标.**
   - `relay_auth_cache_hit_total`, `relay_auth_cache_miss_total`, `relay_session_db_seconds`, `relay_touch_skipped_total`.
6. **验收.**
   - 100 个并发会话, 每会话 50 chunk/s, 持续 10 分钟.
   - p99 内容帧转发延迟 (不含网络) < 5 ms.
   - DB QPS 相比现状下降 90% 以上.
   - 撤销测试: 流进行中撤销设备/grant, 下一帧前订阅必须被丢弃.

**代码落点**

- `services/remote/internal/relay/server.go`: `liveConnection` 增加 `lastTouch`, `grantCache`.
- `services/remote/internal/relay/sessions.go`: 订阅时授权, 帧级改为内存检查.
- `services/remote/internal/relay/revocation.go`: 失效缓存和订阅.
- `services/remote/internal/store/session_access.go`: 可保留现有 SQL, 但只在新订阅/缓存 miss 时调用.

---

### P0-2 没有端到端加密

**现象**

README 说 Relay "不保存聊天正文", 但 Relay 在路由时必须持有明文帧; `docs/development-status.md` 明确把端到端加密列为后续增强.

证据:

- [`README.md:4`](../README.md#L4)
- [`docs/development-status.md:75`](development-status.md#L75)
- [`docs/session-read-protocol.md`](session-read-protocol.md): "Bodies and assistant chunks are never written to the Relay database or audit log", 但没有说 Relay 不能读.

**影响**

- 被攻破或恶意的 Relay 可以读取 prompt、代码、工具输出、文件内容.
- 对公网服务, 这是最大的信任边界; 对自托管可信 Relay, 可以接受, 但必须在文档和 UI 里说清楚.
- 与 ds-harness-remote 的 Noise E2E 相比, 隐私能力差一个层级.

**排期说明**

本文把 E2E 放在 Phase 2, 是因为它需要设备密钥、握手、帧格式、迁移和兼容策略, 改动面大. 但如果你的 Relay 部署在公网或由第三方托管, E2E 应该提前到 Phase 1; 如果 Relay 只在学校内网/自托管可信环境运行, 可以先用"明确信任模型 + TLS + 严格授权"过渡.

**改进方案**

1. **设备身份密钥.**
   - Android 在 Keystore 生成 X25519 或 P-256 设备密钥; Host 使用安装密钥.
   - 账号目录只发布公钥/指纹, 私钥不出设备.
2. **Noise IK 握手.**
   - 复用 ds-harness-remote 的 `Noise_IK_25519_ChaChaPoly_SHA256` 思路.
   - 手机与 Host 在 Relay 之上完成握手; Relay 只看到 `client_id`, `host_id` 和密文.
3. **帧加密.**
   - 只加密业务 payload, 保留最小路由字段 (订阅、epoch、序号).
   - 每个连接独立密钥; 重连重新握手; 单调序号 + 重放窗口.
   - 长度填充或分片固定, 减少流量分析.
4. **审批与文件.**
   - 审批 JWS、文件 manifest、终端输出全部放进密文; Relay 不再需要理解内容.
   - 文件传输用同一密钥流, 分块加密; 保留现有 hash/lease 校验.
5. **兼容与迁移.**
   - 新增 capability `e2e.v1`; 老客户端显式走 `tls-only`, UI 显示"Relay 可见内容".
   - 新客户端默认要求 E2E.
6. **验收.**
   - Relay 进程抓包/日志/DB 中不能出现 prompt 或工具输出标记.
   - 重放、乱序、密钥轮换、设备撤销测试.
   - 多设备并发和断线重连测试.

**代码落点**

- 新增 `host/remote-control/src/e2e/` (Noise 握手、帧加密、密钥存储).
- 新增 `android/core/crypto/E2eSession.kt`.
- `services/remote/internal/relay/` 只负责转发密文, 不解析业务 payload.
- 协议文档新增 `e2e.v1` 一节.

---

### P0-3 未签名模式只拦 3 个工具

**现象**

配置默认:

```ts
highRiskTools: ['bash', 'pwsh', 'run_code']
```

`InteractionRouter.protected()` 只检查工具名是否在这个列表里. 因此 `edit`、`write`、`patch`、某些 MCP 工具等有副作用的调用, 在未签名模式下不会经过手机审批.

证据:

- [`host/remote-control/src/index.ts:81`](../host/remote-control/src/index.ts#L81)
- [`host/remote-control/src/interaction-router.ts:74`](../host/remote-control/src/interaction-router.ts#L74)

**影响**

- 手机 token 被盗后, 可以直接修改文件, 不需要用户点允许.
- 对"远程控制电脑上的 Agent"这个产品定位, 这是不可接受的默认值.
- 签名模式可以解决, 但它需要额外的 provider/authority 集成; 很多用户会先用未签名模式.

**改进方案**

1. **工具风险分类.**
   - 新增 `tool-policy.ts`, 按工具名 + schema + 能力分类:
     - `read`: 只读、检索、展示.
     - `mutate`: 写文件、改配置、创建/删除.
     - `execute`: shell、代码执行、子进程.
     - `network`: 对外请求、下载、webhook.
     - `secret`: 凭据、密钥、账号.
2. **默认策略.**
   - `mutate` / `execute` / `secret` 默认必须审批.
   - `read` 可以不审批.
   - 未知工具默认按 `execute` 处理, 拒绝或要求审批.
3. **未签名模式.**
   - 只允许 `read`; 所有有副作用工具都必须手机审批.
   - 如果用户确实要放宽, 只能在 Host 本机配置里显式允许, 手机不能改.
4. **签名模式.**
   - 所有受管理调用默认都需要签名审批; `requiresApproval` 只能作为可信集成提供的例外.
5. **验收.**
   - 对 `edit`、`write`、`patch`、`bash`、`run_code`、未知 MCP 工具分别测试: 没有审批记录时不能执行.
   - 桌面端和手机端同时在线时, 审批顺序和拒绝路径要测试.

**代码落点**

- 新增 `host/remote-control/src/tool-policy.ts`.
- `interaction-router.ts` 的 `protected()` 改为 `policy.requiresApproval(exec)`.
- `index.ts` 的 `highRiskTools` 保留为兼容项, 但默认由策略覆盖.
- 桌面 UI 显示当前策略和每个工具的类别.

---

### P0-4 dsh 内部耦合与版本锁

**现象**

插件注入 13 个 dsh 服务:

```ts
inject = ['sessionController', 'sessions', 'agents', 'tools', 'approval',
  'userQuestions', 'sessionProjections', 'workspaceRegistry', 'fileUploads',
  'attachments', 'fs', 'loader']
```

还依赖:

- `api-session/added`, `api-session/removed`, `api-session/status`, `api-session/activity`
- `domain/changed` + `workspaceDomainSpec` / `workspaceDomainState`
- `Reflect.get(filesystem, symbols.original)`
- `sessionController.inspect`、`resolveAgent`、`sessions.flush` 等偏内部的方法.
- 版本锁 `0.2.1-rc.5`.

证据:

- [`host/remote-control/src/index.ts:57`](../host/remote-control/src/index.ts#L57)
- [`host/remote-control/src/dsh-client.ts:168`](../host/remote-control/src/dsh-client.ts#L168)
- [`host/remote-control/src/dsh-client.ts:247`](../host/remote-control/src/dsh-client.ts#L247)
- [`README.md:24`](../README.md#L24)

**影响**

- dsh 仍是 developer preview, 这些内部面随时可能变.
- 每次升级都要改插件; 无法同时支持多个 dsh 版本.
- `symbols.original` 和 `workspaceDomain*` 属于实现细节, 不是稳定契约.

**改进方案**

1. **Adapter 层.**
   - 新增 `src/dsh-adapter/`, 定义 `SessionPort`, `EventPort`, `WorkspacePort`, `FilePort`, `ApprovalPort`.
   - 每个 dsh 版本一个实现; 业务代码只依赖接口.
2. **优先公共面.**
   - 用 `session/event` + `sessionProjections.onChanged` 替代 `api-session/*` 能替代的部分.
   - 用 `sessionController.list/inspect` 的公开签名; 不直接读 `session.seq` 以外的内部字段.
   - 把 `symbols.original` 收敛到一个文件, 其他代码不直接使用.
3. **版本探测.**
   - 启动时检查 `dshVersion`、必需服务、必需事件; 不满足时给出明确错误码 `dsh.unsupported`, 而不是运行到一半崩溃.
   - 新增 `compat.ts` 版本矩阵.
4. **CI 矩阵.**
   - 至少测试当前版本和下一个 RC; contract tests 跑两遍.
5. **验收.**
   - 升级 dsh 时, 只改 adapter 文件即可通过测试; 业务层不需要改.

---

### P1-1 每次操作都全量扫描会话目录

**现象**

`resolve()` 每次都调用 `list()`:

```ts
async resolve(id, signal) {
  await this.list(signal)
  const nativeId = this.ids.get(id)
  ...
}
```

`list()` 对每个可访问会话执行 `controller.inspect()`. `session.page` 也会走 `resolve()`, 所以翻一页历史会扫描全部会话.

证据:

- [`host/remote-control/src/dsh-client.ts:218`](../host/remote-control/src/dsh-client.ts#L218)
- [`host/remote-control/src/dsh-client.ts:90`](../host/remote-control/src/dsh-client.ts#L90)
- [`host/remote-control/src/session-sync.ts:120`](../host/remote-control/src/session-sync.ts#L120)

**影响**

- 会话越多, 每个 prompt/cancel/page 越慢.
- 冷会话的 `inspect` 可能触发磁盘读取.
- 多设备并发时, 目录扫描会放大 I/O.

**改进方案**

1. `SessionSync` 的 `Sync` cut 里保存 `nativeSessionId`、`workspaceId`、`nativeCwd`.
2. `page` 直接使用 cut 里的 native ID, 不再 `resolve()`.
3. `prompt`/`cancel`/`archive` 用 single-flight resolver:
   - 先查 `mobile -> native` 缓存;
   - 缓存 miss 时只 `inspect` 目标会话, 验证 cwd 仍在允许工作区;
   - 不遍历全部会话.
4. 目录快照仍由 `sessionCatalog` 维护, 但它只服务列表页, 不参与每个命令.

**验收**

- 1000 个会话时, 翻页和 prompt 延迟与会话数量无关.
- 冷会话只 inspect 一次.

---

### P1-2 `ids` 全局 Map 的并发竞态

**现象**

`DshClient.ids` 是全局 Map:

- `list()` 用 `this.ids = ids` 整体替换.
- `create()` 和 `sessionCatalog()` 局部 `set`.
- `resolve()` 在 `await list()` 之后读取.

两个异步操作交错时, 后完成的 `list()` 可能覆盖 `create()` 刚写入的新会话映射.

证据:

- [`host/remote-control/src/dsh-client.ts:106`](../host/remote-control/src/dsh-client.ts#L106)
- [`host/remote-control/src/dsh-client.ts:164`](../host/remote-control/src/dsh-client.ts#L164)
- [`host/remote-control/src/dsh-client.ts:361`](../host/remote-control/src/dsh-client.ts#L361)

**影响**

- 新会话创建后立即 prompt, 偶发 `session.not_found`.
- 多设备并发 create + list 时更容易复现.

**改进方案**

1. 去掉"整体替换 Map"的做法, 改为合并 + 版本号.
2. 或者把解析逻辑收敛到 `SessionResolver`:

```ts
class SessionResolver {
  private readonly map = new Map<string, { native: SessionId; cwd: string; seenAt: number }>()
  private readonly inflight = new Map<string, Promise<SessionId>>()

  async resolve(mobile: string, signal: AbortSignal): Promise<SessionId> {
    const cached = this.map.get(mobile)
    if (cached && Date.now() - cached.seenAt < 5_000) return cached.native
    const existing = this.inflight.get(mobile)
    if (existing) return existing
    const task = this.inspectAndVerify(mobile, signal).finally(() => this.inflight.delete(mobile))
    this.inflight.set(mobile, task)
    return task
  }
}
```

3. `list()` 只更新 `seenAt` 和 `cwd`, 不删除其他条目; 只有明确的 `unavailable` 才删除.

**验收**

- 并发 create + list + prompt 压力测试无 `session.not_found`.
- 两个设备同时创建和发送消息, 结果正确.

---

### P1-3 3 秒超时被报成 `runtime.offline`

**现象**

`relay-sessions.ts` 对模型目录和消息预览使用 `AbortSignal.timeout(3000)`; 超时后 `signal.aborted` 为真, 代码把错误码设为 `runtime.offline`.

证据:

- [`host/remote-control/src/relay-sessions.ts:218`](../host/remote-control/src/relay-sessions.ts#L218)
- [`host/remote-control/src/relay-sessions.ts:233`](../host/remote-control/src/relay-sessions.ts#L233)
- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionModels.kt:196`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionModels.kt#L196)
- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionMessageActions.kt:267`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionMessageActions.kt#L267)

**影响**

- 一次慢预览 (大文件、慢磁盘、远程 FS) 会让手机以为主机离线.
- UI 可能切到 OFFLINE, 用户看到错误的重连状态.
- 3 秒对消息预览明显偏短.

**改进方案**

1. 新增独立错误码 `request.timeout`, 不要复用 `runtime.offline`.
2. 在 `relay-sessions.ts` 区分:
   - 订阅/Host 连接失效 => `runtime.offline`.
   - `AbortSignal.timeout` => `request.timeout`.
3. 超时时间可配置:
   - 模型目录 5–10 秒.
   - 消息预览 15–30 秒.
   - 工作区变更 15–30 秒.
4. Android 把 `request.timeout` 映射为 `TRANSIENT_ERROR`, 不切 OFFLINE.
5. 验收: 慢预览返回 `request.timeout`, 会话保持 READY.

---

### P1-4 单条大记录会让整个会话无法同步

**现象**

`projectEvent` 原样带出 `tool/call` 的 `arguments` 和 `tool/result` 的 `message.content`. Relay 的逻辑 payload 上限是 2 MiB; 单条超过 2 MiB 时:

- `encodeSessionFrames` 抛 `protocol.snapshot_too_large`.
- Android 把 `maxMessages` 减到 1 仍然失败, 最终进入 ERROR.

证据:

- [`host/remote-control/src/projection.ts:44`](../host/remote-control/src/projection.ts#L44)
- [`host/remote-control/src/relay-sessions.ts:64`](../host/remote-control/src/relay-sessions.ts#L64)
- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt:252`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt#L252)

**影响**

- 一个大的 `write` 调用或大的工具输出就能让会话不可读.
- 用户只能回电脑看, 手机端无法恢复.

**改进方案**

1. 在 projection 层做有界投影:
   - `tool/call.arguments`: 超过 64 KiB 时替换为 `{ truncated: true, bytes, preview }`.
   - `tool/result` 内容: 超过 64 KiB 时保留首尾摘要, 中间用 `[已截断 N 字节]`.
   - `assistant/message` 如果异常大, 也按同样规则.
2. 增加按需读取接口:
   - `session.record` 或 `session.value` 只读单条记录, 支持范围/分块.
   - 手机端点击"查看完整内容"时再拉取.
3. Android `SessionRecord.parse` 接受 `truncated` 字段, UI 显示截断提示.
4. 验收: 3 MiB 的 tool result 不影响会话打开和后续事件; 只显示截断提示.

---

### P1-5 没有局域网直连/P2P, 强制 Relay + PostgreSQL + OIDC

**现象**

远程路径必须有一个可达的 Go Relay; LAN 直连、P2P、TURN、隧道都没有. 对比:

- dsh-mobile: LAN + Tailscale/cpolar/cloudflared/FRP/iroh.
- ds-harness-remote: LAN → P2P → TURN → Relay.
- dsh-Go: SSH / DSH Remote.

**影响**

- 同一 Wi-Fi 下也要跑一整套服务端, 部署门槛高.
- 没有公网服务器时无法使用.
- 与三个参考项目相比, 产品可用性差距最大.

**改进方案**

1. **短期: 插件自带 LAN HTTPS/WSS 网关.**
   - 类似 dsh-mobile: 插件启动独立 HTTPS 服务, 绑定具体 LAN IP.
   - 二维码配对, 设备令牌, mDNS 发现.
   - Android 连接顺序: LAN → Relay.
   - 协议复用现有 v1, 不改手机主流程.
2. **中期: P2P/隧道.**
   - 引入 WebRTC DataChannel 或 iroh, Noise E2E.
   - 失败回退 TURN/Relay.
   - 连接选择器统一在 Android `ConnectionSelector` 中.
3. **长期: 多路径.**
   - LAN / P2P / TURN / Relay 统一为 `Transport` 接口; 每条路径都承载同一份密文.
4. **验收.**
   - 同一 Wi-Fi, 不启动 Go/PG, 手机可以完整使用.
   - 断开 LAN 后自动切 Relay, 会话不丢.

**代码落点**

- 新增 `host/remote-control/src/lan-gateway.ts`.
- 新增 `android/core/network/LanDiscovery.kt`、`ConnectionSelector.kt`.
- 复用现有 pairing/device token 逻辑, 不要新造一套.

---

### P1-6 第三方 dsh 插件 UI 无法进入手机

**现象**

Android 只有固定页面: 会话、创建、审批、提问、文件、变更、通知等. 没有 WebView, 也没有插件 UI 协议. 第三方 dsh 插件新增的工具卡、设置页、面板在手机上完全不可见.

对比:

- dsh-mobile: WebView 直接复用 Web UI, 第三方插件基本免费获得.
- ds-harness-remote: Web + RN 原生混合, 也能复用.
- dsh-Go: 提供原生插件 SDK.

**影响**

- 随着 dsh 插件生态增长, 手机会越来越"残缺".
- 用户为了一个插件 UI 可能不得不回到电脑.
- 这是原生 UI 路线的最大生态代价.

**改进方案**

方案 A (推荐先做): **WebView 插件槽回退**.

- 主会话仍用原生 UI.
- 对第三方插件提供的 slot/panel, 用同源 WebView 渲染 DSH Web UI 的对应区域.
- 认证复用现有设备令牌/cookie, 严格同源, 禁止任意 URL.
- 优点: 快速获得生态 UI; 缺点: WebView 复杂度、主题/性能/安全.

方案 B (长期): **移动插件 SDK**.

- 定义 `mobile-slot.json` + JSON Schema + Compose renderer.
- 插件声明 slot、输入、动作; 手机用原生组件渲染.
- 类似 dsh-Go 的 plugin-kit.
- 优点: 原生体验; 缺点: 生态需要适配, 工作量大.

建议: v1 用 A 覆盖 80% 场景, v2 再对高频插件提供 B.

**验收**

- 一个第三方 dsh 插件 (例如 sidebar/terminal 卡片) 能在手机上显示并交互.
- WebView 不能读取设备密钥, 不能访问非白名单 Origin.

---

### P1-7 缺少终端、文件预览和语音

**现象**

当前只有文件上传/导出, 没有终端、PDF/图片预览、语音输入. 对比:

- dsh-mobile: Web UI 自带终端、文件面板、预览.
- ds-harness-remote: 远程终端、只读预览、文件传输.
- dsh-Go: 终端/网页模式、语音输入、图片.

**影响**

- 移动端只能"聊天", 不能完成"看一眼终端/预览一个文件"的高频动作.
- 与"随身控制台"的定位不匹配.

**改进方案**

1. **终端.**
   - 复用 dsh `ctx.terminals` / `subprocess`; 新增 `terminal.open/input/resize/output/close` 帧.
   - 走现有 session stream 或独立 stream; 有字节/频率/生命周期上限.
   - Android 用 xterm.js + 沙箱 WebView, 或原生 terminal emulator.
   - 终端运行权限与 Agent 审批分开, UI 明确提示.
2. **文件预览.**
   - 复用现有 export/handoff; 只读.
   - 图片/PDF/文本/代码; 沙箱 WebView, 禁止外部 Intent 和脚本.
   - 大文件分块 + 取消.
3. **语音输入.**
   - Android `SpeechRecognizer` 或云端 STT; 只把转写文本发给 Host.
   - 隐私开关; 不上传音频, 除非用户明确选择.
4. **验收.**
   - 打开终端、输入命令、缩放、断线恢复.
   - 预览 20 MB PDF 不 OOM, 可取消.
   - 语音转写失败有明确错误.

---

### P1-8 只有 Android

**现象**

没有 iOS、Web、PWA. 对比:

- dsh-mobile: Android + iOS/iPadOS PWA.
- ds-harness-remote: Android + iOS Beta + Web + Desktop.
- dsh-Go: Android (iOS 计划中).

**影响**

- 用户换设备就流失.
- 无法在电脑浏览器里临时接管.
- 学校/企业场景通常需要 Web 入口.

**改进方案**

1. **先做 Web/PWA.**
   - 复用现有 v1 协议, 用 TypeScript 写一个轻客户端.
   - 浏览器 WSS + fetch; 复用同一套认证/配对.
   - 成本最低, 覆盖面最大.
2. **再做 iOS.**
   - 如果团队会 React Native, 用 RN 共享逻辑; 否则 SwiftUI + 共享协议 codegen.
   - 不要直接复制 Android UI.
3. **协议 codegen.**
   - 用 JSON Schema 或 Protobuf 定义 v1; 生成 Kotlin/Swift/TS 类型, 减少三端漂移.
4. **验收.**
   - Web 端能完成 list/prompt/follow/approval 四个核心流程.
   - 与 Android 同时在线, 互不干扰.

---

### P1-9 没有许可证

**现象**

仓库没有 LICENSE 文件, GitHub API 的 license 为 null. 这意味着默认保留所有权利, 别人不能合法复用, 你也不能把 Apache/MIT 代码混进来而不处理.

对比:

- dsh-mobile: Apache-2.0.
- ds-harness-remote: 插件 MIT.
- dsh-Go: AGPL-3.0.

**影响**

- 无法作为开源项目被采用.
- 学校/企业法务无法评估.
- 复用参考项目代码时有合规风险.

**改进方案**

1. 选择许可证:
   - 想被广泛采用: Apache-2.0 或 MIT.
   - 想保持衍生开源: AGPL-3.0.
   - 想保留商业闭源: 双许可或明确 proprietary, 但不要假装开源.
2. 加 `LICENSE`, `THIRD_PARTY_NOTICES.md`, 保留参考项目声明.
3. 如果复用 dsh-mobile 代码, 保留 Apache 声明; 如果复用 dsh-Go, 遵守 AGPL.
4. 验收: 法务/社区能清楚知道可以做什么, 不可以做什么.

---

### P2-1 主机重连没有终止认证状态

**现象**

`run()` 捕获所有错误并无限重连; `HostReconnectBackoff` 最长 60 秒. `auth.proof_invalid`、runtime 撤销、`protocol.unsupported` 也会一直重试; `status()` 只有 connecting/connected/reconnecting/stopped.

证据:

- [`host/remote-control/src/relay-client.ts:866`](../host/remote-control/src/relay-client.ts#L866)
- [`host/remote-control/src/relay-backoff.ts:3`](../host/remote-control/src/relay-backoff.ts#L3)

**影响**

- 用户看到永久"重连中", 不知道需要重新登记.
- 撤销后仍然定时打服务器.

**改进方案**

- 增加 `HostRelayStatus`: `enrolled`, `connecting`, `connected`, `reconnecting`, `auth_required`, `revoked`, `unsupported`, `stopped`.
- 对 `auth.*`, `installation.*`, `device.*`, `access.revoked` 做终止分类.
- 桌面 UI 显示明确操作: 重新登录/重新登记/查看诊断.

---

### P2-2 Android 不支持系统代理

**现象**

`LoginHttp.relaySocket()` 只支持 `Proxy.Type.DIRECT`; 检测到系统代理直接 `network.proxy_unsupported`. `SessionReader` 把它归为不可重试 ERROR.

证据:

- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/LoginHttp.kt:77`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/LoginHttp.kt#L77)
- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt:262`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt#L262)

**影响**

- 校园/企业 Wi-Fi 常要求 HTTP 代理; 这些网络无法使用.
- 用户只会看到 `network.proxy_unsupported`.

**改进方案**

- 用 OkHttp WebSocket (支持代理) 替代手写 socket; 或实现 HTTP CONNECT.
- 保留 TLS pinning/hostname verification.
- 代理错误做成可解释提示, 允许切换网络后重试.

---

### P2-3 固定 10 秒 settlement 超时会触发重连

**现象**

`SessionWindow` 对 held settlement 固定 10 秒; 超时后 `SessionReader` 的 timeout 变成 0, 抛 `SessionDataException` 并重连.

证据:

- [`android/core/session/src/main/kotlin/io/github/enthusjast/tokenworks/session/SessionWindow.kt:128`](../android/core/session/src/main/kotlin/io/github/enthusjast/tokenworks/session/SessionWindow.kt#L128)
- [`android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt:409`](../android/core/network/src/main/kotlin/io/github/enthusjast/tokenworks/network/SessionReader.kt#L409)

**影响**

- 慢主机/大 tool result 会造成无谓 resync.
- 弱网下可能反复重连.

**改进方案**

- 做成可配置 (建议 30 秒), 或先标记 "settling" 并给更长宽限.
- 只有持续 gap 或协议错误才断线.

---

### P2-4 范围膨胀与验收滞后

**现象**

分支名是 `m0-host-prototype`, 但代码有 1556 个文件、约 22.6 万行、502 个 Go 文件、259 个 Kotlin 文件, 覆盖 Relay、账号、审批、文件、导出、推送、应用更新、Android 全套. `docs/development-status.md` 明确说 M0–M4 复选框没有因为代码存在而完成, Android 未按目标设备验收.

**影响**

- 维护成本高, 新贡献者难进入.
- 安全审计面大.
- "原型"与"产品"边界不清, 容易把未验收能力当成已完成.

**改进方案**

- 冻结 M0 范围: 只保留 list/create/prompt/follow/approval/question/cancel + 最小文件.
- 其他能力移到 M1/M2, 用 feature flag 关闭.
- 每个能力必须有: 协议文档、威胁模型、测试、目标设备验收、回滚方案.
- 发布页只列已验收能力.

---

## 6. v2 方案: 复用 dsh Typert 协议

### 6.1 决策记录

#### 决策

TokenWorks Mobile 不再维护自定义 v1 语义协议. 手机直接使用 dsh 的 Typert 协议:

- 一元调用: `POST /api/<namespace>/<method>`, body `{ args }`, 结果 `{ ok, value }` 或 `{ ok, error }`.
- 流: WebSocket `/api/remote.mux`, 帧 `open/item/end/cancel` 与 `item/error/end`.
- 事件: Gateway 内部逻辑流 `$events`, 帧 `ready/emit/waterfall/cancel`; 回答走 `POST /api/$events/result`.
- 精确 HTTP 路由: 文件上传/下载、图片、导出等继续走 `/api/...` 的 Fetch 路由.
- 缺失的移动能力用新的 Typert Remote 命名空间扩展 (例如 `mobile.changes`, `mobile.terminal`, `mobile.push`), 不再新造 wire protocol.

Host 插件退化为 **认证 + 传输适配器**; Go Relay 退化为 **通用 HTTP/WS 隧道**. 两者都不再理解 session, command, approval, file 的业务语义.

#### 保留

- Android 原生 Compose UI.
- 设备配对、设备令牌、主机绑定、撤销、TLS、FCM、应用更新、诊断.
- 可选的签名审批: 作为 `$events/result` 的外层签名, 不是新的审批协议.
- 可选的 Host 侧 durable command journal: 作为 Typert 扩展 `mobile.command`, 不是新的 wire protocol.
- 加密 transcript cache、离线历史、通知、文件导出等产品能力.

#### 放弃

- `host/remote-control/src/protocol.ts` 的自定义 v1 命令/帧.
- `session-sync.ts` 的自定义会话打包.
- `command-journal.ts` 作为协议核心 (可降级为可选 `mobile.command` 扩展).
- `interaction-router.ts` 的自定义审批状态机 (改用 dsh `$events`).
- `relay-sessions.ts`, `relay-approvals.ts`, `relay-files.ts`, `relay-exports.ts`, `relay-question-*` 的协议解析.
- Android `SessionReader`/`SessionWindow`/`SessionCommands`/`SessionApprovals`/`SessionExports` 的自定义 v1 线协议部分.
- Relay 的 session/approval/file/export/command 解析器和对应的 per-frame DB 查询.

#### 一句话

**手机说的是 dsh 的话, 插件只负责把这句话安全地送到同进程的 dsh; Relay 只负责把字节送到插件. 不再有第二套 Agent 协议.**

---

### 6.2 为什么要改

#### v1 的问题

v1 已经实现了很多正确的东西, 但它重复实现了 dsh 已有的语义:

- 会话快照、分页、事件、assistant 流.
- 命令接收、幂等、回执、outcome_unknown.
- 审批、提问、文件、导出、变更、模型目录.
- Relay 的逐帧授权和命令账本.

结果是:

- 1556 个文件, 约 22.6 万行新增, 502 个 Go 文件, 259 个 Kotlin 文件.
- Host, Relay, Android 三端都要维护同一套会话/命令/审批语义.
- dsh 每次改动 `SessionFollowFrame`、`SessionWireEvent`、`SessionController` 或 `WorkspaceRegistry`, 三端都要跟.
- Relay 每帧查 DB, 性能和扩展性受限.
- 自定义审批/命令日志虽然强, 但和 dsh 原生审批/会话状态存在两套真相的风险.

#### dsh 已经提供的能力

dsh 的 Typert 协议已经覆盖了移动端的大部分需求:

| 能力 | dsh 端点/机制 |
| --- | --- |
| 会话列表/搜索 | `session.list`, `session.search` |
| 会话创建/fork/归档/重命名 | `session.create`, `session.fork`, `session.archive`, `session.rename` |
| 发送/排队/打断 | `session.prompt`, `session.cancel`, `session.updateQueue` |
| 会话快照/历史/实时流 | `session.follow`, `session.page`, `session.events`, `session.observeEvent`, `session.observeState` |
| assistant 逐 token | `session.follow` 的 `assistantStream: true` |
| 模型目录/选择 | `session.modelCatalog`, `session.selectModel` |
| 审批 | `$events` 里的 `approval/request` waterfall + `$events/result` |
| 用户提问 | `$events` 里的 `user-questions/request` waterfall + `userQuestions.answer` |
| 文件/图片 | `/api/file`, 文件上传精确路由, `session.attachment` |
| 工作区打开 | `session.openWorkspacePath` |
| 技能/文件引用 | `skills.*`, `fileReferences.*` |

dsh 还提供了这些关键语义:

- `session.prompt` 的 `requestId` 在进入 queue 或日志后, 重试会返回原来的接受结果, 不会重复插入.
- `session.follow` 的 opening snapshot + cursor + assistant stream 已经是成熟的恢复模型.
- `$events` 的 `ready/waterfall/cancel` 已经处理了 owner-only、`interactions: 'answer'` 和结果回传.
- `remoteAccess` 已经处理了非 owner Peer 的资源级授权.

#### 收益与代价

收益:

- 协议只有一套, 不再三端重复维护.
- Host 插件代码大幅缩小; Relay 不再解析业务.
- dsh 升级时, 只需更新生成的 contract 和少量适配.
- Web/PWA 客户端可以直接复用 dsh Web 协议.
- v1 的 P0-1、P0-3、P1-1、P1-2、P1-3、P2-3 随协议层删除而消失.

代价:

- Android 必须实现 dsh 的 Typert 客户端, 包括 `SessionFollowFrame`、`SessionWireEvent`、`$events` 和 exact routes.
- 需要 contract/codegen, 否则 Kotlin 模型会漂移.
- dsh 仍是 developer preview, wire format 可能变; 必须锁版本并做 contract tests.
- `/api` 原本面向受信任浏览器; 暴露给手机必须加设备配对/E2E/撤销.
- 自定义的 Host 侧命令日志和签名审批需要重新定位: 作为 Typert 扩展或外层签名, 而不是协议核心.

结论: **值得. v1 的复杂度已经证明"自己写一套"不划算; 复用 dsh 协议能把工程重心转回移动 UX 和安全边界.**

---

### 6.3 目标架构

```text
Android (原生 Compose)
  ├─ POST /api/<ns>/<method>            # 一元
  ├─ WS /api/remote.mux                 # open/item/end/cancel
  ├─ $events 逻辑流                     # ready/emit/waterfall/cancel
  └─ GET/POST /api/file, /api/upload... # exact routes
        │
        │ HTTPS/WSS (LAN 直连 或 Relay/E2E)
        ▼
Host 插件 (Cordis, 同进程)
  ├─ 设备配对/认证/撤销
  ├─ 每连接创建 owner Peer
  ├─ ctx.connection.createSharedFetchHandler('/api').fetch(request, peer)
  ├─ ctx.typertGateway.invoke({ namespace, method, args, peer, signal })
  ├─ ctx.typertGateway.wireStream.open(endpoint, payload, uplink, peer, signal)
  └─ 可选: $events/result 签名校验, mobile.* 扩展
        │
        ▼
dsh Connection / Typert Gateway / Controllers / Agent / Tools
```

Relay 的角色:

```text
Phone ── HTTPS/WSS ──▶ Relay (设备认证 + 路由 + 配额)
                            │  opaque HTTP/WS tunnel
                            ▼
                        Host 插件 (出站连接)
```

Relay 不再解析 `session.*`, `approval.*`, `file.*`, `command.*`. 它只做:

- 设备/主机认证.
- Host 出站连接与手机连接的路由配对.
- 连接租约、配额、撤销、审计元数据.
- 可选的 E2E 外层 (Noise).
- HTTP/WS 字节转发.

LAN 模式:

```text
Phone ── HTTPS/WSS ──▶ Host 插件自带的 LAN 网关
                            │
                            ▼
                     dsh in-process Gateway
```

同一 Wi-Fi 不依赖 Relay, 但手机仍说同一套 dsh 协议.

---

### 6.4 协议映射

#### 总表

| dsh 协议 | 手机发送 | Host 适配器 | 说明 |
| --- | --- | --- | --- |
| 一元 RPC | `POST /api/<ns>/<method>` body `{ args }` | `sharedFetch.fetch(request, peer)` | 包含 Typert RPC 与 exact routes; 结果原样返回 |
| 流 | WS `/api/remote.mux` 的 `open/item/end/cancel` | `typertGateway.wireStream.open(...)` | 逐项转发 `item/error/end`; uplink 原样传递 |
| 事件 | `open` endpoint `$events`, payload `{ args: {} }` | 同一 `wireStream.open('$events', ...)` | 首帧 `ready`; 之后 `emit/waterfall/cancel` |
| 事件结果 | `POST /api/$events/result` body `{ args: { clientId, eventId, outcome } }` | `sharedFetch.fetch(request, peer)` | 可选先验签, 再转发 |
| 文件上传/下载 | `GET/POST /api/file...`, `/api/upload...` | `sharedFetch.fetch(request, peer)` | multipart/二进制由 Response 原样转发 |
| 图片附件 | `session.attachment` 一元方法 | `sharedFetch` / `typertGateway.invoke` | 结果含 base64 或二进制附件 |
| 模型 | `session.modelCatalog`, `session.selectModel` | 同上 | 不需要自定义模型协议 |
| 会话 | `session.list/create/fork/archive/prompt/cancel/follow/page` | 同上 | 不需要自定义 session sync |

#### 一元示例

```http
POST /api/session/list HTTP/1.1
Content-Type: application/json

{ "args": {} }
```

```json
{
  "ok": true,
  "value": {
    "items": [
      { "sessionId": "...", "cwd": "/home/me/project", "running": false }
    ]
  }
}
```

注意: 手机不需要 dsh 浏览器 Cookie. Host 适配器用设备认证创建 `PeerScope`, 并把该 Peer 传给 `sharedFetch.fetch(request, peer)`. Cookie 只属于浏览器路径.

#### 流示例

```json
// Client -> Host
{"type":"open","streamId":"s1","endpoint":"session/follow","payload":{"args":{"address":{"kind":"session","sessionId":"..."},"assistantStream":true,"maxMessages":50}}}
{"type":"item","streamId":"s1","value":{"...":"uplink item"}}
{"type":"end","streamId":"s1"}
{"type":"cancel","streamId":"s1"}

// Host -> Client (示例省略了 header/projections 的完整字段)
{"type":"item","streamId":"s1","value":{"type":"snapshot","header":{"version":1,"id":"...","createdAt":0,"isSeeded":false},"cursor":42,"records":[],"hasMore":false,"projections":{}}}
{"type":"item","streamId":"s1","value":{"type":"event","event":{"seq":43,"type":"assistant/message","data":{}}}}
{"type":"item","streamId":"s1","value":{"type":"assistant-stream","frame":{"type":"chunk","attemptId":"...","revision":1,"index":0,"time":0,"chunk":{"type":"text-delta","index":0,"text":"..."}}}}
{"type":"error","streamId":"s1","error":{"code":"session.not_found","message":"...","details":{}}}
{"type":"end","streamId":"s1"}
```

`session.follow` 的 payload 和帧类型完全由 dsh 定义; Host 不重新投影.

#### `$events` 示例

```json
// Client -> Host: open
{"type":"open","streamId":"e1","endpoint":"$events","payload":{"args":{}}}

// Host -> Client
{"type":"item","streamId":"e1","value":{"type":"ready","clientId":"...","host":{"home":"/home/me"}}}
{"type":"item","streamId":"e1","value":{"type":"emit","event":"example/broadcast","args":[]}}
{"type":"item","streamId":"e1","value":{"type":"waterfall","event":"approval/request","eventId":"...","agentId":"...","request":{...}}}

// Client -> Host: answer
POST /api/$events/result
{"args":{"clientId":"...","eventId":"...","outcome":{"kind":"result","value":"allowed-once"}}}
```

#### exact routes

手机对文件/图片的请求直接走 dsh 的 exact Fetch 路由:

```http
GET /api/file?path=%2Fhome%2Fme%2Fproject%2Fchart.png&v=...
POST /api/upload...
```

Host 适配器构造 `Request`, 调用 `sharedFetch.fetch(request, peer)`, 然后把 `Response` 的 status/headers/body 原样通过 HTTP 隧道返回. 这样 multipart、二进制、范围请求、图片下载都不需要自定义协议.

---

### 6.5 Host 适配器设计

#### 职责

只做四件事:

1. 认证手机, 建立设备到 Peer 的绑定.
2. 把手机的 HTTP 请求映射为 `sharedFetch.fetch(request, peer)`.
3. 把手机的 WS 帧映射为 `typertGateway.wireStream.open(...)`.
4. 管理连接生命周期、取消、撤销、可选的签名/审计.

不再做:

- 会话快照/事件投影.
- 命令日志/回执.
- 审批状态机.
- 文件/导出协议.
- 会话目录/模型目录的自定义缓存.

#### 关键 API

```ts
// 1. 每连接一个 Peer
const peer = ctx.connection.createPeer({
  principal: { kind: 'owner' },
  interactions: 'answer',
})

// 2. HTTP: 一元 + exact routes 统一入口
const sharedFetch = ctx.connection.createSharedFetchHandler('/api')
const response = await sharedFetch.fetch(request, peer)

// 3. WS: 流 + $events
const stream = await ctx.typertGateway.wireStream.open(
  endpoint,
  payload,
  uplink,
  peer,
  signal,
)

// 4. 失败投影
const failure = ctx.typertGateway.failure(error)
```

`sharedFetch.fetch` 的 `request` 是 **channel 之下** 的 Request:

- 手机请求 `POST /api/session/prompt`.
- 适配器构造 `new Request('http://local/session/prompt', ...)`.
- 不要把 `/api` 前缀重复传给 handler.

**直接复用官方类型和常量, 不要手写协议.**

- 一元信封: `packages/client/connection/src/rpc.ts` 的 `ClientRequest`/`ServerResponse`/`RpcId`.
- 流帧: `packages/api/gateway/src/stream-protocol.ts` 的 `REMOTE_STREAM_MUX_PATH`, `REMOTE_EVENT_STREAM_ENDPOINT`, `REMOTE_EVENT_RESULT_ENDPOINT`, `parseRemoteStreamClientMessage`, `parseRemoteStreamServerMessage`.
- 流句柄: `packages/typert/protocol` 的 `RemoteStream`/`RemoteStreamHandle`.
- 事件帧: `RemoteEventReadyFrame`/`RemoteEventEmitFrame`/`RemoteEventInvocationFrame`/`RemoteEventCancellationFrame`.
- 错误: `RemoteError`/`remoteErrorOf`.

这样适配器只是搬运官方类型, 不是重新定义协议.

#### 连接生命周期

```ts
const lifetime = new AbortController()
const peer = ctx.connection.createPeer({ principal: { kind: 'owner' }, interactions: 'answer' })
const streams = new Map<string, AbortController>()

try {
  await serve(phoneSocket, { peer, lifetime, streams })
} finally {
  for (const controller of streams.values()) controller.abort()
  await peer.dispose()
  lifetime.abort()
}
```

要求:

- 连接关闭 => 中止所有流 => dispose Peer.
- Peer dispose 后, Gateway 以 `gateway/peer-disposed` 拒绝后续调用.
- 不要在多个手机连接之间共享 Peer; 每个连接独立, 便于撤销和审计.

#### HTTP 映射

```ts
async function handleHttp(phoneRequest: PhoneHttpRequest, peer: PeerScope, sharedFetch: ConnectionFetchHandler) {
  const url = new URL(phoneRequest.path.replace(/^\/api/, ''), 'http://local')
  const request = new Request(url, {
    method: phoneRequest.method,
    headers: phoneRequest.headers,
    body: phoneRequest.body, // 需要时用 ReadableStream
  })
  const mode = sharedFetch.requestBodyMode(request)
  if (mode === 'buffered') {
    // 有上限的缓冲; 超过上限返回 413
  }
  const response = await sharedFetch.fetch(request, peer)
  return {
    status: response.status,
    headers: response.headers,
    body: response.body, // 原样流式返回
  }
}
```

要求:

- 保留 status/headers/body, 不重新编码 JSON.
- multipart 响应不能转成 JSON; 直接转发字节流.
- 请求体超过上限时返回 413, 不要无限缓冲.
- 取消时中止 `Request.signal`, 并停止读取 body.

#### WS 映射

```ts
type ClientMessage =
  | { type: 'open'; streamId: string; endpoint: string; payload: unknown }
  | { type: 'item'; streamId: string; value?: unknown }
  | { type: 'end'; streamId: string }
  | { type: 'cancel'; streamId: string }

async function openStream(msg: OpenMessage, peer: PeerScope) {
  const controller = new AbortController()
  const uplink = new UplinkQueue(msg.streamId)
  const values = await ctx.typertGateway.wireStream.open(
    msg.endpoint,
    msg.payload,
    uplink,
    peer,
    controller.signal,
  )
  void forward(values, msg.streamId, controller)
  return { controller, uplink }
}
```

要求:

- 严格校验 `open` 的 endpoint/payload 形状; 不接受客户端自定义的 namespace.
- `item`/`end`/`cancel` 只作用于已打开的 `streamId`.
- 客户端 `cancel` => abort; 不要等待 Host 流自然结束.
- Host 流结束 => 发送 `end`, 不要关闭整个 socket.
- Host 流错误 => 发送 `{type:'error', error:{code,message,details}}`, 不要关闭 socket.
- `$events` 的 uplink 可以是空迭代器; Gateway 会立即 return 它.
- 每条流的 `streamId` 必须唯一; 重放/重复 `open` 拒绝.

#### `$events`

`$events` 是 Gateway 内部逻辑流, 不需要 Host 插件自己实现事件广播:

```ts
const events = await ctx.typertGateway.wireStream.open(
  '$events',
  { args: {} },
  emptyUplink(),
  peer, // interactions 必须为 'answer'
  signal,
)
```

要求:

- Peer 必须是 `interactions: 'answer'`, 否则 `gateway/interactions-forbidden`.
- 只有 owner Peer 能回答 waterfall; 非 owner 只收广播.
- `$events/result` 通过 `sharedFetch` 转发, 不要自己解析 outcome.
- 如果要做签名审批, 在这一层校验签名, 再转发.
- 确认 Host 装配的 forwarded event allowlist 包含 `approval/request` 和 `user-questions/request`; 这两类 waterfall 不一定会自动转发给手机. 不在 allowlist 时, 在 API Remotes assembly 中显式加入.
- `$events` 的 `ready` 帧是连接代次的分界; 手机在收到 `ready` 前不要发起依赖 generation 的读取.

#### 认证

推荐的连接顺序:

```text
手机 -> Relay/LAN 网关: 设备配对/令牌/证书
Relay -> Host: 设备身份 + 连接租约
Host 插件: 验证设备身份, 创建 owner Peer
```

要求:

- Peer 的 principal 来自传输层认证, 不能来自手机请求体里的 `device_id`/`user_id`.
- 个人使用: `principal: { kind: 'owner' }`.
- 多用户: 声明 `PrincipalMap` 的新 kind (例如 `mobile-user`), 在 dsh `remoteAccess` 里配置资源/角色; 不要直接把 owner 给所有人.
- 撤销: 关闭连接 => abort 所有流 => dispose Peer; 同时清理本地缓存.
- 不要在 dsh 请求里信任手机自报的 session/workspace; dsh 的 `remoteAccess` 会按 Peer 重新判定.

**dsh 审批策略是安全边界, 不是手机.**

复用 dsh 协议后, 手机只回答 dsh 发出的 `approval/request` waterfall. 如果 dsh 原生策略把某个工具设为自动允许, 手机根本不会收到审批卡. 因此:

- 对受管会话, 在 dsh 侧把 mutating/execute 工具配置为 `ask`.
- 手机端不要假设"没收到审批 = 安全"; 它只是"dsh 没问".
- 用 `bash`, `edit`, `write`, `run_code`, 未知 MCP 工具逐项测试.
- 如果保留 v1 的"默认拒绝所有副作用工具"策略, 把它作为 dsh 策略配置的一部分, 而不是手机侧的自定义协议.

#### 可选: 签名审批

dsh 原生审批仍然生效. 如果保留"手机签名"能力:

1. 手机在 `$events` 收到 `approval/request` waterfall, 拿到完整的 approval context.
2. 手机对 canonical `{ clientId, eventId, outcome, context_digest }` 签名.
3. 手机调用 `$events/result` 时带上 `signature` 和 `key_id`.
4. Host 适配器在转发前验证:
   - 设备公钥已登记且未撤销.
   - 签名覆盖当前 event/context.
   - context 与 waterfall 一致.
5. 验签通过后, 去掉签名, 把标准 outcome 转发给 Gateway.

这不是新协议, 只是 `$events/result` 的外层安全封装.

#### 可选: durable command 扩展

dsh 的 `session.prompt` 有 requestId 去重; 其他命令 (cancel/archive/create/fork/model select) 不保证. 如果仍需要 v1 的 durable receipt, 把它做成 Typert 扩展:

```ts
class MobileCommandService extends TypertRemoteService {
  constructor(ctx: Context) { super(ctx, 'mobile.command') }

  @Remote('submit')
  async submit(request: MobileCommandRequest, signal: AbortSignal): Promise<MobileCommandResult> {
    // 复用现有 command-journal, 但输入/输出是 Typert 描述符
  }

  @Remote({ mode: 'stream' })
  async *follow(request: MobileCommandFollow, signal: AbortSignal): AsyncIterable<MobileCommandFrame> {
    // 可选的命令状态流
  }
}
```

手机用同一套 `POST /api/mobile.command/submit` 和 `/api/remote.mux` 调用. 这不是第二套协议, 是 dsh Typert 的新 namespace.

#### 备选: 直接反向代理 dsh Web Server

如果只想最快验证"手机说 dsh 协议", 可以不做进程内 Gateway 适配, 而是让插件反向代理本机 dsh Web Server:

```text
Phone -> Relay/LAN -> Host 插件 -> http://127.0.0.1:3080 + /api/remote.mux
```

优点:

- 语义代码最少; 手机看到的就是 Web UI 的协议.
- exact routes, multipart, WS, 静态资源都由 Web Server 处理.
- 可以直接复用 dsh Web UI 的资源/插件 UI.

缺点:

- 需要 dsh Web Server 在运行; Host 插件必须拿到 token/cookie.
- 手机/Relay 到插件再到 Web Server 多一跳; 需要处理 HTTP/WS 转发和超时.
- 插件不容易按设备创建独立 Peer/principal; 它只是代理, 权限模型是 Web Server 的 owner.
- 需要严格限制可代理的路径, 防止把任意本机 HTTP 暴露给手机.

建议:

- **原型阶段**用反向代理快速验证.
- **产品阶段**用 4.1–4.9 的进程内适配器, 把设备身份、Peer、签名和审计做在插件里.
- 两种方式共用同一套手机 dsh 客户端; 手机不感知差异.

---

### 6.6 Relay 与隧道

#### 目标

Relay 只做通用隧道:

- 设备注册/登录/绑定/撤销.
- Host 出站连接, 手机连接, 路由配对.
- 连接租约、配额、审计元数据.
- HTTP 请求/响应与 WS 帧的原样转发.
- 可选 E2E.

**授权边界会移动.**

- Relay 只做 **设备 → 主机** 的连接授权; 它不再知道 session/workspace, 也不再做 session 级过滤.
- Host 适配器用 Peer principal + dsh `remoteAccess` 做 **session/workspace/资源** 级授权.
- 个人使用: 设备映射到 owner Peer, 全量访问.
- 多用户: 设备映射到非 owner principal, 在 dsh `remoteAccess` 里配置资源和角色.
- 如果必须在 Relay 做 session 级过滤, 它就必须解析 dsh 帧, 这又回到"自己写协议"的老路. 这是 v2 明确接受的取舍.

#### 删除

- `session.subscribe/page/content` 解析.
- `command.receipt`/命令账本.
- `approval.*`/`question.*`/`file.*`/`export.*` 解析.
- 每帧 `AuthorizeRealtimeSessionRead` / `TouchRuntimeConnection`.
- 对应的 PostgreSQL 表和迁移.

保留:

- 设备、主机、安装、runtime、grant、撤销.
- 连接路由、租约、配额、指标.
- 文件对象存储可以保留为 **可选数据面**: 大文件走 Relay/S3 可能比 Host 直传更稳; 但它是传输优化, 不是 dsh 协议的一部分. 手机可以选择 Host exact routes 或 Relay 文件服务.

#### HTTP 隧道

两种实现:

A. **反向代理式**: Relay 接受手机的 HTTP 请求, 通过 Host 的出站 WS 发送 `{requestId, method, path, headers, body}`; Host 返回 `{status, headers, body}`. 支持流式 body 和 multipart.

B. **字节隧道**: Relay 在 WS 上复用 HTTP/2 或 yamux, 把 TCP 字节流转发. 实现更通用, 但调试复杂.

建议 A: 足够支持 dsh 的 `/api` 和 exact routes, 且可以按请求做配额/审计.

要求:

- 请求/响应 body 采用流式分块, 不要整包缓冲.
- 设置单请求/单连接上限.
- 保留 status/headers; 不要改写 content-type.
- 取消/断线要传播到 Host 和手机.
- WS 升级单独走 `upgrade` 帧, 之后进入双向帧转发.

#### 备选: 先用现成隧道

如果目标是尽快验证 v2, 可以先不改造 Go Relay:

- 局域网: Host 插件自带 HTTPS/WSS.
- 远程: Tailscale / Cloudflare Tunnel / FRP / 自有反代.
- 手机直接连隧道入口, Host 插件做设备认证.

这样 v2 的第一版可以完全跳过 Relay 改造; 等协议稳定后再把 Go Relay 改成通用隧道.

#### E2E

E2E 放在隧道层, 不碰 dsh 协议:

- 手机与 Host 做 Noise IK 握手.
- Relay 只看到密文.
- dsh 的 HTTP/WS 字节在密文内.
- 审批签名、文件、终端输出全部在密文内.

这与 ds-harness-remote 的思路一致; dsh 协议本身不需要知道 E2E.

#### LAN 直连

Host 插件自带:

- HTTPS/WSS server, 绑定具体 LAN IP.
- 配对二维码/设备令牌.
- mDNS 广播.
- 与 Relay 相同的 dsh 协议入口.

Android 连接顺序:

```text
LAN -> Relay -> (未来) P2P/TURN
```

---

### 6.7 Android 客户端设计

#### 分层

```text
DshTransport      // HTTP/WS through LAN or Relay
DshRpc            // unary envelope, rpcId, result/error
DshStream         // mux, stream handles, uplink, cancel
DshEvents         // $events, waterfall, result
DshSessionStore   // follow snapshot/event/assistant-stream
DshUi             // Compose screens, existing models
```

#### Contract 与代码生成

dsh 的 Typert 已经有生成产物:

- Host: `typert.host.js`, 含 descriptor/schema.
- Client: `typert.remote-client.js`/`.d.ts`, 含 wire descriptor 和类型.

Android 不应该手写这些模型. 推荐:

1. Host 插件暴露一个只读 contract 端点:

```http
GET /api/mobile.contract
```

返回所选 namespace 的 `InvocationDescriptor` + JSON Schema + stream frame schema. 数据来自 `ctx.typert` 注册表, 不手写.

2. 构建期生成 Kotlin:

```text
contract.json
  -> Kotlin data classes
  -> kotlinx.serialization codecs
  -> DshSessionClient / DshApprovalClient / ...
```

3. 运行时握手带上 `contract_version`/`dsh_version`; 不匹配时拒绝并提示升级.

4. 备选: 在 Android 内嵌 JS runtime (QuickJS/WebView), 直接复用 dsh 的 TypeScript client; 原生 UI 通过 bridge 读取状态. 适合 WebView/PWA 或快速验证, 不适合追求极致原生性能.
5. 如果 codegen 太重, 第一版可以手写核心子集:
   - `session.list`
   - `session.follow`
   - `session.prompt`
   - `session.cancel`
   - `session.create`
   - `session.modelCatalog`
   - `session.selectModel`
   - `$events` + `$events/result`
   - `userQuestions.answer`
   - `session.attachment`

   但必须用 golden/contract tests 锁住 JSON 形状.

#### Session follow

手机收到 `SessionFollowFrame`:

```text
snapshot -> 替换窗口, 记录 cursor
event -> 按 seq 追加
assistant-stream -> start/chunk/end 瞬时状态
```

要求:

- 使用 dsh 的 cursor 和 WebSocket `streamId`; 分页用 `session.page` 的 `throughSeq`/`beforeSeq`, 不再自定义 `sync_id`.
- `snapshot` 是原子替换; 旧 transient 状态清空.
- `event.seq` 必须连续; 缺口触发重新订阅.
- `assistant-stream` 的 revision/index 必须连续.
- 持久化状态通过 `session.state`/`session.wait` 查询, 不从 follow 帧里伪造 checkpoint.
- 会话 ID 直接使用 dsh 的 `sessionId`; 不再用 `mobileSessionId` 映射. 缓存、通知、审批引用和深链全部迁移到原生 ID.
- 如果仍要对外隐藏原生 ID, 在 Host 适配器做一层稳定的公开 ID 映射, 但不要把它做成第二套会话状态.
- 保留现有的 Compose `ConversationItem`/`SessionTranscript`, 但 parser 改为 dsh 的 `SessionWireEvent`.
- 大记录在手机侧截断/摘要; 隧道/WS 仍有帧大小上限, 需要定义并测试.

#### 命令

- `session.prompt`: 手机生成稳定的 `requestId`, 重试复用; dsh 会去重.
- `session.cancel`: 绑定当前 session; 不自动重试.
- `session.create/fork/archive/model select`: 不自动重试; 结果未知时查询状态.
- 可选: 调用 `mobile.command.submit` 获得 durable receipt; 这是 Typert 扩展, 不是 v1.

手机本地 outbox:

- 仍然保留加密 SQLite; 记录 requestId、状态、最后尝试时间.
- 只对可安全重试的 prompt 自动重试.
- 其他命令提供"重试/放弃/查询状态"三个显式动作.

#### 审批与提问

- 打开 `$events`, 等 `ready`.
- `waterfall`:
  - `approval/request` -> 审批卡.
  - `user-questions/request` -> 提问卡.
- 回答:
  - `POST /api/$events/result` body `{ args: { clientId, eventId, outcome } }`.
  - `outcome.value` 的类型由 Host 的 approval/question listener 决定; 手机应从 waterfall request 或 contract 中获取, 不要硬编码.
- 可选签名:
  - 对 `{clientId,eventId,outcome,context_digest}` 签名; Host 验签后再转发.
- 不自己发明 approval id/lease/expiry; dsh 原生审批策略继续生效.

#### 文件

- 上传/下载走 exact routes.
- 图片走 `session.attachment` 或 `/api/file`.
- multipart/二进制直接转发, 不要 base64 包一层.
- 保留现有加密缓存、大小上限、撤销清理.

#### UI 映射

- 保留现有 Compose 页面: 会话、创建、审批、提问、文件、变更、通知.
- 替换数据源:
  - `SessionReader` -> `DshSessionStore`.
  - `SessionCommands` -> `DshRpc` + `mobile.command`.
  - `SessionApprovals` -> `DshEvents`.
  - `SessionExports` -> exact routes.
- `SessionWindow` 的 seq/gap/settlement 语义可以保留, 但输入改为 dsh 的帧.

#### 离线缓存

- 仍用 SQLCipher.
- cache key: `(host_id, session_id, dsh_version, cursor)`.
- 只缓存持久事件, 不缓存 live chunk.
- 重连时用 dsh snapshot 替换; 不自己造 baseline.

---

### 6.8 Typert 扩展

"复用 dsh 协议"不等于"只能用 dsh 现有方法". 缺失的移动能力应以 **新的 Typert Remote namespace** 扩展, 复用同一套 descriptor/codec/stream 机制.

#### 候选扩展

| 扩展 | 类型 | 用途 | 替代 v1 的哪部分 |
| --- | --- | --- | --- |
| `mobile.changes` | stream | 工作区文件变更 | 自定义 `session.changes` |
| `mobile.terminal` | stream + unary | 终端 open/input/resize/close | 无 v1, 新增能力 |
| `mobile.push` | unary | 注册/更新 FCM token | 现有推送 |
| `mobile.diagnostics` | unary | 连接/版本/网络诊断 | 现有诊断 |
| `mobile.command` | unary + stream | durable command journal | 自定义命令协议 |
| `mobile.approval` | unary | 可选签名审批辅助 | 自定义审批协议 |
| `mobile.export` | stream/unary | 工作区交付物导出 | 自定义导出协议 |

#### 实现方式

```ts
import { Remote, TypertRemoteService } from '@deepseek-ai/dsh-typert-protocol'

export class MobileChangesService extends TypertRemoteService {
  constructor(ctx: Context) { super(ctx, 'mobile.changes') }

  @Remote({ mode: 'stream' })
  async *follow(request: MobileChangesRequest, signal: AbortSignal): AsyncIterable<MobileChangesFrame> {
    // 复用现有 WorkspaceChanges, 但输出 dsh Typert stream
  }
}
```

要求:

- 用 `@Remote`/`@RemoteScope` 标注.
- 加入 Typert 生成流水线, 产出 `typert.host.js` 和 `typert.remote-client.*`.
- Android contract 生成包含这些 namespace.
- 不新造 envelope/frame; 使用 `RemoteStream<Out, In>` 和标准 codec.

这样既满足"不要自己写协议", 又不丢功能.

---

## 7. 迁移计划

### 7.1 Phase 0: 准备

- 冻结 v1 协议; 不再新增 v1 命令.
- 定义 v2 的 method/stream 白名单.
- 写 contract 生成脚本 (至少覆盖核心子集).
- Host 适配器原型:
  - `createPeer`.
  - `sharedFetch` 一元/ exact route.
  - `wireStream` 流/`$events`.
- Android `DshRpc`/`DshStream`/`DshEvents` 原型.
- 用 loopback/一个真实 dsh 实例做 smoke test.

### 7.2 Phase 1: 核心 parity

- `session.list`
- `session.follow` + assistant stream
- `session.prompt`/`session.cancel`
- `session.create`
- `session.modelCatalog`/`selectModel`
- `$events` 审批/提问 + `$events/result`
- Android UI 切换到新数据源.
- 与 v1 并行, feature flag 切换.

退出标准:

- 手机能完成: 列会话 -> 打开 -> 发消息 -> 看流 -> 审批 -> 提问 -> 取消.
- 断线重连后 snapshot 正确.
- dsh 原生审批策略生效.
- 无 v1 命令参与.

### 7.3 Phase 2: 功能 parity

- `session.page` 历史.
- `session.fork`/`archive`/`rename`.
- 文件上传/下载/图片.
- `mobile.changes`.
- `mobile.export`.
- 通知/推送.
- 离线缓存.
- 与 v1 逐项对比.

退出标准:

- v1 的所有核心用户路径在 v2 可用.
- 大记录、multipart、撤销、弱网测试通过.

### 7.4 Phase 3: 删除 v1

- 删除 `protocol.ts`, `session-sync.ts`, `command-journal` 的协议核心, `relay-sessions.ts`, `relay-approvals.ts`, `relay-files.ts`, `relay-exports.ts`, `relay-question-*`.
- 删除 Android v1 线协议类.
- 删除 Relay 的 session/approval/file/export/command 解析器和表.
- 更新文档/迁移脚本.
- 保留一个 release 的 v1 回滚包.

### 7.5 Phase 4: 网络与多端

- LAN 直连.
- E2E.
- Web/PWA.
- iOS.
- 终端/预览/插件 UI.

---

## 8. 网络与产品扩展

> 以下能力在 v2 中仍然适用; 与 v2 协议层的耦合点已在第 6 节说明. 这些是"协议之外"的产品/网络能力.

### 8.1 E2E 设计

> 对应 6.6.5; 这里给出密码学与帧格式细节.

建议直接复用 ds-harness-remote 的密码学选择, 避免自创:

- 握手: `Noise_IK_25519_ChaChaPoly_SHA256`.
- 设备身份: X25519 或 P-256; Android Keystore.
- Host 身份: 安装密钥; 公钥在账号目录登记.
- 帧: 长度前缀 + 单调序号 + 密文; 重放窗口.
- 握手后每条连接独立密钥; 重连重新握手.
- Relay 只看到 `client_id`, `host_id`, `connection_id` 和密文.
- 审批 JWS、文件 manifest、终端输出全部在密文内.
- 旧客户端显式 `tls-only`; UI 标记"Relay 可见内容".

迁移顺序:

1. 双方 capability `e2e.v1`.
2. 先对业务 payload 加密, 保留现有 envelope 字段用于路由.
3. 再把审批/文件/终端纳入.
4. 最后废弃 `tls-only` 默认.

### 8.2 LAN 直连与多路径

> 对应 6.6.6; 这里给出网关、发现和回退细节.

短期 LAN 网关:

```text
Host 插件
  ├─ HTTPS/WSS server on LAN IP
  ├─ pairing QR / device token
  ├─ mDNS advertisement
  └─ same v1 protocol
Android
  ├─ LAN discovery + pair
  ├─ try LAN first
  └─ fallback Relay
```

实现要点:

- 插件可以复用现有 pairing/device token 逻辑, 但不要复用 Relay 的账号体系.
- Android 端新增 `LanDiscovery` 和 `ConnectionSelector`.
- 连接选择顺序: LAN → Relay; 后续扩展 P2P/TURN.
- 所有路径使用同一份协议和同一套会话恢复逻辑.

长期 P2P:

- WebRTC DataChannel 或 iroh.
- Noise E2E.
- 失败回退 TURN/Relay.
- 连接选择器和健康检查统一.

### 8.3 第三方插件 UI

方案 A: WebView 插件槽.

```text
Native Session Screen
  ├─ 原生消息时间线
  └─ PluginSlotHost (WebView)
        ├─ 同源加载 DSH Web UI 的 slot 区域
        ├─ 只允许白名单 Origin
        └─ 不注入设备密钥
```

关键:

- Cookie/Token 只给同源 WebView, 不暴露给任意 JS.
- 禁止任意 URL、外部 Intent、文件访问.
- 主题/字体/深色模式通过 CSS 变量对齐.
- 失败时回退到"请在电脑端查看".

方案 B: 移动插件 SDK.

- 插件包声明 `mobile` 入口、slot、schema、action.
- App 用 Compose 渲染工具卡/表单/面板.
- 需要定义版本、权限、沙箱和降级.

建议先 A 后 B.

### 8.4 终端与文件预览

终端:

- Host 注入 `ctx.terminals`/`ctx.subprocess`, 新建 `TerminalSession`.
- 帧: `terminal.open`, `terminal.input`, `terminal.resize`, `terminal.output`, `terminal.exit`.
- 限制: 单会话 1 个终端, 输出 1 MiB/s, 总缓冲 4 MiB, 5 分钟无操作关闭.
- Android 用 xterm.js + 沙箱 WebView, 或原生 terminal.

文件预览:

- 复用 `PinnedExportSource`/`ExportDirectory`; 只读.
- 图片/PDF/文本/代码; 沙箱 WebView; 禁止外部打开.
- 大文件分块, 支持取消和范围读取.

### 8.5 多端

- 先做 Web/PWA: 复用 v1 协议, TypeScript 客户端, 浏览器 WSS/fetch.
- 再做 iOS: SwiftUI 或 RN; 共享协议 codegen.
- 用 JSON Schema/Protobuf 生成三端类型, 避免手工漂移.
- 统一认证/配对/设备管理 API.

### 8.6 可观测性与验收

- Relay: 每帧延迟、DB 查询、缓存命中、队列深度、断开原因.
- Host: follow 帧速率、checkpoint 耗时、审批等待、命令状态分布.
- Android: 连接阶段、重连次数、首屏时间、分页耗时、内存.
- 验收:
  - 100 并发会话 × 50 chunk/s.
  - 1000 会话目录.
  - 3 MiB tool result.
  - 撤销 mid-stream.
  - 代理/弱网/断网 10 分钟.
  - 目标 Android 设备 8 小时后台.

---

## 9. 风险与缓解

| 风险 | 影响 | 缓解 |
| --- | --- | --- |
| dsh 协议是 preview | Android 模型漂移 | 锁版本; contract codegen; golden tests; 版本握手 |
| owner Peer 权限过大 | 手机 token 等于完整控制 | 设备配对 + E2E + 撤销; 多用户用非 owner principal |
| HTTP 隧道复杂 | multipart/大文件/流式 body 出错 | 先支持核心 JSON; 文件单独 exact routes; 上限/取消测试 |
| `$events` 仅 owner + answer | 审批收不到 | 创建 Peer 时 `interactions: 'answer'`; 非 owner 只读 |
| dsh 原生审批策略过宽 | 手机不收到审批, 工具自动执行 | 在 dsh 侧对 mutating/execute 配置 `ask`; 逐工具测试 |
| 失去 Host 侧命令日志 | 非幂等命令结果未知 | 手机 outbox + 状态查询; 可选 `mobile.command` 扩展 |
| 失去自定义签名审批 | 审批不可追责 | 在 `$events/result` 外层加签名; Host 验签 |
| 大记录 > 帧上限 | 会话无法同步 | 手机侧截断/摘要; 单事件分块接口 |
| Relay 改造量大 | 迁移风险 | 先用现成隧道 (Tailscale/Cloudflare/FRP); Go Relay 逐步退化 |
| codegen 成本 | 开发周期 | 第一版手写核心子集 + contract tests; 后续生成 |
| 现有 UI 与 dsh 事件模型差异 | 返工 | 保留 Compose 组件, 只换 parser/store; 先做核心四流程 |

---

## 10. v2 修订后的优先级

### 10.1 消失的问题

v1 审查中的这些问题随协议层删除而消失:

- P0-1 Relay 每帧 DB.
- P0-3 未签名模式只拦 3 个工具 (改用 dsh 原生审批).
- P1-1 全量 catalog/resolve.
- P1-2 `ids` 竞态.
- P1-3 3 秒超时被报成离线.
- P2-3 固定 10 秒 settlement.

### 10.2 仍然存在的问题

- P0-2 没有 E2E.
- P0-4 dsh 版本耦合 (但改为 `typertGateway`/`connection` 公共面, 比 `sessionController` 内部方法更稳).
- P1-4 大记录.
- P1-5 没有 LAN/P2P.
- P1-6 第三方插件 UI.
- P1-7 终端/文件预览/语音.
- P1-8 只有 Android.
- P1-9 没有许可证.
- P2-1 主机重连终止状态.
- P2-2 Android 代理.
- P2-4 范围膨胀.

### 10.3 新的 Top 6

1. **Contract + codegen**: 定义 v2 白名单, 生成 Kotlin/TS 类型.
2. **Host 适配器**: `createPeer` + `sharedFetch` + `wireStream` + `$events`.
3. **Android 核心客户端**: unary/stream/events; 替换 `SessionReader`.
4. **审批/提问**: `$events` + `$events/result`; 可选签名.
5. **Relay 退化**: 通用 HTTP/WS 隧道, 删除业务解析.
6. **删除 v1**: 逐项 parity 后清理.

### 10.4 新 backlog

| 优先级 | 任务 | 说明 |
| --- | --- | --- |
| P0 | v2 contract/codegen | 核心子集先手写 + tests |
| P0 | Host 适配器 | 同进程 Peer + sharedFetch + wireStream |
| P0 | Android DshRpc/DshStream/DshEvents | 核心四流程 |
| P0 | 审批/提问桥 | `$events` waterfall + result |
| P0 | Relay 通用隧道 | 或先用 Tailscale/Cloudflare |
| P0 | 删除 v1 | parity 后 |
| P1 | LAN 直连 | 插件 HTTPS/WSS + QR/mDNS |
| P1 | E2E | Noise 外层 |
| P1 | 终端/预览 | Typert `mobile.terminal` + exact routes |
| P1 | 插件 UI 回退 | WebView 槽 |
| P1 | 许可证 | Apache/MIT/AGPL |
| P2 | Web/PWA | 复用 dsh 协议 |
| P2 | iOS | SwiftUI/RN |
| P2 | ACP/多后端 | 后续 |
| P3 | 手机操控/桌宠 | 可选 |

---

## 11. 功能 backlog

> 功能 backlog 与协议版本无关, v1/v2 都适用. v2 迁移任务见第 7 节和 10.4.

| 优先级 | 功能 | 为什么 | 实现方式 | 参考 |
| --- | --- | --- | --- | --- |
| P1 | LAN 直连 | 去掉强制 Relay | 插件 HTTPS/WSS + QR/mDNS | dsh-mobile |
| P1 | E2E | Relay 不可读正文 | Noise IK + 帧加密 | ds-harness-remote |
| P1 | 终端 | 移动高频场景 | PTY 帧 + xterm WebView | ds-harness-remote/dsh-mobile |
| P1 | 文件预览 | 看结果 | 只读沙箱 WebView | ds-harness-remote |
| P1 | 第三方插件 UI | 生态完整 | WebView 插件槽 → 原生 SDK | dsh-mobile/dsh-Go |
| P2 | Web/PWA | 多端入口 | TS 客户端 + 同协议 | dsh-mobile |
| P2 | iOS | 覆盖 iPhone | SwiftUI/RN | ds-harness-remote |
| P2 | 语音输入 | 免打字 | Android SpeechRecognizer | dsh-Go |
| P2 | ACP 后端 | Codex/Cursor/Claude | Host ACP 适配 | ds-harness-remote |
| P2 | 诊断包 | 自助排障 | 脱敏日志 + 网络图 | dsh-mobile |
| P2 | 模型/插件设置 | 完整管理 | 原生表单 + Host API | ds-harness-remote |
| P3 | 手机操控 | 自动化手机 | 无障碍服务 | dsh-Go |
| P3 | 桌宠/主题 | 个性化 | Compose overlay | dsh-Go |
| P3 | 会话分享/LiveShare | 协作 | DSH LiveShare | 官方实验 |

---

## 12. 测试与验收矩阵

### 12.1 v1 历史测试矩阵

> v1 专用, 保留作为回归参考. v2 以 12.2 为准.

| 层级 | 内容 | 目标 |
| --- | --- | --- |
| 单元 | 工具策略、超时映射、截断、解析缓存 | 每个修复有测试 |
| 契约 | v1 schema、golden、审批 JWS | 三端一致 |
| 集成 | Go + PG + dsh + Android 模拟器 | 核心链路 |
| 设备 | 真实 Android, 弱网, 代理, 后台 | 目标机型 |
| 安全 | 撤销、重放、越权、恶意 Relay | 不可绕过 |
| 负载 | 100 会话 × 50 chunk/s | p99 延迟 |
| 故障 | DB 断、Relay 断、Host 断、手机断 | 可恢复 |
| 兼容 | dsh 当前版本 + 下一 RC | adapter 可升级 |

关键测试用例:

1. 流进行中撤销设备: 下一帧前订阅被丢弃, 手机进入 REVOKED, 本地缓存清理.
2. grant version 提升: 旧 lease 不能继续.
3. 3 MiB tool result: 会话可打开, 只显示截断提示.
4. 慢消息预览: 返回 `request.timeout`, 不进入 OFFLINE.
5. 并发 create + list + prompt: 无 `session.not_found`.
6. 1000 会话: page/prompt 延迟不随会话数增长.
7. 恶意 Relay: 无法读取 prompt/tool output (E2E 后).
8. 代理网络: Android 可连接.
9. 同 Wi-Fi 无 Relay: LAN 模式可用.
10. 第三方插件卡: 手机可显示并交互.

### 12.2 v2 测试矩阵 (主)

| 测试 | 目标 |
| --- | --- |
| unary contract golden | 手机/ Host 的 `{args}`/`{ok}` 一致 |
| stream frames golden | `open/item/end/cancel` 与 `item/error/end` 一致 |
| `$events` waterfall | ready/emit/waterfall/result 一致 |
| `session.follow` snapshot/event/assistant | 断线重连 baseline 正确 |
| multipart upload/download | 字节/headers 不变 |
| revocation mid-stream | Peer dispose, 流中止 |
| dsh 版本升级 | contract mismatch 明确失败 |
| 100 会话 | 无全量扫描 |
| 大记录 | 截断/提示 |
| 代理/弱网 | 可重连 |

## 13. 结论与建议

### 13.1 保留

- 原生 Compose UI 和现有移动交互.
- 设备配对、设备令牌、主机绑定、撤销、TLS、FCM、应用更新、诊断.
- 加密 transcript cache、离线历史、撤销清理.
- 可选的签名审批 (作为 `$events/result` 外层签名).
- 可选的 durable command journal (作为 `mobile.command` Typert 扩展).
- 对 dsh `session.follow`/`$events`/exact routes 的直接使用.

### 13.2 先做

1. **v2 contract + codegen**: 方法/流白名单, Kotlin/TS 类型, 版本握手.
2. **Host 适配器**: `createPeer` + `sharedFetch` + `wireStream` + `$events`.
3. **Android 核心客户端**: unary/stream/events; list/follow/prompt/cancel/create/model.
4. **审批与提问**: `$events` waterfall + `$events/result`; 可选验签.
5. **Relay 通用隧道**: 或先用 Tailscale/Cloudflare/FRP; 删除业务解析.
6. **删除 v1**: 逐项 parity 后清理协议层、Relay 解析器和 Android v1 客户端.

许可证、LAN 直连、E2E 可以与上述工作并行; 其中许可证是发布前必须完成的合规项.

### 13.3 再做

1. LAN 直连: 去掉"必须有 Relay"的硬门槛.
2. E2E: 把 Relay 从"可信"变成"不可读".
3. 终端/文件预览: 用 `mobile.terminal` + exact routes 补齐移动高频场景.
4. 插件 UI 回退: WebView 插件槽接住 dsh 生态.
5. Web/PWA: 最低成本的多端.
6. iOS 和原生插件 SDK: 在协议稳定后再做.

### 13.4 不要现在做

- 不要先做手机本地 runtime (dsh-Go 路线); 这会把你拖进 Linux 打包、PRoot、存储和功耗的深水区.
- 不要先做手机无障碍操控; 风险高、审核重、与核心远控无关.
- 不要同时做 iOS + Web + 原生插件 SDK; 先把 Web/PWA 或核心 v2 parity 做出来.
- 不要在 v2 未 parity 前继续给 v1 增加新功能; 那只会扩大迁移面.

### 13.5 一句话

**v2 的目标是: 手机说 dsh 的话, Host 插件做认证和传输, Relay 只搬字节; 保留 TokenWorks 在设备配对、签名审批和审计上的优势, 去掉第二套协议.** 如果目标只是个人使用, 直接用 dsh-mobile 或 ds-harness-remote 更省事; 如果目标是做差异化的安全原生远控, 这条路线值得继续, 但要把范围收紧, 把验收做实.

---

## 附录 A: 参考项目能力速查

| 能力 | dsh-mobile | ds-harness-remote | dsh-Go |
| --- | --- | --- | --- |
| Android 原生 UI | 否 (WebView) | 是 (RN) | 是 (Flutter) |
| iOS | PWA | TestFlight | 计划 |
| Web | 是 | 是 | 否 |
| 局域网 | 是 | 是 (LAN 优先) | SSH |
| P2P/TURN | iroh 实验 | 是 | 否 |
| E2E | 否 | 是 | DSH Remote 是 |
| 终端 | 是 (Web) | 是 | 未明确 (网页模式) |
| 文件预览 | 是 (Web) | 是 | 部分 (网页/分享) |
| 第三方插件 UI | 是 | 是 | 原生 SDK |
| 签名审批 | DSH 原生 | DSH 原生 | DSH 原生 |
| 许可证 | Apache-2.0 | 插件 MIT | AGPL-3.0 |

## 附录 B: v2 核心文件地图

```text
host/remote-control/src/
├── index.ts                     # 注入 connection/typertGateway, 生命周期
├── adapter/
│   ├── peer.ts                  # createPeer + dispose
│   ├── http.ts                  # sharedFetch.fetch(request, peer)
│   ├── streams.ts               # wireStream.open + frames
│   └── events.ts                # $events + $events/result
├── mobile-changes.ts            # @Remote mobile.changes
├── mobile-terminal.ts           # @Remote mobile.terminal
├── mobile-push.ts               # @Remote mobile.push
├── mobile-command.ts            # 可选 durable command
├── approval-signature.ts        # $events/result 外层验签
├── lan-gateway.ts               # 局域网 HTTPS/WSS
└── relay-tunnel.ts              # 通用隧道客户端

android/core/
├── network/DshTransport.kt      # HTTP/WS
├── network/DshRpc.kt            # unary
├── network/DshStream.kt         # mux
├── network/DshEvents.kt         # $events
├── session/DshSessionStore.kt   # follow frames
├── session/DshProjection.kt     # SessionWireEvent -> UI
└── command/MobileCommand.kt     # 可选 durable command

services/remote/internal/
├── relay/tunnel.go              # 通用 HTTP/WS 隧道
├── relay/device_auth.go         # 保留
├── relay/routing.go             # 保留
└── relay/...                    # 删除 session/approval/file/export 解析
```

## 附录 C: v2 白名单示例

```json
{
  "version": 1,
  "methods": [
    "session.list",
    "session.search",
    "session.create",
    "session.fork",
    "session.archive",
    "session.rename",
    "session.prompt",
    "session.cancel",
    "session.updateQueue",
    "session.modelCatalog",
    "session.selectModel",
    "session.attachment",
    "session.openWorkspacePath",
    "userQuestions.answer",
    "mobile.changes.follow",
    "mobile.command.submit"
  ],
  "streams": [
    "session.follow",
    "session.observeEvent",
    "session.observeState",
    "userQuestions.attachWait",
    "mobile.changes.follow",
    "mobile.terminal.open",
    "$events"
  ]
}
```

## 附录 D: 参考资料

- TokenWorks 仓库: <https://github.com/Enthusjast/tokenworks-mobile>
- dsh-mobile: <https://github.com/saya-ch/dsh-mobile>
- ds-harness-remote: <https://github.com/liguobao/ds-harness-remote>
- dsh-Go: <https://github.com/ccch1mneyyy/dsh-Go>
- DSH Typert 协议: <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/typert.zh.md>
- DSH API Gateway: <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/api-gateway.zh.md>
- DSH Remote 流帧: <https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/gateway/src/stream-protocol.ts>
- DSH Session Controller: <https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/session-controller/README.zh.md>
- DSH Connection: <https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/README.zh.md>
- DSH 审批: <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/approval.zh.md>
- DSH 用户提问: <https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/subsystems/user-questions.zh.md>

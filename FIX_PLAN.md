# TokenWorks Mobile 修复计划与功能扩展 (v2 surface)

- 审查对象: `Enthusjast/tokenworks-mobile`, 分支 `codex/m0-host-prototype`, HEAD `a0ea881` ("update root scripts and dev dependencies for the v2 surface").
- 与 `IMPROVE_PLAN.md` 的关系: 那份是 v2 架构方案 (怎么做); 本文档是当前代码的问题清单 (哪里坏了) 和功能扩展 backlog (还能加什么).
- 审查方式: 静态阅读源码/契约/文档 + 在本机实际执行 `npm ci` 验证; 未运行 `npm run check`/`build`/Gradle (被 F-01 阻塞).
- 验证环境: Windows 11, Node v26.7.0 (项目要求 `>=24.15.0 <25`, 验证时建议用 Node 24.20).

---

## 1. 执行摘要

### 1.1 一句话

v2 的骨架 (Host 原生适配器, Android dsh 客户端, Go `/v2` 隧道, 契约快照) 已经落地; 但当前处于 **"干净环境装不上依赖、审批/提问/文件/终端有代码没接线、文档还是 v1"** 的过渡状态. 先修 F-01/F-02/F-03 三个 P0, 再补 F-04~F-11, 项目才能进入"可日常使用".

### 1.2 问题总表

| 编号 | 级别 | 问题 | 一句话修复 | 阶段 |
| --- | --- | --- | --- | --- |
| F-01 | P0 | lockfile 幽灵依赖: 11 个 `@deepseek-ai/tokenworks-mobile-*` 包在 npm 上不存在, `npm ci` 必然 404 | 名字改回官方 `@deepseek-ai/dsh-*` | A |
| F-02 | P0 | Peer 模型卡死: 非 owner + `interactions:'none'`, `$events` 审批/提问和 exact routes 全部不可用 | 个人设备用 owner Peer + answer; 多用户用 owner 代理 | A |
| F-03 | P0 | LAN 网关不可用: action 名/资源形状不合法, WS 接线错误, 且从未挂载 | 修三处 bug + 挂载, 或先降级为"未启用" | C |
| F-04 | P1 | v2 模块"有代码没接线": `DshEvents`/`NativeInteractionBridge`/`DshTerminal`/`FilePreview` 在 App 中 0 引用 | 接入审批/提问/终端/文件预览 UI | B |
| F-05 | P1 | prompt 无稳定 requestId/outbox, 断线重试可能重复发消息 | 草稿绑定 requestId + 持久 outbox | B |
| F-06 | P1 | `DshMux` 每条流 `Channel.UNLIMITED`, 无背压 | 有界 channel + 溢出策略 | B |
| F-07 | P1 | 文档/脚本全面滞后于 v2 代码; 两份 plan 并存 | 以 `IMPROVE_PLAN.md` 为唯一方案, 重写 README/host README/status | A (并行) |
| F-08 | P1 | Host v2 传输层零测试 (`NativeGateway`/`RelayTunnel`/`NativeAccess` 无测试) | 补三类传输测试 | B |
| F-09 | P2 | host 包 peerDependencies 与实际 import 不符 (缺 3 个关键包, 多个陈旧项) | 按 import 对齐 | A |
| F-10 | P1 | `DshSessionReader` 无 AUTH/REVOKED 终态, 撤销/过期会无限退避重试 | 映射错误码到终态并停止重试 | B |
| F-11 | P2 | 开放问题的回答路径可疑: 调 `userQuestions/answer` 后又回 `Missing` result | 开放问题经 waterfall result 返回答案; `answer` 只用于 continued/迟到 | B |
| F-12 | P2 | `LanGateway` 每个 HTTP 请求新建 sharedFetch handler, 且 Peer 随请求建/毁 | 缓存 handler; 连接级 admission | C |
| F-13 | P2 | 大快照帧无分片/上限: `session.follow` 的 snapshot 可能超过客户端接收限制 | 实测帧大小; 需要时在 Host 侧分片 | C |
| F-14 | P2 | 本地未跟踪文档与已提交 plan 重复, 工作区有备份目录 | 二选一保留, 删除冗余 | A |

### 1.3 新增能力总表 (详见第 4 节)

| 优先级 | 能力 | 依赖 |
| --- | --- | --- |
| P1 | 审批/提问 UI 与签名决策 | F-02, F-04, F-11 |
| P1 | prompt outbox + queue/steer | F-05 |
| P1 | 终端 UI (`terminal/*` 契约已生成) | F-02 + 授权矩阵补 terminal |
| P1 | 文件附件/预览/导出 | F-02 (exact routes 需 owner) |
| P2 | 会话搜索/技能/文件引用 UI | 契约已就绪 |
| P2 | 工作区管理 UI (pin/rename/delete/create) | 契约已就绪 |
| P2 | LAN 配对/发现 UI | F-03 |
| P2 | 子会话 (subagent) 支持 | 类型已生成 (`address` Case2) |
| P2 | E2E (Noise IK over tunnel) | 隧道已就绪 |
| P2 | Web/PWA 客户端 | 复用 dsh 协议 |
| P3 | 推送/通知 (FCM 重新接入) | 服务端迁移已在 |
| P3 | 诊断/连接医生/日志导出 | - |
| P3 | 第三方插件 UI (WebView 插槽) | - |
| P3 | iOS 客户端 | 协议稳定后 |

---

## 2. 详细问题

### F-01 lockfile 幽灵依赖, 干净环境无法安装 (P0, 新发现)

**现象**

在本机对干净检出执行 `npm ci` 失败:

```text
npm error 404 Not Found - GET https://registry.npmjs.org/@deepseek-ai/tokenworks-mobile-host-webserver/-/tokenworks-mobile-host-webserver-0.2.1-rc.5.tgz - Not found
```

**证据**

lockfile 里有 11 个 `@deepseek-ai/tokenworks-mobile-*` 包条目 ([package-lock.json:5759](package-lock.json#L5759) 起):

```text
tokenworks-mobile-file-reference            ← dsh-file-reference
tokenworks-mobile-file-reference-local      ← dsh-file-reference-local
tokenworks-mobile-host-directory-picker     ← dsh-host-directory-picker
tokenworks-mobile-host-directory-picker-auto/browse/native
tokenworks-mobile-host-frontend-static      ← dsh-host-frontend-static
tokenworks-mobile-host-open-in-app          ← dsh-host-open-in-app
tokenworks-mobile-host-plugin-inventory     ← dsh-host-plugin-inventory
tokenworks-mobile-host-product-telemetry-otel
tokenworks-mobile-host-webserver            ← dsh-host-webserver
```

逐一核验结果 (11/11):

1. 这 11 个名字在公开 npm 上全部 404.
2. 每个包条目的 `integrity` 与官方 `@deepseek-ai/dsh-<同名>@0.2.1-rc.5` tarball **完全一致** (抽查 webserver/file-reference/frontend-static/open-in-app/plugin-inventory/telemetry-otel/directory-picker 系列/file-reference-local 全部 same).
3. 官方 `@deepseek-ai/dsh-web-app@0.2.1-rc.5` 的 manifest 引用的是**原始名** (`dsh-host-webserver` 等), 而 lockfile 里同一包却引用改名后的名字.
4. 仓库源码 0 处 import 这些名字; 也没有 `.npmrc`/`overrides`/私有 registry 说明.

结论: **这不是功能依赖, 是 lockfile 里的包名被机械替换过** (内容与官方 tarball 逐字节一致, 只有名字变了), 且替换后没有对应 registry 可解析. 引入路径: 根 `devDependencies` 的 `@deepseek-ai/dsh@0.2.1-rc.5` 拉入整个 bundle, 其中 `dsh-web-app`/`dsh-api-session-controller`/`dsh-api-workspace-controller`/`dsh-plugin-manager`/`dsh-deepseek-account-platform` 的 manifest 引用这 11 个名字.

**影响**

- 干净机器/CI 无法 `npm ci`; `npm run check`/`build`/`test` 全部被阻塞.
- 协作者无法复现环境; 与"MIT 开源"定位冲突.

**修复方案 (推荐 A)**

A. 把 lockfile 中 11 个名字改回官方名. 内容与官方 tarball 一致, 恢复名字后必然可安装:

```sh
# 在仓库根执行; 只影响这 11 个名字, 不会碰到其它内容
sed -i 's|@deepseek-ai/tokenworks-mobile-|@deepseek-ai/dsh-|g' package-lock.json
npm ci --ignore-scripts
npm run check
```

需要同步检查 `package.json` 里是否有引用 (当前没有); 修复后删除 lockfile 里残留的旧名字条目由 npm 自动完成.

B. 如果确实需要"自有命名"的 fork 包: 发布到私有 registry, 提交 `.npmrc` (registry + auth 说明), 并在 README 写清环境要求.

C. 用 npm `overrides` 把改名映射到官方包. 但既然内容逐字节相同, 这一层抽象没有收益, 不推荐.

**验收**

- 干净检出 `npm ci` 成功; `npm run check` 通过 (或暴露下一层真实问题).
- `npm view @deepseek-ai/<每个恢复后的包名> dist.integrity` 与 lockfile 一致.

---

### F-02 Peer 模型卡死 `$events` 审批/提问与文件 exact routes (P0)

**现象**

[host/remote-control/src/native-access.ts:94](host/remote-control/src/native-access.ts#L94) 为每条设备租约创建的 Peer 是:

```ts
const peer = this.connection.createPeer({ principal, interactions: 'none' })
```

其中 `principal` 是自定义的 `{ kind: 'tokenworks-device', ... }`, **不是 owner**.

**dsh 侧的两条硬规则**

1. `$events` 的 scoped waterfall (审批 `approval/request`, 提问 `user-questions/request`) 只投递给 **owner 且 `interactions:'answer'`** 的 Peer; 非 owner 只可能收到广播 `emit`, 且需要 `event:<name>` 授权. 见 [dsh API Gateway README](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/gateway/README.zh.md).
2. exact Fetch routes (文件下载 `/api/file`、上传等) 只放行 owner: [dsh connection rpc-host.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/src/rpc-host.ts) 中非 owner 一律 403.

**影响**

- 审批卡、提问卡永远收不到 waterfall → [android/.../NativeInteractionBridge.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/NativeInteractionBridge.kt) 与 `DshEvents` 是死代码.
- 即使接入, 非 owner 也无法回答 waterfall (`gateway/interactions-forbidden`).
- 文件上传/下载/预览经 exact routes 的路径全部 403.
- `IMPROVE_PLAN.md` 第 6.5/6.7 节设计的审批/提问桥接与 F-04 的接线全部被此阻塞.

**修复方案**

按产品形态二选一, 并把选择写进文档:

A. **个人/单用户设备 (推荐先做)**: 已配对设备直接映射为 owner Peer + `interactions:'answer'`. 改动点在 `NativeAccess.admit`/`#read`: 设备租约校验通过后, 用 `{ kind: 'owner' }` 创建 Peer. `remoteAccess` 对 owner 不生效 (dsh 语义), 设备撤销 = dispose Peer, 安全边界由配对/撤销承担.

B. **多用户/受控设备**: 保留非 owner principal, 但:
   - `interactions` 改 `'answer'` (至少允许收广播);
   - Go 侧 `OwnerBindings()` ([services/remote/internal/store/owner_bindings.go](services/remote/internal/store/owner_bindings.go)) 需要补充 `event:approval/request`、`event:user-questions/request`、`terminal/*` 等 action;
   - 审批/提问这种必须 owner 的交互, 由 Host 内一个 owner 身份的"交互代理"代替手机应答 (手机发指令给代理), 或明确声明"非 owner 设备不支持审批".

C. **Peer 生命周期**: 当前一个租约共享一个 Peer, 最多 8 个连接 ([native-access.ts:107](host/remote-control/src/native-access.ts#L107)). 若改为 `answer`, 多条 `$events` 流共用同一 Peer 会有"谁应答 waterfall"的歧义; 建议改为 **每连接一个 Peer** (principal 仍派生自租约), 断线即 dispose, 审批应答天然绑定到发起连接.

**验收**

- 手机能收到 `approval/request` waterfall 并通过 `$events/result` 完成 allow/reject.
- 非 owner 场景: 广播 `emit` 按 `event:*` 授权到达; scoped waterfall 明确不可达 (测试覆盖).
- exact route (文件) 在 owner Peer 下返回 200; 非 owner 下返回 403 (测试覆盖).

---

### F-03 LAN 网关不可用: 三处 bug + 未挂载 (P0)

**现象与证据**

1. **action 名/资源形状不合法**: [lan-gateway.ts:101](host/remote-control/src/lan-gateway.ts#L101) 的租约 introspection 返回 `actions: ['session.list', 'session.read', ...]` 且资源为 `{ kind: 'global' }`. 但 [native-access.ts:21](host/remote-control/src/native-access.ts#L21) 的 schema 要求 action 匹配 `namespace/method` 或 `event:...` → LAN 准入在 `#read` 解析时必然抛错, 所有请求 401.
2. **即使 action 合法, 授权也不对**: `global` 资源不覆盖 session/workspace 调用; `expand()` ([native-access.ts:136](host/remote-control/src/native-access.ts#L136)) 对 `session`/`workspace` kind 才会展开 identities. LAN 应返回 `{ kind:'session' }` / `{ kind:'workspace' }` 的 kind 级绑定.
3. **WS 接线错误**: [lan-gateway.ts:113](host/remote-control/src/lan-gateway.ts#L113) 把已升级的 `ws` WebSocket 传给 `NativeGateway.accept`, 后者把它当 HTTP Duplex 喂给 `http.createServer` ([native-gateway.ts:43](host/remote-control/src/native-gateway.ts#L43)); 应给 `NativeGateway` 增加 `acceptMux(ws, peer)` 公共入口, 两条路径 (HTTP upgrade 与 LAN) 共用.
4. **从未挂载**: `src/index.ts` 只创建 `NativeGateway` + `RelayTunnel`; 全仓库 `new LanGateway` 只出现在测试里. 没有 host/port/证书/配对令牌配置, 没有配对 UI.
5. LAN 授权矩阵只有 7 个 action, 缺 `session/cancel`、`session/rename`、`workspace/archiveSession`、`terminal/*`、`event:*` 等; 且 exact routes 仍受 F-02 owner 限制.

**测试现状**: [lan-gateway.test.ts](host/remote-control/tests/lan-gateway.test.ts) 只覆盖构造参数/phase/无令牌拒答, 不覆盖 listen/mux.

**修复方案**

1. `#introspect` 返回合法 bindings: `{ kind:'session' }` + `{ kind:'workspace' }` 两个 kind 级绑定, actions 用 `namespace/method` 语法, 并覆盖实际会用到的方法集 (与 Go `OwnerBindings()` 对齐).
2. `NativeGateway` 增加 `acceptMux(ws: WebSocket, peer: PeerScope)`, 内部直接调用现有 `#mux`; LAN 的 `upgrade` 分支改用它.
3. `index.ts` 增加 LAN 配置项 (host/port/cert/deviceToken), 默认关闭; 生成/轮换配对令牌的桌面入口; QR 展示.
4. 若短期不做 LAN, 从 `IMPROVE_PLAN`/README 中明确降级为"实验能力未启用", 避免文档宣称.

**验收**

- 同一 Wi-Fi、不启动 Relay: 手机完成配对 → list/follow/prompt 全链路可用.
- 错误令牌/未配对设备: 401, 无协议面暴露.
- `lan-gateway.test.ts` 增加 mux/授权用例.

---

### F-04 v2 功能模块"有代码没接线" (P1)

**现象**

以下模块有实现和 JVM 测试, 但 `android/app/src/main` 中 0 引用:

| 模块 | 状态 | 对应功能 |
| --- | --- | --- |
| [DshEvents.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/DshEvents.kt) | 有实现/测试, 未接线 | `$events` 流, waterfall 收发 |
| [NativeInteractionBridge.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/NativeInteractionBridge.kt) | 有实现/测试, 未接线 | 审批卡/提问卡状态 |
| [DshTerminal.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/DshTerminal.kt) | 有实现/测试, 未接线 | 终端 attach 流 |
| [FilePreview.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/FilePreview.kt) | 有实现/测试, 未接线 | 文件预览 |

同时, v1 时代的 UI 文件已被删除 (`ApprovalReviewScreen`, `QuestionCard`, `ExportsPanel`, `FcmPushProvider`, `ForegroundNotifications` 等), 当前 App 只有会话/聊天/模型/创建/归档/宿主绑定/更新/外观.

**影响**: 当前 App 是"核心聊天客户端"; 审批、提问、终端、文件、推送全部缺失. 配合 F-02 修复后, 需要一轮 UI 接线才能达到 `IMPROVE_PLAN` Phase 1 的 parity.

**修复方案**

1. `LoginController` 持有每 runtime 一个 `DshEvents` (mux 复用 `openMux`), 包一层 `NativeInteractionBridge`, 以 StateFlow 暴露 `interactions`.
2. 新增审批卡/提问卡 Composable (可参考删除前的 v1 版本, `git show` 旧提交可取回布局).
3. 终端: 新增 TerminalScreen, `terminal/list|create|close|environment` + `terminal/attach` 流; 契约已生成 (`DshMethods.Terminal*`).
4. 文件: 图片走 `session/attachment` (base64) + `FilePreview`; 下载走 exact route (需 F-02 owner).
5. 每个功能补一条端到端仪器测试 (fixture Host + 真实 dsh).

**验收**: 手机完成"审批一次 bash 调用"与"回答一个提问"的闭环; 终端能开/输入/缩放/关闭; 图片能显示.

---

### F-05 prompt 无稳定 requestId / outbox (P1)

**证据**

- [DshSession.kt:37](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/DshSession.kt#L37): `prompt(text, requestId = UUID.randomUUID())` 默认每次随机.
- [LoginController.kt:661](android/app/src/main/kotlin/cn/edu/ustc/tokenworks/LoginController.kt#L661): 发送时 `current.prompt(body)`, 未传 requestId, 无持久队列; 失败时仅恢复草稿.
- `DshSessionReader.prompt` 把 mode 写死为 `"queue"`, v1 的 steer 能力丢失.

**影响**: 请求已到达 Host 但手机未收到确认时, 重试会用新 requestId → dsh 视为新消息, 可能重复执行; 与 `IMPROVE_PLAN` 6.4/6.7 的 outbox 设计相悖.

**修复方案**

1. `prompt` 增加 `mode: 'queue' | 'steer'` 参数, UI 长按发送 = steer.
2. 发送前生成 `requestId = UUID` 并与草稿一起持久化 (Room/SQLCipher 或轻量文件); 成功确认后清除; 失败保留并允许"重试 (同 requestId)/放弃".
3. `dsh` 的 `session.prompt` 对已入队 requestId 幂等, 因此重试安全; 其它命令 (cancel/archive/create/fork) 不自动重试, 结果未知时先查询状态.
4. 断线恢复后, outbox 中 pending 项显示在 UI (带"已提交, 结果未知"态).

**验收**: 断网发送 → 重连 → 重试同 requestId → dsh 只入队一次 (用固定 prompt 前缀验证).

---

### F-06 `DshMux` 无背压 (P1)

**证据**: [DshMux.kt:46](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/DshMux.kt#L46) `Channel<DshMuxFrame>(Channel.UNLIMITED)`.

**影响**: assistant chunk 高频到达而 UI 消费慢 (后台/卡顿) 时, 内存无界增长; 与 v1 `SessionReader` 的有界队列 + 丢弃策略相比是回退.

**修复方案**

- `DshStream` 通道改为有界 (如 256 帧 / 4 MiB 字节预算), 满时挂起读取者或对 `assistant.chunk` 类可丢弃帧合并 (保留最新), 对 `snapshot/event/checkpoint` 绝不丢弃而是反压到 socket 读循环.
- Host 侧已有 4 MiB socket 缓冲保护 ([native-gateway.ts:81](host/remote-control/src/native-gateway.ts#L81)); 客户端补齐消费端.

**验收**: 人为延迟 UI 消费, 内存有界; 恢复后不丢持久事件.

---

### F-07 文档/脚本滞后于 v2, 两份 plan 并存 (P1)

**证据**

| 文件 | 现状 |
| --- | --- |
| [README.md](README.md) | 项目结构表仍写 Host "命令日志、签名审批与文件交接", `services/remote` "账号网页" 等 v1 描述 |
| [host/remote-control/README.md](host/remote-control/README.md) | 整篇是 v1 (probe socket、command journal、签名审批、`protocol.ts`), 而这些文件已删除 |
| [docs/development-status.md](docs/development-status.md) | 仍是 Go relay/签名审批/文件服务的 v1 状态; 无任何 v2/Native/Tunnel 内容 |
| [services/remote/README.md](services/remote/README.md) | 描述 v1 session/command/approval 路由; 实际 Go 已改为 `/v2` tunnel/leases |
| [PLAN.md](PLAN.md) (1433 行) | 旧 v1 计划; [IMPROVE_PLAN.md](IMPROVE_PLAN.md) (2192 行) 才是现行 v2 方案 |
| [docs/README.md](docs/README.md) | 未链接任何 v2 方案文档 |
| 本地 `docs/architecture-review-vs-references.md` | 未跟踪, 与 `IMPROVE_PLAN.md` 内容重复 |
| `npm run test:remote-dsh` / `run_remote_dsh.py` | 脚本已删, 文档仍引用 |

**修复方案**

1. 宣布 `IMPROVE_PLAN.md` 为唯一方案; `PLAN.md` 移入 `docs/archive/` 或删除.
2. 重写根 README 的项目结构/开发入口 (v2: 插件 + `/v2` 隧道 + LAN + Android dsh 客户端; 去掉账号网页描述或按现状改).
3. 重写 host README (v2: NativeGateway/RelayTunnel/NativeAccess/LanGateway, 配置样例, 认证模型).
4. `docs/development-status.md` 重写为 v2 能力表 (已接线/未接线/阻塞中), 并链接 `IMPROVE_PLAN.md`.
5. `docs/README.md` 增加方案链接; 清理指向已删脚本/文件的链接.
6. v1 专题文档 (approval-native/file-*/question-* 等) 归档到 `docs/archive/v1/`, 或在顶部加"历史, 对应 v1"横幅.

**验收**: 新人按 README 从干净检出走到 `npm run dev` 不再被不存在的东西误导.

---

### F-08 Host v2 传输层零测试 (P1)

**证据**: [host/remote-control/tests](host/remote-control/tests) 只有 canonical/desktop-ui/go-bundle-validator/host-enrollment-*/host-local-enrollment/host-resource-jwt/jwks-cache/lan-gateway (构造级). `NativeGateway`/`RelayTunnel`/`NativeAccess` 无任何测试.

**修复方案** (每类至少 3 个用例):

- `NativeGateway`: HTTP 请求映射 (含 multipart 上传/下载, set-cookie 透传, 404/401), mux `open/item/end/cancel` 与 `error/end` 回传, 背压 (bufferedAmount 超限 1013), peer dispose 后 401.
- `RelayTunnel`: DPoP challenge/nonce 重试, 断线退避, revoked/auth_required 终态停止, GOAWAY 顺序不崩溃.
- `NativeAccess`: 租约刷新/失效/过期, `expand()` 的 kind 级展开, 非法 action 拒绝, 8 连接上限.
- Android 侧已有 `DshMuxTest`/`DshEventsTest` 等, 补一条"Host↔Android 真实 TLS"集成用例 (可复用旧 `run_remote_dsh` 的思路, 但走 v2 隧道).

**验收**: `npm test` 覆盖传输层核心分支; CI 可跑.

---

### F-09 host 包 peerDependencies 与实际 import 不符 (P2)

**证据**: [host/remote-control/package.json](host/remote-control/package.json) 的 peerDependencies 缺少源码实际引用的:

- `@deepseek-ai/dsh-client-connection` (native-gateway/lan-gateway/relay-tunnel)
- `@deepseek-ai/dsh-api-gateway` (+ `.../stream-protocol`)
- `@deepseek-ai/dsh-api-remote-access`

同时保留着 v1 时代、现在 `inject` 已不含的项 (`dsh-user-approval`, `dsh-user-questions`, `dsh-tools`, `dsh-attachment`, `dsh-client-file-upload`, `dsh-fs-*`, `dsh-working-directory`, `dsh-llm`, `dsh-agent`, `dsh-api-session-controller`, `dsh-session-projection`).

**修复**: 按 `import` 语句对齐 peerDependencies (保留 cordis/loader/schemastery/zod/typert-protocol/session/workspace + 新增 3 个), 删除未引用项; 以 `tsc --noEmit` 在"仅安装 peer"的最小环境验证.

---

### F-10 `DshSessionReader` 无 AUTH/REVOKED 终态 (P1)

**证据**: [SessionReadState.kt:6](android/core/session/src/main/kotlin/cn/edu/ustc/tokenworks/SessionReadState.kt#L6) 定义了 `AUTH_REQUIRED`/`REVOKED`, 但 [DshSession.kt](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/DshSession.kt) 的 follow 循环把所有错误都归入 `BACKOFF` 无限重试 (`safeCode` 只透传 code), 不会停止.

**影响**: 设备被撤销/租约过期/授权版本变更后, App 永远转圈重试; 服务端持续被打.

**修复**: 在 follow 循环区分错误类: `auth.*`/`lease.*`/`access.*` → 发布 `AUTH_REQUIRED`/`REVOKED` 并停止重试; 网络类 → BACKOFF; 协议类 → ERROR + 手动重试. 与 `LoginController` 的登录态联动 (REVOKED → 清缓存, 回绑定页).

---

### F-11 开放问题的回答路径可疑 (P2)

**证据**: [NativeInteractionBridge.kt:104](android/core/network/src/main/kotlin/cn/edu/ustc/tokenworks/NativeInteractionBridge.kt#L104): `submitAnswers` 先 `post("userQuestions/answer", args)`, 然后 `result(eventId, DshEventOutcome.Result(DshOptional.Missing))`.

**问题**: dsh 语义中, **开放中的问题只能通过 waterfall 返回值回答**; `userQuestions.answer` 是给 continued/迟到问题排入用户消息用的. 现在的实现把 waterfall result 设为 `Missing`, Host 的提问监听器会拿到"空结果", 问题可能既没被真正回答, 也没被正确拒绝.

**修复**: 开放问题 → `DshEventOutcome.Result(结构化答案)` 直接作为 waterfall result; 仅当问题已 `continued` (timed 超时后) 才走 `userQuestions/answer`. 补一条 Host 侧联调用例验证"答案到达工具结果".

---

### F-12 `LanGateway` 每 HTTP 请求新建 handler/Peer (P2)

**证据**: [lan-gateway.ts:143](host/remote-control/src/lan-gateway.ts#L143) 每个请求调用一次 `createSharedFetchHandler('/api')`; `#admit` 返回的 admission 在请求结束即 `release()`, 而 `NativeAccess` 在连接数归 0 时 dispose Peer 并 `invalidate` 授权 → 下一个请求重建 Peer/重新 introspect.

**修复**: 构造函数缓存 sharedFetch handler; HTTP 请求复用与 WS 相同的 admission (按令牌绑定连接级 Peer, 或至少把 Peer 保留一个 TTL); 这也是 F-02 改 owner Peer 后的必要配套.

---

### F-13 大快照帧无分片 (P2, 待实测)

**现象**: `session.follow` 的 opening snapshot 把整段历史放进一帧. v1 自定义协议有 64 KiB 帧分片 + 2 MiB 上限; v2 `NativeGateway.#mux` 的发送路径没有大小上限 (仅 bufferedAmount 背压), `WebSocketServer.maxPayload` 2 MiB 只约束**入站**.

**风险**: 大会话首屏帧可能超过 Android 端接收限制 (OkHttp/底层默认值需实测), 表现为连接被对端关闭且每次重连都在同一帧上失败.

**修复**: 实测最大帧; 若超限, 在 Host `#mux` 对超过阈值的 item 做 `session.fragment` 式分片 (手机端 `SessionFragments` 已有解析), 或把 snapshot 拆成 snapshot + 分页. Android `DshMux.receive` 侧加帧大小日志.

---

### F-14 文档副本与备份目录 (P2, hygiene)

- 本地未跟踪的 [docs/architecture-review-vs-references.md](docs/architecture-review-vs-references.md) 与已提交的 `IMPROVE_PLAN.md` 内容基本相同 (前者多 2 行说明).
- 工作区根存在 `backup-review-docs/` (含两份旧文档拷贝), 不在 git 内.

**修复**: 二选一保留方案文档 (建议只留 `IMPROVE_PLAN.md`); 删除未跟踪副本与 `backup-review-docs/`; 若需要保留审查历史, 在 git 里以 `docs/archive/` 提交一次即可.

---

## 3. 新增能力 backlog (在 F-01/F-02 解锁后)

> 每项给出: 为什么值得做 / 怎么实现 (落到现有模块) / 前置依赖. 契约基础: `contracts/typert/native-api.json` 已生成 `session/*`, `workspace/*`, `terminal/*`, `userQuestions/*`, `fileUploads/*`, `skills/list`, `fileReferences/list`, `remoteAccess/follow` 等全部方法, Android 侧 `DshNativeTypes.kt` 已有对应 codec, 大部分功能不需要再动协议.

### N-1 审批/提问 UI + 决策签名 (P1)

- 为什么: 远程控制的安全核心; 也是 `IMPROVE_PLAN` Phase 1 的 parity 项.
- 实现: F-04 接线 + F-11 修正回答路径; 决策前在手机端对 canonical context 做 `ApprovalSignatures` 签名 (android/core/crypto 已有), Host 侧在 `$events/result` 转发前验签 (v2 计划 4.8).
- 前置: F-02 (owner Peer), F-04.

### N-2 终端 (P1)

- 为什么: 移动端"看一眼输出/敲一条命令"的高频场景.
- 实现: `DshTerminal` + `terminal/list|create|close|environment|attach` (契约与 Kotlin codec 已生成); UI 用 Compose 自绘或内嵌终端视图; **注意 Go `OwnerBindings()` 目前没有 `terminal/*` action, 需要在 F-02 一并补授权矩阵**; `tests/fixtures/host-terminal-pty.py` 说明 Host 侧 PTY 夹具已有基础.

### N-3 文件附件/预览/导出 (P1)

- 实现: 图片走 `session/attachment` + `FilePreview`; 上传走 `fileUploads/upload` (action 已在 global 绑定); 下载/导出走 exact routes (F-02 owner 后可用); 大文件建议加 Range/分块.
- 前置: F-02.

### N-4 prompt outbox + queue/steer (P1)

- 实现: 见 F-05; 顺带把 `session.updateQueue` (契约已有) 做成队列管理 UI (查看/编辑/删除排队消息).

### N-5 会话搜索 / 技能 / 文件引用 (P2)

- 实现: `session/search`, `skills/list`, `fileReferences/list` 均已在契约与 Kotlin codec 中; 在会话页加搜索框与"插入引用"入口.

### N-6 工作区管理 UI (P2)

- 实现: `workspace/follow` (流) + `pin/unpin/insertBefore/rename/delete/create/initializeDefault`; 恢复 v1 的置顶/排序体验; `workspaceId` 参与授权矩阵.

### N-7 子会话 (subagent) (P2)

- 实现: 生成的 `SessionFollowInputRequestAddress` 已支持 `{ kind:'subagent', parentSessionId, childSessionId, mode }`; UI 上把子 Agent 会话挂到父会话下; 注意 dsh 对 subagent 的授权矩阵 (v1 经验: 需要显式授权).

### N-8 LAN 配对/发现 UI (P2)

- 实现: F-03 修复后, 桌面端生成配对二维码 (令牌 + 指纹 + 地址), 手机扫码保存; mDNS/手动输入兜底; 连接选择器 LAN → Relay 自动回退 (v1 `ConnectionSelector` 思路).

### N-9 端到端加密 (P2)

- 实现: 在隧道层做 Noise IK (`Noise_IK_25519_ChaChaPoly_SHA256`), Host 出站 + 手机入站, Relay 只见密文; dsh 协议字节不动; 参考 ds-harness-remote.
- 前置: 隧道稳定后; 多设备密钥目录复用现有设备注册.

### N-10 Web/PWA 客户端 (P2)

- 实现: 一个 TypeScript 客户端复用同一 `/api` + `/api/remote.mux` + `$events`; 部署在 Host 插件自己的 HTTPS 入口; 与 Android 共享同一套认证/授权模型.

### N-11 推送/通知 (P3)

- 实现: 服务端迁移 (014/015) 与文档仍在; 客户端恢复轻量 FCM 接收 + 点击后走已授权会话读取; 只携带引用不携带正文.

### N-12 诊断/连接医生 (P3)

- 实现: 客户端记录连接阶段/错误码/重连序列; "导出诊断"生成脱敏文本 (不含正文/令牌); Host 侧已有 desktop-ui 状态可复用.

### N-13 第三方插件 UI (P3)

- 实现: WebView 插槽加载 dsh Web UI 的对应区域 (同源 + 白名单), 主会话仍原生; 这也是 dsh 插件生态进入手机的最低成本路径.

### N-14 iOS 客户端 (P3)

- 实现: 协议已文档化且为纯 HTTP/WS, SwiftUI 或 RN 均可; 建议在 E2E 与 LAN 稳定后再启动.

### 明确不建议现在做

- 手机本地 runtime (PRoot/ Termux 路线), 手机无障碍操控, iOS + Web + 原生插件 SDK 三线并进.

---

## 4. 建议执行顺序

### Phase A · 解锁与对齐 (第 1 周)

1. F-01 修 lockfile, `npm ci` + `npm run check` 跑通.
2. F-02 定 Peer 模型 (owner for personal; 补 `OwnerBindings` 的 `event:*`/`terminal/*`).
3. F-09 对齐 peerDependencies.
4. F-07/F-14 文档统一 (IMPROVE_PLAN 唯一化, README/host README/status 重写, 删重复文档).
5. F-08 启动传输层测试.

### Phase B · 核心闭环 (第 2–4 周)

1. F-04 审批/提问接线 (含 F-11 修正).
2. F-05 outbox + queue/steer; F-06 背压; F-10 终态.
3. F-08 完成; 真机验收"发送→流式→审批→提问"闭环.

### Phase C · 完整度 (第 5–8 周)

1. F-03 LAN 修复 + N-8 配对 UI.
2. N-2 终端, N-3 文件, N-5/N-6/N-7 按 UX 优先级排.
3. F-12/F-13 边角修复.

### Phase D · 隐私与生态 (持续)

1. N-9 E2E.
2. N-10 Web/PWA.
3. N-11 推送, N-12 诊断.
4. N-13 插件 UI, N-14 iOS.

---

## 5. 验收清单 (合并)

- [ ] 干净检出: `npm ci` 成功; `npm run check`/`npm test` 通过 (F-01).
- [ ] 同 Wi-Fi 无 Relay: 配对 → 会话列表 → 发消息 → 流式回复 (F-03, N-8).
- [ ] 有 Relay: 外网完成同样闭环; 撤销设备后 30 秒内断开并清缓存 (F-02, F-10).
- [ ] 工具审批: 手机收到卡片 → allow → bash 执行; reject → 不执行; 篡改/重放被拒 (N-1).
- [ ] 提问: 选项提交后 Agent 继续推进; timed 超时后迟到回答走消息路径 (F-11).
- [ ] prompt 幂等: 断线重试不产生重复消息 (F-05).
- [ ] 终端: 打开/输入/缩放/关闭 (N-2).
- [ ] 文件: 图片预览 + 附件上传 + 导出下载 (N-3).
- [ ] 文档: 新人按 README 走通; 无指向已删文件的链接 (F-07).

---

## 附录 A · lockfile 幽灵包对照与修复

| lockfile 名 (不存在) | 官方名 (存在) | integrity 对比 |
| --- | --- | --- |
| `@deepseek-ai/tokenworks-mobile-host-webserver` | `@deepseek-ai/dsh-host-webserver` | same |
| `@deepseek-ai/tokenworks-mobile-host-frontend-static` | `@deepseek-ai/dsh-host-frontend-static` | same |
| `@deepseek-ai/tokenworks-mobile-host-open-in-app` | `@deepseek-ai/dsh-host-open-in-app` | same |
| `@deepseek-ai/tokenworks-mobile-host-plugin-inventory` | `@deepseek-ai/dsh-host-plugin-inventory` | same |
| `@deepseek-ai/tokenworks-mobile-host-product-telemetry-otel` | `@deepseek-ai/dsh-host-product-telemetry-otel` | same |
| `@deepseek-ai/tokenworks-mobile-file-reference` | `@deepseek-ai/dsh-file-reference` | same |
| `@deepseek-ai/tokenworks-mobile-file-reference-local` | `@deepseek-ai/dsh-file-reference-local` | same |
| `@deepseek-ai/tokenworks-mobile-host-directory-picker` | `@deepseek-ai/dsh-host-directory-picker` | same |
| `@deepseek-ai/tokenworks-mobile-host-directory-picker-auto` | `@deepseek-ai/dsh-host-directory-picker-auto` | same |
| `@deepseek-ai/tokenworks-mobile-host-directory-picker-browse` | `@deepseek-ai/dsh-host-directory-picker-browse` | same |
| `@deepseek-ai/tokenworks-mobile-host-directory-picker-native` | `@deepseek-ai/dsh-host-directory-picker-native` | same |

修复命令 (在仓库根, 提交前请 review diff):

```sh
sed -i 's|@deepseek-ai/tokenworks-mobile-|@deepseek-ai/dsh-|g' package-lock.json
npm ci --ignore-scripts
npm run check
```

引用方 (lockfile 中引用这些名字的包): `dsh-web-app` (10 个), `dsh-api-session-controller` (file-reference), `dsh-api-workspace-controller` (directory-picker), `dsh-plugin-manager` (plugin-inventory), `dsh-deepseek-account-platform` (host-webserver, peer).

## 附录 B · 验证命令

```sh
# Host 侧
npm ci --ignore-scripts
npm run check                     # tsc --noEmit
npm run contracts:typert:check    # 契约快照是否过期
npm test                          # host 单测 (当前覆盖面见 F-08)
npm run build

# Android 侧
npm run test:android:core         # JVM 模块测试 (无需 Android SDK)
npm run test:android              # 需要 Android SDK/模拟器
```

## 附录 C · 参考

- 架构方案: `IMPROVE_PLAN.md` (本文的问题均可在其第 6/7 节找到对应设计).
- dsh 权限规则: [API Gateway README](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/gateway/README.zh.md), [Connection rpc-host.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/client/connection/src/rpc-host.ts).
- dsh 流帧: [stream-protocol.ts](https://github.com/deepseek-ai/deepseek-harness/blob/master/packages/api/gateway/src/stream-protocol.ts).
- 参考项目: dsh-mobile / ds-harness-remote / dsh-Go (见 `IMPROVE_PLAN.md` 第 10 节).

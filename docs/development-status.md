# 当前开发状态与验证入口 (v2)

[文档导航](README.md) · [项目首页](../README.md) · [v2 架构方案](../IMPROVE_PLAN.md) · [修复计划](../FIX_PLAN.md)

本页描述 v2 代码的**实际接线状态**。历史验证记录见 [verification-history.md](verification-history.md)；
v1 构建史见 [archive/PLAN-v1.md](archive/PLAN-v1.md)。v1 专题文档（approval-*/file-*/question-* 等）
描述的能力已随 v1 协议层移除，仅作历史参考。

## 基线

| 项目 | 依据 |
| --- | --- |
| npm DSH | 锁定 `0.2.1-rc.5`；`npm run contracts:typert:check` 检查快照过期（当前 38 个方法） |
| Node | `>=24.15.0 <25` |
| Go | 1.26.x；数据库基线 `deploy/sql/baseline.sql` |
| Android | JVM 模块测试 `npm run test:android:core`；真机验收另行安排 |

## 能力接线状态

| 能力 | 代码 | 接线 | 说明 |
| --- | --- | --- | --- |
| 会话列表/打开/流式/发送/取消 | Host+Android | ✅ | `session/list,follow,page,prompt,cancel` 经隧道 |
| 会话创建/fork/归档/重命名 | Host+Android | ✅ | 控制面 unary |
| 模型目录/选择 | Host+Android | ✅ | `session/modelCatalog,selectModel` |
| 审批/提问（`$events` waterfall） | `DshEvents`+`NativeInteractionBridge`（Host/Android 均有） | ⚠️ App UI 未接 | F-02 owner Peer 已解锁 waterfall 投递；卡片 UI 待接（FIX_PLAN F-04） |
| 终端 | `DshTerminal` + Host `terminal/*` 契约 | ⚠️ App UI 未接 | `DshTerminalTest` 覆盖帧解码；TerminalScreen 待建 |
| 文件预览/附件 | `FilePreview` + `/api/file` exact route | ⚠️ App UI 未接 | exact route 需 owner Peer（F-02 已默认 owner） |
| LAN 直连 | `LanGateway`（Host） | ❌ 未挂载 | 默认关闭；F-03 修复中 |
| 推送/通知 | 已删除 | — | P3，见 FIX_PLAN N-11 |
| 会话搜索/技能/文件引用 | 契约+codec 已生成 | ❌ | N-5 |
| 工作区管理 UI | 契约+codec 已生成 | ❌ | N-6 |

## 验证入口

| 范围 | 命令 |
| --- | --- |
| Host 传输层 | `npm test`（71 用例：tunnel/gateway/access/lan/desktop/enrollment） |
| 契约快照 | `npm run contracts:typert:check` |
| 类型 + 构建 | `npm run check` / `npm run build` |
| Android JVM | `npm run test:android:core` |
| Relay 隧道 | `cd services/remote && go test ./internal/relay/ -race` |
| 组合 | `npm run dev`（私有 PG + Go + TS watch + dsh HMR），状态以 `dev:status` 为准 |

## 仍开放的验收出口

- 真机端到端闭环（发送→流式→审批→提问）未验收；依赖 F-04 UI 接线。
- LAN 配对 UI 与真机验收未开始（F-03/N-8）。
- prompt outbox 幂等验收未开始（F-05）。
- 学校 SSO、生产密钥托管、FCM、备份设施不在 v2 范围（P3/Phase D）。

完整计划与优先级见 [`FIX_PLAN.md`](../FIX_PLAN.md)。

# tokenworks-mobile Host 插件 (v2)

本包是默认关闭的 Cordis 函数插件，导出 `name / inject / Config / apply`。它只做两件事：
**认证已配对设备** 和 **把 dsh 原生协议安全地透传给同进程的 dsh**。会话、命令、审批、
提问、文件的语义全部属于 dsh；本插件不理解也不重写它们。

架构与设计决策见 [`IMPROVE_PLAN.md`](../../IMPROVE_PLAN.md) 第 6.3/6.5 节。

## 模块

| 模块 | 职责 |
| --- | --- |
| `relay-tunnel.ts` | 出站 WSS 隧道（`RelayTunnel`）：安装凭据 DPoP、租约 introspection、`NativeTunnelPhase` 终止状态机（`revoked/auth_required/unsupported` 停止重连） |
| `native-gateway.ts` | 帧透传（`NativeGateway`）：`/api` 经 sharedFetch，mux 流经 `wireStream.open`；4 MiB socket 背压 |
| `native-access.ts` | 准入（`NativeAccess`）：租约 → Peer。默认 **owner 模式**（个人设备，`interactions: 'answer'`，审批/提问/exact routes 可用）；`device` 模式保留非 owner principal 供受控部署，走 remoteAccess policy |
| `lan-gateway.ts` | **实验能力，默认未启用**：LAN HTTPS/H2 直连（`LanGateway`），设备令牌配对，同一协议面 |
| `desktop-ui.ts` | dsh 账号菜单内的状态页（`/tokenworks-mobile/status`，owner-only） |
| `host-enrollment*` / `host-credentials.ts` | 安装凭据获取、DPoP 与 OS 密钥库适配 |

## 配置

最小启用（Relay 路径）：

```yaml
- id: tokenworks-mobile
  name: /absolute/path/to/tokenworks-mobile/host/remote-control/lib/index.js
  config:
    enabled: true
    runtimeId: 44444444-4444-4444-8444-444444444444
    relayUrl: https://your-relay.example.edu
    applicationVersion: 1.0.0
```

发行集成通过 `HostCredentialProvider` 提供安装凭据；不要把安装私钥写进 DSH YAML。

`peerMode`（经宿主集成传入 `RelayTunnel`）：`owner`（默认，个人设备）或 `device`（受控部署，
需配合 remoteAccess 授权矩阵与 Go 侧 `OwnerBindings()`，见
[services/remote/internal/store/owner_bindings.go](../../services/remote/internal/store/owner_bindings.go)）。

`relayUrl` 仅接受 HTTPS/WSS。LAN 网关是独立入口，当前默认不启用；启用需要 host/port/证书/
配对令牌配置，并自行承担暴露面评估。

## 认证模型

1. Host 出站连接 Relay（不需要公网入站端口）。
2. 手机向 Relay 出示设备令牌 + DPoP 证明；Relay 校验租约后把请求沿隧道转发。
3. 隧道内每条 CONNECT 经 `NativeAccess.admit` 准入：租约过期/撤销即 dispose Peer。
4. owner 模式下设备即 Host 本机用户；安全边界由配对流程与撤销承担。

## 开发

```sh
npm run build          # tsc -p tsconfig.build.json → lib/
npm test               # 71 用例（tunnel/gateway/access/lan/desktop/enrollment/...）
```

`development.mjs` 供 `npm run dev` 监督使用；测试夹具见 `tests/harness.ts`。

# Remote service (Go `/v2` tunnel)

Go 服务已从 v1 业务控制面缩减为 **通用 `/v2` 隧道**：设备认证、租约、节点路由和字节转发。
会话、审批、文件、命令语义全部在 dsh（Host 插件）内；本服务不解析 dsh 帧。

架构决策见 [`IMPROVE_PLAN.md`](../../IMPROVE_PLAN.md) 第 6.3/6.6 节。

## 模块

| 模块 | 职责 |
| --- | --- |
| `internal/relay/tunnel.go` | 每 Host runtime 一条 HTTP/2 CONNECT 隧道；每租约 8 / 每 runtime 128 连接预算；控制事件驱动撤销 |
| `internal/relay/routing.go` | 节点间转发（mutual TLS，cluster CA 携带唯一 node URI SAN） |
| `internal/relay/ids.go` | UUID 语法校验 |
| `internal/auth` | 安装凭据、DPoP、设备注册/撤销 |
| `internal/store` | 隧道租约、owner 绑定矩阵（`OwnerBindings()`）、运行时连接 |
| `internal/accounts` | 本地用户名密码账号（独立于隧道授权） |
| `internal/releases` | Android 发行包目录与策略 |

## 验证

```sh
go build ./...
go test ./internal/relay/ -race        # 隧道预算/撤销/终止
go test ./internal/...                 # auth、store、releases 等
```

## 历史

v1 时代的会话/审批/文件/命令路由、签名审批、推送、冷归档与审计归档已被移除
（见 `FIX_PLAN.md` F-01~F-03 与 `IMPROVE_PLAN.md` §6.6）；对应专题文档保留在
`docs/` 仅作历史参考，描述的能力不再存在于本服务。

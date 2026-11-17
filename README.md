# tokenworks-mobile

TokenWorks Mobile 移动远程控制平台：进程内 Host 插件（dsh 原生适配器）、Go `/v2` 通用隧道，以及原生 Android dsh 客户端。
手机直接说 dsh 的 Typert 协议（`/api`、`/api/remote.mux`、`$events`）；Host 插件只做认证与传输适配；Relay 只搬字节，不解析业务。

[连接与配对](docs/guide/01-connect.md) · [会话、审批与文件](docs/guide/02-sessions.md) · [故障排查](docs/guide/03-troubleshooting.md) · [安全说明](SECURITY.md) · [隐私与数据](PRIVACY.md)

## 项目结构

| 位置 | 职责 |
| --- | --- |
| [host/remote-control](host/remote-control/README.md) | Cordis 插件：认证 + 传输适配（RelayTunnel 隧道、NativeGateway 帧透传、NativeAccess 准入、LanGateway LAN 直连） |
| [services/remote](services/remote/README.md) | Go `/v2` 通用隧道：设备认证、租约、路由配对与字节转发 |
| [android](android/README.md) | 原生 Android dsh 客户端：unary/mux/`$events`/exact routes |
| [contracts](contracts/) | dsh 原生 API 构建期类型快照（38 个方法，生成基线 `0.2.2-rc.1`） |
| [deploy](deploy/) | v2 起点数据库基线与受控配置 |
| [tools](tools/) | 本机开发、契约生成、发行构建 |
| [docs](docs/README.md) | 指南与协议说明；v1 专题文档仅作历史参考 |

插件与客户端接受完整 dsh `0.2.2` 系列，包括合法的预发布版和构建元数据；peer 范围为 `>=0.2.2-0 <0.2.3-0`，不接受相邻版本。原生 API 构建／生成依赖固定使用已验证的 `0.2.2-rc.1`：`npm run contracts:typert` 从已安装依赖生成
`contracts/typert/native-api.json` 和 Android `DshNativeTypes.kt`；`npm run contracts:typert:check`
只检查产物是否过期。这些文件是构建期类型快照，不是新的业务协议或服务端握手。
加密握手准入按系列判断，但 READY 必须与同次已认证 HELLO 的完整版本字符串一致；显式配置的远端版本 pin 仍要求精确匹配。实际原生调用与集成验证使用 `0.2.2-rc.1`，不把版本范围声明当作其他发行版已逐一验收。


## 本机开发

Linux 开发环境需要 Node.js `>=24.15.0 <25`、npm、已安装的 dsh `0.2.2` 系列（当前验证基线 `0.2.2-rc.1`）、
Go 1.26.x、PostgreSQL 18 工具及 Python 3。依赖与路径说明见[本机持续开发](docs/local-development.md)。

```sh
npm ci --ignore-scripts
npm run build
npm run dev
npm run dev:status
```

`dev` 启动私有 PostgreSQL、本地身份夹具、Go 隧道、TypeScript watch 和 dsh 插件 HMR。
已有 dsh 在运行时，按[现有 Web profile 接入指南](docs/dsh-desktop-plugin.md)使用 `--attach-pid`。
运行状态和实际地址以 `dev:status` 为准。

| 入口 | 地址或操作 |
| --- | --- |
| 本地身份夹具 | `npm run dev` 启动的 issuer（地址以 `dev:status` 为准） |
| Go 隧道就绪检查 | `dev:status` 输出的 `/readyz` |
| dsh Web | 默认 `http://127.0.0.1:3000`；使用 dsh 自己的浏览器授权 |
| 插件状态 UI | dsh 账号菜单 → 设置 → 手机远控 |
| 停止开发服务 | `npm run dev:stop` |

## 验证

```sh
npm run verify                # 契约快照 + tsc + 构建
npm test                      # Host 传输层套件（tunnel/gateway/access/lan/desktop）
npm run test:android:core     # Android JVM 模块（无需 SDK）
cd services/remote && go test ./internal/relay/ -race   # Relay 隧道
```

## 继续阅读

- 使用或调试：[本机持续开发](docs/local-development.md)、[已有 dsh 接入](docs/dsh-desktop-plugin.md)。
- 协议：dsh 原生 `/api`、`/api/remote.mux`、`$events`；本仓库只保留构建期类型快照 `contracts/typert/`。[专题索引](docs/README.md)。
- 部署与恢复：[受保护配置](docs/trusted-service-configuration.md)、[Go 发行包](docs/go-service-bundle.md)。

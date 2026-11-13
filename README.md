# tokenworks-mobile

TokenWorks Mobile 移动远程控制平台：进程内 Host 插件、Go 中转服务、用户名密码账号网页，以及 Android 客户端工程。
Host 复用当前 dsh 的会话与模型服务；中转负责身份、授权、路由和元数据，不保存聊天正文。

[文档导航](docs/README.md) · [当前能力与验收边界](docs/development-status.md) · [历史验证记录](docs/verification-history.md)

本项目已切换产品标识；新包与旧客户端、签名和本机数据不兼容。[升级与重新登记说明](docs/project-identity-cutover.md)定义新包名、Android ID 和数据库隔离步骤。

## 项目结构

| 位置 | 职责 |
| --- | --- |
| [host/remote-control](host/remote-control/README.md) | Cordis 插件、会话适配、命令日志、签名审批与文件交接 |
| [services/remote](services/remote/README.md) | Go 控制面、Relay、账号网页、文件和通知服务 |
| [android](android/README.md) | Android 客户端；实现和目标设备验收按专题分别记录 |
| [contracts](contracts/) | dsh 原生 API 类型快照与仍使用的安全向量 |
| [deploy](deploy/) | SQL 迁移、受控配置示例和监控规则 |
| [tools](tools/) | 本机开发、定向联调、发行构建和校验 |
| [docs](docs/README.md) | 按主题分类的操作指南、协议说明与验证证据 |

原生 API 绑定固定使用 dsh `0.2.1-rc.5`：`npm run contracts:typert` 从已安装依赖生成
`contracts/typert/native-api.json` 和 Android `DshNativeTypes.kt`；`npm run contracts:typert:check`
只检查产物是否过期。这些文件是构建期类型快照，不是新的业务协议或服务端握手。

## 本机开发

Linux 开发环境需要 Node.js `>=24.15.0 <25`、npm、已安装的 dsh `0.2.1-rc.5`、
Go 1.26.x、PostgreSQL 18 工具及 Python 3。依赖与路径说明见[本机持续开发](docs/local-development.md)。

```sh
npm ci --ignore-scripts
npm run build
npm run dev
npm run dev:status
```

`dev` 启动私有 PostgreSQL、本地身份夹具、Go 中转、TypeScript watch 和 dsh 插件 HMR。
已有 dsh 在运行时，按[现有 Web profile 接入指南](docs/dsh-desktop-plugin.md)使用 `--attach-pid`，
不要为接管同一活动会话另起一个进程。运行状态和实际地址以 `dev:status` 为准。

默认开发入口：

| 入口 | 地址或操作 |
| --- | --- |
| 中转账号网页 | [http://127.0.0.1:8080/account/](http://127.0.0.1:8080/account/) |
| 中转就绪检查 | `http://127.0.0.1:8080/readyz`，就绪为 204 |
| dsh Web | 默认 `http://127.0.0.1:3000`；使用 dsh 自己的浏览器授权 |
| 插件状态 UI | dsh 账号菜单 → 设置 → 手机远控 |
| 停止开发服务 | `npm run dev:stop`；只停止监督进程拥有的资源 |

账号网页支持注册、登录、账号和已授权主机概览、改密及退出。无默认用户名或密码，
新账号不自动获得组织或远控权限；Cookie 登录不替代原 `/v1` 的设备证明和授权。
[账号配置与边界](docs/relay-accounts.md)说明如何单独启用本地账号。网页 SSO 仅预留，尚未启用。

## 继续阅读

- 使用或调试：[本机持续开发](docs/local-development.md)、[已有 dsh 接入](docs/dsh-desktop-plugin.md)、[账号网页](docs/relay-accounts.md)。
- 通信协议：dsh 原生 `/api`、`/api/remote.mux`、`$events`；本仓库只保留构建期类型快照 `contracts/typert/`。[专题索引](docs/README.md)。
- 部署与恢复：[受保护配置](docs/trusted-service-configuration.md)、[Go 发行包](docs/go-service-bundle.md)、[恢复隔离](docs/database-recovery.md)。
- 查验结果：[当前状态](docs/development-status.md)、[历史证据与包版本](docs/verification-history.md)。

## 验证与范围

完成一批相关改动后集中运行对应检查，选择方法见[验证入口](docs/development-status.md#verification)。
`npm run verify` 和不带模式的 `run_remote_dsh.py` 包含较广或全量范围，不作为每次开发的默认步骤。
本机日志位于 `.local/`，可能包含敏感凭据，不随文档提交或公开。

已有源码、夹具和发行包证据不等同于学校身份、真实模型、企业桌面组合、目标设备、
生产依赖或目标规模验收。早期版本的结果保留其原版本和范围，完整开放出口见[当前状态](docs/development-status.md)。

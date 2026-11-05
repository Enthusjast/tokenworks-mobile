# 在已有 dsh Web 中开发远控插件

本机 npm dsh 基线为 `0.2.1-rc.5`。插件可链接到当前 Web profile，使用原进程的会话和模型服务。
接入不会另外启动一个 dsh，也不会改动模型配置或发送模型请求。

相关入口：[文档导航](README.md#start)、[本机监督进程](local-development.md)、[中转账号网页](relay-accounts.md)。

## 连接当前进程

附加模式要求目标 profile 的 `node_modules/@ustc/dsh-remote-control` 链接本仓库的
`host/remote-control`；工具会校验链接目标。尚未链接时，先用该 profile 的正常依赖安装方式链接本包。
下面的 PID 和端口必须替换为实际运行的 dsh；命令只接受当前用户拥有的 `dsh web` 和本机 HTTP origin。

```sh
python3 tools/dev/local.py start --profile web \
  --attach-pid <现有 dsh 的 PID> --dsh-url http://127.0.0.1:3080
python3 tools/dev/local.py status
```

监督进程启动自己的私有 PostgreSQL、本地身份夹具、Go Relay 和 TypeScript watch。
原 dsh 不进入它的子进程列表，stop 不会关闭或重新启动原 dsh；原 dsh 退出后监督进程清理自己启动的服务。
附加模式当前使用 HTTP 本地夹具，不支持与 `--mobile` 同时使用。

`attach-profile.mjs` 校验依赖确实链接这个检出，将原 patch 备份至私有的
`.local/dev/profile-backups`，再原子写入独立插件入口和 HMR 配置；已有模型、语言等配置保留。
HMR 监听 Host lib、客户端 UI 和开发 adapter；TypeScript 编译失败时保留上一版输出。
开发入口 `development.mjs` 只适用于链接检出，不进入发行包。

修改代码后，Node 宿主和浏览器客户端分别由 dsh 的 HMR 更新；运行代次改变后旧审批失效。
关闭后端时，profile 中的插件入口仍保留并尝试重连。需要停用插件时使用 dsh 插件管理或移除
对应 `ustc-remote-control-dev` patch；不要复原旧备份覆盖后来修改的整个 profile。

## dsh 内的界面

打开账号菜单 → 设置 → **手机远控**；首次接入可刷新一次浏览器。
页面显示中转连接、代码热重载、本机接入、日志保护器、运行实例/代次和允许的工作区，
支持刷新和复制运行实例 ID。中转未配置、重连中、认证失效或重载暂时不可用各有独立状态。
读取失败会移除上一份状态，不保留陈旧的“已连接”。页面每五秒更新一次，后台页面暂停读取。

此界面只读。工作区白名单和服务端当前授权共同限制远控范围；当前开发配置仅允许本仓库工作区，
不会自动开放用户整个 HOME 或其它会话。没有配置 preset 时不允许远程新建会话。
本地 fixture 身份不会显示为学校登录，也没有默认启用文件服务或真实推送。

dsh 的浏览器授权、这个只读状态页和中转 `/account/` 是不同入口。中转的用户名密码登录
不会登录 dsh、绑定 Host 或自动授予远控权限；账号功能及 SSO 未启用的边界见[账号指南](relay-accounts.md)。

状态接口 `/ustc-remote-control/status` 复用 dsh Connection 的认证、Host/Origin 与 owner 检查，
只接受 GET、禁止缓存；不返回凭据、密钥、正文、文件系统路径或原始异常。
注册及客户端样式随插件卸载清理。无 Web 服务的 SDK/无头 Host 不需要此 UI。

发行校验器接受精确的 `client/index.js` 及客户端声明，继续兼容旧的无 UI 包。
UI 进入包的来源清单，缺失客户端或夹带开发导出均被拒绝；不将开发身份 adapter 打进包。

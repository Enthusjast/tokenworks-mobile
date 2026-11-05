# 常驻开发 Relay 的身份夹具

默认 HTTP 开发环境现在把本机 npm dsh 的正常 Host 插件连接到常驻 Go Relay。入口仍是
`npm run dev`；`npm run dev:status` 检查实时状态；`npm run dev:probe` 运行只读协议探针。
这是本地身份／密钥夹具，不是学校 SSO、生产密钥存储或手机 APP 的验收。

## 登记与重启

监督进程仅在独立 issuer 首次初始化时，通过正常 identities CLI 应用一个完整、明确标记的
开发身份快照。后续重启不重放该快照，不重新激活被暂停的用户或已撤销的安装。

`tools/dev/fixture-host.mjs` 使用现有 HostEnrollmentClient、恢复存储和绑定存储。第一次
登记先保存申请，再登记测试客户端的独立 DPoP／审批密钥，经真实 REST preview、客户端
确认及 Host 复核后持久化批准绑定。确认回调校验组织、profile、安装公钥及预设测试身份；
只对这个本地夹具自动确认，不代表有人进行真实的手机或桌面确认。

已有批准每次启动都向 Go 在线复核。中断的申请恢复原 request_id／配对码；缺少原申请、
过期、冲突或撤销时失败关闭，不删除记录重新配对。原来的 `.local/tokenworks-mobile-dev/runtime.json` 和
M0 `journal` 保留，签名模式使用自己独立的运行实例与日志。

插件通过 createEnrolledRemoteControlPlugin 装配，继续验证绑定、工作区 UUID、安装身份和
签名审批策略。HostResourceJwtProvider 核对本地 JWT，再由 HostRestCredentialProvider 取得
短期 Host credential。WebSocket 握手仍要求安装密钥证明。开发入口不自动批准工具，也不能
用本机未签名 interaction.answer 绕过签名执行约束。

## 存储与边界

固定端口保持为 Go 8080、公开 JWKS 8082、dsh Web 3000，均只监听回环地址。JWKS 只输出
公钥白名单字段，没有 token HTTP 发放端点。测试客户端由本机私有夹具签发当前 DPoP token，
不接触学校密码、token 或用户 dsh 的登录配置。夹具 assurance 和签名不证明实际 OTP／认证强度。

私钥、封装主密钥、绑定、恢复记录及签名命令日志保存在：

```text
~/.local/state/tokenworks-mobile-development/<仓库绝对路径 SHA-256 前16位>/
```

专用目录为 0700，文件为 0600，正常 Host 的目录、符号链接和文件检查保留。仓库 `.local`
可能允许组写，不能拿它充当受保护绑定的祖先目录；本实现不修改仓库或其它 profile 的权限。
这里的 file-backed protector 仅用于开发：复制密钥目录仍可获得封装主密钥和安装私钥，
不能宣称已完成 OS 密钥库接入。旧版保留在 `.local/tokenworks-mobile-dev` 中的夹具文件不再用于当前身份。

状态 `relay_plugin_connected` 是瞬时连接观察值，不是执行许可。它必须同时匹配当前本机
probe epoch、runtime connection、dispatcher incarnation／route 及有效 node lease。
数据库停止、网络断开或 HMR 切换期间可为 false；只有重新握手后才恢复。

## 热重载与证据

夹具对象按一次 apply 创建并注册清理。HMR 卸载取消旧账号、provider、绑定和 key owner；
新代次重新复核批准并连接。Go 重编译／重启后，Host 在原生代次内重新握手，不重复登记。
大批构建输出可能引发多次替换和短暂断开，应以实时状态和最终协议结果判断恢复。

本轮默认 HTTP 链路证据保存在 `.local/dev`：

- `enrolled-host-verify.log`：契约、类型、构建及 Host 495 项全部通过，使用本机 npm dsh rc.5。
- `reviewed-http-smoke-proof.json`：独立检出的提交版本再次通过同样的 DPoP／WebSocket 检查，
  摘要与热重载记录一致；错误 profile 被拒绝且身份文件未改变。
- `relay-smoke-proof.json`：独立夹具客户端通过 DPoP 查询已授权 runtime／会话目录，并以
  session.read 范围的 ticket 和设备密钥完成真实 WebSocket challenge／hello.ok。
- `enrolled-reload-proof.json`：实际修改再恢复开发入口和 TypeScript 源码，dsh PID 不变，
  插件 epoch 与连接更新。夹具源摘要同时固定本轮实际验证的 HTTP 实现。
- `enrolled-restart-proof.json`：完整停止／启动复用同一绑定；enrollment、device、installation、
  runtime 数量仍各为 1，身份 revision 仍为 1。
- `enrolled-go-reload-proof.json`：Go 源码修改和恢复分别触发重启及新签名连接；dsh PID 与
  插件 epoch 保持不变，源文件恢复原摘要。

这些结果不包含真实学校 IdP、推理／工具执行、Android APP、模拟器／真机、大负载或厂商推送。
并行工作区中的其它模式和客户端改动不属于上述证据范围，也没有随本轮 HTTP 夹具提交。

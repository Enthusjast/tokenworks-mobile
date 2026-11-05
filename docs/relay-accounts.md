# 中转账号与网页入口

中转服务支持独立的用户名密码账号，网页位于 `/account/`，根路径跳转至该页。
提供注册、登录、账号/组织概览、当前已授权主机、改密与退出登录。
Go 内嵌 HTML/CSS/JavaScript，无前端构建步骤、CDN、外部字体或客户端 token 存储。

## 开启

先依次应用迁移至 `055_tokenworks_mobile_identity`（含 053/054）；已有旧身份数据库先按[标识切换](project-identity-cutover.md)隔离升级，然后设置：

```sh
export REMOTE_ACCOUNTS_ORIGIN='https://remote.example.edu'
# 仅在允许自助注册时开启；默认关闭。
export REMOTE_ACCOUNTS_REGISTRATION='true'
```

origin 必须是精确的 HTTPS origin，不带路径、查询、用户信息或结尾斜杠；仅 loopback 可使用 HTTP。
反向代理需保留原 Host，公网 HTTPS 由可信入口终止，不依赖客户端提供的 forwarded headers。
浏览器会话 cookie 的 Secure 属性由受控 origin 决定，不能将 HTTPS origin 用于明文公网服务。

仅运行本地账号时无需设置 `REMOTE_ISSUER/AUDIENCE/JWKS_URL/CLIENT_IDS/REQUIRED_SCOPES`。
现有数据库、恢复 epoch、Relay node ID 等服务配置仍按原要求提供。
若这些外部身份配置中出现任何一项，则仍要求完整合法配置；不会失败后降级成本地账号。
没有配置外部身份时，原 DPoP 身份入口返回 `auth.not_configured`，Cookie 不被 `/v1` 当作 access token。
原有 installation 证明、设备注册、审批与 runtime grant 机制没有改为 Cookie 鉴权。

本地开发 `python3 tools/dev/local.py start` 会启用 `http://127.0.0.1:8080/account/` 和自助注册。
支持已有的 `--attach-pid` 模式；Go 热重载同时监听嵌入的三个网页文件，编译成功才替换服务。
未提供任何默认用户名或密码。首次访问使用“注册账号”；生产可关闭注册。

## 身份与权限

- 用户名为 3–32 位 ASCII 字母、数字、`_ . -`，首位是字母或数字，去除首尾空白并转小写。
- 新密码至少 12 个 Unicode 字符、最多 256 UTF-8 字节；不修剪密码，允许空格、粘贴和密码管理器。
- 注册生成新的内部用户 UUID 和独立随机 subject，issuer 固定为 `urn:tokenworks-mobile:local-account:v1`。
  不按用户名或邮箱关联已有学校用户。注册不创建组织成员关系、设备、安装或主机 grants。
- 网页主机概览复用既有 `ListHosts` 归属和授权查询，仅显示当前账号的可见元数据。
  新账号正常显示空列表；已有组织关系也不自动授予主机访问权限。
- 自助注册不是管理员创建：组织成员关系和 runtime grants 仍需由现有可信管理流程显式分配。
  本批没有新增网页授权管理、聊天执行或工具审批入口。

## 密码、会话与请求保护

密码采用标准库 PBKDF2-HMAC-SHA256，600000 次迭代、每条独立 128-bit 随机盐和 256-bit 派生值。
摘要携带固定算法/参数标识；不保存明文、可逆密码或密码提示。未知账号也执行同成本摘要比较。
每实例最多八个密码计算任务；普通请求体上限 2 KiB，不读取或记录密码正文到日志。

登录生成独立的 256-bit session 与 CSRF secret，只将 SHA-256 摘要写入数据库。
Cookie 为 host-only、HttpOnly、SameSite=Strict、路径 `/account`；HTTPS 下使用 Secure 和
`__Secure-` 前缀。固定会话上限十二小时、不滑动续期；每账号最多八个浏览器会话，新的登录清理
最早会话。替换当前浏览器登录时撤销它此前的会话。

登录/注册要求精确 Origin 和 JSON，所有写入拒绝跨源；登出/改密还校验服务端保存的 CSRF 摘要。
`GET session` 仅在 session 和 CSRF cookie 均正确时返回页面使用的 CSRF 值。
不使用 localStorage/sessionStorage 保存凭据。页面从服务端重新取得状态，退出后清空敏感表单；
网络错误不自动重放注册、登录或改密，不把请求超时报告为已经成功或肯定未执行。

数据库维护十分钟固定窗口的全局/IP/用户名尝试预算（240/40/12），每次登录、注册和改密均计入。
忽略未验证的 X-Forwarded-For；反向代理后 IP 可能相同，因此这些保守限制需按实际部署评估。
额度跨节点和重启共享，不通过更换服务实例恢复。过期额度会清理，CPU 槽在计算结束后释放。

每次会话读取重新检查账号 active、密码版本、数据库恢复代次、ready 状态和绝对有效期。
退出只撤销当前浏览器；改密验证旧密码后原子更新版本并撤销该账号全部浏览器会话。
注册/登录/退出/改密的元数据审计和状态修改在同一事务，审计或 commit 失败不返回成功。
恢复隔离仍暂停旧用户，旧数据库代次的 Cookie 不能恢复会话。
账号停用时数据库 trigger 撤销全部浏览器会话，重新启用账号不会复活旧 Cookie。

## SSO 预留

`GET /account/api/providers` 返回 `password: enabled` 和 `sso: disabled`。
网页明确显示“SSO 登录 · 尚未启用”，不提供假回调、假 token 或自动放行。
现有 users 的 issuer/subject 身份结构保留供未来可信 SSO provider 使用；未来需单独实现提供方
适配、回调和明确的账号绑定策略，不能按用户名/邮箱合并身份。
本批没有新增 SSO 客户端、Discovery、授权码交换或学校登录流程；原有可选资源 JWT 验证器继续独立运行。

## 验证入口

新增 `internal/accounts` 的定向测试使用真实隔离 PostgreSQL，覆盖注册去重/无默认权限、登录、
跨源/CSRF 拒绝、改密后所有会话撤销、退出、过期、停用、恢复 epoch、共享限流和审计失败回滚。
`cmd/remote` 的密码启动用例确认无需 IdP，以及不完整 OIDC 配置不能静默降级。

本批已集中通过四个定向 Go 用例（含真实 PostgreSQL 与 race 检查），新增停用后重启用边界后
单独复核账号生命周期用例。真实浏览器完成注册、错误密码、登录、改密、新旧密码切换及退出；
桌面和 390px 窄屏均检查了页面。另启动不带任何 IdP 配置的生产二进制，完成注册/登录/概览，
并确认浏览器 Cookie 不能进入 `/v1/me`。本地记录位于 `.local/accounts-evidence`，未运行全量测试。
生产运行前仍需配置 HTTPS、受控数据库备份和运维策略；本地夹具不构成生产验收。

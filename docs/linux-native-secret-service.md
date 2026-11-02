# Linux 原生 Secret Service：锁定与重开

2026-11-02 使用 Ubuntu `libsecret-tools` 0.21.7-2build1 的真实 `secret-tool`、系统 GNOME
Keyring 50.0 和 D-Bus 1.16.2，在独立会话总线和临时密钥库中验证安装身份与受保护日志。
Host 实现来自已校验的 `9fa43c4` npm 包，清单摘要和来源见[包收据](package-verification-9fa43c4.json)。
没有使用查找命令替身，也没有访问用户当前登录会话的密钥库。

## 验证范围

一个显式启用的用例完成以下阶段：

1. 通过真实 `secret-tool` 预置独立的日志保护密钥，调用 `LinuxInstallationKeyStore.create` 生成安装身份，
   验证 P-256 签名，并创建含持久命令的受保护 journal。引用和封装文件为 0600，没有同目录明文 `.key`，
   SQLite 中不存在命令正文的明文标记。
2. 通过私有 D-Bus 锁定真实 collection，并确认其 Locked 属性。新客户端的安装签名、安装身份重开和
   journal 解封全部拒绝；原引用、封装文件保持原样，不产生替代密钥。
3. 错误密码不能解锁；正确密码解锁后，新的客户端重开同一安装公钥、thumbprint 和原 journal 命令。
4. 正常停止并重新启动同一个临时 GNOME Keyring，保持临时数据目录和测试密码；重开后仍为同一身份和命令。
   所有者 signal 取消后，已返回签名器拒绝继续签名。

每个阶段使用新 Node 客户端，避免把上一阶段的内存密钥当成重开证据。
锁定检查针对新的查找和解封；已经打开的 journal 所持数据密钥仍由原有生命周期清理，不声称锁定会自动擦除它。
这里验证的是正常守护进程重启，不是断电、崩溃恢复或生产备份验收。

## 隔离与测试驱动

测试为 HOME、XDG 数据/配置/runtime 和身份/journal 创建独立私有目录，通过 `dbus-run-session`
启动自己的总线及前台 keyring。子进程环境采用白名单，不继承用户总线、keyring control、display
或动态加载注入变量。测试仅停止自己创建的进程，并在退出后清理这些临时目录。

`secret-tool` 必须由调用者显式指定绝对路径和独立核对的 SHA-256。测试有界读取该文件，校验后复制到
私有临时目录再执行，保留适配器原有的程序/祖先目录权限检查。本次复用已有的 APT 提取结果，程序摘要为
`949b1223e548aa2a2b33e63493b2ee174ff3da986cc38c2d8ac4328d578e99c3`；没有安装系统软件或修改共享目录权限。

GNOME 50 的 `gnome-keyring-daemon --unlock` 成功退出不证明一个已被 D-Bus 锁定的集合已解锁。
协调器因此使用 GNOME 特定的 `UnlockWithMasterPassword` 接口驱动测试密码检查，再读取真实 Locked 状态。
测试的 plain Secret session 仅在这条私有总线上传递合成密码。这个接口属于测试协调器，不是产品解锁适配，
也不作为其他 Secret Service 后端或发行版的兼容承诺。

## 单独运行

默认测试不会启动这项原生集成；只有 `REMOTE_TEST_NATIVE_KEYRING=1` 时才启用。
需要系统 `/usr/bin/python3` 的 Gio/GLib、`/usr/bin/dbus-run-session`、`/usr/bin/gnome-keyring-daemon`
以及经核对的 `secret-tool`。从仓库根目录选择已保存的 Host 包：

```sh
REMOTE_TEST_NATIVE_KEYRING=1 \
REMOTE_TEST_SECRET_TOOL=/absolute/path/to/secret-tool \
REMOTE_TEST_SECRET_TOOL_SHA256=<独立核对的程序SHA-256> \
REMOTE_TEST_HOST_BUNDLE_DIRECTORY="$PWD/.local/current-bundles-evidence/host" \
REMOTE_TEST_HOST_BUNDLE_MANIFEST_SHA256=1211b6ec9a2d8e4fafe3d5ae0a10a8e0989f45f8a2c8e53ed46e1c199faed449 \
node --import tsx --test host/remote-control/tests/linux-secret-service.integration.test.ts
```

若明确验证工作树构建，可先构建 Host 并省略两项 bundle 变量。启用后缺少前置条件或摘要错误会失败，
不会自动改用命令替身。阶段失败只报告固定名称，不转发密钥、密码、原始 native stderr 或断言中的私有内容。

本机通过的定向日志为 `.local/native-keyring-evidence/native-keyring-verified.log`，类型检查也通过。
没有运行全量测试、TokenWorks、Android、Windows/Wine、真机、大负载或厂商通道配置。
真实学校登录、生产会话解锁策略、用户密钥库、硬件隔离及其他平台仍需各自验收。

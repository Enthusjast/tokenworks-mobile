# Host 包验证：1a40092

本批在干净运行输入 `1a40092e37f9da48f24296797b7eb795083ad55d` 构建新的 Host npm 包，包含：

- `80ef253`：日志状态检查与更新处于同一 SQLite 写事务，保留竞争进程先提交的终态/结果。
- `16a0844`：已超时的审批调用仍占用工作槽，直到未响应取消的验证/授权任务真正结束。
- `1a40092`：Secret Service writer 限制每实例八个存活子进程，实际 close 后释放预算。

包仍为私有 `0.0.1`，包含 260 个文件，未签名、发布或安装到常驻 profile。
文件位于 `.local/host-release-1a40092-evidence/host/ustc-dsh-remote-control-0.0.1.tgz`。
[摘要与范围收据](host-package-verification-1a40092.json)记录完整源码 revision、包/清单 SHA-256 和本地日志摘要。
旧的 `9fa43c4` 包保留其原收据和验证范围，不包含上述三个补丁。

## 对包内代码的定向验证

现有回归通过 `hostPackageModule` 显式选择源码或经校验的包内模块。
未选择包时仍直接测试源码，无需预先构建 lib；选择包时复用离线校验和新目录解包，失败不回退。
日志并发用例的父/子进程使用同一次解包的 journal 与 protocol，避免把不同模块副本的错误类混用。
审批额度用例实际使用包内 journal/approval 实现；writer 用例实际使用包内 Linux 写入实现。
测试数据、签名输入和进程协调仍由源码测试夹具提供。

本批集中通过：

| 模式 | 范围 | 结果 |
| --- | --- | --- |
| 源码 | journal 的四种竞争终态/结果场景 | 通过，含父用例共 5 项结果 |
| 已校验包 | 同四种跨进程 journal 场景、审批额度、writer 进程预算、安装 CLI 文件流程、真实 Secret Service | 通过，含父/子用例共 9 项结果 |
| 静态 | TypeScript 检查；包构建与离线校验 | 通过 |

包模式全程没有工作树 `host/remote-control/lib`，PATH 中会记录并拒绝的 Go/npm 替身没有被调用。
CLI 使用本机安装的 dsh 0.2.1-rc.5 和临时 profile；Secret Service 使用真实、固定程序摘要的
secret-tool 与独立 D-Bus/GNOME Keyring。常驻开发进程、用户密钥库和未提交修改均未改动。

本轮没有重新构建 Go 包，也没有运行 Go 全量或通用双节点组合；较早的[Go/Host 组合](package-verification-9fa43c4.md)
仍绑定其旧 Host 源码，不能把它写成新 Host 的双节点复测。学校身份、生产依赖、目标平台与放量出口仍开放。

## 单独复核包内修复

从仓库根目录显式选择本包与独立记录的摘要，集中运行三项修复回归：

```sh
REMOTE_TEST_HOST_BUNDLE_DIRECTORY="$PWD/.local/host-release-1a40092-evidence/host" \
REMOTE_TEST_HOST_BUNDLE_MANIFEST_SHA256=7f66a71250257d618b1605b444ad4bf11420038d9deae6edd719b0b6d4f71059 \
node --import tsx --test \
  --test-name-pattern='SQLite journal transitions preserve|timed-out approval admission retains capacity|Secret Service writer bounds live' \
  host/remote-control/tests/journal-transitions.integration.test.ts \
  host/remote-control/tests/approval-journal.test.ts \
  host/remote-control/tests/secret-service-writer.test.ts
```

安装 CLI 与原生密钥库的独立条件分别见[文件入口](../tools/integration/FILE_LIFECYCLE.md)和
[Linux 原生专项](linux-native-secret-service.md)。这些入口用于相关交付复核，不要求每次改动全部执行。

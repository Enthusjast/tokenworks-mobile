# Go／Host 本地包验证：9fa43c4

2026-11-02 在干净的隔离检出 `9fa43c4e758f37d173b95338e075876a31effc44` 构建 Go 服务和 Host npm 包。
该源码包含近期文件适配、scanner 检查和 FCM OAuth 生命周期修复。
构建没有读取或提交主工作区尚未完成的 Host README、开发启动器或 Android 改动。

包、清单、构建日志和验证日志保存在本机 `.local/current-bundles-evidence`；
[范围与摘要收据](package-verification-9fa43c4.json)随本记录提交，便于核对来源和后续保存的包。
本轮只生成本地产物，没有签名、发布、安装到常驻 profile 或部署服务。

## 产物

| 包 | 内容 | 工具链 |
| --- | --- | --- |
| `service/remote-service-linux-amd64.tgz` | 116 个文件，包括 Linux/amd64 二进制、迁移、模块与契约 | Go 1.26.10，CGO=0，GOAMD64=v1 |
| `host/ustc-dsh-remote-control-0.0.1.tgz` | 260 个文件，包括 package metadata、README、编译后的 JS/声明/source map | Node 24.20.0、锁定 TypeScript，dsh 0.2.1-rc.5 |

两个包的 source commit 均为上述完整 revision；清单 SHA-256 与归档 SHA-256 见 JSON 收据。
构建使用现有 [Go 包工具](go-service-bundle.md)和 [Host 包工具](host-bundle.md)，完成各自离线校验。
Go 包重新解包后，实际二进制 `version` 与清单的 Go/platform/revision/source digest 完全匹配。
这是一次当前源码构建，没有为本轮重新宣称“两次构建可重现”；旧包的重复构建记录仍绑定旧源码。

## 集中验证范围

本批集中运行三项验证，均通过：

1. 双节点 Go／Host 包组合。两个真实 Go 服务进程、socket-only PostgreSQL 18、私有双向 TLS、
   本机安装的 npm dsh 0.2.1-rc.5 和正常 Host 插件，经过登记/绑定、凭据/ticket/握手、
   会话/模型、历史与流、问题、签名审批、重复请求和撤权流程。使用 `--dsh-only`，没有运行独立 Go 全量测试。
2. 安装 CLI 的文件专项。`dsh-cli-files.integration.test.ts` 加载经校验并重新解包的 Host 包，
   验证 receipt、持久附件字节、错误摘要拒绝、附件导出、工作区导出、符号链接拒绝和 changes 适配。
3. Go 包文件检查。`TestFileCheckBuiltServiceCommandDoesNotStartTheServer` 调用解包后的服务二进制，
   在无有效数据库/issuer/listener 配置时完成 scanner 两次协议自检，并输出准确的固定 JSON。
   Go 测试编译的是测试程序，服务入口来自已解包的发行二进制。

测试前后，隔离检出的 `host/remote-control/lib` 均不存在。验证环境在 PATH 放置会记录并拒绝调用的
`go`/`npm` 替身，组合与 CLI 验证没有触发它们；Go 单用例显式使用绝对编译器路径。
这些检查确认所选运行入口来自包，没有回退到工作树 Host lib 或重新构建服务。
脚本、测试夹具、DSH 及 peer 依赖仍由测试环境提供，不包含在这两个包中。

## 重现入口

从仓库根目录选择已保存包和独立记录的清单摘要：

```sh
REMOTE_TEST_DSH_BIN=/本机/npm/安装的/dsh \
python3 tools/integration/run_remote_dsh.py --dsh-only --cross-node \
  --service-bundle-directory "$PWD/.local/current-bundles-evidence/service" \
  --service-bundle-manifest-sha256 78671159025d000f2f16a59c9244ecd74a2bb3a0e8c0a60948a5a6a13997d2a0 \
  --host-bundle-directory "$PWD/.local/current-bundles-evidence/host" \
  --host-bundle-manifest-sha256 1211b6ec9a2d8e4fafe3d5ae0a10a8e0989f45f8a2c8e53ed46e1c199faed449
```

文件专项使用同一 Host 包和摘要设置 `REMOTE_TEST_HOST_BUNDLE_DIRECTORY`、
`REMOTE_TEST_HOST_BUNDLE_MANIFEST_SHA256`、`REMOTE_TEST_DSH_BIN`，只选择对应 CLI 文件测试。
包模式和完整前置条件见[组合说明](packaged-service-composition.md)，文件命令见[文件专项入口](../tools/integration/FILE_LIFECYCLE.md)。
这些入口用于相关交付验证，不要求每次开发改动重复运行。

身份、模型、密钥、scanner 与部署配置仍为本地夹具。没有运行 TokenWorks、Android、Windows/Wine、
真机、大负载或厂商推送测试。包验证不证明学校身份、实际 S3/KMS/病毒引擎、生产密钥服务、
目标平台、试点放量或生产回退已验收；完整阶段出口继续按[开发状态页](development-status.md)保留。

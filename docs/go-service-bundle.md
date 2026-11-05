# Linux Go 服务发行包

`tools/release/go_bundle.py` 为真实 Go 入口构建 Linux/amd64 静态二进制、完整迁移脚本及公开
OpenAPI／WebSocket Schema，并生成可独立固定摘要的清单。它不发布、签名、启动服务或应用迁移。

相关入口：[文档导航](README.md#release)、[当前状态](development-status.md)、[历史包与收据](verification-history.md)。

## 构建与离线校验

从仓库根目录使用已安装的 Go 1.26.x，输出目录必须是 `.local` 下尚不存在的新目录：

```sh
python3 tools/release/go_bundle.py build \
  --go-bin /opt/go1.26.10/bin/go \
  --output .local/go-service-new
```

模块缓存使用当前用户的 `~/go/pkg/mod`，构建缓存使用 `~/.cache/go-build`。构建环境不继承
部署凭据、代理或 Go 全局设置；关闭代理下载、sumdb 网络访问、workspace、CGO 和自动工具链
下载。缺少缓存会失败并保留私有 build.log，不自动联网安装。现有模块校验与 go.sum 约束保留；
本工具不替代依赖审计、工具链来源验证或完整供应链证明。

构建器只接受已提交的 Go／模块／迁移／协议内容及白名单中的账号网页 HTML/CSS/JavaScript，拒绝修改过的输入、符号链接以及新增或
被忽略的发行输入。从固定 Git commit 读取 blob 到私有临时快照，再编译该快照；并行修改
工作区不能混入二进制。不打包测试源码、PLAN.md、账号／部署配置文件、私钥文件或环境文件。
构建输出也通过持有的目录句柄创建，输出路径被替换时拒绝给出验证结果。
源码清单、编译器版本／可执行文件摘要、Python／压缩库版本、两个构建脚本摘要和依赖模块的 go.sum 值保留
在 manifest。Go 构建使用 trimpath、关闭 VCS 自动探测及 build ID，固定 CGO=0、GOAMD64=v1。

构建结果返回 `manifest_sha256`。该值必须从可信构建步骤独立保存、审核或签名分发；不能
下载一个 manifest 后自行计算其摘要，就将同一下载内容当成信任根。

```sh
python3 tools/release/go_bundle.py verify \
  --directory "$PWD/.local/go-service-new" \
  --expected-manifest-sha256 <独立可信的64位小写摘要> \
  --unpack "$PWD/.local/go-service-new/unpacked"
```

verify 不需要 Go，不启动候选二进制。先校验清单摘要，再检查固定归档、每文件摘要、模式、
来源关联及迁移连续配对；拒绝重复 JSON 字段、未知路径、链接、特殊文件、稀疏文件、异常
权限、重复成员、尾随数据和解压预算超限。ELF 检查限定当前静态 Linux/amd64 格式并拒绝
动态链接／解释器；这些结构检查不是任意程序语义或运行正确性的证明。

可选解包只接受私有父目录中的新目标，使用目录句柄、O_NOFOLLOW 和独占创建写入白名单
路径，不调用 extractall；输出文件存在时不覆盖。校验失败没有可用结果，部分解包目录也
不能当成完成的安装。构建器对自己刚生成的二进制调用离线 version，自检 revision／源码
摘要；离线 verifier 不通过执行候选来相信其自报信息。

## 内容与使用边界

归档固定为 `remote-service-linux-amd64.tgz`：

- `service/bin/remote`：0755 静态服务二进制。
- `service/module/go.mod`、`go.sum`：模块记录。
- `service/migrations`：从 001 开始连续、每项 up/down 配对的 SQL；不自动执行。
- `service/contracts`：公开的 REST 与 WebSocket 协议。

账号网页资源纳入源码输入摘要，编译时嵌入 `service/bin/remote`，不会作为独立静态目录输出。
旧版包不包含之后加入的账号网页功能；选择包时核对源码 commit 和[验证记录](verification-history.md)。

解包后可显式调用 `service/bin/remote version` 核对构建信息，见[离线版本命令](go-service-version.md)。
真实服务启动仍须提供数据库、Relay node ID 和恢复 epoch，并按启用的功能提供策略及受保护配置。
[本地账号](relay-accounts.md)可不配置 IdP 独立运行；启用原资源 JWT 入口时，仍需完整的
issuer/audience/JWKS/client/scope/public origin。TLS 与数据库恢复围栏不因账号入口而放宽。
迁移、生产签名、部署权限、回滚兼容性和平台／网络验收各自独立；通过摘要校验不代表已获准部署，
也不代表学校身份、HA、容量或故障恢复目标已验收。

## 本地证据

本机 Go 1.26.10 的两次隔离构建得到相同归档和 manifest，包含 104 个文件；解包校验通过，
解包二进制在无效数据库／issuer 设置下仍能正确输出离线版本。记录保存在
`.local/go-service-bundle-evidence`；源 commit 为 cfd06c0，该 commit 包含本轮版本入口。

六组 Python 测试覆盖确定性、清单与源关联、归档攻击、ELF／迁移约束、提交快照和不可执行
离线校验；另模拟构建输出被改名、解包子目录被换成符号链接，确认日志／文件不会写进替换目标。Node 测试入口将这些
检查纳入现有 Host 测试。契约检查、类型检查和构建通过；排除 Windows 专用文件及相关名称
用例后，Host 493 项通过。Go 命令包测试也通过。

本批没有使用 Wine 或运行 Windows、Android、真机、大负载及厂商推送测试，也没有发布包。

[发行包联合验证](packaged-service-composition.md)可显式使用本包二进制、SQL 和协议文件启动
隔离测试。verify 的 JSON 结果包含已通过清单验证的 version；测试在打开数据库前，将它与
解包程序输出比较。构建／校验工具自身仍不部署或启动业务服务。

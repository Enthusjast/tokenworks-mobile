# Go 服务离线版本信息

`remote version` 和 `remote --version` 直接向 stdout 输出 `tokenworks-mobile-remote-service-version/v1` JSON，
包含 Go 版本、编译平台、源码 revision 和源码清单摘要。它们不读取部署配置，不连接数据库，
也不启动 HTTP／Relay、身份或密钥服务；额外参数被拒绝。

普通 `go build`／`go run` 的 revision、source_sha256 为空，明确表示未由发行构建器固定。
不能根据执行目录的 Git HEAD 或环境变量猜测版本。发行构建器通过 ldflags 同时嵌入两项；
缺项或格式错误时命令失败，不回显无效内容。版本数据只是构建来源信息，不是签名或部署许可。

本机 Linux 使用 Go 1.26.10 验证：命令包测试通过；在无效的数据库／issuer 配置下运行
`go run ./cmd/remote --version` 仍成功输出 JSON。原无参数服务启动及管理命令的选择保持原行为。
未运行 Windows／Wine、Android、真机或大负载测试。

[Go 服务发行构建器](go-service-bundle.md)现在把固定 Git 源码清单摘要与 revision 嵌入
Linux 静态二进制，构建时自检输出；离线校验归档时不会执行候选。

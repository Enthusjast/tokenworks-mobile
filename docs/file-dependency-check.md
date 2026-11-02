# 文件服务依赖检查

在启用或更换文件配置前，可以直接运行已构建的 Go 服务检查部署配置和扫描器：

```sh
/path/to/remote files check --config /absolute/path/to/files.json --timeout 10s
```

主配置、对象凭据、scanner policy 及相关 CA 的读取规则与[文件服务启动](file-rest-service.md)一致。
命令也校验配额、保留期限、worker 数和任务期限；启用导出时要求扫描策略覆盖 2 GB。
`--config` 必须显式指定绝对路径，不从环境变量推断；`--timeout` 的范围是 1 至 30 秒，
默认 10 秒，约束两次扫描自检的总耗时。单次 scanner 连接仍受配置自身的超时约束。

命令通过原生 clamd 适配发送一个干净样本，再要求内存中的标准无害 EICAR 样本被拒绝。
Unix socket 或经验证的 TLS/TCP 使用实际配置。成功只输出一条 JSON：

```json
{
  "format": "dsh-remote-file-check/v1",
  "configuration_valid": true,
  "scanner_self_test": "passed",
  "policy_sha256": "<实际受保护扫描策略的 SHA-256>",
  "exports_enabled": false,
  "storage_check": "configuration_only"
}
```

配置错误、scanner 不可达、超时、always-clean、always-reject 或畸形应答都会以非零状态退出，
不输出成功 JSON。错误仅使用固定说明，不转发远端应答、配置路径、凭据或连接串。

检查不连接数据库或对象服务，不创建上传、扫描任务、文件对象、Relay/HTTP 监听器或运行时健康授权。
`storage_check` 明确只表示对象存储配置通过验证；它不证明 bucket 权限、实际 KMS、对象上传下载或生命周期策略可用。
自检也不证明扫描器加载了指定策略、病毒库保持更新或能够识别所有恶意文件。
运行中的文件服务仍须独立完成自己的自检，并持续更新本进程健康代次。

本地验证使用真实 clamd 协议适配和 Unix socket 夹具，覆盖正向与拒绝路径、整体超时、
配置失败先于网络访问、无对象访问及实际服务二进制的子命令分派。未使用真实病毒引擎或生产对象存储。

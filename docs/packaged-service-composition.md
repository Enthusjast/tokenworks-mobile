# 使用 Go／Host 发行包进行联合验证

`tools/integration/run_remote_dsh.py` 现在可把通过独立摘要校验的 Go 服务包接入原有本机 npm
DSH 联合测试。它验证真实发行二进制与插件包的装配，不发布包或操作常驻开发服务。

## 使用 Go 服务包

```sh
REMOTE_TEST_DSH_BIN=/本机/npm/dsh \
python3 tools/integration/run_remote_dsh.py --dsh-only \
  --service-bundle-directory /绝对路径/Go包目录 \
  --service-bundle-manifest-sha256 <独立可信的清单SHA-256>
```

包模式要求 `--dsh-only`，使用 Linux/amd64 二进制；需要 PostgreSQL 工具、Node/npm 测试依赖
及已安装 dsh。无需 Go 编译器。未提供 Host 包时沿用工作树 Host 的构建；Go 服务二进制、
本次应用的 SQL 和用于校验 API／帧的协议 Schema 均来自服务包。

目录和摘要必须同时提供，目录为绝对路径，摘要为 64 位小写十六进制。必须独立取得可信的
构建清单摘要；不能自行信任下载文件旁的摘要。包选择不通过继承的测试环境变量自动启用。

测试首先离线校验包，然后在自己的私有临时目录重新校验并解包。只有解包程序的离线 version
与已验证清单的 version 完全匹配，才初始化 PostgreSQL。第二次校验发现变化、解包失败、
版本不符或输出无效时，均在数据库启动前退出。不回退到源码编译，也不混用工作区的 SQL／Schema。

## 同时使用 Host 包与双节点

```sh
REMOTE_TEST_DSH_BIN=/本机/npm/dsh \
python3 tools/integration/run_remote_dsh.py --cross-node --dsh-only \
  --service-bundle-directory /绝对路径/Go包目录 \
  --service-bundle-manifest-sha256 <Go清单的可信SHA-256> \
  --host-bundle-directory /绝对路径/Host包目录 \
  --host-bundle-manifest-sha256 <Host清单的可信SHA-256>
```

明确选择两个包时，测试也跳过工作树 Host 的构建，重新校验并加载 Host 包中的入口。
测试夹具仍通过 tsx 运行；安装的 dsh 和兼容 peer 依赖仍是外部运行条件。两个包可以来自不同
commit，但其协议和实际运行必须兼容；成功校验各自摘要不证明它们互相兼容。

身份、授权策略、安装／设备密钥、模型 provider 和示例发行配置由原有私有夹具提供，不从
生产学校账号取值。每次测试创建自己的 socket-only PostgreSQL，应用所选包的 up 迁移；
双节点模式运行两个实际服务进程并通过私有相互认证 TLS 路由。退出后清理自己的进程、数据库
及解包目录，不操作常驻开发数据库、dsh profile 或正在运行的开发进程。

## 验证记录

本机 npm dsh 0.2.1-rc.5、Node 24 和 PostgreSQL 18 的两种组合均通过：

| 组合 | 服务来源 | Host 来源 | 结果 |
| --- | --- | --- | --- |
| 单节点 | 源 commit cfd06c0 的 Go 包，104 文件 | 工作树构建 | 通过 |
| 双节点 | 同一 Go 包，两个实际服务进程 | 源 commit d4b94c2 的 Host 包，239 文件 | 通过 |

双节点验证期间，把隔离工作树的 Host lib 暂时移走，并用会记录失败的 go/npm 替身拦截调用；
测试通过后 lib 仍未生成，替身记录不存在。证明此次运行使用了所选产物，没有退回 Go／Host
源码构建。测试覆盖登记与双端绑定、凭据／ticket／握手、会话与模型选择、流与历史、问题、
签名审批、重复请求、撤权及正文持久边界；配置、身份和模型仍是夹具。

证据保存在 `.local/packaged-composition-evidence`，包括两个包的清单、Host 包、运行日志和
receipt；Go 包保留在 `.local/go-service-bundle-evidence`。负面回归还用禁止调用的 PG／Go
工具验证坏参数／坏清单不会初始化数据库或编译，模拟二次解包及版本失败时也没有启动数据库。
契约检查、类型检查、构建通过；排除 Windows 文件和相关用例后，Host 495 项通过。

未执行 Windows／Wine、Android APP、真机、大负载或厂商推送测试。该组合不等于学校身份、
企业发行、平台密钥库、生产部署、备份恢复或容量验收。

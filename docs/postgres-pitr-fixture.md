# 加密基础备份、WAL 与指定恢复点夹具

`tools/integration/run_pitr_recovery.py` 在两个自建临时 PostgreSQL 集群间验证物理备份和指定恢复点，
随后使用已校验 Go 服务包执行恢复隔离与身份释放。它没有现有数据库地址、数据目录或备份来源参数，
不连接常驻开发库，也不是生产备份/密钥管理设施。

## 单独运行

需要 Linux 非 root 用户、PostgreSQL 18 的 initdb/pg_ctl/psql/pg_basebackup/pg_verifybackup、
系统 `/usr/bin/gpg`、`/usr/bin/gpgconf`、tar 和 Python。服务入口及迁移来自明确选择的
[Go 包](go-service-bundle.md)，启动前离线验证、重新解包并核对实际二进制 version；不编译源码。

从仓库根目录显式选择包、独立记录的清单摘要和一个尚不存在的输出目录：

```sh
python3 tools/integration/run_pitr_recovery.py \
  --service-bundle-directory "$PWD/.local/current-bundles-evidence/service" \
  --service-bundle-manifest-sha256 78671159025d000f2f16a59c9244ecd74a2bb3a0e8c0a60948a5a6a13997d2a0 \
  --output "$PWD/.local/pitr-new-run"
```

输出目录必须为仓库 `.local` 下的新直接子目录。`REMOTE_TEST_PG_BIN` 可显式选择已安装的 PG 工具目录。
默认测试不会自动运行本场景；它用于备份/恢复相关改动的独立复核，不随日常修复反复执行。

## 实际验证顺序

1. 创建私有、只监听独立 Unix socket 的源集群，应用服务包内迁移并写入少量合成用户、设备、grant 和标记。
2. `pg_basebackup -X stream` 创建基础备份，选择 SHA-256 backup manifest，并用 `pg_verifybackup` 校验。
   基础 tar 与 archive_command 交付的完整 WAL/backup-history 文件通过 GnuPG AES-256、SHA-256 S2K 和 MDC 保护。
3. 基础备份之后再提交 `wal-only` 标记，创建具名 restore point；在该点之后撤销设备/grant 并提交另一标记，
   然后切换 WAL，等待对应密文存在且 PG archiver 已确认成功。
4. 删除临时基础备份和 tar，再从密文恢复；错误口令和末尾被篡改的密文均失败，不向 PostgreSQL 目标路径发布明文。
   GPG 可能在最终完整性校验前写出暂存明文，辅助程序仅在成功后 fsync/原子发布，失败清理自己创建的暂存文件。
5. 通过 restore_command 解密完整 WAL，等待指定恢复点自动推广；核对基础标记和 wal-only 标记存在、点后的标记缺席。
   两库 system identifier 相同，恢复后的 timeline 前进，说明这是物理 WAL 恢复。只读连接可用不等于已完成推广，脚本会分别等待。
6. 确认旧设备/grant 行的未撤销事实确实回退，再生成备份之外的新部署 epoch。Go 生产入口因代次不符拒绝启动；
   `recovery quarantine` 后未释放仍拒绝。使用隔离之后生成的合成完整身份清单释放后，旧设备和 grants 继续撤销，旧 epoch 不能启动。
   quarantine/release 相同 ID 与参数重试均返回原结果。

此场景证明恢复旧**行状态**及应用恢复围栏，不构造真实学校 token 或补验全部 HTTP 设备/凭据流程。
现有生产入口及逻辑恢复测试的范围见[恢复说明](database-recovery.md)。

## 密钥与资源边界

测试口令为随机 256 bit 熵的十六进制文本，只放在临时私有文件中并通过 GPG stdin 传入，不在命令行、环境或日志中携带。
GPG 使用独立 homedir、禁用个人 options 和对称口令缓存；测试结束只终止该 homedir 对应的进程。
archive 辅助程序要求明确 fixture purpose、专有临时根目录、受保护配置/口令以及固定 WAL/base 文件名；输入输出不能逃出该根目录。
已有 archive 不会被覆盖，重试先解密核对其内容。基础备份也单独加密，不把口令文件放入基础备份。

源库和恢复库使用普通临时 PG 数据目录；这里验证备份副本加密，不声称 PostgreSQL 源磁盘已加密。
合成数据、密文和口令在验证及停机确认后清理；保留的文件摘要仅用于记录本次随机密文，不是可下载的长期备份。
如果不能确认临时 postmaster 停止，保留私有工作目录并失败退出，避免删除仍可能在用的数据目录。
需要人工排查时只处理本次输出日志所指向的自有临时实例，不操作常驻开发库。

## 本地结果与限制

PostgreSQL 18.6、GnuPG 2.4.8 与源 `9fa43c4` 的已校验 Go 包完成本场景。
基础备份与解密后 manifest 均通过，三个 WAL 段、backup-history 及基础 tar 共五份密文完成归档，
错误口令/篡改拒绝、指定恢复点、代次阻断、幂等隔离/释放和旧权限保持撤销均通过。
启动/坏包拒绝与不确认停机时保留数据目录的三个单元守卫通过。
本机记录保存在 `.local/pitr-evidence`，其中 summary 不含口令、数据库地址或正文标记；常驻 Go/dsh 未重启。
[当次验证摘要](postgres-pitr-verification.json)保留服务来源、通过项和临时密文摘要，未保留测试口令或数据库文件。

这不是负载测试或生产 RPO 5 分钟/RTO 30 分钟测量，也不验证生产归档存储、保留/轮换策略、密钥托管、
真实学校目录、对象存储恢复或多平台验收。本轮未运行 Go 全量、TokenWorks、Android、Windows/Wine、真机或厂商通道配置。

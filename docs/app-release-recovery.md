# 恢复后重新核对发行策略

迁移 `052_app_release_recovery_confirmation` 记录每个包的 `validated_epoch`。更新元数据准入
要求目录摘要一致、数据库 ready 且策略已在当前数据库代次验证。身份恢复释放不会更新此字段，
因此旧备份中的推荐不能随着用户恢复自动开放；普通服务启动也不能替代管理员核对。

数据库状态保留 `configured:true` 和备份策略摘要，但 `app-releases status` 的 `current:false`
表示尚未在新代次接受。已配置更新目录的服务在监听前失败；已经持有该目录的 handler 对当前合法
手机返回 503 `update.unavailable`。这不改变账号、设备、runtime grants 或会话的恢复规则。

## 操作

按[数据库恢复流程](database-recovery.md)隔离旧入口、选择新的外部代次，完成当前学校身份清单
导入和释放。保持入口隔离，从备份之外的可信发行记录取得完整当前目录；不得把恢复库中的目录
或 status 摘要直接当作当前发布事实。先核对 APK 身份、撤回、灰度、组织和支持窗口，再执行：

```sh
# REMOTE_DATABASE_URL、REMOTE_DATABASE_EPOCH 从受控部署环境提供。
remote app-releases status --package-name cn.edu.ustc.dsh.mobile
remote app-releases describe --catalog-file /受控目录/current-releases.json

# 仅在可信当前目录与恢复库中的策略完全一致时确认。
remote app-releases confirm \
  --catalog-file /受控目录/current-releases.json \
  --operation-id <本次确认的新UUID> \
  --expected-sha256 <已核对的恢复库策略摘要>
```

`confirm` 要求候选文件摘要等于 expected 摘要，也等于数据库当前策略；它不能更改目录、灰度或
恢复已撤回版本。若可信当前目录不同，使用既有[replace 命令](app-release-policy.md)，以恢复库中的
摘要作为 CAS 前值，提交可信的新目录。成功的显式替换同时验证新代次；无需再 confirm。

确认在一个事务中重新核对当前 ready 代次和不可变制品登记，保留唯一操作回执，再更新
`validated_epoch` 和写一次审计。策略摘要、revision、原始变更时间和语义变更代次保持原样。
正常初次登记和策略 replace 也建立当前代次验证，不要求日常重启反复人工确认。

stdout 丢失或提交结果不明确时，以原 operation UUID、原 expected 摘要和同一候选策略重试，
返回同一确认时间和回执。后来已变更策略时，查询旧回执不会恢复旧目录；确认回执也不能证明
其目录仍为当前策略，应另查 status。新代次不能借用旧代次回执，已验证的同代次不接受新的重复确认。
状态 `current:true` 且候选摘要一致后，才能加载受控配置并恢复更新入口。

## 审计与迁移

确认回执不可更新、删除或 truncate；策略验证代次只能由匹配回执或合法策略替换更新。
审计只含操作 UUID、包名、摘要、revision 和代次，不记录 URL、目标组织或发行说明。
审计被抑制或数据库失败时，回执与代次更新整体回滚。确认后 down 迁移拒绝删除历史。

升级已有库时，迁移从每条策略原有 `changed_epoch` 初始化验证代次，而不是使用迁移执行时的
当前代次；已恢复的旧策略不会因为补迁移而得到确认。先迁移再升级所有提供更新接口的节点；
旧二进制不执行本检查，恢复操作仍须停用旧节点并隔离入口。

本机制要求部署方正确选择新的外部代次并提供可信当前目录。它不能发现从未进入备份的历史制品，
不能证明人工清单正确，也不替代独立的防回滚账本、生产加密备份或 RPO／RTO 演练。

## 定向验证

集中用例使用真实 PostgreSQL custom-format dump，在另一新建 socket-only 集群恢复备份。
源库在备份后撤回发行，恢复库缺少这次撤回；经过正常隔离／身份释放后，新登记且仍有组织成员
资格的手机也不能取得旧目录准入。以可信当前空目录显式 replace
后，新目录可用，旧推荐仍拒绝。

另一个生产二进制夹具验证隔离释放但策略未确认时在监听前退出。
未变化目录另覆盖 confirm、历史重试不重放、审计失败回滚、并发唯一性、禁止修改／删除回执、
迁移保留旧验证代次，以及生产 CLI stdout 丢失后按原操作对账。这里验证本地数据库与 Go 逻辑，
没有运行 Android、Host 全链路、Windows／Wine、学校身份、真机、大负载或生产备份设施测试。

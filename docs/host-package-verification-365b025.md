# Host 包验证：365b025

本批从干净运行输入 `365b02534e5ff6f423d13f3142295462af4fc398` 构建 Host npm 包，
纳入审批授权缓存的单调时钟修复。墙钟暂停或回拨不能延长缓存持有的签名权限，
首次证明延迟、验签耗时和最终执行检查共用连接建立时的时钟锚点。

包仍为私有 `0.0.1`，包含 260 个文件，未发布或安装到常驻 profile。
文件位于 `.local/host-release-365b025-evidence/host/ustc-dsh-remote-control-0.0.1.tgz`。
[摘要与范围收据](host-package-verification-365b025.json)记录源码 revision、包/清单 SHA-256 和日志摘要。
旧的 [1a40092 包记录](host-package-verification-1a40092.md)保持原范围。

## 执行边界回归

新增 `stalled-wall-body` 用例使用锁定的 npm DSH 0.2.1-rc.5 SDK、临时 Host 和本地签名夹具。
在手机决定及两秒授权证明被接受后、实际工具正文之前，冻结测试进程的 `Date.now`，
同步阻塞 2.3 秒；事件循环此时无法调度普通过期定时器。测试结束恢复回调，不修改系统时钟。

同一回归对源 `1a40092` 的旧包得到预期失败：工具正文执行一次，而断言要求零次。
包含修复的新包拒绝进入正文，并留下已失效、取消或过期的审批状态。
正常批准、拒绝、撤权、工具上下文变更及已开始执行的结果语义也由该原生执行用例覆盖。
`SignedApprovals` 从离线校验后新解包的包内模块加载；签名数据、原生日志和测试宿主由源码夹具提供。
本批没有重新运行已安装 CLI 或 Go/DSH 双节点组合。

## 集中验证

| 范围 | 结果 |
| --- | --- |
| 已校验包内缓存时钟：暂停、未观察到的回拨、前跳后暂停、小数累积、迟到验签/首次证明、异常时钟 | 13 项通过，含父/子结果 |
| 已校验包内原生审批执行边界 | 29 项通过，含父/子结果 |
| TypeScript 类型检查、Host 包构建与离线校验 | 通过 |

包内回归全程没有工作树 `host/remote-control/lib`，PATH 中会记录并拒绝的 Go/npm 替身未被调用。
没有运行全量测试、Android、Windows/Wine、真机或大负载测试。
常驻开发栈保持原配置和进程，学校身份与生产设施验收仍开放。

复核时显式选择包与独立记录的摘要，只运行相关回归：

```sh
REMOTE_TEST_HOST_BUNDLE_DIRECTORY="$PWD/.local/host-release-365b025-evidence/host" \
REMOTE_TEST_HOST_BUNDLE_MANIFEST_SHA256=41bdf1e382e2fbb07be8cddbd09248980a5d20ef0ee74cbd7b91de912b78f512 \
node --import tsx --test \
  --test-name-pattern='normal native approval owner|approval cache never stretches|in-flight approval verification cannot outlive|delaying the first approval proof|invalid or reversed monotonic clocks' \
  host/remote-control/tests/approval-lease.test.ts \
  host/remote-control/tests/signed-approvals.dsh.test.ts
```

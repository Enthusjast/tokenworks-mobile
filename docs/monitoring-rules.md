# 私有指标采集与规则包

`deploy/monitoring` 提供现有 Go 私有指标端点的 Prometheus 采集示例、四条 recording rule 和
七条告警。它们覆盖采集配置、控制任务、可选 scanner 健康及未知结果数据质量，不改变业务权限、租约、数据库
或协议。不自动安装／启动 Prometheus、加载配置、发送通知或修改常驻开发服务。

<a id="targets"></a>

## 启用与标签

先按[服务指标说明](operational-metrics.md)在实际 Go 部署中显式启用私有指标监听器，再由
同机 collector 采集。`prometheus.example.yml` 的 127.0.0.1:9090 是示例；业务 API 的
/metrics 仍为 404，不应改成公网或 wildcard 监听来迁就采集。

将示例复制到实际监控配置并审核，保持 job 名 dsh-remote，或同步修改规则中的 job 选择器。
示例 scrape／evaluation 均为 15 秒，超时 5 秒。`rules.yml` 相对配置文件放置；规则不会
因为仓库有这个文件而生效。通知路由和接收者继续由部署方在现有监控设施中明确配置。

TargetMissing 表示没有该 job 的 up 序列，ScrapeUnavailable 表示已有目标但采集失败。
后者不能直接判定学校身份、数据库或业务 HTTP 是否可用；先检查采集路径和指标开关。

<a id="scope"></a>

## 共享数据库作用域

每个 scrape target 必须有 cluster 与 database_scope 标签：同一控制数据库的所有节点
使用相同的两项值；不同数据库必须有不同的 database_scope。这里是基础设施作用域，
不能放用户、组织、会话、命令 ID、正文或凭据。规则没有从数据库 URL 推导标签。

缺少标签的目标会告警，并被排除在数据库聚合之外，不能把不同数据库合并进空作用域。
Prometheus external_labels 不会自动参与本机查询；示例把这些标签放在 target labels 中。
复制到服务发现／relabel 配置时，应保留相同语义，并保证 job／instance 标识一个采集来源。

<a id="contract"></a>

## 指标合同

up=1 只说明 HTTP scrape 成功。规则还检查当前版本预置的五类 control_available、
unknown_snapshot_available、relay_draining 和 file_scanner_enabled 是否存在。
file_scanner_enabled=1 时，还要求存在值为 0／1 的 file_scanner_ready；enabled=0 不要求 ready。
新增规则需与服务指标合同同步升级，旧版本缺少 enabled 不能被当作停用的健康状态。缺失系列会触发 MetricsIncomplete，
不会当成健康的零值。升级、换 exporter 或 metric_relabel 删除指标后，需要复核这个合同。
采样首次成功前 unknown_results 和 age 本来就缺席，不属于合同缺失。

<a id="control"></a>

## 控制任务

ControlWorkerUnhealthy 同时查看最近尝试失败、最近成功过旧，以及成功时间超过监控时钟
30 秒的异常。它只对采集正常、非 draining 的实例生效；不可采集时交给采集告警，计划 drain
不触发工作任务告警。告警不能延长租约，也不能把失败关闭的授权改成允许。

示例时间如下，部署前需结合实际 scrape、轮询、执行期限与试点测量审核：

| 条件 | 示例窗口 |
| --- | --- |
| outbox / leases / consumer / approval_expiry 最近成功过旧 | 30 秒 |
| consumer_cleanup 最近成功过旧 | 180 秒 |
| 最近成功时间位于监控时钟未来 | 超过 30 秒 |
| 工作任务异常持续多久才告警 | 1 分钟 |

cleanup 约每 60 次 consumer 续期执行，不能按一秒级任务的窗口判断。其他告警的 for 窗口
为 1–2 分钟，主要用于避免启动／短暂切换噪声。它们不是 PLAN 的小于 5 秒撤权指标或已测
可用性 SLO；告警发生前业务仍按原有授权检查和失败关闭逻辑执行。

<a id="scanner"></a>

## 可选 scanner 健康

ScannerUnready 只对 enabled=1、ready=0、up=1 且非 draining 的实例生效，持续一分钟才告警。
文件服务未配置不会告警；抓取失败交给 ScrapeUnavailable，缺少 enabled／ready 则由 MetricsIncomplete
报告合同缺失，不把缺样本填成健康值。时序夹具覆盖启动短暂未就绪后恢复、持续故障、停用、缺指标、
drain 和抓取失败。阈值是部署前需审核的示例，不代表生产 scanner 或文件服务 SLO 已验收。

先查看自检固定错误日志、探测年龄、时钟同步与 clamd 配置／签名库，再核对当前健康证明。
保留文件准入失败关闭；不要延长证明、标记 clean 或放宽扫描策略来消除告警。
告警不会自动修改服务、部署监控规则或向接收方发送通知。

<a id="unknown"></a>

## 未知结果快照

fresh 规则要求同一实例 up=1、available=1、上报年龄非负，年龄加上 Prometheus 采集后经过的
时间仍小于 30 秒，并且 command／approval 两类非负计数都存在。只有符合这些条件的实例
才能参与数据库级 max 聚合。示例关闭 honor_timestamps，使用 collector 的采集时间。
不能只比较一个被 lookback 保留的旧年龄值，否则旧样本会继续伪装成新鲜数据。

同库多个节点重复观察同一批行，不能相加。规则值是最近一次 evaluation 的结果，还会受采集／评估间隔影响。
max 仍可能受采样时差影响，只是新鲜快照中的
保守聚合，不是持久精确账本。不同数据库保持独立；30 秒为示例新鲜度窗，不是生产保证。

没有新鲜来源时 recording series 缺席，不填零；持续两分钟触发 UnknownSnapshotUnavailable。
该告警恢复只表示数据重新可靠，不表示未知结果已清空。成功的空库快照才输出真实零值。
告警表达式的数值是该作用域登记的目标数，不是未知结果行数。
本包没有自动重发、重新执行或清除未知记录的动作，仍须按原 request_id 对账。

来源计数也缺席时不能解释为零个未知结果；它表示没有可用的样本。这个区别适用于失败、
超时、样本过旧、某类计数缺失和负值等情况。详见[快照指标边界](unknown-results-metrics.md)。

## 路由诊断

owner_route_seconds:p95_5m 从成功 owner 路由的 histogram bucket 汇总后计算分位数；不会
相加节点 p95。它不包含前置 TLS／代理全部往返、模型／人工等待或失败投递，不能称为完整
客户端端到端路由 SLO。无样本时可以是缺席或 NaN，不能用零替代。规则不为这项指标预设
性能告警阈值，也不证明已满足目标负载。

## 验证方式

逻辑稳定后可集中运行：

```sh
/path/to/promtool check config deploy/monitoring/prometheus.example.yml
/path/to/promtool test rules deploy/monitoring/rules.test.yml deploy/monitoring/scanner.rules.test.yml
```

规则夹具覆盖同库去重、跨库隔离、加权 bucket 分位数、无流量、真实零值、初始无数据、失败／
过旧／不完整样本、单节点保持新鲜、采集失败、标签和合同缺失、控制失败／停滞／时钟异常、
cleanup 周期及 drain。这里只做规则语义验证，不连接真实指标端点或通知接收方。

## 本地验证记录

使用 Prometheus promtool 2.53.5+ds1（Linux/amd64）集中验证：采集配置及十条规则语法通过，
13 组时序语义场景通过。额外的旧样本场景确认 raw age 仍被 lookback 保留为零时，数据库级
未知计数已经缺席，并产生无新鲜来源告警。没有执行 Go／Host 全量、Windows／Wine、Android、
真机或大负载测试，也没有安装系统服务或接入通知通道。

promtool 来自配置的 Ubuntu APT 源，只解压到任务 `.local`；包 SHA-256 已与 APT 元数据比对。
版本、校验输出、规则副本与包来源收据保存在 `.local/monitoring-rules-evidence`。这些结果不等于
真实生产采集、通知送达、告警响应或 SLO 验收。

本轮 scanner 扩展使用同一现有本地 promtool：十一条规则和采集配置检查通过，原有时序夹具与
新增七组 scanner 场景均通过；Go 健康指标及相关扫描的定向 race 用例通过。
没有加载常驻 Prometheus 配置、启动采集或接入通知。

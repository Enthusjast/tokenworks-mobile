# Host 发布、消费与投影计数

可选能力 `host.flow.observations.v1` 增加三个实际采样阶段的固定计数，补充已有的
[原生操作／checkpoint 耗时](host-native-observations.md)。它独立协商，只有启用 Go 私有
指标的 Relay 才提供；手机不能取得或发送这项安装实例能力。旧 Relay 不协商时，Host 保持
原有业务，既不采样也不保留离线窗口。

## 采样阶段

| stage | 位置与单位 | kind |
| --- | --- | --- |
| agent | 当前进程 `agent/assistant-stream` 的一次原生发布；每个正常 Host adapter 只监听一次，不因跟随者数量重复注册 | start / chunk / end / other |
| follow | 插件每个 live subscription 从原生 sessionController.follow 消费的一帧 | snapshot / event / start / chunk / replace / end / other |
| projection | SessionSync 为 live subscription 创建的内部投影信封，含 checkpoint 及问题快照 | snapshot / event / checkpoint / start / chunk / replace / end / question / other |

Agent 监听只接受配置工作区根或能在当前 Session registry 证明其祖先的 Agent；无法确定的
外部祖先不被自动纳入统计。归属判断会读取本机 Session 头，但 ID／路径不进入计数窗口。
观察器只读取发布／帧的 type 判别字段，不访问 chunk 正文、工具参数、错误或模型配置。
未知类别归入固定 other，不用来源字符串创建标签。

三个阶段的单位和基数不同。Agent 可以在无人订阅时继续发布；两个订阅会消费两份 follow，
投影还会附加 checkpoint 和问题快照。snapshot 一帧可以带多条历史记录，question 一帧也可
带多个请求；分页、目录、审批和文件等其它路径不属于这里的 live projection 统计。

projection 记录信封创建，包含内部预取或随后放弃的信封，位于后续转换／分片／网络写入之前。
它不是实际投递计数。Agent chunk 也不是模型 token 数或原始供应商 SSE 帧数。不能直接相除
宣称合并率，也不能把三个阶段相加当作唯一事件／命令总数；当前 SDK 的合并、快照替换、
跟随者数量及采样窗口都需分别考虑。Go 原有 received/written 帧指标仍反映自己的传输边界。

## 有界发布

窗口只保存 stage/kind/count，最多 20 个合法组合；count、dropped 和 sequence 最大为十亿。
没有 per-event 队列、资源 ID、正文、耗时或错误对象。未绑定、连接替换及窗口关闭时的数据
不补发到下一连接；观察器异常不会改变原生返回值、异常或投影结果。

每十秒最多尝试一份有变化的累计报告，最多 4 KiB；已关闭或积压超过 128 KiB 的 socket
会跳过此次发送。不等待业务写入、不另建重发队列。背压／发送失败时保留累计值供后续机会，
交给 socket 不等于对端接收，连接末尾或无后续变化时的样本也可能丢失。dropped 只统计源
窗口拒绝的类别／饱和样本，不是完整丢失率。

帧为 `host.flow.observations`，带当前 runtime_id、runtime_epoch、connection_id 和单调 sequence。
共享 Schema／golden 由现有 `generate-native-observation-contract.ts` 一同维护。它不修改旧的
host.observations 或 journal 观测格式。

## Go 接收与指标

Relay 检查角色、协商能力、最小业务身份字段、4 KiB 载荷、固定组合唯一性、计数上界及当前
runtime／epoch／connection。每个连接的报告尝试最多每秒处理一次；合法报告还复核当前数据库
安装连接。新序号相对上一窗口求差，旧 cell 不得消失或回退；相同序号只有内容相同时才幂等忽略。
接收路径额外的有期限连接复核有成本，启用后仍须按目标负载测量，不能当作零成本遥测。

非法统计只进入固定结果类别，不替代正常业务的授权、命令、原生 journal 或审批证明。
报告字段里的连接身份仅用于复核，不作为指标标签，不写入业务数据库。Collector 只读取
本地计数；没有在 scrape 时查询 Host 或数据库。

指标共同前缀 `tokenworks_mobile_`：

| 指标 | 含义 |
| --- | --- |
| host_flow_events_total{stage,kind} | 已通过检查的累计报告增量，只有固定组合 |
| host_flow_dropped_samples_total | Host 报告的 dropped 增量 |
| host_flow_reports_total{result} | accepted / duplicate / rejected / throttled / denied |

```promql
sum by (stage, kind) (rate(tokenworks_mobile_host_flow_events_total[5m]))
sum by (result) (rate(tokenworks_mobile_host_flow_reports_total[5m]))
```

未出现某个系列不证明源事件为零：可能未协商、没有新样本、上报受阻或窗口已关闭。这些仍是
可伪报、可丢失的 Host 自报统计，不能作为执行许可、持久回执、精确审计账本或端到端 SLO。

## 验证范围

本轮集中做定向验证，没有运行全量 suite、Windows／Wine、Android APP、真机或大负载。

- TypeScript 检查及构建通过，8 个选定 Node 用例通过，包含真实 npm dsh 0.2.1-rc.5 的原有
  CLI／旧 Relay 路径，以及真实 SDK Agent、两个 live consumers、外部工作区隔离、背压、
  连接重绑和共享 Schema／golden。两个 consumers 不使 agent 发布次数重复。
- Go 的共享 golden 增量、真实 WebSocket 协商／限速／重复／拒绝、手机伪造、当前连接权限
  和指标隐私进行定向 race 验证。未出现的计数系列按缺席处理，不为测试伪造零值。

早期编译和测试辅助器失败日志保留；SDK 签名／迭代器类型与无样本指标的测试轮询已修正。
这不证明真实学校身份、企业 profile、原始模型网络流、跨平台或容量指标已验收。

最后一次隔离 Go／PostgreSQL／npm dsh 联合用例通过：正常 source Host 实际协商这项能力，
三种阶段的 chunk 增量都出现在 owner 的私有指标，原有聊天、问题、签名审批和撤权继续通过。
联合用例使用私有身份／模型／密钥夹具；已选择的旧 Go 或 Host 发行包可以不支持该可选能力，
不会被这项新指标断言强制升级。它不是真实学校或生产平台验收。

证据保存在 `.local/host-flow-evidence`：包括选定 Node 用例、Go delta／接收检查、最后的
原生联合日志、共享协议样例和源码摘要。本轮没有再运行全量 Go／Host suite。

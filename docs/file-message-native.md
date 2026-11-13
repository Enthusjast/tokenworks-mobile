# Host 消息附件转换与恢复

Host Runtime 现在可以将已确认交接的文件 ID 转为同一原生会话的临时 receipt，并随 `queue` / `steer` prompt 提交。服务端已接入 [附件授权 / 消费账本与 v1 Relay](file-message-relay.md)，双方协商 `message.files.v1` 后可使用。[Android 文件上传和消息界面](android-files.md) 已接入。

## ID 与原生准入

消息 content 中的文件项严格为 `{"type":"file","file_id":"<UUID>"}`，最多与文本合计 16 项，同一消息不允许重复文件 ID。原生 receipt、对象 key、工作区路径和客户端提供的文件信息均不能混入命令。本地结构见 `contracts/m0/command.schema.json`，公共 v1 结构及协商规则见 [Relay 文件消息](file-message-relay.md)。

Runtime 在实际 dispatch 时根据当前上传者 user / device、目标会话、连接租约、暂存确认和原生 receipt 状态解析全部文件项。任何一项不可用时整条消息在调用原生 prompt 前被拒绝；没有部分文件入队。失败记为确定的 `file.staging_required`，重试同一命令返回同一结果。确认尚未到达、连接失效或会话释放后都不能绕过检查。

提交给 DSH 的请求 ID 继续按设备分区，命令摘要覆盖原始文件 ID 内容。原生 prompt 尝试结束后退休对应 receipt；文件字节由原生附件存储保留。Relay 文件 peer 检测到 receipt 被消费后释放交接，不继续为已消费映射续租。命令日志的附件项仅接收文件 ID，临时 receipt 不写入 SQLite 或对外结果。

## 崩溃与幂等恢复

本地日志已经 `agent_queued` 的重试直接返回保存结果，不重新解析已经退休的 receipt。同一个 request ID 改为另一个文件 ID 会因摘要冲突而拒绝。

处于 `dispatching` 的消息在崩溃后可能已经进入原生持久日志，也可能尚未入队。恢复缺少 receipt 时，Runtime 直接核对 DSH 0.2.1-rc.2 原生 inbox 与会话事件中的设备分区 request ID，并等待已有状态的持久化检查点，不调用 prompt，也不提交占位 receipt。没有原生记录时，命令保留 `outcome_unknown`，不能据此自动重放。

Host 另提供 `reconcileFileMessage`：它先只读查询原命令日志并验证 owner 和完整摘要，未知命令不会新增日志；即使当前仍有有效 receipt，对账也不能把一个尚未入队的意图变成新消息。同一请求仍在执行入队时，对账等待该次操作并重报其结果。该方法已接入 [Host 专用 Relay 对账帧](file-message-relay.md)，可以处理原生已入队但中转对象到期、回执丢失的情况。

实际 npm DSH Loader 和重新打开的加密 SQLite 日志已验证这些行为：全新 DSH 上下文可恢复原持久消息；仍在原生 inbox 中的请求可以确认已有准入，队列长度不增加；缺少原生证据的恢复不会产生用户消息。恢复普通原生会话仍使用 Session Controller 的既有生命周期。升级 DSH 时必须重新验证原生 request source、队列和持久化契约。

## 验证与下一步

`host/remote-control/tests/file-messages.test.ts` 覆盖设备 / 用户 / 会话隔离、未确认与取消映射、多个附件中的缺项、receipt 退休、已确认重试、内容冲突、有 / 无原生证据的持久恢复、未知意图、有效 receipt 下禁止新准入、并发对账、原生队列和重新加载 DSH。接入 Relay 对账后，`npm run verify` 的 213 项测试全部通过，包含现有 Host 文件交接、DSH CLI、协议和审批回归；本机记录为 `.local/file-reconcile-relay-verify-final.log`。

服务端归属绑定、逐文件授权、能力协商和对象到期后的只读结果恢复已接入，验证记录见 [Relay 文件消息](file-message-relay.md)。文件历史现以合法显示名和字节数生成受限文字摘要；仅有附件的消息也可阅读。下一步继续受限导出和 APK 文件后端联合验收。

## 显式图片与受保护历史预览

视觉输入另行协商 `message.images.v1`，消息项为 `{"type":"image","file_id":"<UUID>"}`。它仍引用同一上传、内容哈希、扫描、Host 交接确认和原始请求消费账本；普通 `type:file` 即使扩展名为 PNG 也不自动成为视觉输入。文件项与图片项合计仍受 16 项限制，同一文件 ID 不能跨两种类型重复引用，原命令摘要包含实际类型。

Host 的 `NativeFileRegistry.resolveImage` 先经过普通暂存与 owner/session 检查，再通过同一原生 receipt 的 `readFileStream` 收集并核对原字节。固定前置上限为 16 MiB 编码、32,000,000 像素、每边 16,384 像素；PNG/JPEG/WebP/GIF 头部准入不替代真实解码。`ctx.attachments.validateImage` 完整解码首帧并执行原生 MIME、方向与正规化验证；同一原生 Context 同时最多进行一次这一完整图片验证。16 位 RGBA 原始解码缓冲的像素上限对应至多 256,000,000 字节，不是整个原生进程的峰值内存承诺。原始上传字节不在手机或 Host 适配层静默重编码。

最终调用仍是原生 Session Controller 的 `PromptContentPart` 图片项，包含实际字节的 canonical base64 与验证所得 `mediaType`。原生在准入时检查当前所选模型的 `inputModalities`；明确不接收图片时报告 `image.model_unsupported`，而不是改成普通附件或宣称模型已看图。图片格式/解码失败报告 `image.invalid`，固定上限报告 `image.too_large`，完整解码繁忙报告 `image.busy`。这些确定拒绝不会掩盖其他原生命令的未知结果。

历史显示投影另行协商 `session.media.v1`。只有既有 `session-file-export` 可见事件来源规则认可的原生图片块，才产生版本 1 的 typed image reference；系统、开发者、工具注入、收件箱和压缩上下文不能利用原生宽泛的 attachment 查询进入手机。引用包含原生事件序号、原内容索引、普通可见投影版本摘要、清单摘要与格式/尺寸，但不包含原生附件 ID、路径、URL 或 receipt。旧客户端仍接收严格文字块。

`session.media.resolve` 绑定当前 runtime epoch、session、subscription 和 sync，将上述显示引用解析成普通的不透明导出 source ID。Relay 在请求与结果两侧独立执行 `session.read` 与 `file.download` 导出授权，并绑定同一设备 key/grant 版本；每订阅最多两个请求，使用短导出租约。Host 重新核对同一持久可见事件与文件摘要，导出 source 在原同步退出或取消时立即失效。之后仍使用普通 `file.export.request`、扫描与受保护下载；显示引用本身不是下载授权。

新增验证用例位于 `media-admission.test.ts`、`media-projection.test.ts`、`media-exports.test.ts` 和 Relay `media_test.go`。本批实现期间未运行构建或测试，不能将这些新增路径视为已完成联合验收。


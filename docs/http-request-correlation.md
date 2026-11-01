# HTTP 请求关联

Go 服务的业务 HTTP 和私有 Relay 监听器，为每次进入应用处理链的请求生成随机 UUID v4，
通过 `X-Request-ID` 响应头返回。关联中间件位于数据库恢复保护之前，因此恢复保护、认证、
授权和普通路由失败也有 ID。HTTP 解析或 TLS 握手尚未进入处理链时的失败不在此范围内。
私有 Prometheus 监听器保持独立，不生成此访问日志。

公共入口忽略请求中的 `X-Request-ID`，每次重试生成新 ID。它表示一次 HTTP 尝试；
协议正文中的 enrollment、命令或文件 `request_id` 仍表示原有的业务身份，重试时继续沿用。
关联 ID 不参与授权、签名验证、幂等判断或 Prometheus 标签。

## 跨节点

入口转发 owner HTTP 或设备 WebSocket 时，从请求上下文复制服务生成的 ID，
不复制公共客户端提供的 ID。owner 先验证内部 mTLS 证书、证书中的节点身份、
当前节点代次以及绑定到自身的路由，然后才接受单个规范小写 UUID v4。
业务凭据、DPoP、ticket 和当前路由检查仍按原流程执行。
即使业务认证失败，通过内部身份检查的尝试也能与入口日志关联。

未通过内部身份检查的请求保留 owner 自己生成的 ID。缺少、重复、含逗号、
不规范的内部 ID 也保留本地 ID。旧版本节点没有传递或生成关联 ID 时仍可工作；
此时仅有单节点关联，入口响应也始终保留入口生成的 ID，不采纳 owner 任意返回的响应头。

## 安全访问日志

生产入口把 `http_request` 事件以结构化 JSON 写入 stderr。除标准 time / level / msg，
固定字段为 `request_id`、`listener`、`method`、`status`、`duration_ms`、`hijacked`、`completed`。
listener 为 public / relay；标准 HTTP method 使用固定词表，其他值记为 OTHER。
日志不读取正文，也不记录 URL、资源路径、查询参数、用户/设备/组织身份、请求头、
凭据、签名、错误详情或响应正文。成功的 GET `/healthz` 和 `/readyz` 探测省略日志，
仍返回关联头；失败探测照常记录。

`duration_ms` 使用本地单调时钟，覆盖整个 handler 生命周期。文件流包含传输等待，
WebSocket 包含升级后的连接寿命，不能当作握手时延或完整业务操作耗时。
`hijacked` 仅表示成功接管底层连接。`completed` 表示 handler 正常返回，
不证明响应送达、文件完整接收或命令执行；panic 继续交给原有 HTTP 处理机制，
未写状态时记录 status=0、completed=false，不伪报为成功的 200。

中间件不缓冲或修改响应正文，保留信息响应、Flush、ResponseController 的传输控制及
WebSocket Hijack。它也不处理升级之后的消息内容。

## JSON 响应格式协商

请求携带单个 `X-DSH-Response-Envelope: request-id.v1` 后，标准 JSON 响应增加顶层
`request_id`，同时通过同名响应头确认格式。该 ID 必须与 `X-Request-ID` 相等。
成功响应为 `{ok:true,data:...,request_id:...}`；错误响应为
`{ok:false,error:{code:...},request_id:...}`。这是 PLAN §6.2 的响应关联格式，
与 `data.request_id` 等既有业务身份相互独立。

不发送协商头时继续返回严格的 `{ok,data}` / `{ok,error}`。
当前 Android 使用这个兼容格式，本批没有修改其代码。
空或重复的协商头返回 400 / protocol.invalid；不支持的值返回 406 / protocol.unsupported，
都在业务分派之前拒绝，并用未协商的错误格式返回，不确认未知格式。
框架路由的 404 / 405 在新格式下也是标准 JSON；405 保留原有 Allow 语义。
认证、Relay 凭据失败和数据库恢复保护的错误也复用同一构造器。
JSON 响应为 no-store，并按协商头设置 Vary。

204、成功的文件内容流和 WebSocket 升级/数据不是 JSON envelope，不增加正文或格式确认头，
仍返回原有 HTTP 关联头。HTTP/TLS 解析失败和框架重定向也不属于标准 JSON 响应。
公开协议见 [OpenAPI](../contracts/openapi/remote.yaml)：各 endpoint 业务 schema 接受两种表示，
有格式确认时还必须满足 `NegotiatedResponseEnvelope`，并在运行时核对头部和正文 ID。

## Host 与混合版本节点

Host 登记和连接凭据传输层主动请求新格式，先严格解码 JSON，验证版本、UUID 和头部一致性，
再移除已验证的顶层传输元数据，交给原有业务 schema。
它不会删除未知业务字段，也不会覆盖嵌套的业务 request_id。
缺少、重复、不同或未确认的关联 ID，以及未知响应格式，都会使该响应失效；
损坏的 401 不能触发 nonce 重试，POST 损坏响应也不会被自动重放。
旧服务没有格式确认且返回原有响应体时，继续按旧 schema 验证；不会额外发送一次 POST 来探测版本。
Issuer/JWKS、对象存储、文件内容和 WebSocket 数据维持各自的独立协议，不发送此 REST 协商头。

跨节点入口从服务上下文传递协商结果。新格式请求的 owner JSON 元数据响应有 64 KiB 上限，
读取最多两秒；入口校验整个 envelope 后，重新使用入口 ID 构造公开响应，
保留业务数据及 DPoP nonce / challenge，移除旧正文长度，由 HTTP 重新确定长度或使用分块传输。
这也兼容旧 owner 的原有标准 JSON，或 owner 在内部身份检查之前生成的本地错误 ID。
owner 的不规范/重复字段、错误版本、头部与正文 ID 不一致、无效状态组合、编码或大小超限，
会在输出公开响应之前失败关闭，返回带入口 ID 的 503 / internal。
这类失败不能证明 owner 的 POST 未提交，必须按原业务身份对账，不能自动重放。
成功文件内容直接走原流式路径，即使字节看起来像 JSON，也不会被解析或重新封装。

## 验证范围

定向验证覆盖独立尝试与公共 ID 替换、日志字段和隐私、成功/失败探测与 panic、
实际 HTTP 信息响应和分段 Flush、实际 WebSocket 升级、已有 API 认证/错误响应，
以及实际 mTLS owner HTTP 转发和私有 Relay 的节点代次/证书身份/路由信任边界。
新格式另覆盖明确协商与拒绝、头部/正文一致性、嵌套业务身份保持、
框架错误及恢复保护、实际 mTLS 新旧 owner 转发和文件内容不被包装，
以及 Host 对损坏元数据的拒绝与 POST 不重放。
这些使用本地身份/证书、隔离 PostgreSQL 和协议夹具，不代表学校生产配置或目标负载验收。

本批集中检查中，TypeScript 检查、89 个相关 Host / HTTP 契约用例及所选 Go race 用例通过；
API 测试的编译问题修正后仅补跑该包。随后单次隔离 PostgreSQL、生产 Go 入口与本机 npm
dsh 0.2.1-rc.5 组合通过，实际登记、凭据获取、原生会话及撤权链路继续正常工作。
未运行全量测试、Windows / Wine、Android、真机或负载测试。

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

## 响应兼容与验证

本批以响应头补齐 HTTP 关联能力。现有 Host 和 Android 使用严格 JSON schema，
因此现有 `{ok,data}` / `{ok,error}` 响应体保持原样。PLAN §6.2 的顶层 JSON `request_id`
尚需另行完成版本协商与客户端兼容；本批不宣称完成该协议升级，也不改变 Android。

定向验证覆盖独立尝试与公共 ID 替换、日志字段和隐私、成功/失败探测与 panic、
实际 HTTP 信息响应和分段 Flush、实际 WebSocket 升级、已有 API 认证/错误响应，
以及实际 mTLS owner HTTP 转发和私有 Relay 的节点代次/证书身份/路由信任边界。
测试使用本地证书与内存目录夹具，不代表学校生产配置或目标负载验收。

# 审批 authority 描述与轮换

`remote approval-authority describe` 从受保护部署配置及其 PEM 密钥生成仅含公开信息的描述，
Host 可用 `parsePinnedApprovalAuthorityDescription` 核对独立部署的摘要后取得公钥配置。
命令不连接数据库或 IdP，不启动监听器，不写配置/密钥，不签发审批 lease，也不报告某个节点已加载了该键。
这些工具支持受控轮换准备和核对；不会自动替换当前开发或生产 authority。

## 导出公开描述

使用与服务一致且独立可信的 OAuth issuer / client allowlist。签名 authority 的 issuer 是
`signing.issuer`，与 OAuth issuer 分工不同，不能混用。

```sh
export REMOTE_ISSUER='https://identity.example.edu'
export REMOTE_CLIENT_IDS='registered-android-client,registered-host-client'
.local/remote approval-authority describe --policy-file /受控目录/approvals.json > /受控目录/authority-public.json
```

也可用 `REMOTE_APPROVAL_POLICY_FILE` 作为 policy-file 默认值。命令复用正常策略的
字段、组织提供的 assurance 映射、私有文件及 P-256 密钥校验；映射和策略无效时不输出描述。
输出只有 `format=tokenworks-mobile-approval-authority/v1`、`authority`、`policy`、`sha256`。
authority 的字段正好为 Host 现有信任锚使用的 issuer、key_id、public_jwk、key_jkt、active、
valid_from、valid_until；没有私钥、PEM 路径、OAuth client 列表或完整 assurance mappings。

`sha256` 为不含 sha256 字段的 `{format,authority,policy}` 的 RFC 8785 UTF-8 SHA-256。
相同键和公开字段的输出及摘要确定，不依赖导出时间或 PEM 编码。
它只标识这些公开字段，不是完整部署配置的摘要，也不能证明配置已部署或替代 assurance 映射审查。
映射变化仍须按既有规则提升 policy version。

描述命令可以核查尚未生效或已经过期的键，方便预分发和历史核对；
active=true 表示该描述中的配置状态，不是“此刻可签名”的证明。
正常服务启动仍检查当前有效期，并在密钥读取完成后再次检查；运行中的签名与 Host 验证
也独立执行期限限制。导出未来/历史描述不会启用审批或放宽时间窗口。

## Host 固定摘要

```ts
import { parsePinnedApprovalAuthorityDescription } from 'tokenworks-mobile'

// raw 来自描述文件；trustedSHA256 来自独立可信的发行配置。
// 不得将该文件自报的 sha256 或收到的 lease/JWS header 当作 trustedSHA256。
const description = await parsePinnedApprovalAuthorityDescription(raw, trustedSHA256)
// 审阅/核对 description.policy 与已有本机策略一致后，显式将 description.authority
// 加入 signedApprovalOptions.authorities。函数不会修改当前 owner/cache/policy。
```

解析上限 16 KiB，拒绝重复/未知字段、非规范坐标、thumbprint 不符、无效 P-256 点、
不合法期限和策略。即使攻击者重新计算文件自报摘要，独立固定摘要不匹配仍会拒绝。
输出被冻结，公钥对象也被冻结；它不是 lease、手机决定或可重复使用的执行许可。
公开形状见 `contracts/security/approval-authority.schema.json`，密码学及固定摘要规则还需运行时检查。
解析本身不检查“此刻可执行”，以允许预分发；实际执行仍需有效的服务/手机双签名、当前连接、
策略、授权和原生最终 guard。没有自动 JWKS/key-URL 获取或从网络 peer 建立信任的降级。

## 受控轮换顺序

1. 在组织的密钥设施准备新 P-256 键，使用新的 key ID 和不同的不可变私钥文件名。
   新配置仍经既有受保护来源发布；不要原地重写当前键文件再另行修改 key ID。
2. 导出候选描述，通过独立可信渠道审阅/部署其摘要与描述，并核对策略元数据。
   Host 先配置信任旧键和新键，最多八个不重复的 key ID。未来生效键可以预分发，但此前不能验证 lease。
3. 受控切换 Go 节点的签名配置并重启。描述命令不执行这一步，也不能以其输出代替节点和链路核对。
   新旧节点共存期间，独立配置的两个信任锚可以验证各自签名，仍绑定同一原手机决定和精确连接。
4. 核对新键链路后，明确移除旧信任锚，并关闭/重建受影响 Host owner 和 lease cache。
   仅修改外部数组不会更新已经复制的缓存。关闭应阻止在途验证重新发布授权；
   配置重载后的新连接也不接受旧连接 lease。
5. 按组织设施策略退休旧私钥及备份。工具不删除键，不清空 journal、nonce、已消费决定或未知结果记录。

紧急撤销时优先移除旧锚和关闭 authority。关闭不能证明已经开始的外部副作用停止；
执行结果按原流程对账，不能因轮换重新使用一次批准。
本流程不引入自动分发信任、不声明硬件密钥保护，也不替代生产发布审批。

## 验证边界

集中定向验证涵盖离线、仅公开且确定的 CLI 输出，生产参数分派、未来/历史键不会启用运行时，
Go 生成并签名的新旧两把夹具键与真实 Host 验证器之间的互操作，重叠期接受、移除旧键后的拒绝、
关闭缓存和关闭期间验证失效，以及摘要/私钥字段/曲线/策略篡改的拒绝。
没有轮换现有开发密钥，也不代表学校身份、生产密钥设施或真实放量已经验收。

本批集中运行相关 Go race 用例、12 个 Host 描述/lease 用例、TypeScript 检查和该契约生成器的
freshness 检查，均通过。没有运行全量测试、Android、Windows / Wine、真机或负载测试。

## 单独的真实进程演练

使用当前源码与本机已安装的 dsh 0.2.1-rc.5，可单独运行以下场景；该开关要求
`--dsh-only`，不与其他故障/续期场景或打包产物混用，`--cross-node` 可选。

```sh
REMOTE_TEST_GO=/opt/go1.26.10/bin/go \
REMOTE_TEST_DSH_BIN='<本机 dsh 的绝对路径>' \
python3 tools/integration/run_remote_dsh.py --dsh-only --cross-node --approval-key-renewal
```

场景只操作一次性私有 PostgreSQL/客户端目录中的两把审批夹具键、Go 子进程和正常 Host 插件。
先用旧键完成一次 Bash 单次批准，再切换为新键并明确重启 Go 节点，在旧/新重叠信任下完成第二次。
随后通过测试包装器关闭、重新装配正常插件的 child scope，仅保留新锚；
DSH 进程、同一个 Agent 与原生会话不重启，远端使用新 epoch/连接重新订阅并核对既有历史。

最后一次批准中，夹具操作端用旧服务私钥对实际新连接/手机决定的同一 lease payload 重新签名，
先独立验证其仍有效，再把这个公开 JWS 交给真实 Host cache。
Host 不读取服务私钥；它必须拒绝旧键 proof，且随后接受尚未过期的原新键 proof，
因此不能把旧键拒绝归因于过期或手机决定/连接不匹配。
三个实际 Bash 分别追加独立文件，原决定重试及最终核对均确认只执行一次。
验证报告仅记录已验证 key ID/approval ID 和结果；不记录许可、正文、令牌或私钥。

本地该双节点场景通过；初次夹具多带手机提交 epoch 被协议拒绝，修正后仅重跑同一场景。
模式完成即提前返回，跳过默认脚本后续无关检查，不重复 Go 全量套件。
测试描述文件和固定摘要由隔离夹具操作端预置，不是生产分发流程；
私钥在同机测试目录中的访问隔离也不能证明跨主机/生产密钥设施边界。
现有开发栈及其审批键未参与轮换，学校身份、物理设备和负载验收仍未涵盖。

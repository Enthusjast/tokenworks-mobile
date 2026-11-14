/* DSH lazy client factory. React and slots come from the running Web client. */
window.__ModuleLoader__.load({
  id: 'tokenworks-mobile',
  factory(require) {
    const { createElement: h, useState, useEffect } = require('react')
    const css = `.tokenworks-mobile{color:var(--dsw-alias-label-primary);font-size:14px;line-height:1.6;max-width:100%;padding:8px 0}.tokenworks-mobile h2{font-size:20px;margin:0 0 8px}.tokenworks-mobile p{color:var(--dsw-alias-label-secondary);margin:8px 0 16px}.tokenworks-mobile article{border:1px solid var(--dsw-alias-border-default,#8885);border-radius:12px;padding:16px;margin:16px 0}.tokenworks-mobile h3{font-size:16px;margin:0 0 12px}.tokenworks-mobile dl{display:grid;grid-template-columns:100px minmax(0,1fr);gap:10px;margin:0}.tokenworks-mobile dt{color:var(--dsw-alias-label-secondary)}.tokenworks-mobile dd{margin:0;overflow-wrap:anywhere}.tokenworks-mobile button{font:inherit;color:inherit;background:var(--dsw-alias-interactive-bg-hover);border:1px solid var(--dsw-alias-border-default,#8885);border-radius:8px;min-height:44px;padding:8px 14px;cursor:pointer}.tokenworks-mobile button:disabled{opacity:.6;cursor:wait}.tokenworks-mobile button:focus-visible,.tokenworks-mobile textarea:focus-visible{outline:2px solid currentColor;outline-offset:3px}.tokenworks-mobile .actions{display:flex;gap:8px;flex-wrap:wrap}.tokenworks-mobile textarea{box-sizing:border-box;width:100%;min-height:110px;font:inherit;color:inherit;background:transparent;border:1px solid var(--dsw-alias-border-default,#8885);border-radius:8px;padding:8px}.tokenworks-mobile img{max-width:100%;height:auto;background:white}.tokenworks-mobile .pair-code{font:700 28px monospace;letter-spacing:.12em}.tokenworks-mobile li{margin:12px 0;overflow-wrap:anywhere}`
    function RemoteToolsPanel() {
      const [status, setStatus] = useState(null), [error, setError] = useState(''), [busy, setBusy] = useState(false), [reload, setReload] = useState(0)
      const [selected, setSelected] = useState(''), [descriptorText, setDescriptorText] = useState(''), [id, setId] = useState(''), [displayName, setDisplayName] = useState('')
      const [addresses, setAddresses] = useState(''), [workspaces, setWorkspaces] = useState('[]'), [presets, setPresets] = useState(''), [paths, setPaths] = useState('[]'), [sessions, setSessions] = useState('[]')
      const [actions, setActions] = useState('discover,workspaces,create_session,prompt,wait,files.list,files.stat,files.read,files.download,files.upload')
      const [allowPrivate, setAllowPrivate] = useState(false), [allowLoopback, setAllowLoopback] = useState(false), [certificate, setCertificate] = useState('')
      useEffect(() => {
        const cancel = new AbortController()
        fetch(new URL('tokenworks-mobile/remote-tools/status', document.baseURI), { credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: cancel.signal })
          .then(async response => { if (!response.ok) throw new Error(response.status === 404 ? '此主机未配置受保护的远端工具存储。' : '远端工具设置只供主机所有者读取。'); return response.json() })
          .then(value => { if (!cancel.signal.aborted) setStatus(value) }).catch(reason => { if (!cancel.signal.aborted) setError(reason.message) })
        return () => cancel.abort()
      }, [reload])
      async function perform(action, body) {
        if (busy) return
        setBusy(true); setError('')
        try {
          const response = await fetch(new URL(`tokenworks-mobile/remote-tools/${action}`, document.baseURI), { method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
          if (!response.ok) { const failure = await response.json().catch(() => ({})); throw new Error(`${failure.code || '远端设置操作未确认'}；不会自动重发。配对结果不明时只读核对。`) }
          if (action === 'export') {
            const blob = await response.blob(), url = URL.createObjectURL(blob), anchor = document.createElement('a')
            anchor.href = url; anchor.download = 'remote-file'; anchor.click(); setTimeout(() => URL.revokeObjectURL(url), 1000)
          } else await response.json()
          if (action === 'pair') setDescriptorText('')
          setReload(value => value + 1)
        } catch (reason) { setError(reason.message); setReload(value => value + 1) }
        finally { setBusy(false) }
      }
      function configure() {
        try {
          const full = JSON.parse(descriptorText), { code, expiresAt, ...descriptor } = full
          if (!window.confirm('请独立核对目标安装/运行实例、HTTPS 地址、TLS SPKI 与 Noise 公钥。配对授予目标完整原生所有者权限；本机动作、工作区与路径过滤不是目标服务器沙箱。确认保存这一个目标的允许清单？')) return
          const csv = value => value.split(',').map(item => item.trim()).filter(Boolean)
          const config = { id, displayName, route: 'direct', descriptor, origin: descriptor.origin, tlsPin: descriptor.tlsPin, allowedAddresses: csv(addresses), allowPrivate, allowLoopback, workspaces: JSON.parse(workspaces), allowedActions: csv(actions), allowedAgentPresets: csv(presets), allowedPaths: JSON.parse(paths), sessionBindings: JSON.parse(sessions), ...(certificate ? { trustedCertificate: certificate } : {}) }
          void perform('configure', { config, confirm: 'direct-only-full-native-owner' }); setSelected(id)
        } catch (reason) { setError(`配置未发送：${reason.message}`) }
      }
      const target = status?.targets.find(value => value.id === selected)
      const field = (label, value, change, multiline = false) => h('label', { key: label, style: { display: 'block', margin: '12px 0' } }, label,
        h(multiline ? 'textarea' : 'input', { value, onChange: event => change(event.target.value), disabled: busy, spellCheck: false, style: { boxSizing: 'border-box', display: 'block', width: '100%', minHeight: 44, padding: 8, font: 'inherit', color: 'inherit', background: 'transparent', border: '1px solid var(--dsw-alias-border-default,#8885)', borderRadius: 8 } }))
      const states = { unpaired: '未配对', 'pair-pending': '配对结果待核对（不会重发）', paired: '已配对，尚未证明当前在线', revoked: '本机已撤销', 're-pair-required': '须重新独立配对' }
      return h('article', { 'aria-label': '原生跨主机工具设置' }, h('h3', null, '原生跨主机工具'),
        h('p', null, '默认关闭。仅直连明确允许且可达的 pinned HTTPS 目标（direct-only）；本安装不会冒充手机或 Relay 设备。模型只能选择目标编号，不能设置地址、凭证或信任策略。'),
        h('p', { role: 'note' }, '重要：每个目标独立配对，目前获得目标完整原生所有者权限。本机动作、工作区与确切路径过滤不是目标服务器强制的沙箱。源 Host 的合资格所有者可回答原生 Agent 审批，不保证只有最初发起页面能回答。'),
        h('div', { role: 'status', 'aria-live': 'polite' }, error || (busy ? '正在处理；请勿重复发送…' : status ? `当前${status.enabled ? '已启用' : '已关闭'} · ${status.route}` : '正在读取远端工具配置…')),
        status && h('div', { className: 'actions' }, h('button', { type: 'button', disabled: busy, onClick: () => perform('enabled', { enabled: !status.enabled }) }, status.enabled ? '关闭并隔离全部出站连接' : '启用已配置目标'), h('button', { type: 'button', disabled: busy, onClick: () => setReload(value => value + 1) }, '只读刷新状态')),
        status?.caller && h('dl', null, h('dt', null, '源安装'), h('dd', null, status.sourceInstallationId), h('dt', null, '源运行实例'), h('dd', null, status.sourceRuntimeId), h('dt', null, '本机调用者'), h('dd', null, `${status.caller.localCallerId} · direct-only（不是 Relay 注册设备）`), h('dt', null, '本机保护者'), h('dd', null, `${status.caller.osOwner.platform} uid ${status.caller.osOwner.uid}`), h('dt', null, '源 owner 摘要'), h('dd', null, status.caller.ownerSha256)),
        status && h('label', { style: { display: 'block', margin: '12px 0' } }, '当前设置目标', h('select', { value: selected, onChange: event => setSelected(event.target.value), disabled: busy, style: { display: 'block', width: '100%', minHeight: 44, font: 'inherit' } }, h('option', { value: '' }, '请选择目标'), ...status.targets.map(value => h('option', { key: value.id, value: value.id }, `${value.displayName} (${value.id}) · ${states[value.status] || value.status}`)))),
        target && h('div', null, h('dl', null,
          h('dt', null, '目标安装'), h('dd', null, target.descriptor.installationId), h('dt', null, '目标实例'), h('dd', null, target.descriptor.runtimeId), h('dt', null, '地址'), h('dd', null, target.origin), h('dt', null, 'TLS SPKI'), h('dd', null, target.tlsPin), h('dt', null, 'Noise 公钥'), h('dd', null, target.descriptor.hostStaticPublicKey), h('dt', null, '授权代次'), h('dd', null, target.authorizationEpoch), h('dt', null, '状态'), h('dd', null, states[target.status] || target.status)),
          h('p', null, `允许地址：${target.allowedAddresses.join(', ')}；动作：${target.allowedActions.join(', ')}。本机撤销与换钥仅隔离本机出站凭证，还须在目标撤销其已配对设备。`),
          h('div', { className: 'actions' },
            h('button', { type: 'button', disabled: busy || target.status !== 'pair-pending', onClick: () => perform('reconcile-pair', { targetId: selected }) }, '只读核对原配对结果'),
            ...['revoke', 'rotate'].map(action => h('button', { key: action, type: 'button', disabled: busy, onClick: () => { if (window.confirm('此操作立即隔离该目标的本机凭证和未派发批准；不会替你撤销目标上的原生配对。须在目标撤销旧设备，之后重新独立配对。确认继续？')) void perform(action, { targetId: selected, confirm: 'local-fence-target-revocation-required' }) } }, action === 'revoke' ? '本机撤销并断开' : '轮换本机身份并重新配对')))),
        h('details', null, h('summary', null, '配置或更新一个独立目标'),
          field('目标编号（模型可见）', id, setId), field('目标显示名称', displayName, setDisplayName), field('目标所有者提供的完整 v2 配对信息 JSON（含双 pin；短码不持久化在浏览器）', descriptorText, setDescriptorText, true),
          field('明确允许的精确 IP / 窄 CIDR，逗号分隔', addresses, setAddresses),
          h('label', { style: { display: 'block', padding: '8px 0' } }, h('input', { type: 'checkbox', checked: allowPrivate, onChange: event => setAllowPrivate(event.target.checked), disabled: busy }), ' 允许上述清单内的私网地址（不放开其他私网）'),
          h('label', { style: { display: 'block', padding: '8px 0' } }, h('input', { type: 'checkbox', checked: allowLoopback, onChange: event => setAllowLoopback(event.target.checked), disabled: busy }), ' 明确允许上述清单内的本机回环地址'),
          field('允许工作区 JSON 数组：workspaceId 与 canonicalRoot（确切绝对路径）', workspaces, setWorkspaces, true), field('允许原生 Agent preset，逗号分隔（创建时必须明确选择）', presets, setPresets), field('允许动作，逗号分隔', actions, setActions),
          field('确切文件路径授权 JSON 数组：path、actions（list/stat/read/download/upload）、maxBytes；不承诺远端 symlink 根目录隔离', paths, setPaths, true),
          field('已有 Session 绑定 JSON 数组：sessionId、workspaceId、sourceAgentId；新建 Session 自动绑定原调用 Agent', sessions, setSessions, true), field('仅此目标的可选 PEM CA 证书（仍校验名称、证书链与 SPKI pin）', certificate, setCertificate, true),
          h('div', { className: 'actions' }, h('button', { type: 'button', disabled: busy || !descriptorText || !id || !displayName || !addresses, onClick: configure }, '核对双 pin 并保存允许清单'),
            h('button', { type: 'button', disabled: busy || !selected || !descriptorText || target?.status === 'pair-pending', onClick: () => { try { const descriptor = JSON.parse(descriptorText); if (window.confirm(`只对目标 ${selected} 发起一次独立完整所有者配对。短码与目标身份须刚刚核对；失去响应后只读核对，不重发。确认？`)) void perform('pair', { targetId: selected, descriptor, confirm: 'direct-only-full-native-owner' }) } catch (reason) { setError(reason.message) } } }, '向选定目标独立配对一次'))),
        status && h('details', null, h('summary', null, `操作审计与未知结果（${status.operations.length}）`), h('p', null, 'accepted 仅表示原生接收；unknown 表示可能已执行。网络恢复、切换目标和刷新不会重放任何变更。使用源 Agent 的 remote_reconcile 只读核对；create/upload 缺少确切证据时保持未知。'),
          h('ul', null, status.operations.map(operation => h('li', { key: operation.operationId }, h('code', null, operation.operationId), ` · ${operation.targetId} · ${operation.kind} · ${operation.state} · rpcId ${operation.requestId}`,
            operation.kind === 'files_download' && operation.state === 'accepted' && h('button', { type: 'button', disabled: busy, onClick: () => { if (window.confirm('显式导出已获原生审批并校验摘要的私有文件到浏览器。不会自动覆盖工作区文件。确认下载？')) void perform('export', { operationId: operation.operationId, confirm: 'export-approved-private-file' }) } }, '显式导出已批准文件'))))))
    }
    function Panel() {
      const [data, setData] = useState(null), [devices, setDevices] = useState([])
      const [offer, setOffer] = useState(null), [busy, setBusy] = useState(false), [message, setMessage] = useState('')
      const [rotation, setRotation] = useState(null), [reload, setReload] = useState(0), [tick, setTick] = useState(Date.now())
      const rotationKey = value => `tokenworks-mobile/host-rotation/${value.runtimeId}/${value.installationId}`
      useEffect(() => {
        const cancel = new AbortController(); let timer
        async function refresh() {
          try {
            const response = await fetch(new URL('tokenworks-mobile/status', document.baseURI), { credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: cancel.signal })
            if (!response.ok) throw new Error('读取主机状态失败，请检查 dsh 登录与连接。')
            const status = await response.json(); if (cancel.signal.aborted) return
            setData(status)
            setOffer(current => current && current.descriptor.hostKeyEpoch === status.pairing?.hostKeyEpoch ? current : null)
            if (status.pairing) {
              let saved
              try { saved = JSON.parse(sessionStorage.getItem(rotationKey(status.pairing)) || 'null') }
              catch { setMessage('无法读取本页的操作编号，请核对主机当前密钥和最近操作。') }
              if (saved && (saved.runtimeId !== status.runtimeId || saved.installationId !== status.pairing.installationId ||
                  !/^[0-9a-f-]{36}$/i.test(saved.operationId) || !Number.isSafeInteger(saved.beforeEpoch))) throw new Error('保存的操作编号与主机不匹配，已停止操作。')
              setRotation(saved || null)
              const paired = await fetch(new URL('tokenworks-mobile/pairing', document.baseURI), { credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: cancel.signal })
              if (!paired.ok) throw new Error('读取已配对设备失败。')
              const body = await paired.json(); if (!cancel.signal.aborted) setDevices(body.devices)
            } else { setDevices([]); setRotation(null) }
          } catch (error) { if (!cancel.signal.aborted) setMessage(error.message) }
          finally { if (!cancel.signal.aborted) timer = setTimeout(refresh, 5000) }
        }
        void refresh()
        return () => { cancel.abort(); clearTimeout(timer) }
      }, [reload])
      useEffect(() => { const timer = setInterval(() => setTick(Date.now()), 1000); return () => clearInterval(timer) }, [])
      async function action(path, body) {
        setBusy(true); setMessage('')
        try {
          const response = await fetch(new URL(path, document.baseURI), { method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) })
          if (!response.ok) throw new Error(response.status === 403 ? '需要主机所有者登录，且必须从同源设置页面操作。' : '操作未获确认。请查询主机状态，不会自动重试。')
          if (path === 'tokenworks-mobile/pairing') {
            if (response.status !== 201) throw new Error('主机未确认创建配对码。')
            const created = await response.json()
            if (created.descriptor?.version !== 2 || created.descriptor?.suite !== 'Noise_IK_25519_ChaChaPoly_SHA256' || typeof created.qrSvg !== 'string') throw new Error('主机未提供所需的加密配对版本，不会使用旧版配对。')
            setOffer(created); setMessage('新配对码有效两分钟，只能使用一次。请核对主机身份与 Noise 公钥。')
          } else {
            if (response.status !== 204) throw new Error('主机未确认撤销。')
            setMessage('设备已撤销，其局域网与中转加密连接已关闭。'); setReload(value => value + 1)
          }
        } catch (error) { setMessage(error.message) }
        finally { setBusy(false) }
      }
      async function rotateHost(retry) {
        const pairing = data?.pairing
        if (!pairing || busy || !Number.isSafeInteger(pairing.hostKeyEpoch)) return
        if (!window.confirm(retry
          ? '使用原操作编号重试。若尚未提交，将轮换密钥并撤销全部旧配对；已提交的同编号操作不会再次轮换。确认继续？'
          : '轮换主机 Noise 密钥将撤销全部已配对设备，关闭局域网与中转加密连接。所有设备须重新核对新二维码并配对；首次配对入口必须可达。确认继续？')) return
        setBusy(true); setMessage(''); setOffer(null)
        let intent = retry ? rotation : null, dispatched = false
        try {
          if (retry && !intent) throw new Error('没有可核对的原操作编号。')
          if (!intent) {
            if (typeof crypto.randomUUID !== 'function') throw new Error('需要 HTTPS 或本机安全浏览器上下文。')
            intent = { operationId: crypto.randomUUID(), runtimeId: data.runtimeId, installationId: pairing.installationId, beforeEpoch: pairing.hostKeyEpoch }
            // Persist only the public operation identity before dispatch; never keys or pair codes.
            sessionStorage.setItem(rotationKey(intent), JSON.stringify(intent)); setRotation(intent)
          }
          dispatched = true
          const response = await fetch(new URL('tokenworks-mobile/pairing/rotate-host', document.baseURI), {
            method: 'POST', credentials: 'same-origin', cache: 'no-store', redirect: 'error', headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ operationId: intent.operationId, confirm: 'rotate-and-revoke-all' }),
          })
          if (!response.ok) throw new Error('主机未确认轮换。')
          const result = await response.json()
          if (result.operationId !== intent.operationId || result.revokedAllPairs !== true || result.requiresOperatorRepair !== true ||
              !Number.isSafeInteger(result.hostKeyEpoch) || result.hostKeyEpoch <= intent.beforeEpoch) throw new Error('轮换响应身份或状态不匹配。')
          setMessage(`主机已确认操作 ${intent.operationId}；全部旧配对已失效，请重新配对。`); setReload(value => value + 1)
        } catch (error) {
          setMessage(dispatched ? `${error.message} 结果未知，保留编号 ${intent.operationId}；请先查询主机状态，不会自动重发。` : `未发起轮换：${error.message}`)
        } finally { setBusy(false) }
      }
      function finishRotation() {
        if (!rotation || data?.pairing?.operationId !== rotation.operationId || data.pairing.hostKeyEpoch <= rotation.beforeEpoch) return
        try { sessionStorage.removeItem(rotationKey(rotation)); setRotation(null); setMessage('已完成本次操作核对。') }
        catch { setMessage('无法清除浏览器操作记录；主机密钥状态不受影响。') }
      }
      const descriptor = offer?.descriptor, remaining = descriptor ? Math.max(0, Math.ceil((descriptor.expiresAt - tick) / 1000)) : 0
      const phases = { connecting: '正在连接', connected: '已连接', reconnecting: '正在重连', stopped: '已停止', listening: '已监听', closed: '已关闭' }
      const row = (label, value) => h('div', { key: label, style: { display: 'contents' } }, h('dt', null, label), h('dd', null, value))
      const pairing = data?.pairing
      const confirmed = !!rotation && pairing?.operationId === rotation.operationId && pairing.hostKeyEpoch > rotation.beforeEpoch
      return h('section', { className: 'tokenworks-mobile', 'aria-label': 'TokenWorks Mobile' },
        h('h2', null, 'TokenWorks Mobile'), h('p', null, '端到端加密载体承载 dsh 原生协议；配对授权与中转账号权限分别管理。'),
        h('div', { role: 'status', 'aria-live': 'polite' }, message || (!data ? '正在读取主机状态…' : '')),
        data && h('article', null, h('h3', null, '主机连接'), h('dl', null,
          row('运行实例', h('code', null, data.runtimeId)), row('中转连接', data.relay ? phases[data.relay.phase] || data.relay.phase : '未配置'),
          data.relay && row('中转地址', data.relay.origin), row('局域网监听', data.lan ? phases[data.lan.phase] || data.lan.phase : '未启用'))),
        pairing && h('article', null, h('h3', null, '加密配对与设备'),
          h('p', null, '只向信任的个人设备提供配对信息。请在手机核对 TLS 指纹、安装标识和 Noise 公钥；中转服务器提供的替代公钥不可信。'),
          h('dl', null, row('安全地址', pairing.origin), row('安装标识', h('code', null, pairing.installationId)),
            row('TLS 指纹', h('code', null, pairing.tlsPin)), row('Noise 密钥代次', pairing.hostKeyEpoch), row('Noise 公钥', h('code', null, pairing.hostStaticPublicKey))),
          pairing.denied && h('p', { role: 'alert' }, '主机当前拒绝新的加密连接。请处理密钥存储或未确认轮换状态，不会回退到明文。'),
          !data.lan && h('p', null, '局域网监听未启用；现有加密配对仍可通过中转使用，新增或重新配对需暂时启用可信 TLS 配对入口。'),
          h('button', { type: 'button', disabled: busy || pairing.denied || data.lan?.phase !== 'listening', onClick: () => action('tokenworks-mobile/pairing', {}) }, busy ? '正在处理…' : '生成一次性配对码 / 二维码'),
          descriptor && h('div', null, h('p', null, remaining ? `剩余 ${remaining} 秒；成功配对后立即失效。` : '配对码已过期，请重新生成。'),
            remaining > 0 && h('div', null, h('div', { className: 'pair-code', 'aria-label': `配对码 ${descriptor.code.split('').join(' ')}` }, descriptor.code),
              h('img', { width: 320, height: 320, src: `data:image/svg+xml;charset=utf-8,${encodeURIComponent(offer.qrSvg)}`, alt: '包含主机身份、TLS 指纹、Noise 公钥和一次性配对码的二维码' }),
              h('label', { htmlFor: 'lan-pairing-payload' }, '无法扫码时，复制完整配对信息到手机'),
              h('textarea', { id: 'lan-pairing-payload', readOnly: true, value: JSON.stringify(descriptor), onFocus: event => event.target.select() }))),
          h('h3', null, '已配对设备'), devices.length ? h('ul', null, devices.map(device => h('li', { key: device.deviceId },
            h('span', null, `${device.deviceName} · 设备密钥第 ${device.deviceKeyEpoch} 代 · 主机密钥第 ${device.hostKeyEpoch} 代 · ${new Date(device.createdAt).toLocaleString()} `),
            h('button', { type: 'button', disabled: busy, 'aria-label': `撤销设备 ${device.deviceName}`, onClick: () => {
              if (window.confirm(`撤销设备“${device.deviceName}”？其局域网与中转加密连接将被关闭。`)) void action('tokenworks-mobile/pairing/revoke', { deviceId: device.deviceId })
            } }, '撤销并断开')))) : h('p', null, '尚无已配对设备。')),
        pairing && h('article', null, h('h3', null, '轮换主机加密密钥'),
          h('p', null, '这是撤销全部配对的安全操作，不是普通重连。成功后旧密钥永不自动恢复，手机须重新核对新公钥。'),
          rotation ? h('div', null, h('p', null, `操作编号：${rotation.operationId}`), h('p', null, confirmed ? '主机当前状态已确认此操作提交。' : '结果尚未核对，请先查询状态；不会自动重发。'),
            h('button', { type: 'button', disabled: busy, onClick: () => setReload(value => value + 1) }, '查询主机状态'),
            confirmed ? h('button', { type: 'button', disabled: busy, onClick: finishRotation }, '完成核对')
              : h('button', { type: 'button', disabled: busy, onClick: () => rotateHost(true) }, '使用原操作编号重试'))
            : h('button', { type: 'button', disabled: busy || !Number.isSafeInteger(pairing.hostKeyEpoch), onClick: () => rotateHost(false) }, '轮换密钥并撤销全部配对')),
        data && !pairing && h('p', { role: 'alert' }, '加密配对存储未配置，远程业务不可用。请配置平台保护器与受信任配对身份，不会提供明文替代连接。'),
        h(RemoteToolsPanel))
    }
    return { inject: ['slots'], apply(ctx) {
      ctx.effect(() => { const style = document.createElement('style'); style.dataset.plugin = 'tokenworks-mobile'; style.textContent = css; document.head.appendChild(style); return () => style.remove() })
      ctx.slots.inject('settings.section', () => ctx.slots.register({ name: 'settings.section', id: 'tokenworks-mobile', order: 25, label: () => 'TokenWorks Mobile' }, Panel))
    } }
  },
})

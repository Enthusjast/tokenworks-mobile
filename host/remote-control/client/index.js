/* DSH lazy client factory. React and slots come from the running Web client. */
window.__ModuleLoader__.load({
  id: 'tokenworks-mobile',
  factory(require) {
    const { createElement: h, useState, useEffect, useRef } = require('react')
    const css = `.tokenworks-mobile{color:var(--dsw-alias-label-primary);font-size:14px;line-height:1.6;max-width:100%;padding:8px 0}.tokenworks-mobile h2{font-size:20px;margin:0 0 8px}.tokenworks-mobile p{color:var(--dsw-alias-label-secondary);margin:8px 0 16px}.tokenworks-mobile article{border:1px solid var(--dsw-alias-border-default, #8885);border-radius:12px;padding:16px;margin:16px 0}.tokenworks-mobile h3{font-size:15px;margin:0 0 12px}.tokenworks-mobile dl{display:grid;grid-template-columns:100px minmax(0,1fr);gap:10px;margin:0}.tokenworks-mobile dt{color:var(--dsw-alias-label-secondary)}.tokenworks-mobile dd{margin:0;overflow-wrap:anywhere}.tokenworks-mobile button{font:inherit;color:inherit;background:var(--dsw-alias-interactive-bg-hover);border:1px solid var(--dsw-alias-border-default, #8885);border-radius:8px;min-height:40px;padding:6px 14px;cursor:pointer}.tokenworks-mobile button:disabled{opacity:.6;cursor:wait}.tokenworks-mobile button:focus-visible{outline:2px solid var(--dsw-alias-brand-primary-new-colorprimary-new-color, #4c79ff);outline-offset:3px}.tokenworks-mobile .actions{display:flex;gap:10px;flex-wrap:wrap}.tokenworks-mobile .state{font-weight:600}.tokenworks-mobile code{font:12px ui-monospace,monospace;overflow-wrap:anywhere}.tokenworks-mobile ul{padding-left:20px}.tokenworks-mobile li{margin:6px 0}.tokenworks-mobile .notice{border-left:3px solid var(--dsw-alias-brand-primary-new-colorprimary-new-color, #4c79ff);padding-left:12px}`
    function Panel() {
      const [view, setView] = useState({ busy: true, data: null, error: '', updated: '' })
      const [reload, setReload] = useState(0)
      const [copied, setCopied] = useState('')
      const copyVersion = useRef(0)
      useEffect(() => {
        let live = true, timer, request
        async function refresh() {
          if (document.hidden) { timer = setTimeout(refresh, 5000); return }
          request = new AbortController()
          const timeout = setTimeout(() => request.abort(), 4000)
          try {
            const response = await fetch(new URL('tokenworks-mobile/status', document.baseURI), {
              credentials: 'same-origin', cache: 'no-store', redirect: 'error', signal: request.signal,
            })
            if (!response.ok) {
              const error = new Error('status unavailable')
              error.displayMessage = response.status === 401 ? '登录已失效，请重新打开 dsh 的登录入口。' :
                response.status === 404 ? '插件未启用或正在重载，请稍后刷新。' : '暂时无法读取插件状态。'
              throw error
            }
            const data = await response.json()
            if (live) setView({ busy: false, data, error: '', updated: new Date().toLocaleTimeString() })
          } catch (error) {
            if (live) setView({ busy: false, data: null, error: error.name === 'AbortError' ? '读取状态超时，请检查 dsh 是否仍在运行。' : error.displayMessage || '暂时无法读取插件状态，请检查连接后刷新。', updated: '' })
          } finally {
            clearTimeout(timeout)
            if (live) timer = setTimeout(refresh, 5000)
          }
        }
        setView({ busy: true, data: null, error: '', updated: '' })
        void refresh()
        return () => { live = false; clearTimeout(timer); request?.abort() }
      }, [reload])
      useEffect(() => () => { copyVersion.current++ }, [])
      async function copyId() {
        const version = ++copyVersion.current
        try { await navigator.clipboard.writeText(view.data.runtimeId); if (version === copyVersion.current) setCopied('运行实例 ID 已复制。') }
        catch { if (version === copyVersion.current) setCopied('复制失败，请手动选择运行实例 ID。') }
      }
      const data = view.data
      const phases = { connecting: '正在连接', connected: '已连接', reconnecting: '正在重连', stopped: '已停止' }
      const row = (label, value) => h('div', { key: label, style: { display: 'contents' } }, h('dt', null, label), h('dd', null, value))
      return h('section', { className: 'tokenworks-mobile', 'aria-label': 'TokenWorks Mobile' },
        h('h2', null, 'TokenWorks Mobile'),
        h('p', null, '查看当前 dsh 的远控连接和允许访问的范围。'),
        h('div', { className: 'actions' }, h('button', { type: 'button', disabled: view.busy, onClick: () => { setCopied(''); setReload(n => n + 1) } }, view.busy ? '正在读取…' : '刷新状态'),
          data && h('button', { type: 'button', onClick: copyId }, '复制运行实例 ID')),
        h('div', { role: 'status', 'aria-live': 'polite' }, view.error || copied || (view.busy ? '正在读取插件状态…' : '')),
        data && h('article', null, h('h3', null, '连接状态'), h('dl', null,
          row('中转连接', h('span', { className: 'state' }, data.relay ? phases[data.relay.phase] || '状态未知' : '未配置，仅本机接入')),
          data.relay && row('中转地址', data.relay.origin),
          row('代码热重载', data.hotReload ? '已启用' : '未启用'),
          row('本机接入', data.localSocket ? '已启用' : '未启用'),
          row('日志密钥', data.protectedJournal ? '已配置保护器' : '本机文件模式'),
          row('运行实例', h('code', null, data.runtimeId)),
          row('运行代次', h('code', null, data.epoch)),
          row('最近刷新', view.updated))),
        data && h('article', null, h('h3', null, '允许访问的工作区'),
          data.workspaces.length ? h('ul', null, data.workspaces.map(workspace => h('li', { key: workspace.id }, workspace.label))) : h('p', null, '尚未配置允许访问的工作区。'),
          h('p', null, data.presets.length ? `可新建会话的助手配置：${data.presets.join('、')}` : '未开放远程新建会话。')),
        h('p', { className: 'notice' }, '工作区及权限由主机配置和当前授权共同约束。修改范围后会生成新运行代次，旧审批失效。'),
        data?.development && h('p', { className: 'notice' }, '当前使用本地开发身份，尚未接入学校登录。文件服务和真实推送尚未配置。'))
    }
    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.effect(() => {
          const style = document.createElement('style')
          style.dataset.plugin = 'tokenworks-mobile'
          style.textContent = css
          document.head.appendChild(style)
          return () => style.remove()
        })
        ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section', id: 'tokenworks-mobile', order: 25, label: () => 'TokenWorks Mobile',
        }, Panel))
      },
    }
  },
})

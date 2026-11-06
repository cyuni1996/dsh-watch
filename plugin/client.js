window.__ModuleLoader__.load({
  id: 'dsh-watch',
  factory(require) {
    const { createElement: h, useEffect, useState } = require('react')
    const endpoint = '/dsh-watch/settings'
    const panel = { display: 'grid', gap: 10, padding: 16, border: '1px solid #8f8065', borderRadius: 12,
      maxWidth: 650, background: '#0c1528', color: '#f0e9d8' }
    const controls = { display: 'flex', gap: 8, alignItems: 'end', flexWrap: 'wrap' }
    const field = { display: 'grid', gap: 4, fontWeight: 500 }
    const input = { background: '#141f37', color: 'inherit', border: '1px solid #9d8c6a',
      borderRadius: 7, padding: '6px 10px', minHeight: 34, boxSizing: 'border-box' }

    async function request(body) {
      const headers = { 'x-dsh-watch-settings': '1' }
      if (body) headers['content-type'] = 'application/json'
      const response = await fetch(endpoint, {
        headers, method: body ? 'POST' : 'GET', body: body ? JSON.stringify(body) : undefined,
      })
      const result = await response.json().catch(() => ({}))
      if (!response.ok) throw new Error(result.error || '无法读取手表连接，请检查插件是否已启用')
      return result
    }

    function InstallWizard({ state, onLinked }) {
      const [status, setStatus] = useState(null)
      const [deviceKey, setDeviceKey] = useState('')
      const [pairAddress, setPairAddress] = useState('')
      const [pairCode, setPairCode] = useState('')
      const [debugAddress, setDebugAddress] = useState('')
      const [termsAccepted, setTermsAccepted] = useState(false)
      const [dshAddress, setDshAddress] = useState('')
      const [installedKey, setInstalledKey] = useState('')
      const [linkedKey, setLinkedKey] = useState('')
      const [busy, setBusy] = useState('status')
      const [notice, setNotice] = useState(null)
      const keyOf = device => device.id + ':' + device.serial
      const online = (status?.devices || []).filter(device => device.state === 'device')
      const selected = online.find(device => keyOf(device) === deviceKey) || (online.length === 1 ? online[0] : null)
      const key = selected ? keyOf(selected) : ''
      const addresses = (state?.addresses || []).filter(address => !/^https:\/\/(127\.|\[::1\]:)/.test(address))
      const address = addresses.includes(dshAddress) ? dshAddress : addresses[0] || ''
      const step = { display: 'grid', gap: 7, borderTop: '1px solid #354969', paddingTop: 11 }
      const help = { color: '#c7d3e6', fontSize: '0.9em', lineHeight: 1.5 }
      const action = (label, name, values, disabled = false) => h('button', {
        type: 'button', disabled: Boolean(busy) || disabled, onClick: () => run(name, values),
        style: { ...input, width: 'fit-content', background: '#25395f' },
      }, busy === name ? label + '…' : label)
      const entry = (label, value, set, options = {}) => h('label', { style: field }, label,
        h('input', { ...options, value, disabled: Boolean(busy), style: { ...input, width: options.maxLength === 6 ? 120 : 210 },
          onChange: event => set(event.target.value) }))

      useEffect(() => {
        let mounted = true
        request({ action: 'install-status' }).then(value => { if (mounted) setStatus(value) })
          .catch(error => { if (mounted) setNotice({ error: true, text: error.message }) })
          .finally(() => { if (mounted) setBusy('') })
        return () => { mounted = false }
      }, [])

      async function run(name, values) {
        setBusy(name)
        setNotice(null)
        if (name === 'pair') setPairCode('')
        try {
          const result = await request({ action: 'install-' + name, ...values })
          if (result.devices) setStatus(result)
          if (result.paired) {
            setPairAddress('')
            setNotice({ text: result.devices.some(device => device.state === 'device')
              ? '无线调试已配对，请从列表选择手表。'
              : '无线调试已配对。稍等后刷新设备，或用无线调试主页面的地址连接。' })
          }
          if (result.installed) {
            setInstalledKey(key)
            setLinkedKey('')
            setNotice({ text: '手表应用已安装。继续下一步连接 DSH。' })
          }
          if (result.linked) {
            setLinkedKey(key + '@' + address)
            setNotice({ text: result.opened ? '手表已连上 DSH，应用已打开。现在可以关闭手表的无线调试。'
              : '手表已通过 DSH 连接验证，请在手表上打开 DSH Watch。' })
            onLinked()
          }
        } catch (error) { setNotice({ error: true, text: error.message }) }
        finally { setBusy('') }
      }

      return h('div', { style: { display: 'grid', gap: 12, padding: 12, borderRadius: 9, background: '#121e34' } },
        h('div', { style: step },
          h('strong', null, '1 · 准备电脑'),
          h('span', { style: help }, '先让电脑与手表连到同一局域网。'),
          h('div', { style: controls }, action(status ? '刷新设备' : '检测调试工具和手表', 'status'),
            status?.adbReady && h('span', { style: help }, '调试工具已就绪'),
            status && !status.adbReady && status.canDownload && action('从 Google 下载调试工具', 'setup', { accepted: termsAccepted }, !termsAccepted)),
          status && !status.adbReady && h('div', { style: help },
            !status.canDownload && '请安装 Platform-Tools，并将 adb 加入电脑的 PATH，然后重启 DSH。',
            ' ', h('a', { href: 'https://developer.android.com/tools/releases/platform-tools', target: '_blank',
              rel: 'noopener noreferrer', style: { color: '#a9d5fc' } }, 'Platform-Tools 官方下载及许可条款')),
          status && !status.adbReady && status.canDownload && h('label', { style: help },
            h('input', { type: 'checkbox', checked: termsAccepted, disabled: Boolean(busy), onChange: event => setTermsAccepted(event.target.checked) }),
            ' 我已阅读并同意 Android SDK 许可条款。')),
        h('div', { style: step },
          h('strong', null, '2 · 在手表打开无线调试'),
          h('span', { style: help }, '小米手表 5 请先切到智能模式。在设置里打开「关于本机」或「我的设备」，连续点手表系统版本号直到开发者选项开启。返回设置，打开「开发者选项 → 无线调试」，按手表提示允许当前网络。'),
          online.length > 0 && h('span', { style: help }, '已发现调试连接，可直接选择手表并继续。'),
          h('details', { key: online.length ? 'connected' : 'pending', open: status?.adbReady && !online.length ? true : undefined },
            h('summary', null, online.length ? '配对另一块手表' : '首次连接或找不到手表'),
            h('p', { style: help }, '在手表点「使用配对码配对设备」，保持弹窗打开，把弹窗里的地址和六位码填到这里。'),
            h('div', { style: controls }, entry('配对弹窗 IP:端口', pairAddress, setPairAddress, { placeholder: '192.168.1.8:37123', maxLength: 21 }),
              entry('六位系统配对码', pairCode, setPairCode, { inputMode: 'numeric', type: 'password', maxLength: 6, autoComplete: 'off' }),
              action('无线调试配对', 'pair', { address: pairAddress, code: pairCode }, !status?.adbReady || !pairAddress.trim() || !/^\d{6}$/.test(pairCode))),
            h('p', { style: help }, '配对过但列表没有手表时，填写「无线调试」主页面的 IP:端口。这个端口和配对弹窗里的可能不同。'),
            h('div', { style: controls }, entry('无线调试主页面 IP:端口', debugAddress, setDebugAddress, { placeholder: '192.168.1.8:41945', maxLength: 21 }),
              action('连接手表', 'connect', { address: debugAddress }, !status?.adbReady || !debugAddress.trim())))),
        h('div', { style: step },
          h('strong', null, '3 · 安装手表应用'),
          status?.devices.some(device => device.state === 'unauthorized') && h('span', { style: help }, '有设备等待授权，请在手表上确认后刷新。'),
          online.length ? h('div', { style: controls },
            h('label', { style: field }, '目标手表',
              h('select', { value: key, disabled: Boolean(busy), style: input, onChange: event => setDeviceKey(event.target.value) },
                h('option', { value: '' }, '请选择设备'),
                online.map(device => h('option', { key: keyOf(device), value: keyOf(device) }, device.model + ' · ' + device.serial)))),
            action('安装 / 更新 DSH Watch', 'install', { id: selected?.id, serial: selected?.serial }, !selected || !status.apkReady),
            key && installedKey === key && h('span', { style: help }, '应用已安装'))
            : h('span', { style: help }, '刷新后，已连接的设备会出现在这里。'),
          status && !status.apkReady && h('span', { style: help }, '这个插件包没有内置手表 APK，请换用带 APK 的安装包。')),
        h('div', { style: step },
          h('strong', null, '4 · 连接 DSH 并打开手表应用'),
          addresses.length ? h('div', { style: controls },
            h('label', { style: field }, '手表连接的电脑地址',
              h('select', { value: address, disabled: Boolean(busy), style: input, onChange: event => setDshAddress(event.target.value) },
                addresses.map(value => h('option', { key: value, value }, value)))),
            action('连接 DSH 并打开', 'link', { id: selected?.id, serial: selected?.serial, address }, !selected),
            key && linkedKey === key + '@' + address && h('span', { style: help }, '已验证连接'))
            : h('span', { style: help }, '先在上方「监听地址」选电脑的局域网地址，保存后回来连接。')),
        notice && h('div', { role: notice.error ? 'alert' : 'status', style: { color: notice.error ? '#ffb5ae' : '#c2e8dc' } }, notice.text))
    }

    function WatchSettings() {
      const [state, setState] = useState(null)
      const [host, setHost] = useState('')
      const [port, setPort] = useState('')
      const [pairing, setPairing] = useState(null)
      const [busy, setBusy] = useState(false)
      const [message, setMessage] = useState('')
      const [confirm, setConfirm] = useState('')
      const [installOpen, setInstallOpen] = useState(false)
      function show(value) {
        setState(value)
        setHost(value.host)
        setPort(String(value.port))
      }
      useEffect(() => {
        let mounted = true
        request().then(value => { if (mounted) show(value) })
          .catch(error => { if (mounted) setMessage(error.message) })
        return () => { mounted = false }
      }, [])

      async function act(action, id) {
        setBusy(true)
        setMessage('')
        try {
          if (action === 'pair') setPairing(await request({ action }))
          else if (action === 'listen') {
            const value = await request({ action, host, port: Number(port) })
            show({ ...state, ...value })
            setMessage('连接设置已保存')
          } else if (action === 'revoke') {
            await request({ action, id })
            setConfirm('')
            show(await request())
            setMessage('已撤销此设备的访问权限')
          } else show(await request())
        } catch (error) { setMessage(error.message) }
        finally { setBusy(false) }
      }

      const button = (title, run, disabled = false) => h('button', {
        type: 'button', disabled: busy || disabled, onClick: run,
        style: { ...input, justifySelf: 'start', background: '#25395f', cursor: 'pointer' },
      }, title)
      const clock = value => new Date(value).toLocaleString('zh-CN', { hour12: false })
      const validPort = /^\d{1,5}$/.test(port) && Number(port) >= 1 && Number(port) <= 65535
      return h('section', { style: panel, 'aria-label': 'DSH Watch · 手表连接' },
        h('strong', null, 'DSH Watch · 手表连接'),
        state ? h('div', { style: { color: '#d8ccae' } }, 'desktop profile · 服务在线') : null,
        state?.readAloudVoice && h('div', { style: { color: '#c7d3e6' } }, '朗读音色 · ' + state.readAloudVoice + '（本地）'),
        state ? h('div', { style: controls },
          h('label', { style: field }, '监听地址',
            h('select', { value: host, disabled: busy, onChange: event => setHost(event.target.value), style: { ...input, width: 148 } },
              state.interfaces.map(value => h('option', { key: value, value }, value === '0.0.0.0' ? '所有网卡' : value)))),
          h('label', { style: field }, '手表端口',
            h('input', { value: port, disabled: busy, inputMode: 'numeric', onChange: event => setPort(event.target.value), style: { ...input, width: 132 } })),
          button('保存连接设置', () => act('listen'), !validPort || (host === state.host && port === String(state.port))))
          : button('刷新连接信息', () => act('refresh')),
        state && state.addresses.map(address => h('div', { key: address, style: { color: '#d8ccae' } }, address)),
        state && h('div', { style: { display: 'grid', gap: 6 } },
          h('div', null, '首次使用请打开安装向导。安装完成后由向导授权，日常连接无需保持无线调试。'),
          h('div', { style: { display: 'flex', gap: 14 } },
            h('a', { href: 'https://github.com/cyuni1996/dsh-watch/blob/main/docs/INSTALL.zh.md',
              target: '_blank', rel: 'noopener noreferrer', style: { color: '#a9d5fc' } }, '首次连接教程'),
            h('a', { href: 'https://github.com/cyuni1996/dsh-watch/blob/main/docs/HELP.zh.md',
              target: '_blank', rel: 'noopener noreferrer', style: { color: '#a9d5fc' } }, '使用帮助'))),
        state && button(installOpen ? '收起安装向导' : '打开手表安装向导', () => setInstallOpen(!installOpen)),
        installOpen && h(InstallWizard, { state, onLinked: () => request()
          .then(value => setState(current => ({ ...current, devices: value.devices }))).catch(() => {}) }),
        state && h('small', { style: { color: '#c7d3e6' } }, '手动配对用十位码。无线调试的六位码由手表系统提供。'),
        state && button('生成配对码', () => act('pair')),
        pairing && h('div', { role: 'status' },
          h('code', { style: { fontSize: '1.2em', letterSpacing: '0.08em' } }, pairing.code.slice(0, 5), ' ', pairing.code.slice(5)),
          h('div', null, '在手表输入 10 位数字，仅可使用一次，有效至 ' + clock(pairing.expiresAt))),
        state && h('strong', null, '已授权设备（' + state.devices.length + '）'),
        state && (state.devices.length === 0 ? h('span', null, '暂无已配对手表') :
          state.devices.map(device => h('div', { key: device.id, style: { display: 'flex', gap: 8, alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap' } },
            h('span', null, (device.name || '手表 ' + device.id.slice(0, 8)) + ' · ' + clock(device.createdAt)),
            confirm === device.id
              ? h('div', { style: { display: 'flex', gap: 8 } }, button('确认撤销', () => act('revoke', device.id)), button('取消', () => setConfirm('')))
              : button('撤销授权', () => setConfirm(device.id))))),
        message && h('div', { role: 'status' }, message))
    }

    return {
      inject: ['slots'],
      apply(ctx) {
        ctx.slots.inject('settings.section', () => ctx.slots.register({
          name: 'settings.section', id: 'dsh-watch', order: 45, label: '手表连接',
        }, WatchSettings))
      },
    }
  },
})

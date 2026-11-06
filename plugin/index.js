import { createServer as createHttpServer } from 'node:http'
import { createServer as createHttpsServer } from 'node:https'
import { createHash, createHmac, pbkdf2, randomBytes, randomInt, randomUUID, timingSafeEqual, X509Certificate } from 'node:crypto'
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises'
import { homedir, hostname, networkInterfaces } from 'node:os'
import { join } from 'node:path'
import { promisify } from 'node:util'
import { Bonjour } from 'bonjour-service'
import selfsigned from 'selfsigned'
import { WatchSessions, RequestError } from './sessions.js'
import { WatchInstaller } from './install.js'
import { synthesizeReply } from './speech.js'
import { SerenaVoice } from './serena.js'

export const name = 'dsh-watch'
export const inject = ['sessionController', 'workspaceController', 'workspaceRegistry', 'agentDefaultModel']
const codeAlphabet = '23456789ABCDEFGHJKLMNPQRSTUVWXYZ'
const pairingProtocol = 2
const derivePairingKey = promisify(pbkdf2)
const hash = value => createHash('sha256').update(value).digest('hex')
const equal = (a, b) => typeof a === 'string' && a.length === b.length &&
  timingSafeEqual(Buffer.from(a, 'utf8'), Buffer.from(b, 'utf8'))
const proof = (key, protocol, role, pin, nonce, ...fields) => createHmac('sha256', key)
  .update([`watch-internet-v${protocol}`, role, pin, nonce, ...fields].join('\n')).digest('hex')
const ipAddresses = () => [...new Set(Object.values(networkInterfaces()).flat()
  .filter(i => i.family === 'IPv4' && !i.internal).map(i => i.address))]

async function writeJson(path, value) {
  const temporary = path + '.' + randomUUID() + '.tmp'
  await writeFile(temporary, JSON.stringify(value), { mode: 0o600 })
  await rename(temporary, path)
}

async function identityAt(directory) {
  await mkdir(directory, { recursive: true, mode: 0o700 })
  const path = join(directory, 'identity.json')
  try {
    return JSON.parse(await readFile(path, 'utf8'))
  } catch (error) {
    if (error.code !== 'ENOENT') throw error
    const { private: key, cert } = await selfsigned.generate([{ name: 'commonName', value: 'DSH Watch' }], {
      keySize: 2048,
      notAfterDate: new Date(Date.now() + 3650 * 86400000),
      algorithm: 'sha256',
    })
    const identity = { id: randomUUID(), key, cert }
    try {
      await writeFile(path, JSON.stringify(identity), { mode: 0o600, flag: 'wx' })
      return identity
    } catch (error) {
      if (error.code !== 'EEXIST') throw error
      return JSON.parse(await readFile(path, 'utf8'))
    }
  }
}

async function readBytes(request, limit) {
  const advertised = Number(request.headers['content-length'])
  if (advertised > limit) throw new RequestError(413, '请求内容过长')
  const parts = []
  let length = 0
  for await (const part of request) {
    length += part.length
    if (length > limit) throw new RequestError(413, '请求内容过长')
    parts.push(part)
  }
  return Buffer.concat(parts, length)
}

async function bodyOf(request, limit = 65536) {
  const bytes = await readBytes(request, limit)
  try {
    const value = JSON.parse(bytes.toString('utf8'))
    if (value === null || typeof value !== 'object' || Array.isArray(value)) throw new Error()
    return value
  } catch { throw new RequestError(400, 'JSON 请求无效') }
}

function respond(response, status, value) {
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
  })
  response.end(JSON.stringify(value))
}

export function respondSnapshot(response, known, snapshot) {
  const body = JSON.stringify(snapshot)
  const etag = '"' + hash(body) + '"'
  const headers = { 'Cache-Control': 'no-store', ETag: etag }
  if (known === etag) {
    response.writeHead(304, headers).end()
    return
  }
  response.writeHead(200, {
    ...headers,
    'Content-Type': 'application/json; charset=utf-8',
    'X-Content-Type-Options': 'nosniff',
  }).end(body)
}

function listen(server, port, host) {
  return new Promise((resolve, reject) => {
    server.once('error', reject)
    server.listen(port, host, () => {
      server.removeListener('error', reject)
      resolve()
    })
  })
}

export async function apply(ctx, config = {}) {
  let host = config.host ?? process.env.DSH_WATCH_HOST ?? '127.0.0.1'
  let port = Number(config.port ?? process.env.DSH_WATCH_PORT ?? 8766)
  const adminPort = Number(config.adminPort ?? process.env.DSH_WATCH_ADMIN_PORT ?? 18766)
  const directory = config.dataDir ?? process.env.DSH_WATCH_DATA_DIR ?? join(process.env.DSH_HOME ?? join(homedir(), '.dsh'), 'dsh-watch')
  if (![port, adminPort].every(p => Number.isInteger(p) && p > 0 && p < 65536) || port === adminPort) {
    throw new Error('DSH Watch requires two distinct TCP ports')
  }
  const defaultListener = { host, port }
  const listenerPath = join(directory, 'listener.json')
  const interfaces = () => ['127.0.0.1', ...ipAddresses(), '0.0.0.0']
  function validateListener(value) {
    if (!interfaces().includes(value?.host) || !Number.isInteger(value.port) ||
        value.port < 1 || value.port > 65535 || value.port === adminPort) {
      throw new RequestError(400, '请选择可用的监听地址并填写有效手表端口')
    }
    return { host: value.host, port: value.port }
  }
  const identity = await identityAt(directory)
  try {
    ({ host, port } = validateListener(JSON.parse(await readFile(listenerPath, 'utf8'))))
  } catch (error) {
    if (error.code !== 'ENOENT') ctx.logger.warn('dsh-watch: 已保存的监听配置不可用，沿用 DSH 默认配置。')
  }
  const pin = new X509Certificate(identity.cert).fingerprint256.replaceAll(':', '').toLowerCase()
  const computerName = (config.computerName ?? hostname()).slice(0, 40)
  const devicesPath = join(directory, 'devices.json')
  let devices
  try { devices = JSON.parse(await readFile(devicesPath, 'utf8')) } catch (error) {
    if (error.code !== 'ENOENT') throw error
    devices = []
  }
  if (!Array.isArray(devices)) throw new Error('DSH Watch device storage is invalid')
  let pending
  let installerPair
  let persistence = Promise.resolve()
  function changeDevices(change) {
    const operation = persistence.then(async () => {
      const next = change(devices)
      await writeJson(devicesPath, next)
      devices = next
    })
    persistence = operation.catch(() => {})
    return operation
  }
  const sessions = new WatchSessions(ctx, config)
  const serena = new SerenaVoice(directory)
  let synthesizing = false
  const addresses = () => (host === '0.0.0.0' ? ipAddresses() : [host])
    .map(address => `https://${address.includes(':') ? '[' + address + ']' : address}:${port}`)
  const installer = new WatchInstaller(directory, config.installer)
  function authorizeInstall(address) {
    if (!addresses().includes(address) || host === '127.0.0.1' || host === '::1') {
      throw new RequestError(400, '请先保存一个手表能访问的局域网监听地址')
    }
    const pairing = {
      code: Array.from({ length: 16 }, () => codeAlphabet[randomInt(codeAlphabet.length)]).join(''),
      until: Date.now() + 90_000, attempts: 0,
    }
    installerPair = pairing
    return {
      value: { address, code: pairing.code, pin, id: identity.id },
      async clear(accepted) {
        if (installerPair === pairing) installerPair = undefined
        if (!accepted && pairing.deviceId) {
          await changeDevices(current => current.filter(device => device.id !== pairing.deviceId))
        }
      },
    }
  }
  async function manage(command, body) {
    switch (command) {
      case 'pair': {
        pending = { code: String(randomInt(10_000_000_000)).padStart(10, '0'), until: Date.now() + 300_000, attempts: 0 }
        return { code: pending.code, addresses: addresses(), expiresAt: pending.until }
      }
      case 'devices':
        return { devices: devices.map(({ id, createdAt, name }) => ({ id, createdAt, name })) }
      case 'revoke': {
        if (typeof body.id !== 'string' || !devices.some(device => device.id === body.id)) {
          throw new RequestError(404, '找不到该设备')
        }
        await changeDevices(current => current.filter(device => device.id !== body.id))
        return { revoked: body.id }
      }
      default:
        throw new RequestError(404, '未找到配对命令')
    }
  }
  const httpOptions = { requestTimeout: 20000, headersTimeout: 10000, maxHeaderSize: 8192 }
  const admin = createHttpServer(httpOptions, async (request, response) => {
    if (request.method !== 'POST' || request.headers.host !== `127.0.0.1:${adminPort}`
      || request.headers.origin || request.headers['x-dsh-watch-cli'] !== '1'
      || request.headers['content-type'] !== 'application/json') return respond(response, 403, { error: '仅允许本机配对命令' })
    try {
      const body = await bodyOf(request)
      respond(response, 200, await manage(request.url.slice(1), body))
    } catch (error) {
      respond(response, error.status ?? 500, { error: error.status ? error.message : '配对配置保存失败' })
    }
  })
  const createWatchServer = () => createHttpsServer({ ...httpOptions, keepAliveTimeout: 20_000, key: identity.key, cert: identity.cert, minVersion: 'TLSv1.2' }, handleWatch)
  async function handleWatch(request, response) {
    try {
      if (request.headers.origin) throw new RequestError(403, '请从 DSH Watch 客户端连接')
      const url = new URL(request.url, 'https://dsh-watch.invalid')
      const auth = request.headers.authorization
      const info = { computerId: identity.id, computerName, certificateSha256: pin, internetPairingProtocol: pairingProtocol }
      if (request.method === 'GET' && url.pathname === '/api/connect/info') return respond(response, 200, info)
      if (request.method === 'POST' && url.pathname === '/api/connect/internet') {
        const body = await bodyOf(request, 2048)
        const numeric = body.protocol === pairingProtocol
        const installerRequest = body.protocol === undefined || body.protocol === 1
        if (!numeric && (!installerRequest || !installerPair)) {
          throw new RequestError(426, '请先更新手表应用，以使用 10 位数字配对码')
        }
        const currentPair = () => numeric ? pending : installerPair
        const pairing = currentPair()
        const { nonce, proof: supplied } = body
        if (!pairing || pairing.until < Date.now()) throw new RequestError(403, '请在电脑生成新的配对码')
        if (pairing.attempts >= 5) throw new RequestError(429, '配对码错误次数过多，请在电脑生成新码')
        if (typeof nonce !== 'string' || !/^[a-f0-9]{32}$/.test(nonce) ||
            typeof supplied !== 'string' || !/^[a-f0-9]{64}$/.test(supplied)) {
          throw new RequestError(400, '配对请求格式无效')
        }
        pairing.attempts++
        const protocol = numeric ? pairingProtocol : 1
        const key = numeric ? await derivePairingKey(pairing.code, `watch-internet-v2\n${pin}\n${nonce}`, 200_000, 32, 'sha256') : pairing.code
        if (currentPair() !== pairing || pairing.until < Date.now()) throw new RequestError(403, '请在电脑生成新的配对码')
        if (!equal(supplied, proof(key, protocol, 'watch', pin, nonce))) {
          if (pairing.attempts >= 5) throw new RequestError(429, '配对码错误次数过多，请在电脑生成新码')
          throw new RequestError(403, '配对码或证书不匹配')
        }
        if (numeric) pending = undefined
        else installerPair = undefined
        const token = randomBytes(32).toString('base64url')
        const device = { id: randomUUID(), hash: hash(token), createdAt: new Date().toISOString() }
        pairing.deviceId = device.id
        await changeDevices(current => [...current, device])
        return respond(response, 200, { ...info, token, proof: proof(key, protocol, 'computer', pin, nonce, identity.id, token) })
      }
      const token = /^Bearer ([A-Za-z0-9_-]{43})$/.exec(auth ?? '')?.[1]
      const digest = token ? hash(token) : ''
      const device = devices.find(item => equal(digest, item.hash))
      if (!device) throw new RequestError(401, '手表未获授权，请重新配对')
      if (request.method === 'GET' && url.pathname === '/api/state') serena.prewarm()
      if (request.method === 'GET' && url.pathname === '/api/connect/check') return respond(response, 200, info)
      if (!['GET', 'POST'].includes(request.method)) throw new RequestError(405, '不支持此请求方式')
      if (request.method === 'POST' && url.pathname === '/api/speak') {
        const body = await bodyOf(request, 2048)
        if (synthesizing) throw new RequestError(429, '电脑正在朗读另一条回复，请稍后再试')
        synthesizing = true
        const abort = new AbortController()
        const disconnect = () => abort.abort()
        response.once('close', disconnect)
        try {
          const text = await sessions.replyForSpeech(body, device.id, AbortSignal.any([abort.signal, AbortSignal.timeout(12000)]))
          const runtime = await serena.runtime()
          const audio = runtime
            ? await serena.speak(text, abort.signal, runtime)
            : await synthesizeReply(text, abort.signal)
          return response.writeHead(200, {
            'Content-Type': 'audio/wav', 'Content-Length': audio.length, 'Cache-Control': 'no-store',
            'X-Content-Type-Options': 'nosniff',
          }).end(audio)
        } finally {
          response.removeListener('close', disconnect)
          synthesizing = false
        }
      }
      if (request.method === 'POST' && url.pathname === '/api/transcribe') {
        if (request.headers['content-type']?.split(';', 1)[0].trim().toLowerCase() !== 'audio/wav') {
          throw new RequestError(415, '请上传 WAV 录音')
        }
        const controller = ctx.get?.('speechController')
        if (typeof controller?.transcribe !== 'function') {
          throw new RequestError(503, '请在电脑 DSH 的插件页启用「语音输入」')
        }
        const abort = new AbortController()
        const disconnect = () => abort.abort()
        response.once('close', disconnect)
        try {
          const audio = await readBytes(request, 1024 * 1024)
          if (audio.length < 46 || audio.toString('ascii', 0, 4) !== 'RIFF' || audio.toString('ascii', 8, 12) !== 'WAVE') {
            throw new RequestError(400, 'WAV 录音无效，请重新录制')
          }
          const signal = AbortSignal.any([abort.signal, AbortSignal.timeout(85_000)])
          const transcript = await controller.transcribe({ audioBase64: audio.toString('base64') }, signal)
          const text = transcript?.text?.trim()
          if (!text) throw new RequestError(422, '没有识别到语音，请靠近手表再试')
          return respond(response, 200, { text })
        } finally {
          response.removeListener('close', disconnect)
        }
      }
      const body = request.method === 'POST' ? await bodyOf(request) : null
      const result = await sessions.route(url, body, device.id, AbortSignal.timeout(12000))
      if (request.method === 'GET' && url.pathname === '/api/state' && url.searchParams.get('sessionId')) {
        return respondSnapshot(response, request.headers['if-none-match'], result)
      }
      respond(response, 200, result)
    } catch (error) {
      if (response.destroyed || response.headersSent) return
      const status = error.status ?? 502
      const remoteCode = typeof error.code === 'string' && /^[a-z][a-z0-9-]+\/[a-z0-9-]+$/.test(error.code)
      respond(response, status, { error: error.status || remoteCode ? error.message.slice(0, 240) : 'DSH 请求失败，请检查插件日志' })
      if (!error.status) ctx.logger.warn(`dsh-watch: ${error.code ?? error.name}: ${error.message}`)
    }
  }
  const closeServer = server => {
    const closed = new Promise(resolve => server.close(resolve))
    server.closeAllConnections()
    return closed
  }
  let server = createWatchServer()
  let bonjour
  let unregisterSettings
  let listenerChange
  let disposed = false
  function updateDiscovery() {
    bonjour?.destroy()
    bonjour = undefined
    if (host === '127.0.0.1' || host === '::1' || config.discovery === false) return
    try {
      bonjour = new Bonjour(undefined, error => ctx.logger.warn(`dsh-watch discovery: ${error.message}`))
      bonjour.publish({ name: `DSH Watch ${identity.id.slice(0, 8)}`, type: 'dshwatch', port,
        txt: { id: identity.id, pin, name: computerName, ipv4: (host === '0.0.0.0' ? ipAddresses() : [host]).join(',') } })
    } catch (error) {
      bonjour?.destroy()
      bonjour = undefined
      ctx.logger.warn(`dsh-watch discovery: ${error.message}`)
    }
  }
  async function replaceListener(next) {
    const previous = server
    const mustCloseFirst = next.port === port && [host, next.host].includes('0.0.0.0')
    const candidate = createWatchServer()
    try {
      if (mustCloseFirst) await closeServer(previous)
      await listen(candidate, next.port, next.host)
      await writeJson(listenerPath, next)
    } catch (error) {
      await closeServer(candidate)
      if (mustCloseFirst && !disposed) await listen(previous, port, host)
      throw new RequestError(409, error.code === 'EADDRINUSE' ? '此端口已被占用，已保留原设置' : '连接设置未保存，请检查监听地址和端口')
    }
    server = candidate
    host = next.host
    port = next.port
    if (!mustCloseFirst) await closeServer(previous)
    if (!disposed) updateDiscovery()
  }
  async function saveListener(body) {
    const next = validateListener(body)
    if (disposed || listenerChange) throw new RequestError(409, '正在更新连接设置，请稍后再试')
    if (next.host === host && next.port === port) return
    listenerChange = replaceListener(next)
    try { await listenerChange } finally { listenerChange = undefined }
  }
  ctx.effect(() => async () => {
    disposed = true
    pending = undefined
    installerPair = undefined
    serena.close()
    unregisterSettings?.()
    bonjour?.destroy()
    await listenerChange?.catch(() => {})
    await Promise.all([closeServer(server), closeServer(admin)])
  })
  try {
    try { await listen(server, port, host) } catch (error) {
      if (host === defaultListener.host && port === defaultListener.port) throw error
      ctx.logger.warn('dsh-watch: 无法启动已保存的监听地址，改用 DSH 默认配置。')
      host = defaultListener.host
      port = defaultListener.port
      await listen(server, port, host)
    }
    await listen(admin, adminPort, '127.0.0.1')
    const webServer = ctx.get?.('webServer')
    const connection = ctx.get?.('connection')
    if (webServer?.register && connection?.requestRejection) {
      unregisterSettings = webServer.register({
        kind: 'exact',
        path: '/dsh-watch/settings',
        handler: async (request, response) => {
          try {
            const rejection = connection.requestRejection(request)
            const local = ['127.0.0.1', '::1', '::ffff:127.0.0.1'].includes(request.socket?.remoteAddress)
            if (rejection || (!local && connection.isTrusted?.(request) !== true) ||
                request.headers['sec-fetch-site'] === 'cross-site' ||
                request.headers['x-dsh-watch-settings'] !== '1') {
              return respond(response, typeof rejection === 'number' ? rejection : 403, { error: '请从 DSH 设置打开手表连接' })
            }
            if (request.method === 'GET') {
              const voice = ctx.get?.('speechController')?.catalog?.()?.selection?.providerId ?? null
              const readAloudVoice = await serena.runtime() ? 'Serena' : null
              return respond(response, 200, { computerName, host, port, interfaces: [...new Set([...interfaces(), host])], addresses: addresses(), voice, readAloudVoice, ...await manage('devices', {}) })
            }
            if (request.method !== 'POST' || request.headers['content-type'] !== 'application/json') {
              return respond(response, 405, { error: '不支持此操作' })
            }
            const body = await bodyOf(request, 2048)
            const action = typeof body.action === 'string' && body.action.startsWith('install-') && body.action.slice(8)
            if (action) {
              if (!local || disposed) throw new RequestError(403, '安装向导只能在这台电脑的桌面 DSH 中使用')
              if (!['status', 'setup', 'pair', 'connect', 'install', 'link'].includes(action)) throw new RequestError(400, '未知安装操作')
              return respond(response, 200, await installer.run({ ...body, action }, authorizeInstall))
            }
            if (body.action === 'listen') {
              await saveListener(body)
              return respond(response, 200, { host, port, addresses: addresses() })
            }
            if (!['pair', 'revoke'].includes(body.action)) throw new RequestError(400, '未知操作')
            return respond(response, 200, await manage(body.action, body))
          } catch (error) {
            return respond(response, error.status ?? 500, { error: error.status ? error.message : '手表设置暂不可用' })
          }
        },
      })
    }
    updateDiscovery()
    if (devices.length) serena.prewarm()
  } catch (error) {
    bonjour?.destroy()
    server.close()
    admin.close()
    throw error
  }
  ctx.logger.info(`dsh-watch: listening on ${host}:${port}. Generate a pairing code with dsh-watch-pair.`)
}

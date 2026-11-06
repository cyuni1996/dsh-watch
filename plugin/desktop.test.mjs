import assert from 'node:assert/strict'
import { createHash, createHmac, pbkdf2Sync } from 'node:crypto'
import { createServer } from 'node:http'
import { request } from 'node:https'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { apply } from './index.js'
import { parseAdbDevices } from './install.js'
import { SerenaVoice } from './serena.js'

test('Serena warms once and reuses the same reply audio', async () => {
  const voice = new SerenaVoice(tmpdir())
  const phrases = []
  let finishWarmup
  voice.runtime = async () => 'gpu'
  voice.generate = async text => {
    phrases.push(text)
    if (text === '你好。') await new Promise(resolve => { finishWarmup = resolve })
    return Buffer.from(text)
  }
  try {
    voice.prewarm()
    voice.prewarm()
    await Promise.resolve()
    const signal = new AbortController().signal
    const first = voice.speak('你好呀主人', signal, 'gpu')
    assert.deepEqual(phrases, ['你好。'])
    finishWarmup()
    assert.equal((await first).toString(), '你好呀主人')
    assert.equal((await voice.speak('你好呀主人', signal, 'gpu')).toString(), '你好呀主人')
    assert.deepEqual(phrases, ['你好。', '你好呀主人'])
  } finally { voice.close() }
})

test('wireless watch serials can contain spaces and reconnect with a new transport ID', () => {
  assert.deepEqual(parseAdbDevices([
    'List of devices attached',
    'adb-123 (2)._adb-tls-connect._tcp device product:grasslte model:M2505W1 transport_id:7',
    '192.168.1.8:41000     offline transport_id:8',
    'USB-device\tunauthorized transport_id:9',
  ].join('\r\n')), [
    { id: '7', serial: 'adb-123 (2)._adb-tls-connect._tcp', state: 'device', model: 'M2505W1' },
    { id: '8', serial: '192.168.1.8:41000', state: 'offline', model: 'Android 设备' },
    { id: '9', serial: 'USB-device', state: 'unauthorized', model: 'Android 设备' },
  ])
})

async function listen(server) {
  await new Promise((resolve, reject) => server.once('error', reject).listen(0, '127.0.0.1', resolve))
  return server.address().port
}

const close = server => new Promise(resolve => server.close(resolve))

test('desktop settings and authorized WAV are routed through the DSH services', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dsh-watch-test-'))
  const sockets = [createServer(), createServer()]
  const [port, adminPort] = await Promise.all(sockets.map(listen))
  await Promise.all(sockets.map(close))
  const token = 'x'.repeat(43)
  await writeFile(join(directory, 'devices.json'), JSON.stringify([
    { id: 'test-device', createdAt: '2026-11-06T00:00:00Z', hash: createHash('sha256').update(token).digest('hex') },
  ]))
  const audio = Buffer.alloc(46)
  audio.write('RIFF', 0)
  audio.write('WAVE', 8)
  let dispose
  let settingsRoute
  let transcriptions = 0
  const services = {
    webServer: { register(route) { settingsRoute = route; return () => { settingsRoute = undefined } } },
    connection: { requestRejection: () => null },
    speechController: {
      catalog: () => ({ selection: { providerId: 'sensevoice-local' } }),
      async transcribe({ audioBase64 }, signal) {
        assert.deepEqual(Buffer.from(audioBase64, 'base64'), audio)
        assert.equal(signal.aborted, false)
        transcriptions++
        return { text: ' 语音测试。 ' }
      },
    },
  }
  let web
  let occupied
  try {
    await apply({
      get: name => services[name],
      effect: fn => { dispose = fn() },
      logger: { info() {}, warn() {} },
    }, { port, adminPort, dataDir: directory, discovery: false })
    assert.equal(settingsRoute.path, '/dsh-watch/settings')
    web = createServer(settingsRoute.handler)
    const endpoint = 'http://127.0.0.1:' + await listen(web) + settingsRoute.path
    assert.equal((await fetch(endpoint)).status, 403)
    const headers = { 'x-dsh-watch-settings': '1' }
    assert.equal((await fetch(endpoint, { headers: { ...headers, 'sec-fetch-site': 'cross-site' } })).status, 403)
    const settings = await (await fetch(endpoint, { headers })).json()
    assert.equal(settings.port, port)
    assert.equal(settings.voice, 'sensevoice-local')
    assert.deepEqual(settings.devices.map(device => Object.keys(device).sort()), [['createdAt', 'id']])
    const rejectedDownload = await fetch(endpoint, {
      method: 'POST', headers: { ...headers, 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'install-setup' }),
    })
    assert.equal(rejectedDownload.status, 400)
    const issueCode = async () => (await fetch(endpoint, {
      method: 'POST', headers: { ...headers, 'content-type': 'application/json' }, body: JSON.stringify({ action: 'pair' }),
    })).json()
    const pair = await issueCode()
    assert.match(pair.code, /^\d{10}$/)
    assert.ok(pair.expiresAt > Date.now())

    const { cert } = JSON.parse(await readFile(join(directory, 'identity.json'), 'utf8'))
    const watch = (path, body, authorization) => new Promise((resolve, reject) => {
      const payload = body && JSON.stringify(body)
      const req = request({
        host: '127.0.0.1', port, path, method: payload ? 'POST' : 'GET',
        ca: cert, checkServerIdentity: () => undefined,
        headers: { ...(authorization ? { authorization } : {}), ...(payload ? {
          'content-type': 'application/json', 'content-length': Buffer.byteLength(payload),
        } : {}) },
      }, res => {
        const chunks = []
        res.on('data', chunk => chunks.push(chunk))
        res.on('end', () => resolve([res.statusCode, JSON.parse(Buffer.concat(chunks))]))
      })
      req.on('error', reject).end(payload)
    })
    const [, info] = await watch('/api/connect/info')
    assert.equal(info.internetPairingProtocol, 2)
    const nonce = '1'.repeat(32)
    const pin = info.certificateSha256
    const proof = (code, role, ...fields) => {
      const key = pbkdf2Sync(code, ['watch-internet-v2', pin, nonce].join('\n'), 200_000, 32, 'sha256')
      return createHmac('sha256', key).update(['watch-internet-v2', role, pin, nonce, ...fields].join('\n')).digest('hex')
    }
    const correct = { protocol: 2, nonce, proof: proof(pair.code, 'watch') }
    assert.equal((await watch('/api/connect/internet', { nonce, proof: '0'.repeat(64) }))[0], 426)
    const pairResults = await Promise.all([watch('/api/connect/internet', correct), watch('/api/connect/internet', correct)])
    assert.deepEqual(pairResults.map(([status]) => status).sort(), [200, 403])
    const [, authorized] = pairResults.find(([status]) => status === 200)
    assert.equal(authorized.proof, proof(pair.code, 'computer', info.computerId, authorized.token))
    assert.equal((await watch('/api/connect/check', null, 'Bearer ' + authorized.token))[0], 200)
    assert.equal((await watch('/api/connect/check', null, 'Bearer ' + token))[0], 200)
    const lock = await issueCode()
    for (let attempt = 1; attempt <= 5; attempt++) {
      assert.equal((await watch('/api/connect/internet', { ...correct, proof: '0'.repeat(64) }))[0], attempt < 5 ? 403 : 429)
    }
    assert.equal((await watch('/api/connect/internet', { ...correct, proof: proof(lock.code, 'watch') }))[0], 429)
    const upload = (auth, targetPort = port) => new Promise((resolve, reject) => {
      const req = request({
        host: '127.0.0.1', port: targetPort, path: '/api/transcribe', method: 'POST',
        ca: cert, checkServerIdentity: () => undefined,
        headers: { 'content-type': 'audio/wav', 'content-length': audio.length, authorization: auth },
      }, res => {
        const chunks = []
        res.on('data', chunk => chunks.push(chunk))
        res.on('end', () => resolve([res.statusCode, JSON.parse(Buffer.concat(chunks))]))
      })
      req.on('error', reject)
      req.end(audio)
    })
    assert.equal((await upload('Bearer wrong'))[0], 401)
    assert.deepEqual(await upload('Bearer ' + token), [200, { text: '语音测试。' }])
    assert.equal(transcriptions, 1)

    occupied = createServer()
    const nextPort = await listen(occupied)
    const configure = (host, port) => fetch(endpoint, {
      method: 'POST', headers: { ...headers, 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'listen', host, port }),
    })
    assert.equal((await configure('127.0.0.1', nextPort)).status, 409)
    assert.equal((await upload('Bearer ' + token))[0], 200)
    await close(occupied)
    occupied = undefined
    const saved = await configure('127.0.0.1', nextPort)
    assert.equal(saved.status, 200)
    assert.equal((await saved.json()).port, nextPort)
    assert.deepEqual(JSON.parse(await readFile(join(directory, 'listener.json'), 'utf8')), { host: '127.0.0.1', port: nextPort })
    assert.equal((await upload('Bearer ' + token, nextPort))[0], 200)
  } finally {
    if (occupied) await close(occupied)
    await dispose?.()
    if (web) await close(web)
    await rm(directory, { recursive: true, force: true })
  }
})

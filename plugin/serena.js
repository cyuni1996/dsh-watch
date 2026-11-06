import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { RequestError } from './sessions.js'

const workerPath = fileURLToPath(new URL('./serena.py', import.meta.url))
const maxResponse = 16 * 1024 * 1024
const maxCacheBytes = 8 * 1024 * 1024
const gpuIdleTime = 10 * 60_000
const unavailable = () => new RequestError(503, '电脑上的 Serena 暂不可用，请检查本地语音环境')

export class SerenaVoice {
  constructor(directory) {
    this.directory = join(directory, 'serena')
    this.cache = new Map()
    this.cacheBytes = 0
  }

  async runtime() {
    try {
      const runtime = (await readFile(join(this.directory, 'ready'), 'utf8')).trim()
      return ['gpu', 'cpu'].includes(runtime) ? runtime : null
    } catch {
      return null
    }
  }

  stop() {
    clearTimeout(this.idle)
    const worker = this.worker
    this.worker = undefined
    worker?.kill()
  }

  close() {
    this.closed = true
    this.stop()
    this.cache.clear()
    this.cacheBytes = 0
  }

  prewarm() {
    if (this.closed || this.worker || this.warming || Date.now() < (this.warmAfter ?? 0)) return
    this.warmAfter = Date.now() + 60_000
    this.warming = this.runtime().then(runtime => {
      if (runtime === 'gpu' && !this.closed && !this.worker) {
        return this.generate('你好。', AbortSignal.timeout(80_000), runtime)
      }
    }).catch(() => {}).finally(() => { this.warming = undefined })
  }

  start(runtime) {
    const python = process.platform === 'win32' ? 'Scripts/python.exe' : 'bin/python'
    const worker = spawn(join(this.directory, runtime === 'cpu' ? 'venv-cpu' : 'venv', python),
      ['-u', '-X', 'utf8', workerPath, join(this.directory, 'model')], {
        windowsHide: true,
        env: {
          ...process.env, HF_HUB_OFFLINE: '1', TRANSFORMERS_OFFLINE: '1',
          ...(runtime === 'cpu' ? { CUDA_VISIBLE_DEVICES: '-1', OMP_NUM_THREADS: '6', MKL_NUM_THREADS: '6' } : {}),
        },
        stdio: ['pipe', 'pipe', 'ignore'],
      })
    this.worker = worker
    this.workerRuntime = runtime
    const exited = () => { if (this.worker === worker) this.worker = undefined }
    worker.once('close', exited)
    worker.on('error', exited)
    worker.stdin.on('error', () => {})
    worker.stdout.setEncoding('utf8')
    return worker
  }

  async speak(text, signal, runtime) {
    signal.throwIfAborted()
    const key = createHash('sha256').update(text).digest('hex')
    const cached = this.cache.get(key)
    if (cached && cached.until > Date.now()) {
      this.cache.delete(key)
      this.cache.set(key, cached)
      return cached.audio
    }
    if (cached) {
      this.cache.delete(key)
      this.cacheBytes -= cached.audio.length
    }
    await this.warming
    signal.throwIfAborted()
    const audio = await this.generate(text, signal, runtime)
    if (!this.closed && audio.length <= maxCacheBytes) {
      while (this.cache.size >= 24 || this.cacheBytes + audio.length > maxCacheBytes) {
        const [oldKey, oldest] = this.cache.entries().next().value
        this.cache.delete(oldKey)
        this.cacheBytes -= oldest.audio.length
      }
      this.cache.set(key, { audio, until: Date.now() + gpuIdleTime })
      this.cacheBytes += audio.length
    }
    return audio
  }

  async generate(text, signal, runtime) {
    signal.throwIfAborted()
    if (this.closed) throw unavailable()
    clearTimeout(this.idle)
    if (this.worker && this.workerRuntime !== runtime) this.stop()
    const worker = this.worker ?? this.start(runtime)
    try {
      return await new Promise((resolve, reject) => {
        let data = ''
        let settled = false
        const finish = (error, audio) => {
          if (settled) return
          settled = true
          clearTimeout(timeout)
          signal.removeEventListener('abort', failed)
          worker.off('close', failed).off('error', failed)
          worker.stdout.off('data', receive)
          if (error) reject(error)
          else resolve(audio)
        }
        const failed = () => {
          if (settled) return
          if (this.worker === worker) this.stop()
          finish(unavailable())
        }
        const receive = part => {
          data += part
          if (data.length > maxResponse) return failed()
          const end = data.indexOf('\n')
          if (end === -1) return
          try {
            const result = JSON.parse(data.slice(0, end))
            if (typeof result.audio !== 'string') return failed()
            const audio = Buffer.from(result.audio, 'base64')
            if (audio.length < 46 || audio.toString('ascii', 0, 4) !== 'RIFF' ||
                audio.toString('ascii', 8, 12) !== 'WAVE') return failed()
            finish(null, audio)
          } catch {
            failed()
          }
        }
        const timeout = setTimeout(failed, 80_000)
        signal.addEventListener('abort', failed, { once: true })
        worker.once('close', failed).once('error', failed)
        worker.stdout.on('data', receive)
        worker.stdin.write(JSON.stringify({ text }) + '\n', error => { if (error) failed() })
      })
    } finally {
      if (this.worker === worker) {
        this.idle = setTimeout(() => this.stop(), runtime === 'gpu' ? gpuIdleTime : 120_000).unref()
      }
    }
  }
}

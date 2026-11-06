import { execFile, spawn } from 'node:child_process'
import { access, mkdir, mkdtemp, rename, rm } from 'node:fs/promises'
import { createWriteStream } from 'node:fs'
import { isIPv4 } from 'node:net'
import { homedir } from 'node:os'
import { join } from 'node:path'
import { Readable, Transform } from 'node:stream'
import { pipeline } from 'node:stream/promises'
import { fileURLToPath } from 'node:url'
import { promisify } from 'node:util'
import { RequestError } from './sessions.js'

const exec = promisify(execFile)
const windows = process.platform === 'win32'
const canDownload = windows && process.arch === 'x64'
const adbName = windows ? 'adb.exe' : 'adb'
const bundledApk = fileURLToPath(new URL('../packaging/prebuilt/dsh-watch.apk', import.meta.url))
const platformToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
const packageIds = new Set(['org.dshwatch.app', 'org.dshwatch.app.preview'])

export function parseAdbDevices(output) {
  return output.split(/\r?\n/).flatMap(line => {
    const match = /^(.+?)\s+(device|offline|unauthorized)(?=\s+\w+:|\s*$)(.*)$/.exec(line)
    if (!match) return []
    const id = /\btransport_id:(\d+)\b/.exec(match[3])?.[1]
    if (!id) return []
    const model = /\bmodel:(\S+)/.exec(match[3])?.[1]?.replaceAll('_', ' ')
    return [{ id, serial: match[1].trim(), state: match[2], model: model || 'Android 设备' }]
  })
}

function addressOf(value) {
  const match = typeof value === 'string' && /^([\d.]+):(\d{1,5})$/.exec(value.trim())
  if (!match || !isIPv4(match[1]) || Number(match[2]) < 1 || Number(match[2]) > 65535) {
    throw new RequestError(400, '请填写手表显示的 IPv4 地址和端口，例如 192.168.1.8:37123')
  }
  return match[1] + ':' + Number(match[2])
}

async function call(executable, args, timeout = 15000, stdin) {
  if (stdin === undefined) {
    try {
      return (await exec(executable, args, { windowsHide: true, timeout, maxBuffer: 256 * 1024 })).stdout
    } catch (error) {
      if (error.killed) throw new RequestError(504, '操作超时，请确认手表亮屏、无线调试已打开，然后重试')
      const output = String(error.stderr || error.stdout || '')
      if (/INSTALL_FAILED_UPDATE_INCOMPATIBLE|signatures do not match/.test(output)) {
        throw new RequestError(409, '手表上的旧版签名不同，覆盖安装已停止，原有数据保留')
      }
      if (/INSTALL_FAILED_VERSION_DOWNGRADE/.test(output)) {
        throw new RequestError(409, '手表已经装了更新版本，请使用同版或新版插件')
      }
      if (/no devices|device .* not found|device offline|unauthorized|transport .* not found|disconnected/i.test(output)) {
        throw new RequestError(409, '手表已断开或还未授权，请刷新设备后重试')
      }
      throw error
    }
  }
  // The system pairing code goes over stdin and stays out of command-line arguments.
  return new Promise((resolve, reject) => {
    const child = spawn(executable, args, { windowsHide: true, timeout })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8').on('data', part => { stdout = (stdout + part).slice(-8192) })
    child.stderr.setEncoding('utf8').on('data', part => { stderr = (stderr + part).slice(-8192) })
    child.stdin.on('error', () => {})
    child.once('error', reject)
    child.once('close', code => resolve(code === 0 && /Successfully paired/i.test(stdout + stderr)))
    child.stdin.end(stdin + '\n')
  })
}

async function exists(path) {
  try { await access(path); return true } catch { return false }
}

export class WatchInstaller {
  constructor(directory, { adbPath, apkPath = bundledApk, packageId = 'org.dshwatch.app' } = {}) {
    if (!packageIds.has(packageId)) throw new Error('Unsupported DSH Watch install package')
    this.directory = directory
    this.tools = join(directory, 'platform-tools')
    this.adbPath = adbPath
    this.apkPath = apkPath
    this.packageId = packageId
  }

  async adb() {
    const candidates = [
      this.adbPath, join(this.tools, adbName), adbName,
      ...[process.env.ANDROID_HOME, process.env.ANDROID_SDK_ROOT,
        windows && process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
        process.platform === 'darwin' && join(homedir(), 'Library', 'Android', 'sdk')]
        .filter(Boolean).map(dir => join(dir, 'platform-tools', adbName)),
    ].filter(Boolean)
    for (const candidate of candidates) {
      try {
        if (/Android Debug Bridge version/.test(await call(candidate, ['version'], 4000))) return candidate
      } catch { /* Try the next SDK location. */ }
    }
  }

  async setup() {
    if (await this.adb()) return
    if (!canDownload) throw new RequestError(409, '请安装 Android SDK Platform-Tools，并让桌面 DSH 能找到 adb')
    await mkdir(this.directory, { recursive: true })
    const temporary = await mkdtemp(join(this.directory, 'adb-download-'))
    try {
      const zip = join(temporary, 'platform-tools.zip')
      const response = await fetch(platformToolsUrl, { redirect: 'error', signal: AbortSignal.timeout(120000) })
      if (!response.ok || !response.body) throw new Error('Platform Tools download failed')
      let size = 0
      const limit = new Transform({
        transform(chunk, _encoding, callback) {
          size += chunk.length
          if (size > 64 * 1024 * 1024) callback(new Error('Platform Tools archive too large'))
          else callback(null, chunk)
        },
      })
      await pipeline(Readable.fromWeb(response.body), limit, createWriteStream(zip))
      const extracted = join(temporary, 'platform-tools')
      await mkdir(extracted)
      const entries = ['adb.exe', 'AdbWinApi.dll', 'AdbWinUsbApi.dll'].map(name => 'platform-tools/' + name)
      const tar = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'tar.exe')
      await call(tar, ['-xf', zip, '-C', extracted, '--strip-components', '1', ...entries])
      if (!/Android Debug Bridge version/.test(await call(join(extracted, adbName), ['version']))) {
        throw new Error('Platform Tools verification failed')
      }
      await rename(extracted, this.tools)
    } catch {
      throw new RequestError(502, '未能安装调试工具，请检查网络后重试，或手动安装 Android SDK Platform-Tools')
    } finally {
      await rm(temporary, { recursive: true, force: true }).catch(() => {})
    }
  }

  async run(body, authorize) {
    if (this.busy) throw new RequestError(409, '安装向导正在操作，请稍后再试')
    this.busy = true
    try {
      if (body.action === 'setup') {
        if (body.accepted !== true) throw new RequestError(400, '请先阅读并同意 Android SDK 许可条款')
        await this.setup()
      }
      const adb = await this.adb()
      const apkReady = await exists(this.apkPath)
      const devices = async () => parseAdbDevices(await call(adb, ['devices', '-l']))
      const status = async () => ({ adbReady: Boolean(adb), canDownload, apkReady, devices: adb ? await devices() : [] })
      if (['status', 'setup'].includes(body.action)) return await status()
      if (!adb) throw new RequestError(409, '请先准备电脑上的调试工具')
      if (body.action === 'pair') {
        const address = addressOf(body.address)
        if (typeof body.code !== 'string' || !/^\d{6}$/.test(body.code)) {
          throw new RequestError(400, '请输入手表显示的六位无线调试配对码')
        }
        if (!await call(adb, ['pair', address], 20000, body.code)) {
          throw new RequestError(409, '无线调试配对失败，请保持配对码页面打开，并检查地址和六位码')
        }
        return { ...await status(), paired: true }
      }
      if (body.action === 'connect') {
        const address = addressOf(body.address)
        const result = await call(adb, ['connect', address], 12000)
        if (!/(?:already )?connected to /i.test(result)) {
          throw new RequestError(409, '连接失败，请输入无线调试主页面的 IP:端口，并确认电脑和手表在同一局域网')
        }
        return await status()
      }
      const device = (await devices()).find(item => item.id === body.id && item.serial === body.serial && item.state === 'device')
      if (!device) throw new RequestError(409, '找不到选中的手表，请刷新设备后重试')
      const deviceCall = (args, timeout) => call(adb, ['-t', device.id, ...args], timeout)
      if (body.action === 'install') {
        if (!apkReady) throw new RequestError(409, '此插件安装包缺少手表应用，请使用带 APK 的插件安装包')
        const features = await deviceCall(['shell', 'pm', 'list', 'features'])
        if (!/^feature:android\.hardware\.type\.watch\s*$/m.test(features)) {
          throw new RequestError(400, '选中的设备不是 Android 手表，请重新选择')
        }
        const result = await deviceCall(['install', '-r', this.apkPath], 120000)
        if (!/^Success\s*$/m.test(result)) throw new RequestError(502, '安装未完成，请检查手表并重试')
        return { installed: true }
      }
      const installed = await deviceCall(['shell', 'pm', 'path', this.packageId])
      if (!installed.includes('package:')) throw new RequestError(409, '请先安装 DSH Watch')
      const credentials = await authorize(body.address)
      const openWatch = wait => deviceCall(['shell', 'am', 'start', ...(wait ? ['-W'] : []), '-n',
        this.packageId + '/org.dshwatch.app.MainActivity'])
      const setupCall = ['shell', 'content', 'call', '--uri', 'content://' + this.packageId + '.setup', '--method']
      const payload = Buffer.from(JSON.stringify(credentials.value)).toString('base64url')
      let result
      try {
        // Some watch firmware restricts LAN access for a newly installed app in the background.
        await deviceCall([...setupCall, 'prepare'])
        await openWatch(true)
        result = await deviceCall([...setupCall, 'pair', '--arg', payload], 60000)
      } finally {
        await credentials.clear(/status=ok[},]/.test(result))
      }
      if (!/status=ok[},]/.test(result)) {
        if (/status=pin_changed/.test(result)) throw new RequestError(409, '电脑证书与手表保存的记录不同，请先在手表核对原连接')
        if (/status=unreachable/.test(result)) {
          throw new RequestError(409, '请保持手表亮屏再试。若仍无法连接，请检查电脑防火墙或换一个局域网地址')
        }
        throw new RequestError(502, '手表没有确认连接，请更新手表应用后重试')
      }
      try {
        const started = await openWatch(false)
        return { linked: true, opened: !/Error|Exception/i.test(started) }
      } catch {
        return { linked: true, opened: false }
      }
    } finally {
      this.busy = false
    }
  }
}

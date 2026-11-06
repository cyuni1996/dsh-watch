import { spawn } from 'node:child_process'
import { join } from 'node:path'
import { RequestError } from './sessions.js'

const maxAudioBytes = 12 * 1024 * 1024
const script = String.raw`
$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.Encoding]::UTF8
Add-Type -AssemblyName System.Speech
$text = [Console]::In.ReadToEnd()
$voice = New-Object System.Speech.Synthesis.SpeechSynthesizer
$audio = New-Object System.IO.MemoryStream
try {
    $language = if ($text -match '[\u3400-\u9fff]') { 'zh' } else { 'en' }
    $candidates = @($voice.GetInstalledVoices() | Where-Object {
        $_.Enabled -and $_.VoiceInfo.Culture.TwoLetterISOLanguageName -eq $language
    })
    if ($language -eq 'zh' -and !$candidates.Count) { exit 4 }
    $selected = $candidates | Where-Object { $_.VoiceInfo.Gender -eq 'Female' } | Select-Object -First 1
    if (!$selected) { $selected = $candidates | Select-Object -First 1 }
    if ($selected) { $voice.SelectVoice($selected.VoiceInfo.Name) }
    $voice.SetOutputToWaveStream($audio)
    $voice.Speak($text)
    $voice.SetOutputToNull()
    $bytes = $audio.ToArray()
    [Console]::OpenStandardOutput().Write($bytes, 0, $bytes.Length)
} finally {
    $voice.Dispose()
    $audio.Dispose()
}
`
const encoded = Buffer.from(script, 'utf16le').toString('base64')

export async function synthesizeReply(text, signal) {
  if (process.platform !== 'win32') throw new RequestError(503, '手表朗读目前需要 Windows 电脑')
  signal?.throwIfAborted()
  const powershell = join(process.env.SystemRoot || 'C:\\Windows', 'System32', 'WindowsPowerShell', 'v1.0', 'powershell.exe')
  const result = await new Promise((resolve, reject) => {
    const process = spawn(powershell, ['-NoProfile', '-NonInteractive', '-EncodedCommand', encoded], {
      windowsHide: true, timeout: 45000, signal, stdio: ['pipe', 'pipe', 'ignore'],
    })
    const chunks = []
    let size = 0
    process.stdout.on('data', chunk => {
      size += chunk.length
      if (size <= maxAudioBytes) chunks.push(chunk)
      else process.kill()
    })
    process.stdin.on('error', () => {})
    process.once('error', reject)
    process.once('close', code => resolve({ code, audio: Buffer.concat(chunks) }))
    process.stdin.end(text, 'utf8')
  }).catch(() => { throw new RequestError(503, '电脑语音合成暂不可用，请检查 Windows 语音设置') })
  if (result.code === 4) throw new RequestError(503, '请先在 Windows 语言设置中安装中文语音')
  const { audio } = result
  if (result.code !== 0 || audio.length < 46 || audio.toString('ascii', 0, 4) !== 'RIFF' ||
      audio.toString('ascii', 8, 12) !== 'WAVE') {
    throw new RequestError(502, '电脑未能生成朗读音频，请稍后再试')
  }
  return audio
}

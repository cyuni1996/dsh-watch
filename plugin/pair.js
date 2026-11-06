#!/usr/bin/env node
const port = Number(process.env.DSH_WATCH_ADMIN_PORT ?? 18766)
const command = process.argv[2] ?? 'pair'
const id = process.argv[3]
try {
  if (!['pair', 'devices', 'revoke'].includes(command) || (command === 'revoke' && !id)) {
    throw new Error('Usage: dsh-watch-pair [pair|devices|revoke <id>]')
  }
  const response = await fetch(`http://127.0.0.1:${port}/${command}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-DSH-Watch-CLI': '1' },
    body: JSON.stringify({ id }),
    signal: AbortSignal.timeout(5000),
  })
  const result = await response.json()
  if (!response.ok) throw new Error(result.error)
  if (command === 'pair') {
    console.log(`10 位配对码（5 分钟内使用一次） ${result.code.slice(0, 5)} ${result.code.slice(5)}`)
    console.log(result.addresses.join('\n'))
  } else console.log(JSON.stringify(result, null, 2))
} catch (error) {
  console.error(`DSH Watch: ${error.message}。请检查插件是否正在运行。`)
  process.exitCode = 1
}

import assert from 'node:assert/strict'
import test from 'node:test'
import { respondSnapshot } from './index.js'

test('unchanged state sends a validator and no body, and new activity sends a fresh snapshot', () => {
  function reply(known, state) {
    const response = {
      writeHead(status, headers) { Object.assign(this, { status, headers }); return this },
      end(body) { this.body = body },
    }
    respondSnapshot(response, known, state)
    return response
  }

  const idle = { id: 'watch-test', busy: false, messages: [{ text: 'Earlier message' }] }
  const initial = reply(undefined, idle)
  assert.equal(initial.status, 200)
  assert.deepEqual(JSON.parse(initial.body), idle)
  const unchanged = reply(initial.headers.ETag, idle)
  assert.equal(unchanged.status, 304)
  assert.equal(unchanged.headers.ETag, initial.headers.ETag)
  assert.equal(unchanged.body, undefined)
  const active = reply(initial.headers.ETag, { ...idle, busy: true })
  assert.equal(active.status, 200)
  assert.notEqual(active.headers.ETag, initial.headers.ETag)
  assert.equal(JSON.parse(active.body).busy, true)
})

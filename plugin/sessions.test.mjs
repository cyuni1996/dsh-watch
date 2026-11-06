import assert from 'node:assert/strict'
import test from 'node:test'
import { WatchSessions } from './sessions.js'

test('watch history keeps user text and skips synthetic DSH context events', async () => {
  const timeLine = 'Time sampled while preparing turn'
  const records = [
    { event: { type: 'user/message', seq: 1, data: { source: { kind: 'user' }, content: [{ type: 'text', text: timeLine }] } } },
    { event: { type: 'user/message', seq: 2, data: { source: { kind: 'time-context' }, content: [{ type: 'text', text: timeLine }] } } },
    { event: { type: 'user/message', seq: 3, data: { source: { kind: 'runtime-context' }, content: [{ type: 'text', text: 'runtime' }] } } },
    { event: { type: 'user/message', seq: 4, data: { source: { kind: 'skill-catalog' }, content: [{ type: 'text', text: 'skills' }] } } },
    { event: { type: 'assistant/message', seq: 5, data: { message: { content: [{ type: 'text', text: 'WATCH-OK' }] } } } },
  ]
  const sessions = new WatchSessions({
    sessionController: {
      state: async () => ({ running: false }),
      events: async () => ({ records, hasMore: false }),
      projections: async () => ({ values: {} }),
    },
    workspaceRegistry: { list: () => [], archivedSessionIds: [] },
  }, {})

  const state = await sessions.route(new URL('https://test.invalid/api/session/link'), { threadId: 'test' }, 'device')
  assert.deepEqual(state.messages, [
    { id: '1', role: 'user', text: timeLine },
    { id: '5', role: 'assistant', text: 'WATCH-OK' },
  ])
  assert.equal(await sessions.replyForSpeech({ sessionId: state.id, messageId: '5', text: 'unused' }, 'device'), 'WATCH-OK')
  await assert.rejects(sessions.replyForSpeech({ sessionId: state.id, messageId: '1' }, 'device'), { status: 404 })
})

import { createHash, randomUUID } from 'node:crypto'

export class RequestError extends Error {
  constructor(status, message) {
    super(message)
    this.status = status
  }
}

function string(value, maximum, label) {
  if (typeof value !== 'string' || value.length > maximum || !value.trim()) {
    throw new RequestError(400, `${label}无效`)
  }
  return value
}

const encodeModel = ({ provider, model }) => Buffer.from(JSON.stringify([provider, model])).toString('base64url')
const status = (running, waiting = false) => waiting ? { status: 'waiting', statusLabel: '待确认' }
  : running ? { status: 'running', statusLabel: '回复中' } : { status: 'idle', statusLabel: '空闲' }
const address = sessionId => ({ kind: 'session', sessionId })
const selectionOf = projections => projections?.modelSelection ?? projections?.model ?? null
const messageText = message => (message?.content ?? []).filter(block => block.type === 'text')
  .map(block => block.text).join('\n')

const internalMessageSources = new Set(['runtime-context', 'skill-catalog', 'time-context'])

function messagesFrom(records) {
  const messages = []
  for (const { event } of records) {
    if (!['user/message', 'assistant/message'].includes(event.type)) continue
    if (event.type === 'user/message' && internalMessageSources.has(event.data.source?.kind)) continue
    const role = event.type === 'user/message' ? 'user' : 'assistant'
    const text = messageText(role === 'user' ? event.data : event.data.message)
    if (event.surfaceOp && event.surfaceOp !== 'append' && event.surfaceOp.op === 'replace') {
      const { startSeq, endSeq } = event.surfaceOp
      const first = messages.findIndex(message => message.seq >= startSeq && message.seq <= endSeq)
      if (first >= 0) {
        const after = messages.findIndex((message, index) => index > first && message.seq > endSeq)
        messages.splice(first, (after < 0 ? messages.length : after) - first,
          ...(text ? [{ id: String(event.seq), seq: startSeq, role, text }] : []))
        continue
      }
    }
    if (text) messages.push({ id: String(event.seq), seq: event.seq, role, text })
  }
  return messages.map(({ seq, ...message }) => message)
}

/** The Android client keeps a per-device view of the native DSH Session journal. */
export class WatchSessions {
  constructor(ctx, config, approvals) {
    this.ctx = ctx
    this.session = ctx.sessionController
    this.workspace = ctx.workspaceController
    this.registry = ctx.workspaceRegistry
    this.config = config
    this.approvals = approvals
    this.views = new Map()
  }

  view(id, device) {
    if (id !== null && id !== undefined && id !== '') string(id, 180, '会话编号')
    const key = id ? `${device}:${id}` : null
    if (key && this.views.has(key)) {
      const view = this.views.get(key)
      this.views.delete(key)
      this.views.set(key, view)
      return view
    }
    // Draft IDs can survive a server restart. Derive the DSH ID from the draft
    // so a lost send receipt can be recovered from the native request journal.
    id ||= 'watch-' + randomUUID()
    const draft = /^watch-[0-9a-f-]{36}$/.test(id)
    const view = { id, threadId: draft ? null : id, draft, projectId: null, selection: null, count: 30, tail: Promise.resolve() }
    if (this.views.size >= 256) {
      const idle = [...this.views].find(([, candidate]) => !candidate.mutations)
      if (!idle) throw new RequestError(429, '会话操作过多，请稍后重试')
      this.views.delete(idle[0])
    }
    this.views.set(`${device}:${id}`, view)
    return view
  }

  change(view, task) {
    view.mutations = (view.mutations ?? 0) + 1
    const result = view.tail.then(task)
    view.tail = result.catch(() => {}).finally(() => { view.mutations-- })
    return result
  }

  projects() {
    return this.registry.list().map(workspace => ({
      id: workspace.id, name: workspace.name ?? workspace.label ?? workspace.path.split(/[\\/]/).filter(Boolean).at(-1) ?? workspace.path,
      sessionIds: workspace.sessionIds,
    }))
  }

  projectOf(id) {
    return this.projects().find(project => project.sessionIds.includes(id))
  }

  async restoreDraft(view, signal) {
    if (view.threadId || !view.draft) return
    const { items } = await this.session.list({}, signal)
    if (items.some(row => row.sessionId === view.id)) view.threadId = view.id
  }

  async snapshot(view, signal, restore = true) {
    if (restore) await this.restoreDraft(view, signal)
    let settings = view.selection
    let title = null
    let messages = []
    let busy = false
    let waitingOnApproval = false
    let approvals = []
    let hasOlder = false
    let turnId = null
    let error = ''
    let project = view.projectId ? this.projects().find(project => project.id === view.projectId) : null
    const threadId = view.threadId
    if (threadId) {
      const [state, history, projections] = await Promise.all([
        this.session.state({ sessionId: threadId }, signal),
        this.session.events({ address: address(threadId), maxMessages: view.count }, signal),
        this.session.projections({ sessionId: threadId }, signal),
      ])
      messages = messagesFrom(history.records)
      hasOlder = history.hasMore && view.count < 300
      const values = projections?.values ?? {}
      title = typeof values.title === 'string' ? values.title : values.title?.title
      settings ??= selectionOf(values)
      busy = state.running || (state.queue?.length ?? 0) > 0
      const start = history.records.findLast(({ event }) => event.type === 'turn/start')?.event
      const end = history.records.findLast(({ event }) => event.type === 'turn/end')?.event
      if (state.running && start && (!end || end.seq < start.seq)) turnId = String(start.seq)
      waitingOnApproval = state.pendingInput?.length > 0
      approvals = this.approvals?.list(threadId) ?? []
      if (waitingOnApproval && !approvals.length) error = 'DSH 有待确认操作，请在电脑处理。'
      project = this.projectOf(threadId)
    }
    return {
      id: view.id, threadId, connected: true, title: title || null, messages, busy, waitingOnApproval, approvals, error,
      turnId, canInterrupt: !!turnId, hasOlder, archived: this.registry.archivedSessionIds.includes(threadId),
      projectId: project?.id ?? null, projectName: project?.name ?? null,
      settings: { model: settings?.provider && settings?.model ? encodeModel(settings) : '', thinking: settings?.reasoningEffort ?? '' },
    }
  }

  async usage(signal) {
    const account = this.ctx.get?.('accountController')
    if (typeof account?.getBalance !== 'function') {
      return { error: '电脑上的 DSH 暂未提供账号余量' }
    }
    const balance = await account.getBalance({
      version: '0.4.3', locale: 'zh-CN', timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60,
    })
    signal?.throwIfAborted()
    if (balance == null) return { error: '请先在电脑 DSH 登录 DeepSeek 账号' }
    if (balance.status !== 'ready') return { error: '暂时读不到余量，请稍后刷新' }
    const wallets = [
      ...balance.value.map(wallet => ({ kind: 'recharge', ...wallet })),
      ...balance.bonusWallets.map(wallet => ({ kind: 'bonus', ...wallet })),
    ].map(({ kind, currency, balance: amount }) => ({ kind, currency, amount }))
    return { wallets, updatedAt: new Date().toISOString() }
  }

  async replyForSpeech(body, device, signal) {
    const messageId = string(body.messageId, 32, '消息编号')
    const view = this.view(string(body.sessionId, 180, '会话编号'), device)
    await this.restoreDraft(view, signal)
    if (!view.threadId) throw new RequestError(404, '找不到需要朗读的会话')
    const { records } = await this.session.events({ address: address(view.threadId), maxMessages: view.count }, signal)
    const reply = messagesFrom(records).find(message => message.id === messageId && message.role === 'assistant')
    if (!reply?.text.trim()) throw new RequestError(404, '找不到需要朗读的回复')
    // Limit synthesis time and avoid reading fenced code or raw link destinations aloud.
    const text = reply.text.replace(/```[^]*?(?:```|$)/g, ' 代码见文字。 ')
      .replace(/\[([^\]]+)\]\(https?:\/\/[^\s)]+\)/g, '$1').trim()
    return Array.from(text).slice(0, 1600).join('')
  }

  async modelCatalog() {
    const catalog = await this.session.modelCatalog()
    const models = catalog.groups.flatMap(group => group.models.map(model => ({
      id: encodeModel({ provider: group.id, model: model.id }), name: `${model.name} · ${group.name}`,
      provider: group.id, model: model.id, efforts: (model.reasoning?.efforts ?? []).map(effort => effort.id),
    })))
    return { default: catalog.default, models }
  }

  async rows(url, signal) {
    const [{ items }, projects] = await Promise.all([this.session.list({}, signal), this.projects()])
    const archived = new Set(this.registry.archivedSessionIds)
    const pinned = new Set(this.registry.pinnedSessionIds)
    const query = url?.searchParams.get('query')?.trim().toLowerCase()
    let matching = new Set()
    if (query && typeof this.session.search === 'function') {
      const result = await this.session.search({ query }, signal)
      matching = new Set(result.items.map(item => item.sessionId))
    }
    const projectBySession = new Map(projects.flatMap(project => project.sessionIds.map(id => [id, project])))
    const conversations = items.filter(row => !row.parentSessionId && !row.blank).map(row => {
      const id = row.sessionId
      const rawTitle = row.projections?.values?.title
      const project = projectBySession.get(id)
      return {
        threadId: id, title: (typeof rawTitle === 'string' ? rawTitle : rawTitle?.title) || 'DSH 对话',
        archived: archived.has(id), pinned: pinned.has(id), projectId: project?.id ?? null, projectName: project?.name ?? null,
        ...status(row.running, this.approvals?.list(id).length > 0),
      }
    }).filter(row => !url || (row.archived === (url.searchParams.get('tab') === 'archived')
      && (!query || row.title.toLowerCase().includes(query) || matching.has(row.threadId))))
    return { conversations, projects: projects.map(({ id, name }) => ({ id, name })) }
  }

  async route(url, body, device, signal) {
    const path = url.pathname
    if (body === null) {
      switch (path) {
        case '/api/state': return this.snapshot(this.view(url.searchParams.get('sessionId'), device), signal)
        case '/api/models': return { models: (await this.modelCatalog()).models }
        case '/api/projects': return { projects: this.projects().map(({ id, name }) => ({ id, name })) }
        case '/api/usage': return this.usage(signal)
        case '/api/conversations': {
          const { conversations, projects } = await this.rows(url, signal)
          const limit = 40
          const cursor = url.searchParams.get('cursor')
          const anchor = url.searchParams.get('currentSessionId')
          const start = cursor ? conversations.findIndex(row => row.threadId === cursor) + 1 : 0
          const end = Math.max(start + limit, anchor && !cursor ? conversations.findIndex(row => row.threadId === anchor) + 1 : 0)
          const page = conversations.slice(start, end)
          return { conversations: page, projects, nextCursor: end < conversations.length ? page.at(-1).threadId : null }
        }
        default: throw new RequestError(404, '未找到请求')
      }
    }
    if (path === '/api/session/link') {
      const id = string(body.threadId, 180, '会话编号')
      const view = this.view(id, device)
      view.threadId = id
      return this.snapshot(view, signal)
    }
    if (path === '/api/conversation/open') {
      const id = string(body.threadId, 180, '会话编号')
      const { conversations } = await this.rows(null, signal)
      const row = conversations.find(item => item.threadId === id)
      if (!row) throw new RequestError(404, 'DSH 会话不存在')
      return row
    }
    if (path === '/api/conversations/status') {
      const { conversations } = await this.rows(null, signal)
      const ids = new Set(Array.isArray(body.threadIds) ? body.threadIds.slice(0, 200) : [])
      return { states: Object.fromEntries(conversations.filter(row => ids.has(row.threadId)).map(row => [row.threadId, row])) }
    }
    if (path === '/api/conversation/action') {
      const sessionId = string(body.threadId, 180, '会话编号')
      const methods = { pin: 'pinSession', unpin: 'unpinSession', archive: 'archiveSession', restore: 'unarchiveSession' }
      if (body.action === 'rename') await this.session.rename({ sessionId, title: string(body.title, 120, '标题').trim() })
      else if (Object.hasOwn(methods, body.action)) await this.workspace[methods[body.action]]({ sessionId })
      else throw new RequestError(400, '不支持的会话操作')
      return { ok: true }
    }
    if (path === '/api/session/new') {
      const view = this.view(null, device)
      view.projectId = this.projectId(body.projectId)
      return { state: await this.snapshot(view, signal, false) }
    }
    const view = this.view(string(body.sessionId, 180, '会话编号'), device)
    return this.change(view, async () => {
      signal.throwIfAborted()
      await this.restoreDraft(view, signal)
      switch (path) {
        case '/api/history/older':
          view.count = Math.min(view.count + 30, 300)
          return this.snapshot(view, signal, false)
        case '/api/session/project':
          if (view.threadId) throw new RequestError(409, '请在 DSH 中移动已有会话')
          view.projectId = this.projectId(body.projectId)
          return this.snapshot(view, signal, false)
        case '/api/session/settings': {
          const { default: fallback, models } = await this.modelCatalog()
          const picked = body.model ? models.find(model => model.id === body.model) : fallback
          if (!picked?.provider || !picked.model || (body.thinking && !picked.efforts?.includes(body.thinking))) {
            throw new RequestError(400, '模型或思考强度不可用')
          }
          view.selection = { provider: picked.provider, model: picked.model,
            ...body.thinking ? { reasoningEffort: body.thinking } : {} }
          return this.snapshot(view, signal, false)
        }
        case '/api/message': {
          const text = string(body.text, 8000, '消息').trim()
          const requestId = string(body.requestId, 100, '请求编号')
          if (!view.threadId) {
            const location = view.projectId ? { workspaceId: view.projectId } : this.config.cwd ? { cwd: this.config.cwd } : {}
            const created = await this.session.create({ sessionId: view.id, ...location })
            view.threadId = created.sessionId
          }
          const sessionId = view.threadId
          if (this.registry.archivedSessionIds.includes(sessionId)) throw new RequestError(409, '请先恢复已归档的会话')
          if (view.selection) {
            await this.session.selectModel({ sessionId, ...view.selection })
            view.selection = null
          }
          const timeZone = typeof body.timeZone === 'string' ? body.timeZone : undefined
          await this.session.prompt({ sessionId, requestId, mode: 'queue', content: [{ type: 'text', text }],
            clientTimeZone: timeZone }, signal)
          return this.snapshot(view, signal, false)
        }
        case '/api/interrupt': {
          const current = await this.snapshot(view, signal, false)
          const alreadyFinished = !current.canInterrupt || current.turnId !== body.turnId
          if (!alreadyFinished) this.session.cancel({ sessionId: view.threadId })
          return { state: await this.snapshot(view, signal, false), result: { alreadyFinished } }
        }
        case '/api/approval':
          if (!this.approvals || !view.threadId) throw new RequestError(409, '请在电脑端确认')
          await this.approvals.answer(view.threadId, body)
          return { state: await this.snapshot(view, signal, false) }
        default: throw new RequestError(404, '未找到请求')
      }
    })
  }

  projectId(value) {
    if (value == null) return null
    if (typeof value !== 'string' || !this.registry.get(value)) throw new RequestError(404, 'DSH 项目不存在')
    return value
  }
}

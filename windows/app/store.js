// All app state, like the phone's Store: chats, messages, live events from the socket.
// Screens subscribe with Store.on(fn) and redraw when something changed.

function preview(m) {
  if (!m) return ''
  if (m.card) return cardLabel(m.card.type)
  if (m.photo) return m.text ? '📷 ' + m.text : '📷 Фото'
  if (m.media_kind === 'voice') return '🎤 Голосовое'
  if (m.media_kind === 'circle') return '⏺ Кружок'
  if (m.media_kind === 'file') return '📎 ' + (m.text || m.media_name || 'Файл')
  if (m.spot) return '📍 ' + (m.spot.title || 'Спот')
  return m.text || ''
}

function cardLabel(t) {
  return { poll: '📊 Опрос', gather: '📅 Сбор', split: '💸 Скидываемся', watch: '🎬 Смотрим вместе', call: '📞 Звонок' }[t] || 'Карточка'
}

const handleOf = (u) => (u.username === 'jt' ? '@JT' : '@' + u.username)
const isOwner = (u) => !!u && u.username === 'jt'

const Store = {
  me: null,
  chats: [],
  /** chat id -> messages, oldest first */
  messages: {},
  historyDone: new Set(),
  openChat: null,
  connected: false,
  /** chat id -> {user id -> until ms} */
  typing: {},
  /** user id -> [online, last_seen] */
  presence: {},
  spots: [],
  people: [],
  watchCards: {},
  tab: 'chats',
  spotFocus: null,
  onWatch: {},
  socket: new Socket(),
  lastTyping: {},
  listeners: [],
  pending: false,

  get meId() { return this.me ? this.me.id : 0 },
  get showOnline() { return localStorage.getItem('show_online') !== '0' },
  set showOnline(v) { localStorage.setItem('show_online', v ? '1' : '0'); this.sendPresence() },
  // a test server (JT_SERVER) keeps its own login
  get tokenKey() { return SERVER.includes('tuna.free') ? 'token' : 'token@' + SERVER },

  on(fn) { this.listeners.push(fn) },

  /** redraw once per frame however many events came */
  changed() {
    if (this.pending) return
    this.pending = true
    requestAnimationFrame(() => {
      this.pending = false
      for (const fn of this.listeners) fn()
    })
  },

  toast(text) { UI.toast(text) },

  init() {
    Api.token = localStorage.getItem(this.tokenKey) || ''
    this.socket.onEvent = (ev) => this.handle(ev)
    this.socket.onState = (up) => {
      this.connected = up
      this.changed()
      if (up) {
        this.sendPresence()
        this.refresh()
      } else {
        this.checkToken()
      }
    }
    this.socket.onBadToken = () => this.logout()
    const activeChanged = () => {
      this.sendPresence()
      if (this.active && this.openChat) {
        this.markRead(this.openChat)
      }
    }
    window.addEventListener('focus', activeChanged)
    window.addEventListener('blur', activeChanged)
    document.addEventListener('visibilitychange', activeChanged)
    if (Api.token) this.start()
  },

  /** window on screen and focused */
  get active() { return document.visibilityState === 'visible' && document.hasFocus() },

  // ---------- account ----------

  async start() {
    try {
      this.me = await Api.call('GET', '/me')
      this.changed()
      this.socket.start()
      await this.refresh()
      if (Notification.permission === 'default') Notification.requestPermission()
    } catch (e) {
      if (e.message === 'bad token') { this.logout(); return }
      // offline: keep trying until the server answers
      this.toast('Нет связи с сервером')
      setTimeout(() => { if (Api.token && !this.me) this.start() }, 5000)
    }
  },

  async checkToken() {
    try { await Api.call('GET', '/me') } catch (e) { if (e.message === 'bad token') this.logout() }
  },

  async login(username, password) {
    this.signedIn(await Api.call('POST', '/login', { body: { username, password } }))
  },

  async register(invite, name, username) {
    let rid = localStorage.getItem('reg_id')
    if (!rid) { rid = uuid(); localStorage.setItem('reg_id', rid) }
    this.signedIn(await Api.call('POST', '/register', { body: { invite, name, username, request_id: rid } }))
  },

  signedIn(r) {
    if (!r.token) throw new ApiError('Странный ответ сервера: ' + JSON.stringify(r).slice(0, 80))
    Api.token = r.token
    localStorage.setItem(this.tokenKey, r.token)
    return this.start()
  },

  async setPassword(password, old = '') {
    this.me = await Api.call('POST', '/me/password', { body: { password, old } })
    this.changed()
  },

  logout() {
    this.socket.stop()
    Api.token = ''
    localStorage.removeItem(this.tokenKey)
    this.me = null
    this.chats = []
    this.messages = {}
    this.openChat = null
    this.connected = false
    this.updateBadge()
    this.changed()
  },

  // ---------- chats and history ----------

  async refresh() {
    let list
    try { list = await Api.call('GET', '/chats') } catch (e) { return }
    this.chats = list
    for (const c of list) for (const u of c.members || []) this.presence[u.id] = [!!u.online, u.last_seen || 0]
    try { this.people = await Api.call('GET', '/users') } catch (e) {}
    if (this.openChat) await this.loadHistory(this.openChat, true)
    this.updateBadge()
    this.changed()
  },

  chat(id) { return this.chats.find((c) => c.id === id) },

  isGroup(c) { return c && c.kind === 'group' },

  other(c) { return (c.members || []).find((u) => u.id !== this.meId) || (c.members || [])[0] },

  user(id) {
    if (id === this.meId) return this.me
    for (const c of this.chats) {
      const u = (c.members || []).find((x) => x.id === id)
      if (u) return u
    }
    return this.people.find((u) => u.id === id)
  },

  name(id) { const u = this.user(id); return u ? u.name : 'Кент' },

  open(id) {
    this.openChat = id
    if (id) {
      this.tab = 'chats'
      if (!this.messages[id]) this.loadHistory(id, true)
      else this.markRead(id)
      UI.clearNotifications(id)
    }
    this.changed()
  },

  async loadHistory(id, reset = false) {
    const have = this.messages[id] || []
    const first = have.find((m) => m.id > 0)
    const before = reset ? 0 : (first ? first.id : 0)
    let got
    try { got = await Api.call('GET', `/chats/${id}/messages`, { query: before > 0 ? { before } : {} }) } catch (e) { return }
    if (reset) {
      // keep my messages still on the way
      this.messages[id] = got.concat((this.messages[id] || []).filter((m) => m.id < 0))
      this.historyDone.delete(id)
    } else {
      this.messages[id] = got.concat(this.messages[id] || [])
    }
    if (got.length < 100) this.historyDone.add(id)
    this.markRead(id)
    this.changed()
  },

  markRead(id) {
    if (!this.active || this.openChat !== id) return
    const c = this.chat(id)
    const list = this.messages[id] || []
    let last = null
    for (let i = list.length - 1; i >= 0; i--) if (list[i].id > 0) { last = list[i]; break }
    if (!c || !last) return
    if (c.unread > 0 || last.sender_id !== this.meId) this.socket.send({ type: 'read', chat_id: id, message_id: last.id })
    c.unread = 0
    this.updateBadge()
    this.changed()
  },

  async openDm(userId) {
    let c
    try { c = await Api.call('POST', '/chats/dm', { body: { user_id: userId } }) } catch (e) { this.toast(e.message); return }
    if (!this.chat(c.id)) this.chats.unshift(c)
    this.open(c.id)
  },

  async search(q) {
    try { return await Api.call('GET', '/users/search', { query: { q } }) } catch (e) { return [] }
  },

  async settings(id, body) {
    try {
      const c = await Api.call('POST', `/chats/${id}/settings`, { body })
      const i = this.chats.findIndex((x) => x.id === id)
      if (i >= 0) this.chats[i] = c
    } catch (e) { this.toast(e.message) }
    this.updateBadge()
    this.changed()
  },

  setMuted(id, muted) { return this.settings(id, { muted }) },

  async setPinned(id, pinned) {
    await this.settings(id, { pinned })
    await this.refresh()
  },

  // ---------- sending ----------

  send(chatId, text, replyTo = 0) {
    text = text.trim()
    if (!text) return
    const cid = uuid()
    const m = { id: -Math.floor(Math.random() * 1e9) - 1, chat_id: chatId, sender_id: this.meId, text, created: Math.floor(Date.now() / 1000), client_id: cid, reply_to: replyTo }
    if (replyTo > 0) {
      const q = (this.messages[chatId] || []).find((x) => x.id === replyTo)
      if (q) m.reply = { id: q.id, sender_id: q.sender_id, text: preview(q), deleted: false }
    }
    ;(this.messages[chatId] = this.messages[chatId] || []).push(m)
    this.socket.send({ type: 'send', chat_id: chatId, text, client_id: cid, reply_to: replyTo })
    this.changed()
  },

  edit(m, text) { this.socket.send({ type: 'edit', chat_id: m.chat_id, message_id: m.id, text }) },

  delete(m) { this.socket.send({ type: 'delete', chat_id: m.chat_id, message_id: m.id }) },

  react(m, emoji) {
    const r = m.reactions || {}
    const mine = Object.keys(r).find((e) => r[e].includes(this.meId))
    this.socket.send({ type: 'react', chat_id: m.chat_id, message_id: m.id, emoji: mine === emoji ? '' : emoji })
  },

  typingNow(chatId) {
    const t = this.lastTyping[chatId]
    if (t && Date.now() - t < 3000) return
    this.lastTyping[chatId] = Date.now()
    this.socket.send({ type: 'typing', chat_id: chatId })
  },

  sendRaw(ev) { this.socket.send(ev) },

  sendPresence() { this.socket.send({ type: 'presence', active: this.active, show: this.showOnline }) },

  async sendPhoto(chatId, blob, caption = '') {
    let j
    try { j = await toJpeg(blob) } catch (e) { this.toast('Не получилось прочитать картинку'); return }
    try {
      await Api.call('POST', `/chats/${chatId}/photo`, { query: { w: j.w, h: j.h, caption, client_id: uuid() }, body: j.blob, timeout: 120000 })
    } catch (e) { this.toast(e.message) }
  },

  async sendFile(chatId, file) {
    const ext = (file.name.split('.').pop() || '').toLowerCase()
    if (file.name.includes('.') && ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp'].includes(ext)) {
      return this.sendPhoto(chatId, file)
    }
    if (file.size > 100 * 1024 * 1024) { this.toast('Файл больше 100 МБ'); return }
    this.toast('Загружаю ' + file.name + '…')
    try {
      const name = await this.upload(file, file.name.includes('.') ? ext : 'bin')
      await Api.call('POST', `/chats/${chatId}/media`, { body: { kind: 'file', media: name, name: file.name, client_id: uuid() } })
      UI.toast(null)
    } catch (e) { this.toast(e.message) }
  },

  /** Raw body to /upload; returns the server file name. */
  async upload(blob, ext, big = false) {
    const query = { ext }
    if (big) query.big = '1'
    const j = await Api.call('POST', '/upload', { query, body: blob, timeout: 1200000 })
    if (!j.name) throw new ApiError('Файл не загрузился')
    return j.name
  },

  // ---------- live events ----------

  handle(ev) {
    const chatId = ev.chat_id || 0
    switch (ev.type) {
      case 'message': {
        const m = ev.message
        if (!m) return
        const list = this.messages[m.chat_id]
        if (list) {
          const i = ev.client_id ? list.findIndex((x) => x.client_id && x.client_id === ev.client_id) : -1
          if (i >= 0) list[i] = m
          else if (!list.some((x) => x.id === m.id)) list.push(m)
        }
        const i = this.chats.findIndex((c) => c.id === m.chat_id)
        if (i >= 0) {
          const c = this.chats[i]
          c.last = m
          if (m.sender_id !== this.meId && m.sender_id !== 0) c.unread = (c.unread || 0) + 1
          // newest chat goes up, below the pinned ones
          this.chats.splice(i, 1)
          let at = this.chats.findIndex((x) => !x.pinned)
          if (c.pinned || at < 0) at = c.pinned ? 0 : this.chats.length
          this.chats.splice(at, 0, c)
        } else {
          this.refresh()
        }
        if (this.typing[m.chat_id]) delete this.typing[m.chat_id][m.sender_id]
        if (m.sender_id !== this.meId && m.sender_id !== 0) this.notify(m)
        this.markRead(m.chat_id)
        this.updateBadge()
        break
      }
      case 'edit':
        if (ev.message) this.replace(ev.message.chat_id, ev.message.id, () => ev.message)
        break
      case 'delete': {
        const list = this.messages[chatId]
        if (list) this.messages[chatId] = list.filter((m) => m.id !== ev.message_id)
        this.refresh()
        break
      }
      case 'reactions':
        this.replace(chatId, ev.message_id, (m) => ({ ...m, reactions: ev.reactions || {} }))
        break
      case 'card':
        if (!ev.card) return
        this.replace(chatId, ev.message_id, (m) => ({ ...m, card: ev.card }))
        if (ev.card.type === 'watch') this.watchCards[ev.card.id] = ev.card
        break
      case 'read': {
        const c = this.chat(chatId)
        if (!c) return
        if (ev.user_id !== this.meId) {
          // double tick = read by everyone; one reader in a group isn't enough, so ask the server
          if (this.isGroup(c)) this.refresh()
          else c.others_read = Math.max(c.others_read || 0, ev.message_id || 0)
        } else {
          c.unread = 0
          this.updateBadge()
        }
        break
      }
      case 'typing': {
        const t = (this.typing[chatId] = this.typing[chatId] || {})
        t[ev.user_id] = Date.now() + 5000
        setTimeout(() => this.changed(), 5100)
        break
      }
      case 'presence':
        this.presence[ev.user_id] = [!!ev.online, ev.last_seen || 0]
        break
      case 'blocked': {
        const list = this.messages[chatId]
        if (list) this.messages[chatId] = list.filter((m) => !(m.client_id && m.client_id === ev.client_id))
        this.toast(ev.reason || 'Не отправлено')
        break
      }
      case 'chat':
        this.refresh()
        break
      case 'spots':
        this.loadSpots()
        break
      case 'watch': {
        const fn = this.onWatch[ev.watch_id]
        if (fn) fn(ev)
        break
      }
      default:
        if (String(ev.type).startsWith('call_')) Calls.handle(ev)
    }
    this.changed()
  },

  replace(chatId, id, fn) {
    const list = this.messages[chatId]
    if (list) {
      const i = list.findIndex((m) => m.id === id)
      if (i >= 0) list[i] = fn(list[i])
    }
    const c = this.chat(chatId)
    if (c && c.last && c.last.id === id) c.last = fn(c.last)
  },

  typingLine(chatId) {
    const t = this.typing[chatId] || {}
    const ids = Object.keys(t).map(Number).filter((id) => t[id] > Date.now() && id !== this.meId)
    if (!ids.length) return null
    if (!this.isGroup(this.chat(chatId))) return 'печатает…'
    return ids.length === 1 ? this.name(ids[0]) + ' печатает…' : ids.length + ' печатают…'
  },

  isOnline(userId) { return !!(this.presence[userId] || [])[0] },

  presenceLine(userId) {
    const [on, seen] = this.presence[userId] || [false, 0]
    if (on) return 'в сети'
    if (!seen) return ''
    const d = dateOf(seen)
    if (isToday(d)) return 'был(а) в ' + clock(seen)
    if (isYesterday(d)) return 'был(а) вчера в ' + clock(seen)
    return 'был(а) ' + d.getDate() + ' ' + MONTHS_SHORT[d.getMonth()]
  },

  // ---------- notifications and taskbar ----------

  unreadTotal() { return this.chats.filter((c) => !c.muted).reduce((n, c) => n + (c.unread || 0), 0) },

  updateBadge() {
    const n = this.unreadTotal()
    document.title = n > 0 ? `JustTalk (${n})` : 'JustTalk'
    if (!window.native) return
    let url = null
    if (n > 0) {
      const c = document.createElement('canvas')
      c.width = c.height = 32
      const x = c.getContext('2d')
      x.fillStyle = '#ff3b30'
      x.beginPath()
      x.arc(16, 16, 16, 0, Math.PI * 2)
      x.fill()
      x.fillStyle = '#fff'
      x.font = `bold ${n > 9 ? 16 : 20}px Segoe UI, sans-serif`
      x.textAlign = 'center'
      x.textBaseline = 'middle'
      x.fillText(n > 99 ? '99+' : String(n), 16, 17)
      url = c.toDataURL()
    }
    window.native.badge(n, url)
  },

  notify(m) {
    const c = this.chat(m.chat_id)
    if (!c || c.muted) return
    if (this.active && this.openChat === m.chat_id) return
    const author = this.name(m.sender_id)
    UI.notify(this.isGroup(c) ? c.title : author, this.isGroup(c) ? author + ': ' + preview(m) : preview(m), m.chat_id)
  },
}

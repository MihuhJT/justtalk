// Small helpers shared by every screen: element builder, icons, dates, server calls, the live socket.
const SERVER = window.native ? window.native.server : 'http://127.0.0.1:8000'
const APP_VERSION = 'Win 0.15.1'
const REACTIONS = ['👍', '❤️', '😂', '🔥', '😮', '😢']

/** h('div.a.b', {onclick, style, ...}, children...) */
function h(tag, attrs, ...kids) {
  const [head, ...cls] = tag.split('.')
  const [name, id] = head.split('#')
  const el = document.createElement(name || 'div')
  if (id) el.id = id
  if (cls.length) el.className = cls.join(' ')
  if (attrs && (typeof attrs !== 'object' || attrs instanceof Node || Array.isArray(attrs))) {
    kids.unshift(attrs)
    attrs = null
  }
  for (const k in attrs || {}) {
    const v = attrs[k]
    if (v == null || v === false) continue
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v)
    else if (k === 'style' && typeof v === 'object') Object.assign(el.style, v)
    else if (k === 'class') el.className += (el.className ? ' ' : '') + v
    else if (k in el && k !== 'list') el[k] = v
    else el.setAttribute(k, v === true ? '' : v)
  }
  add(el, kids)
  return el
}

function add(el, kids) {
  for (const k of kids) {
    if (k == null || k === false) continue
    if (Array.isArray(k)) add(el, k)
    else el.append(k instanceof Node ? k : document.createTextNode(String(k)))
  }
}

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-4-4"/>',
  phone: '<path d="M5 4h4l2 5-2.5 1.5a11 11 0 0 0 5 5L15 13l5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2"/>',
  hangup: '<path d="M3 15.5c5-5 13-5 18 0l-2.5 2.5-3-1.5v-3a12 12 0 0 0-7 0v3l-3 1.5z"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 11v6M12 7.5v.5"/>',
  group: '<circle cx="9" cy="8" r="3.5"/><path d="M2.5 19c.8-3.5 3.4-5 6.5-5s5.7 1.5 6.5 5"/><circle cx="17" cy="9" r="2.5"/><path d="M17 14c2.3 0 4 1.2 4.5 4"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  up: '<path d="M12 19V6M6 11l6-6 6 6"/>',
  down: '<path d="m6 9 6 6 6-6"/>',
  check: '<path d="m5 12 5 5 9-10"/>',
  dcheck: '<path d="m2 12 5 5 9-10M12 16l1 1 9-10"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  x: '<path d="M6 6l12 12M18 6 6 18"/>',
  mic: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3"/>',
  micoff: '<rect x="9" y="3" width="6" height="12" rx="3"/><path d="M5 11a7 7 0 0 0 14 0M12 18v3M3 3l18 18"/>',
  play: '<path d="M7 4.5v15l13-7.5z" fill="currentColor"/>',
  pause: '<path d="M7 4h3.5v16H7zM13.5 4H17v16h-3.5z" fill="currentColor"/>',
  file: '<path d="M6 3h8l4 4v14H6z"/><path d="M12 10v7M9 14l3 3 3-3"/>',
  pin: '<path d="M12 21s7-6.2 7-12a7 7 0 0 0-14 0c0 5.8 7 12 7 12z"/><circle cx="12" cy="9" r="2.5"/>',
  pinned: '<path d="M9 4h6l-1 6 3 3H7l3-3zM12 13v8"/>',
  mute: '<path d="M6 16V11a6 6 0 0 1 10-4.5M18 11v5l2 2H8M10 20a2 2 0 0 0 4 0M3 3l18 18"/>',
  pen: '<path d="M4 20h4L19 9l-4-4L4 16z"/><path d="m14 6 4 4"/>',
  chart: '<path d="M5 20V10M12 20V4M19 20v-7"/>',
  cal: '<rect x="4" y="5" width="16" height="15" rx="2"/><path d="M4 10h16M9 3v4M15 3v4"/>',
  rub: '<path d="M8 20V4h6a4 4 0 0 1 0 8H6M6 16h8"/>',
  film: '<rect x="3" y="5" width="18" height="14" rx="2"/><path d="m10 9 5 3-5 3z" fill="currentColor"/>',
  copy: '<rect x="8" y="8" width="12" height="12" rx="2"/><path d="M16 8V5a1 1 0 0 0-1-1H5a1 1 0 0 0-1 1v10a1 1 0 0 0 1 1h3"/>',
  route: '<path d="M12 3 21 12l-9 9-9-9z"/><path d="M9 14v-2a2 2 0 0 1 2-2h4M13 8l2 2-2 2"/>',
  lock: '<rect x="5" y="11" width="14" height="10" rx="2"/><path d="M8 11V8a4 4 0 0 1 8 0v3"/>',
  back10: '<path d="M4 12a8 8 0 1 0 3-6.2M4 4v4h4"/>',
  fwd10: '<path d="M20 12a8 8 0 1 1-3-6.2M20 4v4h-4"/>',
  circle: '<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.5" fill="currentColor"/>',
}

function icon(name, size) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  s.setAttribute('viewBox', '0 0 24 24')
  s.setAttribute('fill', 'none')
  s.setAttribute('stroke', 'currentColor')
  s.setAttribute('stroke-width', '2')
  s.setAttribute('stroke-linecap', 'round')
  s.setAttribute('stroke-linejoin', 'round')
  if (size) { s.style.width = size + 'px'; s.style.height = size + 'px' }
  s.innerHTML = ICONS[name] || ''
  return s
}

/** The JT monogram: T bar turning into a J hook, accent dot. */
function monogram(size) {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg')
  s.setAttribute('viewBox', '0 0 100 100')
  s.style.width = size + 'px'
  s.style.height = size + 'px'
  s.innerHTML = '<g transform="translate(1 .5)"><rect x="22" y="22" width="56" height="14" rx="7" fill="#fff"/>' +
    '<path d="M50 30v30a14 14 0 0 1-28 0" stroke="#fff" stroke-width="14" stroke-linecap="round" fill="none"/>' +
    '<circle cx="73" cy="67" r="10" fill="#7366f0"/></g>'
  return s
}

// ---------- dates ----------

const MONTHS = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря']
const MONTHS_SHORT = ['янв.', 'февр.', 'мар.', 'апр.', 'мая', 'июн.', 'июл.', 'авг.', 'сент.', 'окт.', 'нояб.', 'дек.']
const WEEKDAYS = ['вс', 'пн', 'вт', 'ср', 'чт', 'пт', 'сб']
const pad = (n) => String(n).padStart(2, '0')
const dateOf = (t) => new Date(t * 1000)

function sameDay(a, b) { return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate() }
function isToday(d) { return sameDay(d, new Date()) }
function isYesterday(d) { const y = new Date(); y.setDate(y.getDate() - 1); return sameDay(d, y) }
function clock(t) { const d = dateOf(t); return pad(d.getHours()) + ':' + pad(d.getMinutes()) }

function shortTime(t) {
  const d = dateOf(t)
  if (isToday(d)) return clock(t)
  if (isYesterday(d)) return 'вчера'
  if (d.getFullYear() === new Date().getFullYear()) return d.getDate() + ' ' + MONTHS_SHORT[d.getMonth()]
  return pad(d.getDate()) + '.' + pad(d.getMonth() + 1) + '.' + String(d.getFullYear()).slice(2)
}

function dayTitle(t) {
  const d = dateOf(t)
  if (isToday(d)) return 'Сегодня'
  if (isYesterday(d)) return 'Вчера'
  return d.getDate() + ' ' + MONTHS[d.getMonth()] + (d.getFullYear() === new Date().getFullYear() ? '' : ' ' + d.getFullYear())
}

function gatherTime(t) {
  const d = dateOf(t)
  return WEEKDAYS[d.getDay()] + ', ' + d.getDate() + ' ' + MONTHS[d.getMonth()] + ' в ' + clock(t)
}

function durationLabel(ms) { const s = Math.floor(ms / 1000); return Math.floor(s / 60) + ':' + pad(s % 60) }

function timeLabel(sec) {
  const t = Math.max(Math.floor(sec), 0)
  return t >= 3600 ? Math.floor(t / 3600) + ':' + pad(Math.floor(t / 60) % 60) + ':' + pad(t % 60) : Math.floor(t / 60) + ':' + pad(t % 60)
}

function sizeLabel(b) {
  if (b >= 1048576) return (b / 1048576).toFixed(1).replace('.', ',') + ' МБ'
  if (b >= 1024) return Math.floor(b / 1024) + ' КБ'
  return b + ' Б'
}

function uuid() { return crypto.randomUUID() }

// ---------- server ----------

class ApiError extends Error {}

const Api = {
  token: '',

  url(path, query) {
    const u = new URL(SERVER + path)
    for (const k in query || {}) u.searchParams.set(k, query[k])
    return u.toString()
  },

  media(name) { return Api.url('/media/' + name, { t: Api.token }) },

  /** body: object -> JSON, Blob / ArrayBuffer -> raw bytes */
  async call(method, path, { query, body, timeout = 30000 } = {}) {
    const headers = {}
    if (Api.token) headers.Authorization = Api.token
    let data
    if (body instanceof Blob || body instanceof ArrayBuffer || ArrayBuffer.isView(body)) {
      data = body
      headers['Content-Type'] = 'application/octet-stream'
    } else if (body !== undefined) {
      data = JSON.stringify(body)
      headers['Content-Type'] = 'application/json'
    }
    const ctl = new AbortController()
    const timer = setTimeout(() => ctl.abort(), timeout)
    let resp
    try {
      resp = await fetch(Api.url(path, query), { method, headers, body: data, signal: ctl.signal })
    } catch (e) {
      throw new ApiError('Нет связи с сервером')
    } finally {
      clearTimeout(timer)
    }
    let json = {}
    const raw = await resp.text()
    try {
      json = JSON.parse(raw)
    } catch (e) {
      // not our server talking (a tunnel page, a proxy): say what came back
      if (resp.status < 400) throw new ApiError(`Сервер ответил не JSON (${resp.status}): ${raw.replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim().slice(0, 80)}`)
    }
    if (resp.status >= 400) {
      if (resp.status === 401) throw new ApiError('bad token')
      throw new ApiError(typeof json.detail === 'string' ? json.detail : 'Ошибка сервера (' + resp.status + ')')
    }
    return json
  },
}

/** One WebSocket for live events; reconnects by itself until stop(). */
class Socket {
  constructor() {
    this.onEvent = () => {}
    this.onState = () => {}
    this.onBadToken = () => {}
    this.ws = null
    this.running = false
    this.retry = 1000
  }

  start() { this.running = true; this.connect() }

  stop() {
    this.running = false
    if (this.ws) this.ws.close()
    this.ws = null
  }

  connect() {
    if (!this.running) return
    const url = Api.url('/ws', { token: Api.token, v: APP_VERSION }).replace(/^http/, 'ws')
    const ws = new WebSocket(url)
    this.ws = ws
    ws.onopen = () => {
      if (ws !== this.ws) return
      this.retry = 1000
      this.onState(true)
    }
    ws.onmessage = (e) => {
      if (ws !== this.ws || typeof e.data !== 'string') return
      try { this.onEvent(JSON.parse(e.data)) } catch (err) { console.error(err) }
    }
    ws.onclose = (e) => {
      if (ws !== this.ws) return
      this.ws = null
      if (e.code === 4401) {
        this.running = false
        this.onBadToken()
        return
      }
      this.onState(false)
      setTimeout(() => this.connect(), this.retry)
      this.retry = Math.min(this.retry * 2, 15000)
    }
    // the tunnel drops quiet sockets: the server answers {"type":"ping"} — any traffic keeps it alive
    clearInterval(this.pinger)
    this.pinger = setInterval(() => {
      if (ws === this.ws && ws.readyState === 1) ws.send(JSON.stringify({ type: 'ping' }))
    }, 25000)
  }

  send(ev) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(JSON.stringify(ev))
  }
}

/** Shrinks a picture to [side] px on the long side, JPEG 0.85, white under transparency. */
async function toJpeg(blob, side = 2560, square = false) {
  const bmp = await createImageBitmap(blob)
  let sx = 0, sy = 0, sw = bmp.width, sh = bmp.height
  if (square) {
    const s = Math.min(sw, sh)
    sx = (sw - s) / 2; sy = (sh - s) / 2; sw = sh = s
  }
  const k = Math.min(1, side / Math.max(sw, sh))
  const w = Math.round(sw * k), hgt = Math.round(sh * k)
  const c = document.createElement('canvas')
  c.width = w; c.height = hgt
  const ctx = c.getContext('2d')
  ctx.fillStyle = '#fff'
  ctx.fillRect(0, 0, w, hgt)
  ctx.imageSmoothingQuality = 'high'
  ctx.drawImage(bmp, sx, sy, sw, sh, 0, 0, w, hgt)
  const out = await new Promise((r) => c.toBlob(r, 'image/jpeg', 0.85))
  return { blob: out, w, h: hgt }
}

/** Text with clickable links. */
function linked(text) {
  const out = []
  const re = /\bhttps?:\/\/[^\s<>"]+|\bwww\.[^\s<>"]+/gi
  let at = 0, m
  while ((m = re.exec(text))) {
    let url = m[0].replace(/[.,!?;:)»]+$/, '')
    out.push(text.slice(at, m.index))
    const href = url.startsWith('http') ? url : 'https://' + url
    out.push(h('a', { onclick: (e) => { e.preventDefault(); window.native && window.native.openExternal(href) } }, url))
    at = m.index + url.length
    re.lastIndex = at
  }
  out.push(text.slice(at))
  return out
}

// «Смотрим вместе» like the phone's Watch.kt: the server holds (playing, pos); remote changes are applied,
// my own play / pause / seek are sent; every second a player that drifted > 2.5 s jumps back.
// YouTube / VK play in a <webview> with the server's /player page, own videos in <video>.

class FileCtl {
  constructor(url) { this.v = h('video', { src: url, preload: 'auto' }) }
  play(at) { if (at != null) this.v.currentTime = at; this.v.play().catch(() => {}) }
  pause(at) { this.v.pause(); if (at != null) this.v.currentTime = at }
  seek(t) { this.v.currentTime = t }
  async position() { return this.v.currentTime || 0 }
  close() { this.v.pause(); this.v.removeAttribute('src'); this.v.load() }
}

class WebCtl {
  constructor(url) {
    this.v = document.createElement('webview')
    this.v.setAttribute('preload', window.native.webviewPreload)
    this.v.setAttribute('src', url)
    this.v.setAttribute('allowpopups', '')
    this.onReady = () => {}
    this.onState = () => {}
    this.onError = () => {}
    this.v.addEventListener('ipc-message', (e) => {
      if (e.channel !== 'jt') return
      const b = e.args[0] || {}
      if (b.k === 'ready') this.onReady(Number(b.d) || 0, b.t || '')
      else if (b.k === 'state') this.onState(Number(b.s), Number(b.t) || 0)
      else this.onError(String(b.e))
    })
    this.v.addEventListener('new-window', (e) => window.native.openExternal(e.url))
  }
  js(s) { try { this.v.executeJavaScript(s).catch(() => {}) } catch (e) {} }
  arg(t) { return t == null ? 'null' : t.toFixed(2) }
  play(at) { this.js(`cmd('play',${this.arg(at)})`) }
  pause(at) { this.js(`cmd('pause',${this.arg(at)})`) }
  seek(t) { this.js(`cmd('seek',${this.arg(t)})`) }
  async position() {
    try { const v = await this.v.executeJavaScript('pos()'); return v >= 0 ? v : null } catch (e) { return null }
  }
  close() { try { this.v.src = 'about:blank' } catch (e) {} }
}

class Sync {
  constructor(send) {
    this.send = send
    this.ctl = null
    this.ready = false
    this.playing = false
    this.pos = 0
    this.at = Date.now()
    this.quietUntil = 0
    this.onChange = () => {}
  }

  expected() { return this.pos + (this.playing ? (Date.now() - this.at) / 1000 : 0) }

  setState(p, position) { this.playing = p; this.pos = position; this.at = Date.now(); this.onChange() }

  remote(p, position, mine) {
    this.setState(p, position)
    if (!mine) this.apply()
  }

  apply() {
    if (!this.ctl || !this.ready) return
    this.quietUntil = Date.now() + 1500
    if (this.playing) this.ctl.play(this.expected())
    else this.ctl.pause(this.expected())
  }

  async togglePlay() {
    if (!this.ctl) return
    const now = (await this.ctl.position()) ?? this.expected()
    const next = !this.playing
    this.setState(next, now)
    this.quietUntil = Date.now() + 1500
    if (next) this.ctl.play(null)
    else this.ctl.pause(null)
    this.send(next ? 'play' : 'pause', now)
  }

  seekTo(t) {
    const target = Math.max(0, t)
    this.setState(this.playing, target)
    this.quietUntil = Date.now() + 1500
    if (this.ctl) this.ctl.seek(target)
    this.send('seek', target)
  }

  async seekBy(d) {
    if (!this.ctl) return
    this.seekTo(((await this.ctl.position()) ?? this.expected()) + d)
  }

  /** the embed's own controls: 1 = playing, 2 = paused (YouTube states) */
  native(state, time) {
    if (Date.now() < this.quietUntil) return
    if (state === 1 && !this.playing) { this.setState(true, time); this.send('play', time) }
    else if (state === 2 && this.playing) { this.setState(false, time); this.send('pause', time) }
    else if (state === 1 && Math.abs(time - this.expected()) > 3) { this.setState(true, time); this.send('seek', time) }
  }

  async check() {
    if (!this.ctl || !this.ready || !this.playing || Date.now() < this.quietUntil) return
    const now = await this.ctl.position()
    if (now != null && Math.abs(now - this.expected()) > 2.5) {
      this.quietUntil = Date.now() + 1500
      this.ctl.seek(this.expected())
    }
  }
}

const Watch = {
  room: null,

  open(id, chatId, card) {
    if (this.room) this.room.close()
    const send = (action, pos) => Store.sendRaw({ type: 'watch', watch_id: id, action, pos })
    const sync = new Sync(send)
    const err = h('.err.hidden')
    const screen = h('.screen')
    const time = h('span.small.mut', { style: { fontVariantNumeric: 'tabular-nums', minWidth: '90px' } }, '0:00')
    const range = h('input', { type: 'range', min: 0, max: 0, step: 0.1, value: 0 })
    const viewers = h('span.small.mut')
    const playBtn = h('button.round.acc', { style: { width: '40px', height: '40px' }, onclick: () => sync.togglePlay() }, icon('play', 18))
    let duration = 0
    let dragging = false
    range.oninput = () => { dragging = true }
    range.onchange = () => { dragging = false; sync.seekTo(Number(range.value)) }
    sync.onChange = () => playBtn.replaceChildren(icon(sync.playing ? 'pause' : 'play', 18))
    const setDuration = (d) => {
      duration = d
      range.max = d
      range.classList.toggle('hidden', !(d > 0))
    }
    setDuration(0)

    const el = h('.watch', screen,
      h('.controls',
        h('button.round', { title: '−10 с', onclick: () => sync.seekBy(-10) }, icon('back10', 16)),
        playBtn,
        h('button.round', { title: '+10 с', onclick: () => sync.seekBy(10) }, icon('fwd10', 16)),
        time, range, h('span.grow', { style: { flex: duration > 0 ? 0 : 1 } }), viewers,
        h('span.semi.ell', { style: { maxWidth: '240px' } }, card.title || 'Смотрим вместе'),
        h('button.btn.ghost.small', { onclick: () => this.room.close() }, 'Закрыть')))
    screen.append(err)

    const source = card.source || ''
    const src = card.src || ''
    if (source === 'file') {
      const f = new FileCtl(Api.media(src))
      sync.ctl = f
      f.v.onloadedmetadata = () => { setDuration(f.v.duration || 0); sync.ready = true; sync.apply() }
      f.v.onerror = () => { err.textContent = 'Видео не открылось'; err.classList.remove('hidden') }
      f.v.onclick = () => sync.togglePlay()
      screen.prepend(f.v)
    } else {
      let url
      if (source === 'youtube') url = Api.url('/player/yt', { v: src })
      else {
        const p = src.split('_')
        url = Api.url('/player/vk', { oid: p[0], id: p[1] || '', hash: p[2] || '' })
      }
      const w = new WebCtl(url)
      w.onReady = (d) => { setDuration(d); sync.ready = true; sync.apply() }
      w.onState = (s, t) => sync.native(s, t)
      w.onError = (e) => {
        err.textContent = source !== 'youtube' ? 'VK Видео не открылось: возможно, видео закрыто'
          : ['101', '150', '152'].includes(e) ? 'Автор разрешил смотреть это видео только на самом YouTube. Возьмите другое'
            : `YouTube не грузится (${e}). Возможно, нужен VPN`
        err.classList.remove('hidden')
      }
      sync.ctl = w
      screen.prepend(w.v)
    }

    Store.onWatch[id] = (ev) => sync.remote(!!ev.playing, Number(ev.pos) || 0, ev.by === Store.meId)
    send('join', 0)
    Api.call('GET', `/watch/${id}`).then((w) => {
      Store.watchCards[id] = w
      sync.setState(!!w.playing, Number(w.pos) || 0)
      sync.apply()
    }).catch(() => {})

    const timer = setInterval(async () => {
      if (sync.ctl) {
        const now = await sync.ctl.position()
        if (now != null) {
          time.textContent = timeLabel(now) + (duration > 0 ? ' / ' + timeLabel(duration) : '')
          if (!dragging) range.value = now
        }
      }
      sync.check()
      const v = (Store.watchCards[id] || {}).viewers || []
      viewers.textContent = v.length ? '👀 ' + v.length : ''
      viewers.title = v.map((x) => Store.name(x)).join(', ')
    }, 1000)

    const keys = (e) => {
      if (e.target.tagName === 'INPUT' || e.target.tagName === 'TEXTAREA') return
      if (e.code === 'Space') { e.preventDefault(); sync.togglePlay() }
      if (e.key === 'Escape') { e.stopPropagation(); this.room.close() }
      if (e.key === 'ArrowLeft') sync.seekBy(-10)
      if (e.key === 'ArrowRight') sync.seekBy(10)
    }
    document.addEventListener('keydown', keys, true)
    document.body.append(el)
    this.room = {
      id,
      close: () => {
        clearInterval(timer)
        document.removeEventListener('keydown', keys, true)
        delete Store.onWatch[id]
        send('leave', 0)
        if (sync.ctl) sync.ctl.close()
        sync.ctl = null
        el.remove()
        this.room = null
      },
    }
  },
}

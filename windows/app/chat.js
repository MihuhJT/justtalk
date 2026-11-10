// One open chat: header, search, message list (kept in place between redraws), composer.

/** One shared player: starting a voice message stops the previous one. */
const Player = {
  audio: null,
  id: null,
  el: null,

  toggle(m, el) {
    if (this.id === m.id) { this.stop(); return }
    this.stop()
    if (!m.media) return
    const a = new Audio(Api.media(m.media))
    this.audio = a
    this.id = m.id
    this.el = el
    a.ontimeupdate = () => this.progress(a.duration > 0 ? a.currentTime / a.duration : 0)
    a.onended = () => this.stop()
    a.onerror = () => { UI.toast('Не получилось воспроизвести'); this.stop() }
    a.play()
    this.paint(true)
  },

  stop() {
    if (this.audio) this.audio.pause()
    this.paint(false)
    this.progress(0)
    this.audio = null
    this.id = null
    this.el = null
  },

  paint(on) {
    if (!this.el) return
    this.el.querySelector('.round').replaceChildren(icon(on ? 'pause' : 'play', 16))
  },

  progress(p) {
    if (!this.el) return
    const bars = this.el.querySelectorAll('.waves i')
    bars.forEach((b, i) => b.classList.toggle('on', i / bars.length < p))
  },
}

class ChatView {
  constructor(chatId) {
    this.chatId = chatId
    this.replyTo = null
    this.editing = null
    this.pasted = null
    this.searching = false
    this.atBottom = true
    this.unseen = 0
    this.cache = new Map()
    this.lastCount = 0
    this.lastId = null
    this.voice = null

    this.head = h('.chathead')
    this.searchBox = h('.searchbar.hidden')
    this.list = h('#msgs')
    this.downBtn = h('button.down.hidden', { onclick: () => this.scrollBottom(true) }, icon('down', 16))
    this.ctx = h('div')
    this.input = h('textarea', { rows: 1, placeholder: 'Сообщение' })
    this.sendBtn = h('button.round.acc', { onclick: () => this.submit() }, icon('up', 16))
    this.micBtn = h('button.round.s2', { title: 'Голосовое', onclick: () => this.startVoice() }, icon('mic', 16))
    this.composer = h('.composer', this.ctx, h('.bar',
      h('button.round', { title: 'Прикрепить', onclick: (e) => this.attachMenu(e) }, icon('plus', 17)),
      this.input, this.sendBtn, this.micBtn))
    this.dropZone = h('.drop.hidden', 'Отправить в чат')
    this.fileInput = h('input.hidden', { type: 'file', multiple: true, onchange: () => {
      const files = [...this.fileInput.files]
      this.fileInput.value = ''
      ;(async () => { for (const f of files) await Store.sendFile(this.chatId, f) })()
    } })
    this.el = h('#chatpane', { style: { display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, position: 'relative' } },
      this.head, this.searchBox, this.list, this.downBtn, this.composer, this.dropZone, this.fileInput)

    this.input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter' && !e.shiftKey && !e.altKey) { e.preventDefault(); this.submit() }
      if (e.key === 'Escape' && (this.replyTo || this.editing || this.pasted)) { e.stopPropagation(); this.clearCtx() }
      if (e.key === 'ArrowUp' && !this.input.value) {
        // like Telegram: up arrow edits my last text message
        const mine = (Store.messages[this.chatId] || []).filter((m) => m.sender_id === Store.meId && m.id > 0 && this.canEdit(m))
        if (mine.length) { e.preventDefault(); this.startEdit(mine[mine.length - 1]) }
      }
    })
    this.input.addEventListener('input', () => {
      this.grow()
      this.paintButtons()
      if (this.input.value && !this.editing) Store.typingNow(this.chatId)
    })
    this.list.addEventListener('scroll', () => this.onScroll())
    let depth = 0
    this.el.addEventListener('dragenter', (e) => { e.preventDefault(); depth++; this.dropZone.classList.remove('hidden') })
    this.el.addEventListener('dragleave', () => { if (--depth <= 0) { depth = 0; this.dropZone.classList.add('hidden') } })
    this.el.addEventListener('dragover', (e) => e.preventDefault())
    this.el.addEventListener('drop', (e) => {
      e.preventDefault()
      depth = 0
      this.dropZone.classList.add('hidden')
      const files = [...e.dataTransfer.files]
      ;(async () => { for (const f of files) await Store.sendFile(this.chatId, f) })()
    })
  }

  get chat() { return Store.chat(this.chatId) }

  mounted() {
    this.input.focus()
    this.paintCtx()
    this.paintButtons()
  }

  destroy() {
    if (this.voice) this.voice.cancel()
    Player.stop()
  }

  // ---------- header ----------

  paintHead() {
    const c = this.chat
    const group = Store.isGroup(c)
    const other = group ? null : Store.other(c)
    const typing = Store.typingLine(this.chatId)
    const sub = typing || (group ? (c.members || []).length + ' участн.' : (other ? Store.presenceLine(other.id) : ''))
    const sig = [c.title, c.avatar, other && other.avatar, sub, other && Store.isOnline(other.id)].join('|')
    if (sig === this.headSig) return
    this.headSig = sig
    this.head.replaceChildren(
      h('.who', { onclick: () => this.info() },
        avatar(c.title, group ? c.avatar : other && other.avatar, 36, !!other && Store.isOnline(other.id)),
        h('div', h('.title', c.title), sub && h('.sub' + (typing || sub === 'в сети' ? '.live' : ''), sub))),
      h('span.handle' + (isOwner(other) ? '.owner' : ''), other ? handleOf(other) : ''),
      h('button.round', { title: 'Поиск в чате', style: other ? {} : { marginLeft: 'auto' }, onclick: () => this.toggleSearch() }, icon('search', 15)),
      h('button.round', { title: 'Позвонить', onclick: () => Calls.start(this.chatId) }, icon('phone', 15)),
      h('button.round', { title: group ? 'Группа' : 'Профиль', onclick: () => this.info() }, icon(group ? 'group' : 'info', 15)))
  }

  info() {
    const c = this.chat
    if (Store.isGroup(c)) People.groupInfo(this.chatId)
    else People.person(Store.other(c))
  }

  toggleSearch() {
    this.searching = !this.searching
    this.searchBox.classList.toggle('hidden', !this.searching)
    if (!this.searching) return
    const found = h('.found')
    const q = h('input', { placeholder: 'Искать в чате' })
    let timer
    q.oninput = () => {
      clearTimeout(timer)
      if (!q.value.trim()) { found.replaceChildren(); return }
      timer = setTimeout(async () => {
        let list = []
        try { list = await Api.call('GET', `/chats/${this.chatId}/search`, { query: { q: q.value } }) } catch (e) {}
        found.replaceChildren(...(list.length ? list.map((m) => h('div', { onclick: () => this.jump(m.id) },
          h('span.acc.semi', Store.name(m.sender_id)), h('span.grow.ell', preview(m)), h('span.mut.small', shortTime(m.created))))
          : [h('.small.mut', 'Ничего не нашлось')]))
      }, 300)
    }
    q.onkeydown = (e) => { if (e.key === 'Escape') { e.stopPropagation(); this.toggleSearch() } }
    this.searchBox.replaceChildren(h('.search', { style: { margin: 0 } }, icon('search'), q,
      h('button.link', { onclick: () => this.toggleSearch() }, 'Закрыть')), found)
    q.focus()
  }

  /** Search result or quote: load older history until the message is there, then scroll to it. */
  async jump(id) {
    for (let tries = 0; tries < 30; tries++) {
      if ((Store.messages[this.chatId] || []).some((m) => m.id === id) || Store.historyDone.has(this.chatId)) break
      await Store.loadHistory(this.chatId)
    }
    this.update()
    const hit = this.cache.get('m' + id)
    if (!hit) { UI.toast('Сообщение не найдено'); return }
    hit.el.scrollIntoView({ block: 'center', behavior: 'smooth' })
    const b = hit.el.querySelector('.bubble')
    if (b) { b.classList.remove('flash'); void b.offsetWidth; b.classList.add('flash') }
  }

  // ---------- messages ----------

  update() {
    if (!this.chat) return
    this.paintHead()
    const msgs = Store.messages[this.chatId]
    if (!msgs) {
      this.list.replaceChildren(h('.spinner', { style: { margin: 'auto' } }))
      this.cache.clear()
      return
    }
    if (!msgs.length) {
      this.list.replaceChildren(h('.empty', 'Напиши первым 👋'))
      this.cache.clear()
      return
    }
    const fromBottom = this.list.scrollHeight - this.list.scrollTop
    const wasAtBottom = this.atBottom
    const c = this.chat
    const group = Store.isGroup(c)
    const items = []
    if (!Store.historyDone.has(this.chatId)) items.push(['loader', 'loader', () => h('.spinner.loader')])
    let prev = null
    for (const m of msgs) {
      if (!prev || !sameDay(dateOf(prev.created), dateOf(m.created))) {
        const t = dayTitle(m.created)
        items.push(['d' + t, t, () => h('.day', t)])
      }
      const showName = group && (!prev || prev.sender_id !== m.sender_id || prev.sender_id === 0)
      const read = m.sender_id === Store.meId && m.id > 0 && m.id <= (c.others_read || 0)
      const author = Store.user(m.sender_id)
      const sig = JSON.stringify(m) + showName + read + (author ? author.name + author.avatar : '') + (m.card ? Calls.callId + Store.meId : '')
      const mm = m
      items.push(['m' + m.id, sig, () => this.row(mm, showName, read)])
      prev = m
    }
    const keep = new Map()
    const nodes = []
    for (const [key, sig, make] of items) {
      let hit = this.cache.get(key)
      // a message that got its server id keeps its place: match by client id
      if (!hit || hit.sig !== sig) hit = { sig, el: make() }
      hit.el.dataset.key = key
      keep.set(key, hit)
      nodes.push(hit.el)
    }
    this.cache = keep
    // move only what changed so scroll and selection survive
    let at = this.list.firstChild
    if (at && !at.dataset.key) { this.list.replaceChildren(); at = null }
    for (const n of nodes) {
      if (n === at) { at = at.nextSibling; continue }
      this.list.insertBefore(n, at)
    }
    while (at) { const next = at.nextSibling; at.remove(); at = next }

    const last = msgs[msgs.length - 1]
    const firstId = msgs[0].id
    if (this.lastId === null) {
      this.scrollBottom(false)
    } else if (last.id !== this.lastId && last.client_id !== this.lastClient) {
      if (wasAtBottom || last.sender_id === Store.meId) this.scrollBottom(true)
      else { this.unseen++; this.paintDown() }
    } else if (firstId !== this.firstId) {
      // older history came in on top: stay on the same message
      this.list.scrollTop = this.list.scrollHeight - fromBottom
    } else if (wasAtBottom) {
      this.list.scrollTop = this.list.scrollHeight
    }
    this.lastId = last.id
    this.lastClient = last.client_id
    this.firstId = firstId
  }

  scrollBottom(smooth) {
    this.list.scrollTo({ top: this.list.scrollHeight, behavior: smooth ? 'smooth' : 'auto' })
    this.unseen = 0
    this.atBottom = true
    this.paintDown()
  }

  onScroll() {
    const l = this.list
    this.atBottom = l.scrollHeight - l.scrollTop - l.clientHeight < 60
    if (this.atBottom) this.unseen = 0
    this.paintDown()
    if (l.scrollTop < 200 && !Store.historyDone.has(this.chatId) && !this.loadingOld && (Store.messages[this.chatId] || []).length) {
      this.loadingOld = true
      Store.loadHistory(this.chatId).finally(() => { this.loadingOld = false })
    }
  }

  paintDown() {
    this.downBtn.classList.toggle('hidden', this.atBottom)
    this.downBtn.replaceChildren(icon('down', 16), this.unseen > 0 ? h('span.badge', this.unseen) : '')
  }

  row(m, showName, read) {
    if (m.sender_id === 0) return h('.sys', m.text)
    const mine = m.sender_id === Store.meId
    const c = this.chat
    const group = Store.isGroup(c)
    const u = Store.user(m.sender_id)
    const bubble = h('.bubble' + (m.id < 0 ? '.sending' : ''))
    if (showName && !mine && u) {
      bubble.append(h('.author' + (isOwner(u) ? '.owner' : ''), { onclick: () => People.person(u) }, u.name))
    }
    if (m.fwd_name) bubble.append(h('.fwd', 'Переслано от ' + m.fwd_name))
    if (m.reply) {
      const q = m.reply
      bubble.append(h('.quote', { onclick: () => this.jump(q.id) },
        h('.grow', h('.qn', Store.name(q.sender_id)), h('.qt.ell', q.deleted ? 'Сообщение удалено' : q.text))))
    }
    this.content(m, mine, bubble)
    const meta = h('.meta', m.edited && h('span', 'изм.'), h('span', clock(m.created)))
    if (mine) meta.append(icon(m.id < 0 ? 'clock' : read ? 'dcheck' : 'check'))
    bubble.append(meta)
    const col = h('.mcol', bubble)
    const r = m.reactions || {}
    const keys = Object.keys(r).sort((a, b) => r[b].length - r[a].length || (a < b ? -1 : 1))
    if (keys.length) {
      col.append(h('.reacts', keys.map((e) => h('button.react' + (r[e].includes(Store.meId) ? '.me' : ''), {
        title: r[e].map((id) => Store.name(id)).join(', '), onclick: () => Store.react(m, e),
      }, e, r[e].length > 1 && h('span.semi.small', r[e].length)))))
    }
    const side = group && !mine ? (showName && u ? h('.side', { style: { cursor: 'pointer' }, onclick: () => People.person(u) }, avatar(u.name, u.avatar, 28)) : h('.side')) : null
    return h('.mrow' + (mine ? '.mine' : '') + (showName ? '.first' : ''), {
      oncontextmenu: (e) => this.msgMenu(e, m, mine),
      ondblclick: (e) => { if (m.id > 0 && !e.target.closest('button, a, .text')) this.reply(m) },
    }, side, col)
  }

  content(m, mine, bubble) {
    if (m.photo) {
      const w = Math.max(m.photo_w || 0, 1), hh = Math.max(m.photo_h || 0, 1)
      const width = Math.min(340, w)
      const height = Math.min(420, width * hh / w)
      bubble.append(h('.photo', {
        style: { width: (m.photo_w ? height * w / hh : 280) + 'px', height: (m.photo_w ? height : 200) + 'px', backgroundImage: `url("${Api.media(m.photo)}")` },
        onclick: () => UI.viewer(m.photo),
      }))
    }
    if (m.media_kind === 'voice') {
      const bars = m.wave ? [...m.wave].map(Number) : Array(30).fill(3)
      const el = h('.voicemsg', h('button.round', icon(Player.id === m.id ? 'pause' : 'play', 16)),
        h('div', h('.waves', bars.map((b) => h('i', { style: { height: 3 + b * 2.3 + 'px' } }))),
          h('.small', { style: { opacity: 0.8 } }, durationLabel(m.duration || 0))))
      el.onclick = () => Player.toggle(m, el)
      if (Player.id === m.id) Player.el = el
      bubble.append(el)
    } else if (m.media_kind === 'circle') {
      bubble.style.background = 'transparent'
      bubble.style.padding = '0'
      bubble.append(h('.circlemsg', {
        style: m.thumb ? { backgroundImage: `url("${Api.media(m.thumb)}")` } : {},
        onclick: () => Recorder.viewCircle(m),
      }, h('.play', icon('play'))))
    } else if (m.media_kind === 'file') {
      bubble.append(h('.filemsg', { title: 'Скачать в Загрузки', onclick: () => UI.save(m.media, m.media_name || m.media) },
        h('.round', icon('file', 18)),
        h('.grow', h('.semi.ell', { style: { fontSize: '14px', maxWidth: '260px' } }, m.media_name || 'Файл'), h('.small', { style: { opacity: 0.7 } }, sizeLabel(m.media_size || 0)))))
    }
    if (m.spot) {
      bubble.append(h('.spotlink', { title: 'Открыть на карте', onclick: () => { Store.spotFocus = m.spot.id; Store.tab = 'spots'; Store.changed() } },
        icon('pin', 15), m.spot.title || 'Спот'))
    }
    if (m.card) {
      // the text of a card message is only a fallback for old apps
      bubble.append(Cards.view(m, mine))
    } else if (m.text && !(m.media_kind === 'file' && m.text === m.media_name)) {
      bubble.append(h('.text', linked(m.text)))
    }
  }

  canEdit(m) { return !m.photo && !m.media && !m.card && !m.spot && !m.fwd_name }

  msgMenu(e, m, mine) {
    const sel = String(window.getSelection())
    const items = []
    if (m.id > 0) {
      items.push(h('.emo', REACTIONS.map((r) => h('button', { onclick: () => { Store.react(m, r); document.querySelector('.menu').remove() } }, r))))
      items.push(['Ответить', () => this.reply(m)])
      if (!m.card) items.push(['Переслать', () => People.forward(m)])
    }
    if (sel) items.push(['Копировать выделенное', () => navigator.clipboard.writeText(sel)])
    else if (m.text) items.push(['Копировать текст', () => navigator.clipboard.writeText(m.text)])
    if (m.photo) items.push(['Сохранить фото', () => UI.save(m.photo, `JustTalk-${m.id}.jpg`)])
    if (m.media) items.push(['Сохранить в Загрузки', () => UI.save(m.media, m.media_name || m.media)])
    if (mine && m.id > 0) {
      items.push('-')
      if (this.canEdit(m)) items.push(['Изменить', () => this.startEdit(m)])
      items.push(['Удалить', () => Store.delete(m), 'red'])
    }
    UI.menu(e, items)
  }

  // ---------- composer ----------

  reply(m) { this.replyTo = m; this.editing = null; this.paintCtx(); this.input.focus() }

  startEdit(m) {
    this.editing = m
    this.replyTo = null
    this.input.value = m.text
    this.grow()
    this.paintCtx()
    this.paintButtons()
    this.input.focus()
  }

  attachImage(file) {
    this.pasted = file
    this.paintCtx()
    this.paintButtons()
    this.input.focus()
  }

  clearCtx() {
    if (this.editing) this.input.value = ''
    this.replyTo = null
    this.editing = null
    this.pasted = null
    this.grow()
    this.paintCtx()
    this.paintButtons()
  }

  paintCtx() {
    const parts = []
    const r = this.replyTo || this.editing
    if (r) {
      parts.push(h('.ctx', h('.grow', h('.small.semi.acc', this.editing ? 'Изменить' : Store.name(r.sender_id)), h('.small.mut.ell', preview(r))),
        h('button.mut', { onclick: () => this.clearCtx() }, icon('x', 16))))
    }
    if (this.pasted) {
      const img = h('img', { src: URL.createObjectURL(this.pasted) })
      parts.push(h('.ctx.noline', img, h('.small.mut.grow', 'Фото из буфера — Enter, чтобы отправить'),
        h('button.mut', { onclick: () => { this.pasted = null; this.paintCtx(); this.paintButtons() } }, icon('x', 16))))
    }
    this.ctx.replaceChildren(...parts)
    this.input.placeholder = this.pasted ? 'Подпись' : 'Сообщение'
  }

  paintButtons() {
    const can = !!this.pasted || !!this.input.value.trim() || !!this.editing
    this.sendBtn.classList.toggle('hidden', !can)
    this.micBtn.classList.toggle('hidden', can)
    this.sendBtn.replaceChildren(icon(this.editing ? 'check' : 'up', 16))
  }

  grow() {
    this.input.style.height = 'auto'
    this.input.style.height = Math.min(this.input.scrollHeight, 180) + 'px'
  }

  submit() {
    const text = this.input.value
    if (this.pasted) {
      const img = this.pasted
      this.pasted = null
      this.input.value = ''
      this.afterSend()
      Store.sendPhoto(this.chatId, img, text.trim())
      return
    }
    if (this.editing) {
      if (text.trim()) Store.edit(this.editing, text.trim())
      this.editing = null
      this.input.value = ''
    } else {
      if (!text.trim()) return
      Store.send(this.chatId, text, this.replyTo ? this.replyTo.id : 0)
      this.replyTo = null
      this.input.value = ''
    }
    this.afterSend()
  }

  afterSend() {
    this.grow()
    this.paintCtx()
    this.paintButtons()
  }

  attachMenu(e) {
    UI.menu(e, [
      ['Фото или файл…', () => this.fileInput.click()],
      ['Кружок…', () => Recorder.circle(this.chatId)],
      '-',
      ['📊 Опрос', () => Cards.newPoll(this.chatId)],
      ['📅 Сбор', () => Cards.newGather(this.chatId)],
      ['💸 Скидываемся', () => Cards.newSplit(this.chatId)],
      ['🎬 Смотрим вместе', () => Cards.newWatch(this.chatId)],
    ])
  }

  async startVoice() {
    if (this.voice) return
    const rec = new VoiceRecorder()
    try { await rec.start() } catch (e) { UI.toast('Нет доступа к микрофону'); return }
    this.voice = rec
    const time = h('span', { style: { fontVariantNumeric: 'tabular-nums' } }, 'Запись 0:00')
    const bar = h('.recbar', h('.recdot'), time, h('span.grow'),
      h('button.btn.ghost.small', { onclick: () => done(false) }, 'Отмена'),
      h('button.round.acc', { onclick: () => done(true) }, icon('up', 16)))
    const done = async (send) => {
      if (this.voice !== rec) return
      this.voice = null
      bar.remove()
      document.removeEventListener('keydown', keys, true)
      const out = await rec.stop(send)
      if (!out) return
      const replyTo = this.replyTo ? this.replyTo.id : 0
      this.replyTo = null
      this.paintCtx()
      Recorder.sendVoice(this.chatId, out, replyTo)
    }
    const keys = (e) => {
      if (e.key === 'Escape') { e.stopPropagation(); done(false) }
      if (e.key === 'Enter') { e.preventDefault(); done(true) }
    }
    document.addEventListener('keydown', keys, true)
    rec.onTick = (s) => { time.textContent = 'Запись ' + durationLabel(s * 1000) }
    rec.onLimit = () => done(true)
    this.composer.append(bar)
  }
}

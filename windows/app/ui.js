// Window layout: login, sidebar with chats, the right pane (chat / spots), modals, menus, toasts.

function avatar(name, photo, size = 44, online = false) {
  const pic = h('.pic', { style: { fontSize: size * 0.42 + 'px' } })
  if (photo) pic.style.backgroundImage = `url("${Api.media(photo)}")`
  else pic.textContent = (name || '?').trim().charAt(0).toUpperCase() || '?'
  return h('.avatar', { style: { width: size + 'px', height: size + 'px' } }, pic, online && h('.dot'))
}

function field(placeholder, value = '', opts = {}) {
  return h('input.field', { placeholder, value, type: opts.secure ? 'password' : 'text', ...opts.attrs })
}

function sw(on, onchange) {
  const el = h('button.switch' + (on ? '.on' : ''))
  el.onclick = () => { el.classList.toggle('on'); onchange(el.classList.contains('on')) }
  return el
}

function seg(options, value, onchange) {
  const el = h('.seg')
  for (const [id, title] of options) {
    el.append(h('button' + (id === value ? '.on' : ''), {
      onclick: () => {
        for (const b of el.children) b.classList.remove('on')
        el.children[options.findIndex((o) => o[0] === id)].classList.add('on')
        onchange(id)
      },
    }, title))
  }
  return el
}

const UI = {
  sideQuery: '',
  chatView: null,
  spotsView: null,
  shownMain: null,

  boot() {
    document.querySelector('#titlebar .logo').replaceWith(monogram(16))
    if (window.native && window.native.platform !== 'win32') document.getElementById('titlebar').style.paddingLeft = '80px'
    Store.on(() => this.render())
    Store.init()
    this.render()
    // a picture in the clipboard (and no text) goes to the open chat
    document.addEventListener('paste', (e) => {
      if (!Store.openChat || !this.chatView) return
      const items = [...(e.clipboardData ? e.clipboardData.items : [])]
      const text = e.clipboardData.getData('text/plain')
      const img = items.find((i) => i.type.startsWith('image/'))
      if (img && !text) {
        e.preventDefault()
        this.chatView.attachImage(img.getAsFile())
      }
    })
    document.addEventListener('keydown', (e) => {
      if (e.key === 'Escape') {
        const top = [...document.querySelectorAll('.menu, .viewer, .modalbg')].pop()
        if (top) { top.dispatchEvent(new Event('close')); top.remove() }
      }
    })
    document.addEventListener('mousedown', (e) => {
      for (const m of document.querySelectorAll('.menu')) if (!m.contains(e.target)) m.remove()
    })
  },

  render() {
    const root = document.getElementById('root')
    if (!Store.me) {
      if (!root.querySelector('.login')) {
        this.chatView = null
        this.spotsView = null
        this.shownMain = null
        root.replaceChildren(this.loginView())
      }
      Calls.render()
      return
    }
    if (!root.querySelector('#side')) {
      root.replaceChildren(this.sidebar(), h('#main'))
      this.shownMain = null
    }
    this.renderSide()
    this.renderMain()
    Calls.render()
    if (Store.me.has_password === false) this.askPassword()
  },

  // ---------- login ----------

  loginView() {
    let mode = 'login'
    const err = h('.small.red')
    const box = h('.col', { style: { width: '300px' } })
    const hint = h('.small.mut')
    const btn = h('button.btn', { style: { width: '300px' } })
    const fields = {}
    const draw = () => {
      err.textContent = ''
      box.replaceChildren()
      if (mode === 'register') {
        fields.invite = field('Код приглашения')
        fields.name = field('Имя')
        fields.username = field('@юзернейм')
        box.append(fields.invite, fields.name, fields.username)
        hint.textContent = 'Код даёт создатель JT'
        btn.textContent = 'Создать'
      } else {
        fields.username = field('@юзернейм')
        fields.password = field('Пароль', '', { secure: true })
        box.append(fields.username, fields.password)
        hint.textContent = 'Пароль задаётся в приложении на телефоне: Профиль → Сменить пароль'
        btn.textContent = 'Войти'
      }
      for (const f of box.querySelectorAll('input')) f.onkeydown = (e) => { if (e.key === 'Enter') submit() }
      box.querySelector('input').focus()
    }
    const submit = async () => {
      btn.disabled = true
      err.textContent = ''
      const v = (k) => (fields[k] ? fields[k].value.trim() : '')
      try {
        if (mode === 'register') await Store.register(v('invite').replace(/\s/g, ''), v('name'), v('username').replace(/^@/, ''))
        else await Store.login(v('username').replace(/^@/, ''), fields.password.value)
      } catch (e) {
        err.textContent = e.message
      }
      btn.disabled = false
    }
    btn.onclick = submit
    const el = h('.login', monogram(84), h('h1', 'JustTalk'),
      seg([['login', 'Вход'], ['register', 'Новый аккаунт']], mode, (m) => { mode = m; draw() }),
      box, err, btn, hint, h('.small.mut', { style: { opacity: 0.6 } }, 'JustTalk ' + APP_VERSION))
    setTimeout(draw)
    return el
  },

  askPassword() {
    if (this.passwordAsked) return
    this.passwordAsked = true
    const p1 = field('Пароль, минимум 6 символов', '', { secure: true })
    const p2 = field('Ещё раз', '', { secure: true })
    const err = h('.small.red')
    const m = this.modal(h('h3', 'Придумай пароль'),
      h('.small.mut', `С ним можно войти в @${Store.me.username} на телефоне или другом компьютере`),
      p1, p2, err,
      h('button.btn', {
        onclick: async () => {
          if (p1.value !== p2.value) { err.textContent = 'Пароли не совпадают'; return }
          try { await Store.setPassword(p1.value); m.close(); this.passwordAsked = false } catch (e) { err.textContent = e.message }
        },
      }, 'Сохранить'))
    m.bg.onclick = null
  },

  // ---------- sidebar ----------

  sidebar() {
    const q = h('input', { placeholder: 'Поиск', value: this.sideQuery, oninput: (e) => { this.sideQuery = e.target.value; this.renderSide() } })
    return h('#side',
      h('.top',
        h('button', { title: 'Профиль', onclick: () => People.profile() }, h('span.meav')),
        h('h2.sidetitle', 'Чаты'),
        h('span.conn'),
        h('button.round.s2', { style: { marginLeft: 'auto', width: '30px', height: '30px' }, title: 'Новый чат или группа', onclick: (e) => this.menu(e, [
          ['Новый чат', () => People.newChat()],
          ['Новая группа', () => People.newGroup()],
        ]) }, icon('pen', 15))),
      h('.tabs'),
      h('.search', icon('search'), q),
      h('#chatlist'))
  },

  renderSide() {
    const side = document.getElementById('side')
    if (!side) return
    side.querySelector('.meav').replaceChildren(avatar(Store.me.name, Store.me.avatar, 30))
    side.querySelector('.sidetitle').textContent = Store.tab === 'spots' ? 'Споты' : 'Чаты'
    side.querySelector('.conn').replaceChildren(Store.connected ? '' : h('.spinner', { title: 'Подключаюсь…' }))
    const tabs = side.querySelector('.tabs')
    if (tabs.dataset.tab !== Store.tab) {
      tabs.dataset.tab = Store.tab
      tabs.replaceChildren(seg([['chats', 'Чаты'], ['spots', 'Споты']], Store.tab, (t) => { Store.tab = t; Store.changed() }))
    }
    const list = side.querySelector('#chatlist')
    const q = this.sideQuery.trim().toLowerCase()
    const uq = q.replace(/^@/, '')
    const chats = !q ? Store.chats : Store.chats.filter((c) =>
      (c.title || '').toLowerCase().includes(q) || (c.members || []).some((u) => u.id !== Store.meId && u.username.startsWith(uq)))
    if (!Store.chats.length) {
      list.replaceChildren(h('.empty', 'Пока нет чатов.', h('br'), 'Нажми ✎, чтобы найти кента'))
      return
    }
    const top = list.scrollTop
    list.replaceChildren(...chats.map((c) => this.chatRow(c)))
    list.scrollTop = top
  },

  chatRow(c) {
    const group = Store.isGroup(c)
    const other = group ? null : Store.other(c)
    const typing = Store.typingLine(c.id)
    let last = 'Нет сообщений'
    if (typing) last = typing
    else if (c.last) {
      const m = c.last
      if (m.sender_id === Store.meId) last = 'Ты: ' + preview(m)
      else if (group && m.sender_id !== 0) last = Store.name(m.sender_id) + ': ' + preview(m)
      else last = preview(m)
    }
    const on = Store.openChat === c.id && Store.tab === 'chats'
    return h('.chatrow' + (on ? '.on' : '') + (c.unread > 0 ? '.unread' : ''), {
      onclick: () => Store.open(c.id),
      oncontextmenu: (e) => this.chatMenu(e, c),
    },
    avatar(c.title, group ? c.avatar : (other && other.avatar), 46, !!other && Store.isOnline(other.id)),
    h('.grow',
      h('.t1', h('span.name.ell', c.title), isOwner(other) && h('span.jtbadge', 'JT'),
        c.muted && icon('mute'), c.pinned > 0 && icon('pinned'),
        c.last && h('span.time', shortTime(c.last.created))),
      h('.t2', h('span.last.ell' + (typing ? '.typing' : ''), last),
        c.unread > 0 && h('span.badge' + (c.muted ? '.muted' : ''), c.unread > 99 ? '99+' : c.unread))))
  },

  chatMenu(e, c) {
    this.menu(e, [
      [c.pinned > 0 ? 'Открепить' : 'Закрепить', () => Store.setPinned(c.id, !c.pinned)],
      [c.muted ? 'Включить уведомления' : 'Без звука', () => Store.setMuted(c.id, !c.muted)],
      '-',
      Store.isGroup(c)
        ? ['Выйти из группы', () => People.leaveGroup(c.id), 'red']
        : ['Удалить переписку у себя', () => People.clearChat(c.id), 'red'],
    ])
  },

  // ---------- right pane ----------

  renderMain() {
    const main = document.getElementById('main')
    let want = 'empty'
    if (Store.tab === 'spots') want = 'spots'
    else if (Store.openChat && Store.chat(Store.openChat)) want = 'chat:' + Store.openChat
    if (want !== this.shownMain) {
      if (this.chatView) this.chatView.destroy()
      this.chatView = null
      if (this.spotsView) this.spotsView.destroy()
      this.spotsView = null
      this.shownMain = want
      if (want === 'spots') {
        this.spotsView = new SpotsView()
        main.replaceChildren(this.spotsView.el)
        this.spotsView.mounted()
      } else if (want.startsWith('chat:')) {
        this.chatView = new ChatView(Store.openChat)
        main.replaceChildren(this.chatView.el)
        this.chatView.mounted()
      } else {
        const m = monogram(64)
        m.style.opacity = 0.5
        main.replaceChildren(h('.placeholder', m, h('div', 'Выбери чат')))
      }
    }
    if (this.chatView) this.chatView.update()
    if (this.spotsView) this.spotsView.update()
  },

  // ---------- overlays ----------

  /** A centered sheet; returns {el, bg, close}. Clicking outside closes it. */
  modal(...kids) {
    const box = h('.modal', ...kids)
    const bg = h('.modalbg', box)
    const close = () => { bg.dispatchEvent(new Event('close')); bg.remove() }
    bg.onclick = (e) => { if (e.target === bg) close() }
    document.body.append(bg)
    const f = box.querySelector('input, textarea')
    if (f) setTimeout(() => f.focus())
    return { el: box, bg, close }
  },

  /** items: [title, fn, cls?] or '-' or an element */
  menu(e, items) {
    e.preventDefault()
    e.stopPropagation()
    for (const m of document.querySelectorAll('.menu')) m.remove()
    const el = h('.menu')
    for (const it of items) {
      if (!it) continue
      if (it === '-') el.append(h('hr'))
      else if (it instanceof Node) el.append(it)
      else el.append(h('button' + (it[2] ? '.' + it[2] : ''), { onclick: () => { el.remove(); it[1]() } }, it[0]))
    }
    document.body.append(el)
    const r = el.getBoundingClientRect()
    el.style.left = Math.min(e.clientX, innerWidth - r.width - 8) + 'px'
    el.style.top = Math.min(e.clientY, innerHeight - r.height - 8) + 'px'
    return el
  },

  toast(text) {
    clearTimeout(this.toastTimer)
    const old = document.querySelector('.toast')
    if (old) old.remove()
    if (!text) return
    const el = h('.toast', { onclick: () => el.remove() }, text)
    document.body.append(el)
    this.toastTimer = setTimeout(() => el.remove(), 4000)
  },

  viewer(name) {
    const v = h('.viewer', { onclick: (e) => { if (e.target === v || e.target.classList.contains('img')) v.remove() } },
      h('.img', { style: { backgroundImage: `url("${Api.media(name)}")` } }),
      h('.vfoot',
        h('button.btn.ghost.small', { onclick: () => UI.save(name, name) }, 'Сохранить в Загрузки'),
        h('button.btn.ghost.small', { onclick: () => v.remove() }, 'Закрыть')))
    document.body.append(v)
  },

  async save(media, name) {
    try { await window.native.saveMedia(Api.media(media), name) } catch (e) { this.toast('Не получилось скачать') }
  },

  notifications: {},

  notify(title, body, chatId) {
    if (Notification.permission !== 'granted') return
    const n = new Notification(title, { body, tag: 'chat' + chatId, silent: false })
    n.onclick = () => {
      window.native && window.native.show()
      Store.open(chatId)
    }
    ;(this.notifications[chatId] = this.notifications[chatId] || []).push(n)
    window.native && window.native.flash()
  },

  clearNotifications(chatId) {
    for (const n of this.notifications[chatId] || []) n.close()
    delete this.notifications[chatId]
  },
}

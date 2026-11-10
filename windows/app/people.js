// People and groups: new chat, new group, group info, a person's card, profile, kents, blocks, forwarding.

const People = {
  personRow(u, onclick, right) {
    return h('.person', { onclick },
      avatar(u.name, u.avatar, 38, Store.isOnline(u.id)),
      h('.grow', h('.semi.ell', { style: { fontSize: '14px' } }, u.name),
        h('.small' + (isOwner(u) ? '.acc.bold' : '.mut'), handleOf(u))),
      right)
  },

  /** People I share a dm with, or search results by @username. */
  picker(onPick, opts = {}) {
    const q = field('Найти по @юзернейму')
    const list = h('.list')
    let found = null
    let timer
    const draw = () => {
      let people = found
      if (!people) {
        people = Store.chats.filter((c) => !Store.isGroup(c)).map((c) => Store.other(c)).filter((u) => u && u.id !== Store.meId)
      }
      if (opts.exclude) people = people.filter((u) => !opts.exclude.includes(u.id))
      list.replaceChildren(...people.map((u) => this.personRow(u, () => onPick(u, draw), opts.mark && opts.mark(u))))
      if (!people.length && q.value) list.append(h('.empty', 'Никого с таким ником'))
    }
    q.oninput = () => {
      clearTimeout(timer)
      const v = q.value.trim().replace(/^@/, '')
      if (!v) { found = null; draw(); return }
      timer = setTimeout(async () => { found = await Store.search(v); draw() }, 250)
    }
    draw()
    return [q, list]
  },

  newChat() {
    const m = UI.modal(h('h3', 'Новый чат'),
      ...this.picker((u) => { m.close(); Store.openDm(u.id) }),
      h('.foot', h('span'), h('button.btn.ghost.small', { onclick: () => m.close() }, 'Закрыть')))
  },

  newGroup() {
    const picked = new Set()
    const title = field('Название группы')
    const ok = h('button.btn.small', 'Создать')
    const err = h('.small.red')
    const re = () => { ok.disabled = !title.value.trim() || !picked.size; ok.textContent = picked.size ? `Создать · ${picked.size}` : 'Создать' }
    title.oninput = re
    const mark = (u) => h('span.check' + (picked.has(u.id) ? '.on' : ''))
    const m = UI.modal(h('h3', 'Новая группа'), title,
      ...this.picker((u, redraw) => { picked.has(u.id) ? picked.delete(u.id) : picked.add(u.id); redraw(); re() }, { mark }),
      err, h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'), ok))
    ok.onclick = async () => {
      ok.disabled = true
      try {
        const j = await Api.call('POST', '/chats/group', { body: { title: title.value.trim(), user_ids: [...picked] } })
        m.close()
        await Store.refresh()
        Store.open(j.id)
      } catch (e) { err.textContent = e.message; re() }
    }
    re()
  },

  groupInfo(chatId) {
    const c = Store.chat(chatId)
    if (!c) return
    const title = field('Название', c.title)
    const avInput = h('input.hidden', { type: 'file', accept: 'image/*' })
    const members = h('.list')
    const drawMembers = () => {
      const cc = Store.chat(chatId) || c
      members.replaceChildren(...(cc.members || []).map((u) => this.personRow(u, () => { if (u.id !== Store.meId) this.person(u) },
        u.id === Store.meId && h('span.small.mut', 'ты'))))
    }
    avInput.onchange = async () => {
      const f = avInput.files[0]
      if (!f) return
      try {
        const j = await toJpeg(f, 512)
        await Api.call('POST', `/chats/${chatId}/avatar`, { body: j.blob })
        await Store.refresh()
        m.close()
        this.groupInfo(chatId)
      } catch (e) { UI.toast(e.message) }
    }
    const m = UI.modal(
      h('.row', { style: { gap: '14px' } },
        h('button', { title: 'Сменить фото группы', onclick: () => avInput.click() }, avatar(c.title, c.avatar, 64)),
        h('.grow', h('h3', c.title), h('.small.mut', (c.members || []).length + ' участн.'))),
      avInput,
      h('.row', title, h('button.btn.ghost.small', { onclick: async () => {
        try { await Api.call('POST', `/chats/${chatId}/title`, { body: { title: title.value.trim() } }); await Store.refresh(); UI.toast('Переименовано') } catch (e) { UI.toast(e.message) }
      } }, 'Переименовать')),
      h('.row', h('.small.semi.mut.grow', 'Участники'),
        h('button.link', { onclick: () => this.addMembers(chatId, () => drawMembers()) }, '+ Добавить кентов')),
      members,
      h('.foot', h('button.link.red', { onclick: () => { m.close(); this.leaveGroup(chatId) } }, 'Выйти из группы'),
        h('button.btn.ghost.small', { onclick: () => m.close() }, 'Готово')))
    drawMembers()
  },

  addMembers(chatId, done) {
    const c = Store.chat(chatId)
    const picked = new Set()
    const ok = h('button.btn.small', 'Добавить')
    const mark = (u) => h('span.check' + (picked.has(u.id) ? '.on' : ''))
    const m = UI.modal(h('h3', 'Добавить в группу'),
      ...this.picker((u, redraw) => { picked.has(u.id) ? picked.delete(u.id) : picked.add(u.id); redraw(); ok.textContent = `Добавить ${picked.size}` },
        { mark, exclude: (c.members || []).map((u) => u.id) }),
      h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'), ok))
    ok.onclick = async () => {
      if (!picked.size) return
      try {
        await Api.call('POST', `/chats/${chatId}/members`, { body: { user_ids: [...picked] } })
        await Store.refresh()
        m.close()
        done()
      } catch (e) { UI.toast(e.message) }
    }
  },

  async leaveGroup(chatId) {
    const c = Store.chat(chatId)
    const m = UI.modal(h('h3', 'Выйти из группы?'), h('.mut', `«${c ? c.title : ''}» пропадёт из списка чатов`),
      h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'),
        h('button.btn.small', { style: { background: 'var(--red)' }, onclick: async () => {
          m.close()
          try { await Api.call('POST', `/chats/${chatId}/leave`) } catch (e) { UI.toast(e.message); return }
          if (Store.openChat === chatId) Store.openChat = null
          Store.chats = Store.chats.filter((x) => x.id !== chatId)
          Store.changed()
        } }, 'Выйти')))
  },

  clearChat(chatId) {
    const m = UI.modal(h('h3', 'Удалить переписку?'), h('.mut', 'История пропадёт только у тебя. Чат вернётся, когда придёт новое сообщение'),
      h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'),
        h('button.btn.small', { style: { background: 'var(--red)' }, onclick: async () => {
          m.close()
          try { await Api.call('POST', `/chats/${chatId}/clear`) } catch (e) { UI.toast(e.message); return }
          if (Store.openChat === chatId) Store.openChat = null
          delete Store.messages[chatId]
          Store.chats = Store.chats.filter((x) => x.id !== chatId)
          Store.changed()
        } }, 'Удалить')))
  },

  isKent(id) { const p = Store.people.find((u) => u.id === id); return !!(p && p.kent) },

  async setKent(id, on) {
    try { await Api.call('POST', `/friends/${id}`, { body: { kent: on } }) } catch (e) { UI.toast(e.message); return }
    const p = Store.people.find((u) => u.id === id)
    if (p) p.kent = on
  },

  facts(u) {
    const out = []
    if (u.gender === 'm') out.push('Парень')
    else if (u.gender === 'f') out.push('Девушка')
    const b = (u.birthday || '').match(/^(\d\d)\.(\d\d)\.(\d{4})$/)
    if (b) {
      const now = new Date()
      let y = now.getFullYear() - Number(b[3])
      if (now.getMonth() + 1 < Number(b[2]) || (now.getMonth() + 1 === Number(b[2]) && now.getDate() < Number(b[1]))) y--
      if (y >= 0 && y < 150) out.push(y + ' лет')
    }
    return out.join(' · ')
  },

  async person(u) {
    if (!u) return
    const dm = Store.chats.find((c) => !Store.isGroup(c) && (c.members || []).some((x) => x.id === u.id))
    const line = Store.presenceLine(u.id)
    const facts = this.facts(u)
    const alias = field('Имя для себя', dm ? dm.alias || '' : '')
    let blocked = false
    const blockBtn = h('button.link.red', '…')
    const m = UI.modal(
      h('.col', { style: { alignItems: 'center', textAlign: 'center' } },
        avatar(u.name, u.avatar, 84, Store.isOnline(u.id)),
        h('h3', u.name),
        h('.small' + (isOwner(u) ? '.acc.bold' : '.mut'), handleOf(u)),
        line && h('.small' + (line === 'в сети' ? '.green' : '.mut'), line),
        u.about && h('div', { style: { fontSize: '13px' } }, u.about),
        facts && h('.small.mut', facts)),
      h('.cbtns',
        h('button.cbtn.on', { onclick: () => { m.close(); Store.openDm(u.id) } }, 'Написать'),
        dm && h('button.cbtn', { onclick: () => { m.close(); Calls.start(dm.id) } }, 'Позвонить')),
      u.id !== Store.meId && h('.row', h('.grow', h('.semi', { style: { fontSize: '14px' } }, 'Кент'),
        h('.small.mut', 'Кенты видят твои споты и получают «Позвать на спот»')), sw(this.isKent(u.id), (v) => this.setKent(u.id, v))),
      dm && h('.row', alias, h('button.btn.ghost.small', { onclick: async () => {
        try { await Api.call('POST', `/chats/${dm.id}/settings`, { body: { alias: alias.value.trim() } }); await Store.refresh(); UI.toast('Сохранено') } catch (e) { UI.toast(e.message) }
      } }, 'Сохранить')),
      u.id !== Store.meId && h('.row', { style: { justifyContent: 'space-between' } }, blockBtn,
        h('button.link.red', { onclick: async () => {
          m.close()
          try { await Api.call('POST', `/kents/${u.id}/remove`) } catch (e) {}
          Store.refresh()
        } }, 'Удалить из кентов')),
      h('.foot', h('span'), h('button.btn.ghost.small', { onclick: () => m.close() }, 'Готово')))
    try { blocked = (await Api.call('GET', '/blocks')).some((x) => x.id === u.id) } catch (e) {}
    const paint = () => { blockBtn.textContent = blocked ? 'Разблокировать' : 'Заблокировать' }
    paint()
    blockBtn.onclick = async () => {
      try { await Api.call(blocked ? 'DELETE' : 'POST', `/blocks/${u.id}`) } catch (e) { UI.toast(e.message); return }
      blocked = !blocked
      paint()
    }
  },

  forward(msg) {
    const list = h('.list', Store.chats.map((c) => {
      const o = Store.isGroup(c) ? null : Store.other(c)
      return h('.person', { onclick: () => {
        Store.sendRaw({ type: 'send', chat_id: c.id, forward: msg.id, client_id: uuid() })
        m.close()
        UI.toast('Переслано в «' + c.title + '»')
      } }, avatar(c.title, o ? o.avatar : c.avatar, 38), h('.semi.ell', c.title))
    }))
    const m = UI.modal(h('h3', 'Переслать'), list, h('.foot', h('span'), h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена')))
  },

  // ---------- profile ----------

  profile() {
    const me = Store.me
    const name = field('Имя', me.name)
    const username = field('@юзернейм', me.username)
    const about = field('О себе', me.about || '')
    const birthday = field('Дата рождения ДД.ММ.ГГГГ', me.birthday || '')
    birthday.oninput = () => {
      // digits only, dots added by themselves
      const d = birthday.value.replace(/\D/g, '').slice(0, 8)
      birthday.value = [d.slice(0, 2), d.slice(2, 4), d.slice(4)].filter(Boolean).join('.')
    }
    const gender = h('select.field', [['', '—'], ['m', 'Парень'], ['f', 'Девушка']].map(([v, t]) => h('option', { value: v, selected: me.gender === v }, t)))
    const uhint = h('.small')
    let timer
    username.oninput = () => {
      clearTimeout(timer)
      const v = username.value.trim().replace(/^@/, '')
      uhint.textContent = ''
      if (!v || v === me.username) return
      timer = setTimeout(async () => {
        try {
          const j = await Api.call('GET', '/username/' + encodeURIComponent(v))
          uhint.textContent = j.free ? '✓ свободен' : (j.error || 'занят')
          uhint.className = 'small ' + (j.free ? 'green' : 'red')
        } catch (e) {}
      }, 300)
    }
    const avInput = h('input.hidden', { type: 'file', accept: 'image/*' })
    const avBox = h('div')
    const drawAv = () => avBox.replaceChildren(avatar(Store.me.name, Store.me.avatar, 72))
    drawAv()
    avInput.onchange = async () => {
      const f = avInput.files[0]
      if (!f) return
      try {
        const j = await toJpeg(f, 512, true)
        Store.me = await Api.call('POST', '/me/avatar', { body: j.blob })
        drawAv(); Store.changed()
      } catch (e) { UI.toast(e.message) }
    }
    const auto = h('span')
    if (window.native) window.native.autostartGet().then((on) => auto.replaceChildren(sw(on, (v) => window.native.autostart(v))))
    const m = UI.modal(
      h('.row', { style: { gap: '14px' } }, avBox,
        h('.col', { style: { gap: '4px' } },
          h('button.link', { style: { textAlign: 'left' }, onclick: () => avInput.click() }, 'Выбрать фото…'),
          me.avatar && h('button.link.red', { style: { textAlign: 'left' }, onclick: async () => {
            try { Store.me = await Api.call('DELETE', '/me/avatar'); drawAv(); Store.changed() } catch (e) { UI.toast(e.message) }
          } }, 'Убрать фото'))),
      avInput, name, username, uhint, about, h('.row', birthday, gender),
      h('button.btn.small', { onclick: async () => {
        try {
          Store.me = await Api.call('POST', '/me', { body: { name: name.value.trim(), username: username.value.trim().replace(/^@/, ''), about: about.value.trim(), birthday: birthday.value, gender: gender.value } })
          Store.changed()
          UI.toast('Профиль сохранён')
        } catch (e) { UI.toast(e.message) }
      } }, 'Сохранить профиль'),
      h('.row', h('span.grow', 'Показывать «в сети»'), sw(Store.showOnline, (v) => { Store.showOnline = v })),
      window.native && window.native.platform === 'win32' && h('.row', h('span.grow', 'Запускать вместе с Windows'), auto),
      h('.row', { style: { flexWrap: 'wrap' } },
        h('button.btn.ghost.small', { onclick: () => { m.close(); this.kents() } }, 'Кенты'),
        h('button.btn.ghost.small', { onclick: () => { m.close(); this.blocks() } }, 'Заблокированные'),
        h('button.btn.ghost.small', { onclick: () => { m.close(); this.changePassword() } }, 'Сменить пароль')),
      h('.row', { style: { justifyContent: 'space-between' } },
        h('button.link.red', { onclick: () => { m.close(); Store.logout() } }, 'Выйти на этом компьютере'),
        h('button.link', { onclick: async () => {
          try {
            const j = await Api.call('POST', '/me/logout_all')
            if (j.token) { Api.token = j.token; localStorage.setItem(Store.tokenKey, j.token); UI.toast('Остальные устройства вышли') }
          } catch (e) { UI.toast(e.message) }
        } }, 'Выйти на других')),
      h('.foot', h('span.small.mut', 'JustTalk ' + APP_VERSION), h('button.btn.ghost.small', { onclick: () => m.close() }, 'Готово')))
  },

  changePassword() {
    const old = field('Старый пароль', '', { secure: true })
    const p1 = field('Новый пароль, минимум 6', '', { secure: true })
    const p2 = field('Ещё раз', '', { secure: true })
    const err = h('.small.red')
    const m = UI.modal(h('h3', 'Сменить пароль'), old, p1, p2, err,
      h('.foot', h('button.btn.ghost.small', { onclick: () => { m.close(); this.profile() } }, 'Назад'),
        h('button.btn.small', { onclick: async () => {
          if (p1.value !== p2.value) { err.textContent = 'Пароли не совпадают'; return }
          try { await Store.setPassword(p1.value, old.value); m.close(); UI.toast('Пароль изменён') } catch (e) { err.textContent = e.message }
        } }, 'Сохранить')))
  },

  kents() {
    const list = h('.list')
    const draw = () => list.replaceChildren(...Store.people.filter((u) => u.id !== Store.meId && !u.removed).map((u) =>
      this.personRow(u, null, sw(!!u.kent, (v) => this.setKent(u.id, v)))))
    draw()
    if (!Store.people.length) list.append(h('.empty', 'Пока никого'))
    const m = UI.modal(h('h3', 'Кенты'),
      h('.small.mut', 'Кенты видят твои споты (кроме «Только я»), твоё «Я тут» и получают «Позвать на спот». Остальные — просто знакомые.'),
      list, h('.foot', h('button.btn.ghost.small', { onclick: () => { m.close(); this.profile() } }, 'Назад'), h('span')))
  },

  async blocks() {
    const list = h('.list', h('.spinner', { style: { margin: '10px auto' } }))
    const m = UI.modal(h('h3', 'Заблокированные'), list,
      h('.foot', h('button.btn.ghost.small', { onclick: () => { m.close(); this.profile() } }, 'Назад'), h('span')))
    const draw = async () => {
      let users = []
      try { users = await Api.call('GET', '/blocks') } catch (e) {}
      list.replaceChildren(...users.map((u) => this.personRow(u, null, h('button.link', { onclick: async () => {
        try { await Api.call('DELETE', `/blocks/${u.id}`) } catch (e) {}
        draw()
      } }, 'Разблокировать'))))
      if (!users.length) list.append(h('.small.mut', 'Никого'))
    }
    draw()
  },
}

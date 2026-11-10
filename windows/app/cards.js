// Live cards of 0.15 Beta inside the message bubble (poll, gather, split, watch, call) and the sheets that create them.

const Cards = {
  /** POST that returns the new card state; my copy is updated right away (the event comes only to people online) */
  async act(path, body = {}) {
    try {
      const j = await Api.call('POST', path, { body })
      if (j && j.type && j.id) {
        for (const cid in Store.messages) {
          Store.messages[cid] = Store.messages[cid].map((m) => (m.card && m.card.type === j.type && m.card.id === j.id ? { ...m, card: j } : m))
        }
        Store.changed()
      }
      return true
    } catch (e) {
      UI.toast(e.message)
      return false
    }
  },

  view(m, mine) {
    const c = m.card || {}
    const el = h('.card')
    if (c.gone) {
      el.append(h('.cs', { style: { opacity: 0.7 } }, cardLabel(c.type) + ' · удалено'))
      return el
    }
    const f = { poll: this.poll, gather: this.gather, split: this.split, watch: this.watch, call: this.call }[c.type]
    if (f) f.call(this, c, el, mine, m)
    else el.append(h('.text', m.text))
    return el
  },

  head(ic, title) { return h('.ch', icon(ic), title) },

  btn(title, on, fn) { return h('button.cbtn' + (on ? '.on' : ''), { onclick: fn }, title) },

  poll(c, el) {
    const options = c.options || []
    const my = c.mine || []
    const total = Math.max(options.reduce((n, o) => n + (o.votes || 0), 0), 1)
    el.append(this.head('chart', (c.anon ? 'Анонимный опрос' : 'Опрос') + (c.multi ? ' · несколько ответов' : '') + (c.closed ? ' · закрыт' : '')),
      h('.ct', c.question || ''))
    options.forEach((o, i) => {
      const picked = my.includes(i)
      el.append(h('button.popt' + (picked ? '.on' : ''), {
        disabled: !!c.closed,
        title: (o.voters || []).map((id) => Store.name(id)).join(', '),
        onclick: () => {
          let next = c.multi ? (picked ? my.filter((x) => x !== i) : my.concat(i)) : (picked ? [] : [i])
          next = next.sort((a, b) => a - b)
          this.act(`/polls/${c.id}/vote`, { options: next })
        },
      }, h('.fill', { style: { width: (100 * (o.votes || 0) / total) + '%' } }),
      h('span', picked ? '●' : '○'), h('span.ell', o.text || ''), h('span.n', o.votes || 0)))
    })
    el.append(h('.row', h('.cm.grow', 'Проголосовали: ' + (c.voters || 0)),
      c.owner_id === Store.meId && !c.closed && h('button.small.semi', { onclick: () => this.act(`/polls/${c.id}/close`) }, 'Закрыть опрос')))
  },

  gather(c, el) {
    const rsvp = c.rsvp || {}
    const my = ['yes', 'maybe', 'no'].find((k) => (rsvp[k] || []).includes(Store.meId))
    el.append(this.head('cal', c.cancelled ? 'Сбор отменён' : 'Сбор'),
      h('.ct', { style: c.cancelled ? { textDecoration: 'line-through' } : {} }, c.title || ''),
      h('.cs.row', icon('clock', 13), gatherTime(c.at || 0)))
    if (c.spot) {
      el.append(h('button.cs.row.semi', { onclick: () => { Store.spotFocus = c.spot.id; Store.tab = 'spots'; Store.changed() } }, icon('pin', 13), c.spot.title || 'Спот'))
    } else if (c.place) {
      el.append(h('.cs.row', icon('pin', 13), c.place))
    }
    if (c.note) el.append(h('.cs', { style: { opacity: 0.85 } }, c.note))
    if (c.cancelled) return
    el.append(h('.cbtns', [['yes', 'Иду'], ['maybe', 'Может'], ['no', 'Не иду']].map(([k, t]) =>
      this.btn(t + ' ' + (rsvp[k] || []).length, my === k, () => this.act(`/gathers/${c.id}/rsvp`, { status: my === k ? '' : k })))))
    const going = (rsvp.yes || []).map((id) => Store.name(id))
    if (going.length) el.append(h('.cm', 'Идут: ' + going.join(', ')))
    if (c.owner_id === Store.meId) el.append(h('button.small.semi', { style: { alignSelf: 'flex-start', opacity: 0.8 }, onclick: () => this.act(`/gathers/${c.id}/cancel`) }, 'Отменить сбор'))
  },

  split(c, el) {
    const shares = c.shares || []
    const owner = c.owner_id === Store.meId
    const paid = shares.filter((s) => s.paid).length
    el.append(this.head('rub', c.closed ? 'Все скинулись' : `Скидываемся · ${paid} из ${shares.length}`),
      h('.row', h('.ct.grow', c.title || ''), h('.ct.bold', (c.total || 0) + ' ₽')))
    if (c.details) {
      el.append(h('button.cs.row', { title: 'Скопировать', onclick: () => { navigator.clipboard.writeText(c.details); UI.toast('Скопировано') } }, icon('copy', 13), c.details))
    }
    for (const s of shares) {
      const can = s.user_id === Store.meId || owner
      el.append(h('button.share' + (s.paid ? '.done' : ''), {
        disabled: !can,
        onclick: () => this.act(`/splits/${c.id}/paid`, { user_id: s.user_id, paid: !s.paid }),
      }, h('span', s.paid ? '✓' : '○'), h('span.grow', s.user_id === Store.meId ? 'Я' : Store.name(s.user_id)), h('span.semi.small', (s.amount || 0) + ' ₽')))
    }
  },

  watch(c, el, mine, m) {
    const viewers = c.viewers || []
    el.append(this.head('film', 'Смотрим вместе · ' + sourceLabel(c.source)))
    if (c.source === 'youtube' && c.src) el.append(h('.ytthumb', { style: { backgroundImage: `url("https://img.youtube.com/vi/${c.src}/hqdefault.jpg")` } }))
    if (c.title) el.append(h('.cs.semi', { style: { fontSize: '15px' } }, c.title))
    if (c.ended) {
      el.append(h('.cs', { style: { opacity: 0.7 } }, 'Просмотр закончен'))
      return
    }
    if (viewers.length) el.append(h('.cm', 'Смотрят: ' + viewers.map((id) => Store.name(id)).join(', ')))
    el.append(h('.cbtns', this.btn('Смотреть', true, () => Watch.open(c.id, m.chat_id, c)),
      c.owner_id === Store.meId && this.btn('Закончить', false, () => this.act(`/watch/${c.id}/end`))))
  },

  call(c, el, mine) {
    const status = c.status || ''
    const inCall = c.in || []
    const missed = status === 'missed'
    el.append(h('.callcard',
      h('.round', { style: missed ? { color: 'var(--red)' } : {} }, icon(missed ? 'hangup' : 'phone', 16)),
      h('.grow',
        h('.cs.semi', { style: { fontSize: '15px' } }, status === 'active' ? 'Идёт звонок' : missed ? (mine ? 'Без ответа' : 'Пропущенный звонок') : 'Звонок'),
        h('.cm', status === 'active' ? 'В звонке: ' + inCall.length : status === 'ended' ? durationLabel((c.duration || 0) * 1000) : '')),
      status === 'active' && Calls.callId !== c.id && h('button.cbtn.on', { style: { flex: 'none', width: '80px' }, onclick: () => Calls.join(c.id, c.chat_id) }, 'Войти')))
  },

  // ---------- creating cards ----------

  sheet(title, ok, build, action) {
    const err = h('.small.red')
    const okBtn = h('button.btn.small', ok)
    const body = h('.col')
    const m = UI.modal(h('h3', title), body, err, h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'), okBtn))
    const check = build(body, () => { okBtn.disabled = !check() })
    okBtn.disabled = !check()
    okBtn.onclick = async () => {
      okBtn.disabled = true
      okBtn.textContent = '…'
      if (await action(err)) m.close()
      okBtn.textContent = ok
      okBtn.disabled = !check()
    }
    return m
  },

  newPoll(chatId) {
    let q, opts, multi = false, anon = false
    this.sheet('Опрос', 'Создать', (body, recheck) => {
      q = field('Вопрос', '', { attrs: { oninput: recheck } })
      opts = h('.col')
      const addOpt = () => {
        opts.append(field('Вариант ' + (opts.children.length + 1), '', { attrs: { oninput: recheck } }))
        more.classList.toggle('hidden', opts.children.length >= 10)
      }
      const more = h('button.link', { style: { alignSelf: 'flex-start' }, onclick: addOpt }, '+ Вариант')
      body.append(q, opts, more,
        h('.row', h('span.grow', 'Несколько ответов'), sw(false, (v) => { multi = v })),
        h('.row', h('span.grow', 'Анонимный'), sw(false, (v) => { anon = v })))
      addOpt(); addOpt()
      return () => q.value.trim() && filled().length >= 2
    }, () => this.act(`/chats/${chatId}/poll`, { question: q.value.trim(), options: filled(), multi, anon, client_id: uuid() }))
    const filled = () => [...opts.children].map((i) => i.value.trim()).filter(Boolean)
  },

  async newGather(chatId) {
    if (!Store.spots.length) await Store.loadSpots()
    let title, when, place, spot, note
    const at = new Date(Date.now() + 2 * 3600 * 1000)
    const local = new Date(at.getTime() - at.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
    this.sheet('Сбор', 'Позвать', (body, recheck) => {
      title = field('Что делаем? Например, «Катаем на Воробьях»', '', { attrs: { oninput: recheck } })
      when = h('input.field', { type: 'datetime-local', value: local, style: { colorScheme: 'dark' }, oninput: recheck })
      spot = h('select.field', h('option', { value: 0 }, 'Без спота'), Store.spots.map((s) => h('option', { value: s.id }, s.title)))
      place = field('Где (необязательно)')
      spot.onchange = () => place.classList.toggle('hidden', spot.value !== '0')
      note = field('Заметка (необязательно)')
      body.append(title, h('.small.mut', 'Когда'), when, h('.small.mut', 'Спот'), spot, place, note)
      return () => title.value.trim() && when.value && new Date(when.value).getTime() > Date.now()
    }, () => this.act(`/chats/${chatId}/gather`, {
      title: title.value.trim(), at: Math.floor(new Date(when.value).getTime() / 1000), place: place.value.trim(),
      spot_id: Number(spot.value), note: note.value.trim(), client_id: uuid(),
    }))
  },

  newSplit(chatId) {
    const members = (Store.chat(chatId) || {}).members || []
    const picked = new Set(members.map((u) => u.id))
    let title, total, details, label
    const sum = () => Number((total.value.match(/\d/g) || []).join('')) || 0
    this.sheet('Скидываемся', 'Создать', (body, recheck) => {
      const re = () => {
        const each = picked.size ? Math.floor(sum() / picked.size) : 0
        label.textContent = 'Кто скидывается' + (each > 0 ? ` · по ${each} ₽` : '')
        recheck()
      }
      title = field('На что? Например, «Пицца»', '', { attrs: { oninput: re } })
      total = field('Сумма, ₽', '', { attrs: { oninput: re } })
      details = field('Куда кидать: карта или телефон для СБП')
      label = h('.small.semi.mut')
      const list = h('.list', { style: { maxHeight: '180px' } }, members.map((u) => {
        const chk = h('span.check' + (picked.has(u.id) ? '.on' : ''))
        return h('.person', { style: { padding: '6px' }, onclick: () => { picked.has(u.id) ? picked.delete(u.id) : picked.add(u.id); chk.classList.toggle('on'); re() } },
          chk, h('span', u.id === Store.meId ? 'Я' : u.name))
      }))
      body.append(title, total, details, label, list)
      setTimeout(re)
      return () => title.value.trim() && sum() > 0 && picked.size > 0 && !(picked.size === 1 && picked.has(Store.meId))
    }, () => this.act(`/chats/${chatId}/split`, { title: title.value.trim(), total: sum(), user_ids: [...picked], details: details.value.trim(), client_id: uuid() }))
  },

  newWatch(chatId) {
    let link, title, file = null, status, fileBtn
    const input = h('input.hidden', { type: 'file', accept: 'video/*' })
    this.sheet('Смотрим вместе', 'Позвать смотреть', (body, recheck) => {
      link = field('Ссылка на YouTube или VK Видео')
      status = h('.small')
      title = field('Название (необязательно)')
      fileBtn = h('button.btn.ghost.small', { style: { alignSelf: 'flex-start' }, onclick: () => input.click() }, 'Или своё видео с компьютера…')
      link.oninput = () => {
        const p = parseVideoLink(link.value)
        status.textContent = !link.value ? '' : p ? '✓ ' + sourceLabel(p[0]) : 'Не понял ссылку: нужна ссылка на видео YouTube или VK'
        status.className = 'small ' + (p ? 'green' : 'red')
        recheck()
      }
      input.onchange = () => {
        file = input.files[0] || null
        if (file) { link.value = ''; status.textContent = '' }
        fileBtn.textContent = file ? 'Видео: ' + file.name : 'Или своё видео с компьютера…'
        recheck()
      }
      body.append(link, status, fileBtn, input, title, h('.small.mut', 'Видео до 1 ГБ. YouTube без VPN может тормозить'))
      return () => !!file || !!parseVideoLink(link.value)
    }, async (err) => {
      let source, src
      if (file) {
        err.className = 'small mut'
        err.textContent = 'Загружаю видео…'
        try {
          src = await Store.upload(file, (file.name.split('.').pop() || 'mp4').toLowerCase(), true)
        } catch (e) {
          err.className = 'small red'
          err.textContent = 'Не получилось загрузить: ' + e.message
          return false
        }
        source = 'file'
      } else {
        [source, src] = parseVideoLink(link.value)
      }
      const t = title.value.trim() || (file ? file.name.replace(/\.[^.]+$/, '') : '')
      return this.act(`/chats/${chatId}/watch`, { source, src, title: t, client_id: uuid() })
    })
  },
}

function sourceLabel(s) { return { youtube: 'YouTube', vk: 'VK Видео' }[s] || 'Видео' }

/** A YouTube or VK link -> [source, src for the server], like the phone's parseVideoLink. */
function parseVideoLink(link) {
  const s = (link || '').trim()
  let g = s.match(/(?:youtu\.be\/|youtube\.com\/(?:watch\?(?:.*&)?v=|shorts\/|live\/|embed\/))([A-Za-z0-9_-]{11})/)
  if (g) return ['youtube', g[1]]
  g = s.match(/video_ext\.php\?(?=.*oid=(-?\d+))(?=.*[?&]id=(\d+))(?:.*hash=([0-9a-f]+))?/)
  if (g) return ['vk', [g[1], g[2], g[3]].filter(Boolean).join('_')]
  g = s.match(/(?:vk\.com|vkvideo\.ru|vk\.ru)\/.*?video(-?\d+)_(\d+)/)
  if (g) {
    const hash = (s.match(/[?&]hash=([0-9a-f]+)/) || [])[1]
    return ['vk', [g[1], g[2], hash].filter(Boolean).join('_')]
  }
  return null
}

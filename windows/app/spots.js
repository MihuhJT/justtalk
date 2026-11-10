// «Споты» like the phone's Spots.kt, on Yandex map tiles (shown with Leaflet): filters, place a new spot by clicking the map,
// spot card with «Я тут» and «Позвать кентов».

const SPOT_KINDS = [['club', 'Клуб'], ['place', 'Место'], ['home', 'Квартира'], ['park', 'Парк'], ['cafe', 'Кафе'], ['study', 'Учёба']]
const SPOT_EMOJI = { club: '🎧', place: '📍', home: '🏠', park: '🌳', cafe: '☕', study: '📚' }
const kindLabel = (k) => (SPOT_KINDS.find((x) => x[0] === k) || [k, k])[1]

Object.assign(Store, {
  async loadSpots() {
    try { this.spots = await Api.call('GET', '/spots') } catch (e) { return }
    this.changed()
  },
})

class SpotsView {
  constructor() {
    this.filter = 'all'
    this.selected = null
    this.placing = false
    this.markers = new Map()
    this.mapEl = h('#map')
    this.bar = h('.spotbar')
    this.cardBox = h('div')
    this.el = h('div', { style: { position: 'relative', flex: 1 } }, this.mapEl, this.bar, this.cardBox)
  }

  mounted() {
    // Yandex tiles use the ellipsoid Mercator (EPSG:3395), not the web one
    this.map = L.map(this.mapEl, { crs: L.CRS.EPSG3395, zoomControl: false, attributionControl: true }).setView([55.751, 37.618], 11)
    L.control.zoom({ position: 'bottomright' }).addTo(this.map)
    this.map.attributionControl.setPrefix('')
    L.tileLayer('https://core-renderer-tiles.maps.yandex.net/tiles?l=map&x={x}&y={y}&z={z}&scale={s}&lang=ru_RU&theme=dark', {
      maxZoom: 19, s: Math.min(2, Math.ceil(devicePixelRatio || 1)), attribution: '© Яндекс Карты',
    }).addTo(this.map)
    this.map.on('click', (e) => {
      if (!this.placing) { this.select(null); return }
      this.placing = false
      this.paintBar()
      this.edit(null, e.latlng)
    })
    Store.loadSpots().then(() => this.focus())
    this.focus()
  }

  destroy() { if (this.map) this.map.remove() }

  update() {
    const sig = JSON.stringify(Store.spots) + this.filter + this.selected + this.placing
    if (sig === this.sig) { this.focus(); return }
    this.sig = sig
    this.paintBar()
    this.paintPins()
    this.paintCard()
    this.focus()
  }

  shown() {
    return Store.spots.filter((s) => this.filter === 'all' || (this.filter === 'mine' && s.owner_id === Store.meId) || s.kind === this.filter)
  }

  paintBar() {
    const kinds = [...new Set(Store.spots.map((s) => s.kind))].sort((a, b) => {
      const ia = SPOT_KINDS.findIndex((k) => k[0] === a), ib = SPOT_KINDS.findIndex((k) => k[0] === b)
      const x = ia < 0 ? 99 : ia, y = ib < 0 ? 99 : ib
      return x === y ? (a < b ? -1 : 1) : x - y
    })
    const chip = (id, t) => h('button.chip' + (this.filter === id ? '.on' : ''), { onclick: () => { this.filter = id; this.update() } }, t)
    this.bar.replaceChildren(chip('all', 'Все'), chip('mine', 'Мои'), ...kinds.map((k) => chip(k, kindLabel(k))), h('span.grow'),
      h('button.btn.small', { onclick: () => { this.placing = !this.placing; this.update() } }, this.placing ? 'Кликни по карте' : '+ Спот'))
    this.mapEl.style.cursor = this.placing ? 'crosshair' : ''
  }

  paintPins() {
    for (const m of this.markers.values()) m.remove()
    this.markers.clear()
    for (const s of this.shown()) {
      const html = `<div class="pin${this.selected === s.id ? ' on' : ''}"><div class="bub">${SPOT_EMOJI[s.kind] || '📍'}` +
        (s.here && s.here.length ? `<span class="here">${s.here.length}</span>` : '') + `</div><div class="lbl"></div></div>`
      const mk = L.marker([s.lat, s.lng], { icon: L.divIcon({ className: '', html, iconSize: [0, 0] }) }).addTo(this.map)
      mk.getElement().querySelector('.lbl').textContent = s.title
      mk.on('click', (e) => { L.DomEvent.stopPropagation(e); this.select(s.id) })
      this.markers.set(s.id, mk)
    }
  }

  select(id) { this.selected = id; this.update() }

  focus() {
    const id = Store.spotFocus
    if (!id || !this.map) return
    const s = Store.spots.find((x) => x.id === id)
    if (!s) {
      if (this.focusTried === id) { Store.spotFocus = null; UI.toast('Спот скрыт или удалён') }
      this.focusTried = id
      return
    }
    Store.spotFocus = null
    this.filter = 'all'
    this.map.setView([s.lat, s.lng], 15)
    this.select(id)
  }

  paintCard() {
    const s = Store.spots.find((x) => x.id === this.selected)
    if (!s) { this.cardBox.replaceChildren(); return }
    const here = s.here || []
    const imHere = here.some((x) => x.user_id === Store.meId)
    const canEdit = s.owner_id === Store.meId || isOwner(Store.me)
    const body = h('.col', { style: { gap: '8px' } })
    const actions = h('.cbtns',
      h('button.cbtn' + (imHere ? '' : '.on'), { onclick: async () => {
        try { await Api.call('POST', '/me/checkin', { body: { spot_id: imHere ? 0 : s.id } }) } catch (e) { UI.toast(e.message) }
        Store.loadSpots()
      } }, imHere ? 'Ухожу' : 'Я тут'),
      h('button.cbtn', { onclick: () => callBox() }, 'Позвать кентов'),
      h('button.round', { title: 'Маршрут в Яндекс Картах', onclick: () => window.native.openExternal(`https://yandex.ru/maps/?rtext=~${s.lat},${s.lng}&rtt=auto`) }, icon('route', 16)))
    const callBox = () => {
      const t = field(`Еду на «${s.title}», подтягивайся!`)
      actions.replaceWith(h('.col', t, h('.row', { style: { justifyContent: 'space-between' } },
        h('button.btn.ghost.small', { onclick: () => this.paintCard() }, 'Отмена'),
        h('button.btn.small', { onclick: async () => {
          try {
            const j = await Api.call('POST', `/spots/${s.id}/call`, { body: { text: t.value.trim() } })
            UI.toast('Позвал кентов: ' + (j.sent || 0))
            Store.refresh()
          } catch (e) { UI.toast(e.message) }
          this.paintCard()
        } }, 'Отправить всем кентам'))))
      t.focus()
    }
    add(body, [
      h('.row', h('span.small.semi.acc', kindLabel(s.kind)), s.vis === 'me' && h('span.small.mut.row', icon('lock', 11), 'Только я'), h('span.grow'),
        h('button.mut', { onclick: () => this.select(null) }, icon('x', 16))),
      h('.bold', { style: { fontSize: '18px' } }, s.title),
      s.about && h('div', { style: { fontSize: '13px' } }, s.about),
      (s.photos || []).length > 0 && h('.photos', s.photos.map((p) => h('div', { style: { backgroundImage: `url("${Api.media(p)}")` }, onclick: () => UI.viewer(p) }))),
      h('.small.mut', 'Добавил(а) ' + (s.owner_id === Store.meId ? 'ты' : s.owner_name)),
      here.length > 0 && h('.small.green.semi', 'Сейчас тут: ' + here.map((x) => x.name).join(', ')),
      actions,
      canEdit && h('.row', { style: { justifyContent: 'space-between' } },
        h('button.link', { onclick: () => this.edit(s, L.latLng(s.lat, s.lng)) }, 'Изменить'),
        h('button.link.red', { onclick: async () => {
          try { await Api.call('DELETE', `/spots/${s.id}`) } catch (e) { UI.toast(e.message) }
          this.selected = null
          Store.loadSpots()
        } }, 'Удалить'))])
    this.cardBox.replaceChildren(h('.spotcard', body))
  }

  edit(spot, point) {
    const known = !spot || SPOT_KINDS.some((k) => k[0] === spot.kind)
    const title = field('Название', spot ? spot.title : '')
    const kind = h('select.field', SPOT_KINDS.map(([k, t]) => h('option', { value: k, selected: spot ? spot.kind === k : k === 'place' }, t)),
      h('option', { value: 'custom', selected: !known }, 'Своё…'))
    const custom = field('Своя категория', known ? '' : spot.kind)
    custom.classList.toggle('hidden', known)
    kind.onchange = () => custom.classList.toggle('hidden', kind.value !== 'custom')
    const about = field('Описание (необязательно)', spot ? spot.about : '')
    let photos = spot ? [...(spot.photos || [])] : []
    let vis = spot ? spot.vis : 'kents'
    const thumbs = h('.thumbs')
    const input = h('input.hidden', { type: 'file', accept: 'image/*', multiple: true })
    const drawThumbs = () => {
      thumbs.replaceChildren(...photos.map((p) => h('.t', { style: { backgroundImage: `url("${Api.media(p)}")` } },
        h('button', { onclick: () => { photos = photos.filter((x) => x !== p); drawThumbs() } }, '✕'))),
      photos.length < 5 ? h('button.t', { title: 'Добавить фото', onclick: () => input.click() }, icon('plus', 18)) : '')
    }
    input.onchange = async () => {
      for (const f of [...input.files].slice(0, 5 - photos.length)) {
        try {
          const j = await toJpeg(f, 1600)
          photos.push(await Store.upload(j.blob, 'jpg'))
          drawThumbs()
        } catch (e) { UI.toast(e.message) }
      }
      input.value = ''
    }
    drawThumbs()
    const err = h('.small.red')
    const save = h('button.btn.small', 'Сохранить')
    const m = UI.modal(h('h3', spot ? 'Спот' : 'Новый спот'), title, kind, custom, about, thumbs, input,
      h('.small.mut', 'Кто видит'), seg([['kents', 'Кенты'], ['me', 'Только я']], vis, (v) => { vis = v }),
      h('.small.mut', 'Кенты — те, кого ты отметил в Профиль → Кенты'), err,
      h('.foot', h('button.btn.ghost.small', { onclick: () => m.close() }, 'Отмена'), save))
    save.onclick = async () => {
      if (!title.value.trim()) { err.textContent = 'Нужно название'; return }
      save.disabled = true
      const k = kind.value === 'custom' ? custom.value.trim() : kind.value
      const body = { title: title.value.trim(), kind: k || 'place', lat: point.lat, lng: point.lng, about: about.value.trim(), photos, vis }
      try {
        if (spot) await Api.call('PUT', `/spots/${spot.id}`, { body })
        else await Api.call('POST', '/spots', { body })
        m.close()
        Store.loadSpots()
      } catch (e) { err.textContent = e.message }
      save.disabled = false
    }
  }
}

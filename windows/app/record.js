// Voice messages (up to 3 min, loudness bars as digits 0-9 like the phone) and video circles (square, up to 30 s).
// MediaRecorder picks MP4 (AAC / H.264) when Chromium can, so the Mac and phones play them; WebM otherwise.

function pickType(list) {
  for (const [type, ext] of list) if (window.MediaRecorder && MediaRecorder.isTypeSupported(type)) return [type, ext]
  return ['', 'webm']
}

const VOICE_TYPES = [['audio/mp4;codecs=mp4a.40.2', 'm4a'], ['audio/mp4', 'm4a'], ['audio/webm;codecs=opus', 'webm'], ['audio/ogg;codecs=opus', 'ogg']]
const CIRCLE_TYPES = [['video/mp4;codecs=avc1.42E01E,mp4a.40.2', 'mp4'], ['video/mp4;codecs=avc1,mp4a.40.2', 'mp4'], ['video/mp4', 'mp4'], ['video/webm;codecs=vp8,opus', 'webm']]

class VoiceRecorder {
  constructor() {
    this.onTick = () => {}
    this.onLimit = () => {}
    this.levels = []
  }

  async start() {
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true } })
    const [type, ext] = pickType(VOICE_TYPES)
    this.ext = ext
    this.rec = new MediaRecorder(this.stream, type ? { mimeType: type, audioBitsPerSecond: 48000 } : {})
    this.chunks = []
    this.rec.ondataavailable = (e) => { if (e.data.size) this.chunks.push(e.data) }
    this.ctx = new AudioContext()
    const an = this.ctx.createAnalyser()
    an.fftSize = 2048
    this.ctx.createMediaStreamSource(this.stream).connect(an)
    const buf = new Float32Array(an.fftSize)
    this.began = Date.now()
    this.rec.start(250)
    this.timer = setInterval(() => {
      an.getFloatTimeDomainData(buf)
      let sum = 0
      for (const v of buf) sum += v * v
      // dBFS like AVAudioRecorder.averagePower
      this.levels.push(20 * Math.log10(Math.sqrt(sum / buf.length) || 1e-6))
      const s = (Date.now() - this.began) / 1000
      this.onTick(s)
      if (s >= 180) this.onLimit()
    }, 100)
  }

  cancel() { this.stop(false) }

  /** {blob, ext, ms, wave} or null when cancelled / too short */
  stop(send) {
    clearInterval(this.timer)
    const ms = Date.now() - this.began
    return new Promise((resolve) => {
      const done = () => {
        this.stream.getTracks().forEach((t) => t.stop())
        this.ctx.close()
        if (!send || ms < 700) { resolve(null); return }
        resolve({ blob: new Blob(this.chunks, { type: this.rec.mimeType }), ext: this.ext, ms, wave: this.wave() })
      }
      if (this.rec.state === 'inactive') done()
      else { this.rec.onstop = done; this.rec.stop() }
    })
  }

  /** 40 bars, loudness in dB mapped to 0-9 */
  wave() {
    const lv = this.levels
    if (!lv.length) return ''
    const n = 40
    let out = ''
    for (let i = 0; i < n; i++) {
      const a = Math.floor(i * lv.length / n)
      const b = Math.max(a + 1, Math.floor((i + 1) * lv.length / n))
      const v = Math.max(...lv.slice(a, Math.min(b, lv.length)), -60)
      out += String(Math.floor(Math.max(0, Math.min(9, (v + 50) / 50 * 9))))
    }
    return out
  }
}

const Recorder = {
  async sendVoice(chatId, v, replyTo) {
    try {
      const name = await Store.upload(v.blob, v.ext)
      await Api.call('POST', `/chats/${chatId}/media`, { body: { kind: 'voice', media: name, duration: v.ms, wave: v.wave, client_id: uuid(), reply_to: replyTo } })
    } catch (e) { UI.toast(e.message) }
  },

  viewCircle(m) {
    Player.stop()
    const v = h('video', { src: Api.media(m.media), autoplay: true, controls: false, onclick: () => (v.paused ? v.play() : v.pause()) })
    const box = UI.modal(h('.circleview', v), h('.foot', h('span.small.mut', durationLabel(m.duration || 0)),
      h('button.btn.ghost.small', { onclick: () => box.close() }, 'Закрыть')))
    box.el.style.width = 'auto'
    box.bg.addEventListener('close', () => v.pause())
  },

  /** camera preview in a circle, record up to 30 s, then send */
  async circle(chatId) {
    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 } }, audio: { echoCancellation: true } })
    } catch (e) {
      UI.toast('Нет доступа к камере или микрофону')
      return
    }
    const SIDE = 480
    const cam = h('video', { muted: true, autoplay: true, playsInline: true })
    cam.srcObject = stream
    const canvas = h('canvas', { width: SIDE, height: SIDE })
    const g = canvas.getContext('2d')
    // centre square of the camera, mirrored like a selfie
    let drawing = true
    const draw = () => {
      if (!drawing) return
      const w = cam.videoWidth, hh = cam.videoHeight
      if (w && hh) {
        const s = Math.min(w, hh)
        g.save()
        g.translate(SIDE, 0)
        g.scale(-1, 1)
        g.drawImage(cam, (w - s) / 2, (hh - s) / 2, s, s, 0, 0, SIDE, SIDE)
        g.restore()
      }
      requestAnimationFrame(draw)
    }
    draw()
    const status = h('.small.mut', { style: { textAlign: 'center' } }, 'До 30 секунд')
    const recBtn = h('button.btn', 'Записать')
    let rec = null, chunks = [], began = 0, timer = null, thumb = null, ext = 'mp4'
    const finish = () => {
      drawing = false
      clearInterval(timer)
      stream.getTracks().forEach((t) => t.stop())
    }
    const m = UI.modal(h('h3', 'Кружок'), h('.cam', canvas), status,
      h('.foot', h('button.btn.ghost.small', { onclick: () => { if (rec) rec.onstop = null; m.close() } }, 'Отмена'), recBtn))
    m.bg.addEventListener('close', () => { if (rec && rec.state !== 'inactive') { rec.onstop = null; rec.stop() } finish() })
    const stop = () => {
      if (!rec || rec.state === 'inactive') return
      recBtn.disabled = true
      status.textContent = 'Отправляю кружок…'
      rec.stop()
    }
    recBtn.onclick = () => {
      if (rec) { stop(); return }
      const [type, e] = pickType(CIRCLE_TYPES)
      ext = e
      const out = canvas.captureStream(30)
      for (const t of stream.getAudioTracks()) out.addTrack(t)
      rec = new MediaRecorder(out, type ? { mimeType: type, videoBitsPerSecond: 1500000 } : {})
      rec.ondataavailable = (ev) => { if (ev.data.size) chunks.push(ev.data) }
      rec.onstop = async () => {
        const ms = Date.now() - began
        const blob = new Blob(chunks, { type: rec.mimeType })
        finish()
        try {
          const name = await Store.upload(blob, ext)
          let t = ''
          if (thumb) t = await Store.upload(thumb, 'jpg')
          await Api.call('POST', `/chats/${chatId}/media`, { body: { kind: 'circle', media: name, duration: ms, thumb: t, client_id: uuid() } })
          m.close()
        } catch (err) {
          status.textContent = 'Не получилось отправить: ' + err.message
        }
      }
      canvas.toBlob((b) => { thumb = b }, 'image/jpeg', 0.85)
      began = Date.now()
      rec.start(250)
      recBtn.textContent = 'Отправить'
      timer = setInterval(() => {
        const s = (Date.now() - began) / 1000
        status.textContent = '● ' + durationLabel(s * 1000) + ' / 0:30'
        if (s >= 30) stop()
      }, 200)
    }
  },
}

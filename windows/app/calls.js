// Voice calls like the phone's Calls.kt: signaling over the main socket ("call_*" events),
// audio over /call — 20 ms Opus packets at 16 kHz, each prefixed with a codec byte (1 = Opus, 2 = raw 8 kHz PCM).
// The server prefixes incoming frames with the sender's 4-byte id.

const RATE = 16000
const FRAME = 320
const CODEC_OPUS = 1
const CODEC_PCM8 = 2

const Calls = {
  callId: null,
  chatId: null,
  muted: false,
  since: null,
  /** someone is calling me: {id, chat, name} */
  ring: null,
  /** call id, or -chatId for a new call, that this computer asked to join */
  pending: null,
  audio: null,
  ringer: null,

  async start(chatId) {
    if (this.callId) return
    if (!(await this.askMic())) return
    this.pending = -chatId
    Store.sendRaw({ type: 'call_start', chat_id: chatId })
  },

  async join(id, chatId) {
    if (this.callId === id) return
    if (this.callId) this.leave()
    if (!(await this.askMic())) return
    this.pending = id
    this.stopRinging()
    Store.sendRaw({ type: 'call_join', call_id: id })
  },

  decline() {
    if (!this.ring) return
    Store.sendRaw({ type: 'call_decline', call_id: this.ring.id })
    this.stopRinging()
  },

  leave() {
    if (!this.callId) return
    Store.sendRaw({ type: 'call_leave', call_id: this.callId })
    this.finish()
  },

  toggleMute() {
    if (!this.callId) return
    this.muted = !this.muted
    if (this.audio) this.audio.muted = this.muted
    Store.sendRaw({ type: 'call_mute', call_id: this.callId, muted: this.muted })
    this.render()
  },

  handle(ev) {
    const id = ev.call_id || 0
    const chat = ev.chat_id || 0
    switch (ev.type) {
      case 'call_ring': {
        if (this.callId) return
        const c = Store.chat(chat)
        this.ring = { id, chat, name: Store.isGroup(c) ? c.title : ev.from_name || 'Кент' }
        if (!(c && c.muted)) this.startRinging(ev.from_name || 'Кент')
        break
      }
      case 'call_joined':
        if (this.pending !== id && this.pending !== -chat) return
        this.pending = null
        this.callId = id
        this.chatId = chat
        this.since = Date.now()
        this.muted = false
        if (this.audio) this.audio.stop()
        this.audio = new CallAudio(id)
        this.audio.start()
        break
      case 'call_taken':
        if (this.ring && this.ring.id === id && this.pending !== id) this.stopRinging()
        break
      case 'call_end':
        if (this.ring && this.ring.id === id) this.stopRinging()
        if (this.callId === id) this.finish()
        break
    }
    this.render()
  },

  finish() {
    if (this.audio) this.audio.stop()
    this.audio = null
    this.callId = null
    this.chatId = null
    this.since = null
    this.muted = false
    this.render()
    Store.changed()
  },

  async askMic() {
    try {
      const s = await navigator.mediaDevices.getUserMedia({ audio: true })
      s.getTracks().forEach((t) => t.stop())
      return true
    } catch (e) {
      UI.toast('Нет доступа к микрофону: Параметры → Конфиденциальность → Микрофон')
      return false
    }
  },

  /** two soft beeps every 2 s, made right here: no sound files */
  startRinging(name) {
    this.stopRinging(true)
    const ctx = new AudioContext()
    const beep = () => {
      for (const at of [0, 0.35]) {
        const o = ctx.createOscillator()
        const g = ctx.createGain()
        o.frequency.value = 660
        g.gain.setValueAtTime(0, ctx.currentTime + at)
        g.gain.linearRampToValueAtTime(0.18, ctx.currentTime + at + 0.03)
        g.gain.linearRampToValueAtTime(0, ctx.currentTime + at + 0.25)
        o.connect(g).connect(ctx.destination)
        o.start(ctx.currentTime + at)
        o.stop(ctx.currentTime + at + 0.3)
      }
    }
    beep()
    this.ringer = { ctx, timer: setInterval(beep, 2000) }
    if (!Store.active) {
      UI.notify('Звонок', name + ' звонит', this.ring ? this.ring.chat : 0)
      window.native && window.native.show()
    }
  },

  stopRinging(keepRing) {
    if (this.ringer) {
      clearInterval(this.ringer.timer)
      this.ringer.ctx.close()
      this.ringer = null
    }
    if (!keepRing) this.ring = null
    this.render()
  },

  // ---------- top panel ----------

  render() {
    const box = document.getElementById('calls')
    if (!box) return
    clearInterval(this.ticker)
    const parts = []
    if (this.ring && Store.me) {
      const r = this.ring
      const c = Store.chat(r.chat)
      const photo = c ? (Store.isGroup(c) ? c.avatar : (Store.other(c) || {}).avatar) : ''
      parts.push(h('.ring', avatar(r.name, photo, 40),
        h('.grow', h('.semi', r.name), h('.small.mut', 'Звонит…')),
        h('button.round.red', { title: 'Отклонить', onclick: () => this.decline() }, icon('hangup', 16)),
        h('button.round.green', { title: 'Ответить', onclick: () => this.join(r.id, r.chat) }, icon('phone', 16))))
    }
    if (this.chatId && Store.me) {
      const c = Store.chat(this.chatId)
      const time = h('span.small.mut', { style: { fontVariantNumeric: 'tabular-nums' } })
      const tick = () => { time.textContent = this.since ? durationLabel(Date.now() - this.since) : '' }
      tick()
      this.ticker = setInterval(tick, 1000)
      parts.push(h('.incall', h('span.green', icon('phone', 16)), h('.semi.ell', c ? c.title : 'Звонок'), time, h('span.grow'),
        h('button.round' + (this.muted ? '.red' : ''), { title: this.muted ? 'Включить микрофон' : 'Выключить микрофон', onclick: () => this.toggleMute() }, icon(this.muted ? 'micoff' : 'mic', 16)),
        h('button.round.red', { title: 'Положить трубку', onclick: () => this.leave() }, icon('hangup', 16))))
    }
    box.replaceChildren(...parts)
  },
}

// mic samples come out of an AudioWorklet in 128-sample blocks
const MIC_WORKLET = `
registerProcessor('jt-mic', class extends AudioWorkletProcessor {
  process(inputs) {
    const ch = inputs[0] && inputs[0][0]
    if (ch) this.port.postMessage(ch.slice(0))
    return true
  }
})`

/** Microphone -> Opus -> socket, socket -> Opus -> speakers. */
class CallAudio {
  constructor(callId) {
    this.callId = callId
    this.muted = false
    this.running = false
    this.retries = 0
    this.pcm = new Float32Array(0)
    this.decoders = new Map()
    this.nextAt = new Map()
    this.ts = 0
  }

  async start() {
    this.running = true
    this.connect()
    try {
      this.ctx = new AudioContext({ sampleRate: RATE, latencyHint: 'interactive' })
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 },
      })
      if (!this.running) { this.stream.getTracks().forEach((t) => t.stop()); return }
      const url = URL.createObjectURL(new Blob([MIC_WORKLET], { type: 'application/javascript' }))
      await this.ctx.audioWorklet.addModule(url)
      const src = this.ctx.createMediaStreamSource(this.stream)
      this.node = new AudioWorkletNode(this.ctx, 'jt-mic')
      this.node.port.onmessage = (e) => this.mic(e.data)
      // the worklet must be pulled by the graph; a muted gain keeps it silent
      const sink = this.ctx.createGain()
      sink.gain.value = 0
      src.connect(this.node).connect(sink).connect(this.ctx.destination)
      this.makeEncoder()
    } catch (e) {
      console.error(e)
      UI.toast('Микрофон не запустился: ' + e.message)
    }
  }

  makeEncoder() {
    if (typeof AudioEncoder === 'undefined') return
    try {
      this.encoder = new AudioEncoder({
        output: (chunk) => {
          const b = new Uint8Array(chunk.byteLength + 1)
          b[0] = CODEC_OPUS
          chunk.copyTo(b.subarray(1))
          this.sendBytes(b)
        },
        error: (e) => { console.error(e); this.encoder = null },
      })
      this.encoder.configure({ codec: 'opus', sampleRate: RATE, numberOfChannels: 1, bitrate: 24000, opus: { frameDuration: 20000, format: 'opus', application: 'voip' } })
    } catch (e) {
      console.error(e)
      this.encoder = null
    }
  }

  stop() {
    this.running = false
    if (this.ws) this.ws.close()
    this.ws = null
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop())
    if (this.ctx) this.ctx.close()
    try { if (this.encoder) this.encoder.close() } catch (e) {}
    for (const d of this.decoders.values()) { try { d.close() } catch (e) {} }
  }

  connect() {
    if (!this.running) return
    const url = Api.url('/call', { token: Api.token, call_id: this.callId }).replace(/^http/, 'ws')
    const ws = new WebSocket(url)
    ws.binaryType = 'arraybuffer'
    this.ws = ws
    ws.onmessage = (e) => {
      if (typeof e.data === 'string') return
      this.retries = 0
      this.play(new Uint8Array(e.data))
    }
    ws.onclose = () => {
      if (ws !== this.ws) return
      // lost network: the server keeps my place for 20 s
      this.ws = null
      if (!this.running || this.retries >= 8) return
      this.retries++
      setTimeout(() => this.connect(), 2000)
    }
  }

  sendBytes(b) {
    if (this.ws && this.ws.readyState === 1) this.ws.send(b)
  }

  // ---------- sending ----------

  mic(block) {
    if (!this.running) return
    const all = new Float32Array(this.pcm.length + block.length)
    all.set(this.pcm)
    all.set(block, this.pcm.length)
    let at = 0
    while (all.length - at >= FRAME) {
      const frame = all.slice(at, at + FRAME)
      at += FRAME
      if (!this.muted) this.send(frame)
    }
    this.pcm = all.slice(at)
  }

  send(frame) {
    if (this.encoder && this.encoder.state === 'configured') {
      this.encoder.encode(new AudioData({ format: 'f32', sampleRate: RATE, numberOfFrames: FRAME, numberOfChannels: 1, timestamp: this.ts, data: frame }))
      this.ts += 20000
      return
    }
    // no Opus: every second sample, 8 kHz raw
    const b = new Uint8Array(1 + FRAME)
    b[0] = CODEC_PCM8
    const v = new DataView(b.buffer)
    for (let i = 0; i < FRAME; i += 2) v.setInt16(1 + i, Math.max(-32768, Math.min(32767, Math.round(frame[i] * 32767))), true)
    this.sendBytes(b)
  }

  // ---------- receiving ----------

  play(d) {
    if (d.length <= 5 || !this.ctx) return
    const sender = ((d[0] << 24) | (d[1] << 16) | (d[2] << 8) | d[3]) >>> 0
    const codec = d[4]
    const payload = d.subarray(5)
    if (codec === CODEC_OPUS) {
      const dec = this.decoder(sender)
      if (!dec) return
      try {
        dec.decode(new EncodedAudioChunk({ type: 'key', timestamp: 0, data: payload }))
      } catch (e) {
        this.decoders.delete(sender)
      }
    } else if (codec === CODEC_PCM8) {
      // 8 kHz -> 16 kHz by repeating each sample
      const v = new DataView(payload.buffer, payload.byteOffset, payload.byteLength)
      const n = Math.floor(payload.byteLength / 2)
      const out = new Float32Array(n * 2)
      for (let i = 0; i < n; i++) { const s = v.getInt16(i * 2, true) / 32768; out[i * 2] = s; out[i * 2 + 1] = s }
      this.schedule(sender, out)
    }
  }

  decoder(sender) {
    if (this.decoders.has(sender)) return this.decoders.get(sender)
    if (typeof AudioDecoder === 'undefined') return null
    const dec = new AudioDecoder({
      output: (data) => {
        const out = new Float32Array(data.numberOfFrames)
        data.copyTo(out, { planeIndex: 0, format: 'f32-planar' })
        data.close()
        this.schedule(sender, out)
      },
      error: (e) => { console.error(e); this.decoders.delete(sender) },
    })
    dec.configure({ codec: 'opus', sampleRate: RATE, numberOfChannels: 1 })
    this.decoders.set(sender, dec)
    return dec
  }

  schedule(sender, samples) {
    const ctx = this.ctx
    if (!ctx || ctx.state === 'closed' || !samples.length) return
    if (ctx.state === 'suspended') ctx.resume()
    const now = ctx.currentTime
    let at = this.nextAt.get(sender) || 0
    // too far behind (network burst): drop instead of growing the delay
    if (at > now + 0.25) return
    if (at < now + 0.04) at = now + 0.06
    const buf = ctx.createBuffer(1, samples.length, RATE)
    buf.copyToChannel(samples, 0)
    const src = ctx.createBufferSource()
    src.buffer = buf
    src.connect(ctx.destination)
    src.start(at)
    this.nextAt.set(sender, at + buf.duration)
  }
}

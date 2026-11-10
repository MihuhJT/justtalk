// The server's /player pages call window.JT.*, like the Android WebView bridge; pass it to the host page.
const { contextBridge, ipcRenderer } = require('electron')

contextBridge.exposeInMainWorld('JT', {
  onReady: (d, t) => ipcRenderer.sendToHost('jt', { k: 'ready', d, t }),
  onState: (s, t) => ipcRenderer.sendToHost('jt', { k: 'state', s, t }),
  onError: (e) => ipcRenderer.sendToHost('jt', { k: 'error', e: String(e) }),
})

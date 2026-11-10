const { contextBridge, ipcRenderer } = require('electron')
const path = require('path')
const { pathToFileURL } = require('url')

contextBridge.exposeInMainWorld('native', {
  server: process.env.JT_SERVER || 'http://127.0.0.1:8000',
  platform: process.platform,
  webviewPreload: pathToFileURL(path.join(__dirname, 'webview-preload.js')).href,
  badge: (count, dataUrl) => ipcRenderer.send('badge', count, dataUrl),
  flash: () => ipcRenderer.send('flash'),
  show: () => ipcRenderer.send('show'),
  focused: () => ipcRenderer.invoke('focused'),
  onFocus: (cb) => ipcRenderer.on('focus', (e, v) => cb(v)),
  openExternal: (url) => ipcRenderer.invoke('open-external', url),
  saveMedia: (url, name) => ipcRenderer.invoke('save-media', url, name),
  autostart: (on) => ipcRenderer.send('autostart', on),
  autostartGet: () => ipcRenderer.invoke('autostart-get'),
})

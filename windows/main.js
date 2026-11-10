// JustTalk for Windows: one window with the web UI from app/, a tray icon that keeps the socket alive
// when the window is closed, taskbar badge and downloads into Downloads\JustTalk.
const { app, BrowserWindow, Tray, Menu, ipcMain, nativeImage, shell, session, net } = require('electron')
const path = require('path')
const fs = require('fs')

app.setAppUserModelId('com.fayor.justtalk.win')
// tests run several copies side by side, each with its own login
if (process.env.JT_PROFILE) app.setPath('userData', path.join(app.getPath('appData'), 'JustTalk-' + process.env.JT_PROFILE))
if (!app.requestSingleInstanceLock()) app.quit()

let win = null
let tray = null
let quitting = false

function createWindow() {
  win = new BrowserWindow({
    width: 1100,
    height: 720,
    minWidth: 760,
    minHeight: 500,
    backgroundColor: '#000000',
    title: 'JustTalk',
    icon: path.join(__dirname, 'app', 'icon.png'),
    autoHideMenuBar: true,
    titleBarStyle: process.platform === 'win32' ? 'hidden' : 'default',
    titleBarOverlay: process.platform === 'win32' ? { color: '#000000', symbolColor: '#f4f4f6', height: 36 } : undefined,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      // the preload needs path/url and process.env
      sandbox: false,
      // the server has no CORS headers; the page only talks to our own server
      webSecurity: false,
      webviewTag: true,
      spellcheck: false,
      // calls and the socket must keep running while the window is hidden in the tray
      backgroundThrottling: false,
    },
  })
  win.removeMenu()
  win.loadFile(path.join(__dirname, 'app', 'index.html'))
  win.on('close', (e) => {
    // closing the window hides it to the tray, like the Mac app stays in the dock
    if (!quitting) {
      e.preventDefault()
      win.hide()
    }
  })
  win.on('focus', () => win.webContents.send('focus', true))
  win.on('blur', () => win.webContents.send('focus', false))
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith('file:')) {
      e.preventDefault()
      shell.openExternal(url)
    }
  })
}

function showWindow() {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.show()
  win.focus()
}

function createTray() {
  const img = nativeImage.createFromPath(path.join(__dirname, 'app', 'icon.png')).resize({ width: 16, height: 16 })
  tray = new Tray(img)
  tray.setToolTip('JustTalk')
  tray.setContextMenu(Menu.buildFromTemplate([
    { label: 'Открыть JustTalk', click: showWindow },
    { type: 'separator' },
    { label: 'Выйти', click: () => { quitting = true; app.quit() } },
  ]))
  tray.on('click', showWindow)
}

// Windows shows toasts only for apps with a Start menu shortcut carrying the same AppUserModelID;
// the zip build has no installer, so the app makes the shortcut itself
function startMenuShortcut() {
  if (process.platform !== 'win32' || !app.isPackaged) return
  const lnk = path.join(app.getPath('appData'), 'Microsoft', 'Windows', 'Start Menu', 'Programs', 'JustTalk.lnk')
  try {
    shell.writeShortcutLink(lnk, fs.existsSync(lnk) ? 'update' : 'create', {
      target: process.execPath,
      appUserModelId: 'com.fayor.justtalk.win',
      description: 'JustTalk',
    })
  } catch (e) {}
}

app.on('second-instance', showWindow)
app.on('before-quit', () => { quitting = true })
app.on('activate', showWindow)

app.whenReady().then(() => {
  // microphone and camera for calls, voice and circles
  session.defaultSession.setPermissionRequestHandler((wc, permission, done) => {
    done(['media', 'notifications', 'clipboard-read', 'clipboard-sanitized-write', 'fullscreen'].includes(permission))
  })
  session.defaultSession.setPermissionCheckHandler(() => true)
  // tuna shows browsers a "Требуется подтверждение" page instead of the server's answer; this header skips it
  // for every request to the tunnel: API, sockets, media, the /player pages
  session.defaultSession.webRequest.onBeforeSendHeaders({ urls: ['*://*.tuna.free/*', '*://*.tuna.am/*', 'wss://*.tuna.free/*', 'wss://*.tuna.am/*'] }, (details, done) => {
    details.requestHeaders['tuna-skip-browser-warning'] = '1'
    done({ requestHeaders: details.requestHeaders })
  })
  createWindow()
  createTray()
  startMenuShortcut()
})

// ---------- calls from the page ----------

ipcMain.on('badge', (e, count, dataUrl) => {
  if (!win) return
  if (process.platform === 'win32') {
    win.setOverlayIcon(count > 0 && dataUrl ? nativeImage.createFromDataURL(dataUrl) : null, count > 0 ? `${count} непрочитанных` : '')
  } else {
    app.setBadgeCount(count)
  }
  tray && tray.setToolTip(count > 0 ? `JustTalk · ${count} непрочитанных` : 'JustTalk')
})

ipcMain.on('flash', () => { if (win && !win.isFocused()) win.flashFrame(true) })
ipcMain.on('show', showWindow)
ipcMain.handle('focused', () => !!win && win.isFocused() && win.isVisible())
ipcMain.handle('open-external', (e, url) => shell.openExternal(url))
ipcMain.on('autostart', (e, on) => {
  if (process.platform === 'win32') app.setLoginItemSettings({ openAtLogin: on, args: ['--hidden'] })
})
ipcMain.handle('autostart-get', () => app.getLoginItemSettings().openAtLogin)

// Downloads\JustTalk, never over an existing file; returns the saved path
ipcMain.handle('save-media', async (e, url, name) => {
  const dir = path.join(app.getPath('downloads'), 'JustTalk')
  fs.mkdirSync(dir, { recursive: true })
  const safe = (name || 'file').replace(/[\\/:*?"<>|]/g, '_')
  const ext = path.extname(safe)
  const base = safe.slice(0, safe.length - ext.length)
  let dest = path.join(dir, safe)
  for (let n = 2; fs.existsSync(dest); n++) dest = path.join(dir, `${base} (${n})${ext}`)
  const resp = await net.fetch(url)
  if (!resp.ok) throw new Error('Не получилось скачать')
  fs.writeFileSync(dest, Buffer.from(await resp.arrayBuffer()))
  shell.showItemInFolder(dest)
  return dest
})

if (process.argv.includes('--hidden')) {
  app.whenReady().then(() => win && win.hide())
}

// Draws the app icon: violet rounded square with a bold "JT".
import AppKit
let size = 1024.0
let img = NSImage(size: NSSize(width: size, height: size))
img.lockFocus()
let rect = NSRect(x: 100, y: 100, width: 824, height: 824)
NSColor(red: 0x73 / 255, green: 0x66 / 255, blue: 0xF0 / 255, alpha: 1).setFill()
NSBezierPath(roundedRect: rect, xRadius: 185, yRadius: 185).fill()
let text = NSAttributedString(string: "JT", attributes: [
    .font: NSFont.systemFont(ofSize: 400, weight: .heavy), .foregroundColor: NSColor.white])
let t = text.size()
text.draw(at: NSPoint(x: (size - t.width) / 2, y: (size - t.height) / 2))
img.unlockFocus()
let png = NSBitmapImageRep(data: img.tiffRepresentation!)!.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))

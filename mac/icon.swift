// Draws the app icon: JT monogram (white T bar → J hook, violet dot) on a black rounded square.
import AppKit
let size = 1024.0
let img = NSImage(size: NSSize(width: size, height: size))
img.lockFocus()
NSColor.black.setFill()
NSBezierPath(roundedRect: NSRect(x: 100, y: 100, width: 824, height: 824), xRadius: 185, yRadius: 185).fill()
// monogram coordinates are 0..100 with y down, drawn into the middle 600 px
let k = 6.0, ox = 212.0 + k, oy = 212.0 + 0.5 * k
func p(_ x: Double, _ y: Double) -> NSPoint { NSPoint(x: ox + x * k, y: size - (oy + y * k)) }
NSColor.white.setFill()
NSBezierPath(roundedRect: NSRect(origin: p(22, 36), size: NSSize(width: 56 * k, height: 14 * k)), xRadius: 7 * k, yRadius: 7 * k).fill()
let stem = NSBezierPath()
stem.move(to: p(50, 30))
stem.line(to: p(50, 60))
stem.appendArc(withCenter: p(36, 60), radius: 14 * k, startAngle: 0, endAngle: -180, clockwise: true)
stem.lineWidth = 14 * k
stem.lineCapStyle = .round
NSColor.white.setStroke()
stem.stroke()
NSColor(red: 0x73 / 255, green: 0x66 / 255, blue: 0xF0 / 255, alpha: 1).setFill()
NSBezierPath(ovalIn: NSRect(origin: p(63, 77), size: NSSize(width: 20 * k, height: 20 * k))).fill()
img.unlockFocus()
let png = NSBitmapImageRep(data: img.tiffRepresentation!)!.representation(using: .png, properties: [:])!
try! png.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))

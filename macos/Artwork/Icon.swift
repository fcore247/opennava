import AppKit
import Foundation

let mark = NSImage(contentsOfFile: CommandLine.arguments[1])!
let canvas = NSImage(size: NSSize(width: 1024, height: 1024))
canvas.lockFocus()
let outline = NSBezierPath(roundedRect: NSRect(x: 56, y: 56, width: 912, height: 912),
                           xRadius: 202, yRadius: 202)
let top = NSColor(srgbRed: 0.16, green: 0.16, blue: 0.21, alpha: 1)
let bottom = NSColor(srgbRed: 0.055, green: 0.055, blue: 0.072, alpha: 1)
NSGradient(starting: bottom, ending: top)!.draw(in: outline, angle: 70)
mark.draw(in: NSRect(x: 195, y: 268, width: 634, height: 498))
canvas.unlockFocus()

let bitmap = NSBitmapImageRep(data: canvas.tiffRepresentation!)!
try bitmap.representation(using: .png, properties: [:])!
    .write(to: URL(fileURLWithPath: CommandLine.arguments[2]))

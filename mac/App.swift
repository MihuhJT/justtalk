import AppKit
import SwiftUI
import UserNotifications

@main
struct JustTalkApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @StateObject private var store = Store.shared

    var body: some Scene {
        Window("JustTalk", id: "main") {
            RootView().environmentObject(store)
                .frame(minWidth: 760, minHeight: 500)
        }
        .defaultSize(width: 1100, height: 720)
        .windowStyle(.hiddenTitleBar)
        .commands {
            CommandGroup(replacing: .newItem) {}
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    func applicationDidFinishLaunching(_ n: Notification) {
        if let url = Bundle.main.url(forResource: "Onest", withExtension: "ttf") {
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
        UNUserNotificationCenter.current().delegate = self
        let nc = NotificationCenter.default
        nc.addObserver(forName: NSApplication.didBecomeActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated {
                let s = Store.shared
                s.sendPresence()
                if let id = s.openChat {
                    s.markRead(id)
                    s.clearNotifications(id)
                }
            }
        }
        nc.addObserver(forName: NSApplication.didResignActiveNotification, object: nil, queue: .main) { _ in
            MainActor.assumeIsolated { Store.shared.sendPresence() }
        }
        // ⌘V with a picture (and no text) in the clipboard attaches it to the open chat
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            guard e.modifierFlags.intersection(.deviceIndependentFlagsMask) == .command, e.charactersIgnoringModifiers == "v" else { return e }
            let pb = NSPasteboard.general
            let hasFile = pb.canReadObject(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true])
            guard !hasFile, pb.string(forType: .string) == nil, let img = NSImage(pasteboard: pb) else { return e }
            let open = MainActor.assumeIsolated { Store.shared.openChat != nil }
            guard open else { return e }
            NotificationCenter.default.post(name: .pasteImage, object: img)
            return nil
        }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { false }

    // closed window + dock icon click = window back
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows: Bool) -> Bool {
        if !hasVisibleWindows { sender.windows.first?.makeKeyAndOrderFront(nil) }
        return true
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                withCompletionHandler done: @escaping (UNNotificationPresentationOptions) -> Void) {
        done([.banner, .sound])
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                withCompletionHandler done: @escaping () -> Void) {
        let id = response.notification.request.content.userInfo["chat_id"] as? Int
        DispatchQueue.main.async {
            NSApp.activate(ignoringOtherApps: true)
            NSApp.windows.first?.makeKeyAndOrderFront(nil)
            if let id { Store.shared.open(id) }
        }
        done()
    }
}

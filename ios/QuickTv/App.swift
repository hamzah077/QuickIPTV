import SwiftUI
import AVFoundation

@main
struct QuickTvApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate
    @State private var model = AppModel.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(model)
                .environment(PlayerController.shared)
                .environment(\.layoutDirection, .rightToLeft)       // التطبيق بالعربي
                .preferredColorScheme(.dark)
                .tint(.white)
        }
    }
}

/// اتجاه الشاشة: المشغّل بملء الشاشة يطلب أفقي، والباقي حر
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var orientationLock: UIInterfaceOrientationMask = .all

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // الصوت يكمل بصورة داخل صورة وبالخلفية
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        return true
    }

    func application(_ application: UIApplication, supportedInterfaceOrientationsFor window: UIWindow?) -> UIInterfaceOrientationMask {
        AppDelegate.orientationLock
    }
}

enum Orientation {
    /// يلف الشاشة (iOS 16+) ويقفلها على الاتجاه المطلوب
    @MainActor
    static func set(_ mask: UIInterfaceOrientationMask) {
        AppDelegate.orientationLock = mask
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.requestGeometryUpdate(.iOS(interfaceOrientations: mask)) { _ in }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    @MainActor
    static func unlock() {
        AppDelegate.orientationLock = .all
        guard let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene else { return }
        scene.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }
}

/// البداية: تسجيل الدخول أو التطبيق
struct RootView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hsize

    var body: some View {
        Group {
            if model.current == nil {
                LoginView()
            } else if hsize == .regular {
                IPadShell()                 // آيباد: قائمة جانبية زجاج
            } else {
                PhoneShell()                // آيفون: تبويبات زجاج عائمة
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        .task(id: model.currentId) { await model.loadAll() }
    }
}

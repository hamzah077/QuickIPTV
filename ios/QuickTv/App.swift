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
                .environment(Downloads.shared)
                .environment(\.layoutDirection, .rightToLeft)       // التطبيق بالعربي
                .preferredColorScheme(.dark)
                .tint(.white)
        }
    }
}

/// اتجاه الشاشة: المشغّل بملء الشاشة يطلب أفقي، والباقي حر
final class AppDelegate: NSObject, UIApplicationDelegate {
    /// الآيفون: التطبيق عمودي دايماً — بس المشغّل بملء الشاشة (لما تضغط تكبير) يلف الفيديو بنفسه
    static var orientationLock: UIInterfaceOrientationMask = UIDevice.current.userInterfaceIdiom == .phone ? .portrait : .all

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // الصوت يكمل بصورة داخل صورة وبالخلفية
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
        UIDevice.current.beginGeneratingDeviceOrientationNotifications()
        return true
    }

    /// التحميلات بالخلفية: النظام يصحّي التطبيق لما تخلص
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        MainActor.assumeIsolated { Downloads.shared.bgCompletion = completionHandler }
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

    /// يقفل الاتجاه بدون ما يلف الشاشة (حركتنا هي اللي تلف الفيديو)
    @MainActor
    static func lock(_ mask: UIInterfaceOrientationMask) {
        AppDelegate.orientationLock = mask
        (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.keyWindow?.rootViewController?.setNeedsUpdateOfSupportedInterfaceOrientations()
    }

    /// يفتح اللف بس لما الجوال يكون عمودي فعلاً (عشان ما يرجع يدخل ملء الشاشة لحاله بعد التصغير)
    @MainActor
    static func unlockWhenPortrait() {
        let o = UIDevice.current.orientation
        if o == .portrait || o == .faceUp || o == .faceDown || o == .unknown { unlock(); return }
        var token: NSObjectProtocol?
        token = NotificationCenter.default.addObserver(forName: UIDevice.orientationDidChangeNotification, object: nil, queue: .main) { _ in
            if UIDevice.current.orientation == .portrait {
                MainActor.assumeIsolated { unlock() }
                if let t = token { NotificationCenter.default.removeObserver(t) }
            }
        }
    }

    @MainActor
    static func unlock() {
        AppDelegate.orientationLock = UIDevice.current.userInterfaceIdiom == .phone ? .portrait : .all
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
            } else if model.updFull {
                UpdateFullView()            // أول دخول: نجهّز القوائم بعدّاد
            } else if hsize == .regular {
                IPadShell()                 // آيباد: قائمة جانبية زجاج
            } else {
                PhoneShell()                // آيفون: تبويبات زجاج عائمة
            }
        }
        .background(Theme.bg.ignoresSafeArea())
        // تحديث من الإعدادات: نافذة منبثقة فوق التطبيق
        .overlay { if model.updPopup { UpdatePopup().transition(.opacity) } }
        .animation(.easeOut(duration: 0.2), value: model.updPopup)
        .animation(.easeOut(duration: 0.25), value: model.updFull)
        .task(id: model.currentId) { await model.loadAll() }
    }
}

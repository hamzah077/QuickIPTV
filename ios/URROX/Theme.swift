import SwiftUI
import UIKit

enum Theme {
    /// الخلفية: نفس خلفية النظام (أسود بالليلي، أبيض بالفاتح)
    static let bg = Color(uiColor: .systemBackground)
    static let surface = Color.primary.opacity(0.07)
    static let muted = Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? UIColor(red: 0.66, green: 0.68, blue: 0.72, alpha: 1) : .secondaryLabel })
    static let red = Color(red: 0.898, green: 0.282, blue: 0.302)         // #E5484D (التقدّم والمباشر)
    static let current = Color.primary.opacity(0.12)                        // تحديد الحالي (رمادي)
}

// MARK: - الزجاج: Liquid Glass على iOS 26، وزجاج عادي على 17/18

extension View {
    /// زجاج بشكل كبسولة/مستطيل مدوّر
    @ViewBuilder
    func glass(_ radius: CGFloat = 999, interactive: Bool = false) -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            if interactive {
                self.glassEffect(.regular.interactive(), in: .rect(cornerRadius: radius))
            } else {
                self.glassEffect(.regular, in: .rect(cornerRadius: radius))
            }
        } else {
            self.legacyGlass(radius)
        }
        #else
        self.legacyGlass(radius)
        #endif
    }

    /// مجموعة عناصر زجاج (صف تصنيفات مثلاً): iOS 26 يرسمها مرة وحدة بدل كل وحدة لحالها = أخف بكثير
    @ViewBuilder
    func glassGroup() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) { GlassEffectContainer(spacing: 0) { self } } else { self }
        #else
        self
        #endif
    }

    /// زجاج دائري (أزرار المشغّل)
    @ViewBuilder
    func glassCircle() -> some View {
        #if compiler(>=6.2)
        if #available(iOS 26.0, *) {
            self.glassEffect(.regular.interactive(), in: .circle)
        } else {
            self.background(.ultraThinMaterial, in: Circle()).overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.5))
        }
        #else
        self.background(.ultraThinMaterial, in: Circle()).overlay(Circle().stroke(.white.opacity(0.25), lineWidth: 0.5))
        #endif
    }

    fileprivate func legacyGlass(_ radius: CGFloat) -> some View {
        self.background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: radius, style: .continuous))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).stroke(.white.opacity(0.22), lineWidth: 0.5))
    }
}

// MARK: - صورة من رابط (مع كاش بسيط)

struct Poster: View {
    let url: String
    var radius: CGFloat = 12
    var contentMode: ContentMode = .fill

    var body: some View {
        // Color.clear ياخذ مقاس الإطار بالضبط، والصورة تتعبّى داخله وتنقص — ما تطلع برا حدودها
        Color.clear
            .overlay {
                GeometryReader { g in
                    CachedImage(url: url, size: g.size, contentMode: contentMode)
                }
                // الصورة المعبّاة تطلع برا الإطار (مقصوصة بالشكل بس): كانت تسرق اللمس من البوستر اللي جنبها
                .allowsHitTesting(false)
            }
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// عنوان قسم + "الكل"
struct SectionHeader: View {
    // (العنوان أثقل من النص عشان الأقسام تنفصل بالعين)
    let title: String
    var more: (() -> Void)? = nil
    var body: some View {
        HStack {
            Text(title).font(.title3.weight(.bold)).accessibilityAddTraits(.isHeader)
            Spacer()
            if let more { Button("الكل", action: more).font(.subheadline.weight(.medium)).foregroundStyle(Theme.muted) }
        }
        .padding(.horizontal, 16)
    }
}

extension View {
    /// ظل خفيف تحت البوستر: يرفعه شوي عن الخلفية الداكنة
    func posterDepth() -> some View { shadow(color: .black.opacity(0.45), radius: 6, y: 3) }
}

/// خط التقدّم الأحمر
struct ProgressLine: View {
    let value: Double   // 0...1
    var height: CGFloat = 3
    var body: some View {
        GeometryReader { g in
            ZStack(alignment: .leading) {
                Rectangle().fill(.white.opacity(0.3))
                Rectangle().fill(Theme.red).frame(width: g.size.width * max(0, min(1, value)))
            }
        }
        .frame(height: height)
        .environment(\.layoutDirection, .leftToRight)
    }
}

/// المقاس: آيباد (عريض) أو آيفون
struct IsWide: EnvironmentKey { static let defaultValue = false }
extension EnvironmentValues {
    var isWide: Bool { get { self[IsWide.self] } set { self[IsWide.self] = newValue } }
}


// MARK: - صور مع ذاكرة (بدال AsyncImage اللي يعيد التنزيل مع كل تمرير)

/// يحمّل الصورة مرة وحدة، يصغّرها لمقاس الخانة (أخف على الذاكرة والتمرير)، ويحفظها بالذاكرة وبالجهاز
final class ImageStore: @unchecked Sendable {
    static let shared = ImageStore()
    private let mem = NSCache<NSString, UIImage>()
    private let session: URLSession
    private var inflight: [String: Task<UIImage?, Never>] = [:]
    private let lock = NSLock()

    private init() {
        mem.totalCostLimit = 120 * 1024 * 1024
        let cfg = URLSessionConfiguration.default
        cfg.urlCache = URLCache(memoryCapacity: 20 * 1024 * 1024, diskCapacity: 400 * 1024 * 1024)
        cfg.requestCachePolicy = .returnCacheDataElseLoad          // مرة وحدة من النت، بعدها من الجهاز
        cfg.timeoutIntervalForRequest = 15
        cfg.httpMaximumConnectionsPerHost = 6
        session = URLSession(configuration: cfg)
    }

    func cached(_ key: String) -> UIImage? { mem.object(forKey: key as NSString) }

    func load(_ url: URL, px: CGFloat) async -> UIImage? {
        let key = "\(url.absoluteString)#\(Int(px))"
        if let i = cached(key) { return i }
        let t: Task<UIImage?, Never> = lock.withLock {
            if let t = inflight[key] { return t }
            let t = Task<UIImage?, Never>.detached(priority: .utility) { [session] in
            guard let (data, _) = try? await session.data(from: url), let img = UIImage(data: data) else { return nil }
            // نصغّرها لمقاس الخانة بس (صور البوسترات أحياناً ٢٠٠٠ بكسل)
            let scale = px / max(img.size.width, img.size.height)
            let out = scale < 1 ? (await img.byPreparingThumbnail(ofSize: CGSize(width: img.size.width * scale, height: img.size.height * scale)) ?? img)
                                : (await img.byPreparingForDisplay() ?? img)
            return out
        }
            inflight[key] = t
            return t
        }
        let img = await t.value
        lock.withLock { inflight[key] = nil }
        if let img { mem.setObject(img, forKey: key as NSString, cost: Int(img.size.width * img.size.height * img.scale * img.scale * 4)) }
        return img
    }
}

struct CachedImage: View {
    let url: String
    let size: CGSize
    var contentMode: ContentMode = .fill
    @Environment(\.displayScale) private var scale
    @State private var img: UIImage?
    @State private var imgKey = ""                      // الخلايا تنعاد تُستخدم: لازم نعرف الصورة لأي رابط

    private var px: CGFloat { max(size.width, size.height) * scale * (contentMode == .fill ? 1.6 : 1) }
    private var key: String { "\(url)#\(Int(px))" }

    var body: some View {
        ZStack {
            if let img = (imgKey == key ? img : nil) ?? ImageStore.shared.cached(key) {
                Image(uiImage: img).resizable().aspectRatio(contentMode: contentMode)
                    .frame(width: size.width, height: size.height)
            } else {
                Theme.surface
            }
        }
        .frame(width: size.width, height: size.height)
        .task(id: key) {
            if imgKey != key { img = nil; imgKey = key }
            guard size.width > 1, ImageStore.shared.cached(key) == nil, let u = URL(string: url), u.scheme != nil else { return }
            let k = key
            let loaded = await ImageStore.shared.load(u, px: px)
            if !Task.isCancelled, k == imgKey { withAnimation(.easeOut(duration: 0.15)) { img = loaded } }
        }
    }
}

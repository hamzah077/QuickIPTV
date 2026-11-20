import SwiftUI

enum Theme {
    static let bg = Color(red: 0.043, green: 0.047, blue: 0.063)          // #0B0C10
    static let surface = Color.white.opacity(0.07)
    static let muted = Color(red: 0.66, green: 0.68, blue: 0.72)
    static let red = Color(red: 0.898, green: 0.282, blue: 0.302)         // #E5484D (التقدّم والمباشر)
    static let current = Color.white.opacity(0.12)                        // تحديد الحالي (رمادي)
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
                AsyncImage(url: URL(string: url), transaction: Transaction(animation: .easeOut(duration: 0.2))) { phase in
                    switch phase {
                    case .success(let img): img.resizable().aspectRatio(contentMode: contentMode)
                    default: Rectangle().fill(Theme.surface)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .contentShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
    }
}

/// عنوان قسم + "الكل"
struct SectionHeader: View {
    let title: String
    var more: (() -> Void)? = nil
    var body: some View {
        HStack {
            Text(title).font(.title3.weight(.semibold))
            Spacer()
            if let more { Button("الكل", action: more).font(.subheadline).foregroundStyle(Theme.muted) }
        }
        .padding(.horizontal, 16)
    }
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

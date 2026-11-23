import SwiftUI

// MARK: - تحديث القوائم بعدّاد: المباشر ← الأفلام ← المسلسلات (نفس الأندرويد)

/// صفوف الخطوات الثلاث
struct UpdateRows: View {
    @Environment(AppModel.self) private var model
    var big = false

    var body: some View {
        VStack(spacing: 8) {
            ForEach(model.updSteps) { s in
                HStack(spacing: 10) {
                    icon(s)
                    VStack(alignment: .leading, spacing: 6) {
                        HStack {
                            Text(s.title).font((big ? Font.body : .subheadline).weight(.semibold))
                            Spacer()
                            Text(label(s)).font(.footnote.monospacedDigit()).foregroundStyle(Theme.muted)
                        }
                        GeometryReader { g in
                            ZStack(alignment: .leading) {
                                Capsule().fill(Color.primary.opacity(0.1))
                                Capsule().fill(Theme.red).frame(width: g.size.width * (s.state >= 2 ? 1 : s.frac))
                            }
                        }
                        .frame(height: 4)
                        .environment(\.layoutDirection, .rightToLeft)
                    }
                }
                .padding(.horizontal, 12).padding(.vertical, 10)
                .background(Color.primary.opacity(0.05), in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .animation(.linear(duration: 0.2), value: s.frac)
            }
        }
    }

    private func icon(_ s: AppModel.UpdStep) -> some View {
        let (t, fg, bg): (String, Color, Color) = switch s.state {
        case 2: ("checkmark", Color.green, Color.green.opacity(0.18))
        case 1: ("arrow.triangle.2.circlepath", Color.primary, Color.primary.opacity(0.12))
        case 3: ("exclamationmark", Theme.red, Theme.red.opacity(0.2))
        default: ("circle.fill", Theme.muted, Color.primary.opacity(0.05))
        }
        return Image(systemName: t).font(.system(size: s.state == 0 ? 5 : 12, weight: .bold)).foregroundStyle(fg)
            .frame(width: 30, height: 30).background(bg, in: Circle())
    }

    private func label(_ s: AppModel.UpdStep) -> String {
        switch s.state {
        case 2: return "\(s.count.formatted()) \(s.unit)"
        case 1:
            if s.frac >= 0.9 { return "نرتّب…" }
            if s.bytes > 0 { return String(format: "%.1f م.ب", Double(s.bytes) / 1_048_576) }
            return s.frac > 0 ? "\(Int(s.frac / 0.9 * 100))٪" : "نتصل…"
        case 3: return "تعذّر — بنستخدم الموجود"
        default: return "بالانتظار"
        }
    }
}

/// أول دخول: صفحة كاملة، ولما تخلص تدخل التطبيق لحالها
struct UpdateFullView: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        VStack(spacing: 0) {
            Text("\(model.updating ? model.updTotal : 100)٪").font(.system(size: 48, weight: .bold).monospacedDigit())
                .contentTransition(.numericText()).animation(.default, value: model.updTotal)
                .padding(.top, 70)
            Text(model.updating ? "نجهّز قوائمك…" : "جاهز").foregroundStyle(Theme.muted).padding(.top, 4).padding(.bottom, 28)
            UpdateRows(big: true)
            Spacer()
            // السيرفر بطيء؟ تقدر تدخل والتحديث يكمل بالخلفية
            Button("تخطّي — كمّل بالخلفية") { model.updFull = false }
                .font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                .padding(.horizontal, 18).padding(.vertical, 10)
                .background(Color.primary.opacity(0.1), in: Capsule())
                .padding(.bottom, 10)
            Text("أول مرة بس — بعدها يفتح على طول").font(.footnote).foregroundStyle(Theme.muted).padding(.bottom, 24)
        }
        .padding(.horizontal, 20)
        .frame(maxWidth: 520)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Theme.bg.ignoresSafeArea())
        .environment(\.layoutDirection, .rightToLeft)
    }
}

/// من الإعدادات: نافذة منبثقة (إخفاء = يكمل بالخلفية)
struct UpdatePopup: View {
    @Environment(AppModel.self) private var model

    var body: some View {
        ZStack {
            Color.black.opacity(0.55).ignoresSafeArea()
            VStack(spacing: 12) {
                HStack {
                    Text("تحديث القوائم").font(.headline)
                    Spacer()
                    Text("\(model.updating ? model.updTotal : 100)٪").font(.subheadline.monospacedDigit()).foregroundStyle(Theme.muted)
                }
                UpdateRows()
                Button { model.updPopup = false } label: {
                    Text(model.updating ? "إخفاء" : "تم").font(.subheadline.weight(.semibold))
                        .frame(maxWidth: .infinity).frame(height: 42)
                        .background(Color.primary.opacity(0.1), in: Capsule())
                }
                .buttonStyle(.plain)
            }
            .padding(16)
            .frame(maxWidth: 380)
            .background(Color(uiColor: .secondarySystemBackground), in: RoundedRectangle(cornerRadius: 22, style: .continuous))     // يتبع الفاتح/الليلي
            .padding(.horizontal, 16)
            .environment(\.layoutDirection, .rightToLeft)
        }
        .foregroundStyle(.primary)
    }
}

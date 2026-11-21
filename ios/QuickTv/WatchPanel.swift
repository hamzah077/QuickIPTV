import SwiftUI

// MARK: - صفحة التشغيل (الجوال) — زي يوتيوب
// الصفحة كلها (المشغّل + التفاصيل والحلقات) طبقة فوق الأقسام. تسحبها من المشغّل لتحت:
// الصفحة كلها تنزل وتصغر وهي نفس المربع لين تصير المشغّل الصغير فوق الشريط السفلي (بدون تلاشي)،
// والصفحة اللي تحتها تبان. وتسحب المربع الصغير لفوق = نفس الحركة بالعكس.
// t = 0 صفحة كاملة، t = 1 مشغّل صغير.

struct WatchPanel: View {
    @Environment(PlayerController.self) private var pc
    let W: CGFloat, H: CGFloat, top: CGFloat, bottom: CGFloat
    /// t الحالي (أثناء السحب يتبع الإصبع)
    let t: CGFloat
    /// سحب: المسافة (موجب = لتحت) وهل خلص
    let onDrag: (CGFloat, Bool) -> Void
    let onExpand: () -> Void

    @State private var fromMini: Bool?

    private func L(_ a: CGFloat, _ b: CGFloat) -> CGFloat { a + (b - a) * t }

    // المربع الصغير: فوق الشريط السفلي
    private var miniRect: CGRect { CGRect(x: 12, y: H - bottom - 66 - 60, width: W - 24, height: 60) }
    private var box: CGRect {
        let m = miniRect
        return CGRect(x: L(0, m.minX), y: L(0, m.minY), width: L(W, m.width), height: L(H, m.height))
    }
    /// الفيديو داخل المربع (طريقة "ب"): ينزل مع الصفحة بعرضها كامل، وفي آخر ثلث الحركة يصغر ويدخل زاويته —
    /// ومقاسه 16:9 طول الوقت (ما يتمطّط)
    private var video: CGRect {
        let bw = box.width
        let x0 = (t - 0.62) / 0.38
        let k = x0 <= 0 ? 0 : x0 >= 1 ? 1 : (x0 < 0.5 ? 2 * x0 * x0 : 1 - pow(-2 * x0 + 2, 2) / 2)
        let w = bw + (96 - bw) * k
        let baseY = top + (3 - top) * t                     // ينزل مع الصفحة
        let y = baseY + (3 - baseY) * k
        let x = (bw - 6 - 96) * k                           // يروح يمين المربع (مكانه بالمشغّل الصغير)
        return CGRect(x: x, y: y, width: w, height: w * 9 / 16)
    }
    /// مسافة السحب الكاملة (من صفحة لمربع صغير)
    static func range(H: CGFloat, bottom: CGFloat) -> CGFloat { max(200, H - bottom - 66 - 60) }

    var body: some View {
        let b = box, v = video
        ZStack(alignment: .topLeading) {
            // التفاصيل والحلقات تحت الفيديو (تنقص مع المربع — ما تختفي بتلاشي)
            if let vod = pc.watchVod {
                DetailsView(vod: vod, inPanel: true)
                    .environment(\.layoutDirection, .rightToLeft)
                    .frame(width: W, height: max(0, H - top - W * 9 / 16))
                    .offset(y: v.maxY)
                    .allowsHitTesting(t < 0.05)
            }

            // الفيديو + أزراره (الأزرار بس بالصفحة الكاملة)
            ZStack {
                Color.black
                VideoSurface(active: !pc.fullscreen)
                if t < 0.25 {
                    PlayerOverlay(compact: true, onDragDown: { dy, ended in onDrag(dy, ended) }) {
                        pc.autoFullscreen = false; pc.fullscreen = true
                    }
                    .opacity(Double(1 - t * 4))
                    .environment(\.layoutDirection, .rightToLeft)
                }
            }
            .frame(width: v.width, height: v.height)
            .clipShape(RoundedRectangle(cornerRadius: min(8, 8 * t * 3), style: .continuous))
            .offset(x: v.minX, y: v.minY)

            // معلومات المشغّل الصغير جنب الفيديو (تطلع آخر الحركة)
            if t > 0.6 {
                miniInfo
                    .frame(width: max(0, b.width - 96 - 18), height: b.height)
                    .offset(x: 8)
                    .opacity(Double((t - 0.7) / 0.3))
                    .allowsHitTesting(t > 0.95)
            }
            if t > 0.95, !pc.isLive, pc.duration > 0 {
                ProgressLine(value: pc.position / pc.duration, height: 2)
                    .frame(width: b.width - 24)
                    .offset(x: 12, y: b.height - 2)
            }
        }
        .frame(width: b.width, height: b.height, alignment: .topLeading)
        .background(t > 0.5 ? Color(white: 0.11) : Theme.bg)
        .clipShape(RoundedRectangle(cornerRadius: L(0, 14), style: .continuous))
        .contentShape(RoundedRectangle(cornerRadius: L(0, 14), style: .continuous))
        // المربع الصغير: ضغطة = يكبر، سحب لفوق = يكبر مع إصبعك (الصفحة الكاملة: الأزرار تشتغل عادي)
        .gesture(TapGesture().onEnded { if t > 0.95 { onExpand() } }, including: t > 0.95 ? .all : .subviews)
        .simultaneousGesture(DragGesture(minimumDistance: 8)
            .onChanged { d in
                if fromMini == nil { fromMini = t > 0.95 }            // السحب بدأ من المربع الصغير؟
                if fromMini == true { onDrag(d.translation.height, false) }
            }
            .onEnded { d in
                if fromMini == true { onDrag(d.translation.height, true) }
                fromMini = nil
            })
        .offset(x: b.minX, y: b.minY)
        .onAppear { reportFrame() }
        .onChange(of: t) { _, _ in reportFrame() }
    }

    /// مكان الفيديو بالصفحة الكاملة (منه تبدأ حركة ملء الشاشة)
    private func reportFrame() {
        if t == 0 { pc.inlineFrame = CGRect(x: 0, y: top, width: W, height: W * 9 / 16) }
    }

    private var miniInfo: some View {
        HStack(spacing: 2) {
            VStack(alignment: .leading, spacing: 2) {
                Text(pc.req?.title ?? "").font(.subheadline.weight(.semibold)).lineLimit(1)
                if let s = pc.req?.sub, !s.isEmpty { Text(s).font(.caption).foregroundStyle(Theme.muted).lineLimit(1) }
            }
            Spacer(minLength: 2)
            Button { pc.togglePlay() } label: {
                Image(systemName: pc.isPlaying ? "pause.fill" : "play.fill").font(.title3).frame(width: 40, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(pc.isPlaying ? "إيقاف مؤقت" : "تشغيل")
            Button { withAnimation(.easeOut(duration: 0.2)) { pc.stop() } } label: {
                Image(systemName: "xmark").font(.headline).frame(width: 40, height: 44)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("إغلاق")
        }
        .foregroundStyle(.white)
        .environment(\.layoutDirection, .rightToLeft)
    }
}

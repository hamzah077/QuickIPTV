import SwiftUI

// MARK: - صفحة التشغيل والمشغّل العائم (الجوال) — نفس يوتيوب الجديد (2025)
// تسحب الفيديو لتحت: الصفحة تنزل وتتلاشى، والفيديو يصغر لين يصير نافذة عائمة بزوايا مدوّرة فوق الشريط السفلي.
// النافذة: ضغطة = يطلع زر الإيقاف والإغلاق فوق الفيديو، ضغطة ثانية = تكبير للصفحة.
// تسحبها لأي مكان وتلتصق بأقرب زاوية، وترميها على حافة الشاشة = تنخبى بلسان صغير ويوقف الفيديو
// (تضغط اللسان = ترجع وتكمل).
// t = 0 صفحة كاملة، t = 1 نافذة عائمة.

struct WatchPanel: View {
    @Environment(PlayerController.self) private var pc
    let W: CGFloat, H: CGFloat, top: CGFloat, bottom: CGFloat
    /// t الحالي (أثناء السحب يتبع الإصبع)
    let t: CGFloat
    /// الزاوية: 0 فوق يسار، 1 فوق يمين، 2 تحت يسار، 3 تحت يمين
    @Binding var corner: Int
    /// مخبّية على الحافة: -1 يسار، 1 يمين، 0 ظاهرة
    @Binding var hiddenSide: Int
    /// سحب الصفحة الكاملة لتحت: المسافة، وهل خلص
    let onDrag: (CGFloat, Bool) -> Void
    let onExpand: () -> Void

    @State private var move: CGSize = .zero
    @State private var showBtns = false
    @State private var btnTask: Task<Void, Never>?
    @State private var pausedByHide = false

    private func L(_ a: CGFloat, _ b: CGFloat) -> CGFloat { a + (b - a) * t }

    // النافذة العائمة: نص عرض الشاشة، 16:9
    private var fw: CGFloat { (W * 0.5).rounded() }
    private var fh: CGFloat { fw * 9 / 16 }
    static func floorY(H: CGFloat, bottom: CGFloat) -> CGFloat { H - bottom - 62 - 10 }
    private func cornerOrigin(_ c: Int) -> CGPoint {
        let x = c % 2 == 0 ? 10 : W - fw - 10
        let y = c < 2 ? top + 8 : Self.floorY(H: H, bottom: bottom) - fh
        return CGPoint(x: x, y: y)
    }
    /// مسافة السحب (من الصفحة الكاملة لمكان النافذة)
    static func range(W: CGFloat, H: CGFloat, top: CGFloat, bottom: CGFloat) -> CGFloat {
        max(200, floorY(H: H, bottom: bottom) - (W * 0.5) * 9 / 16 - top)
    }

    /// مكان الفيديو: من فوق الصفحة بعرضها، لمكان النافذة العائمة
    private var video: CGRect {
        var o = cornerOrigin(corner)
        if t >= 1 {
            if hiddenSide != 0 { o.x = hiddenSide < 0 ? -fw - 6 : W + 6 }
            o.x += move.width; o.y += move.height
        }
        return CGRect(x: L(0, o.x), y: L(top, o.y), width: L(W, fw), height: L(W * 9 / 16, fh))
    }

    var body: some View {
        let v = video
        ZStack(alignment: .topLeading) {
            // الصفحة تحت الفيديو: تنزل وتتلاشى (زي يوتيوب)
            if t < 1, let vod = pc.watchVod {
                DetailsView(vod: vod, inPanel: true)
                    .environment(\.layoutDirection, .rightToLeft)
                    .frame(width: W, height: max(0, H - top - W * 9 / 16))
                    .background(Theme.bg)
                    .offset(y: top + W * 9 / 16 + t * 220)
                    .opacity(Double(max(0, 1 - t * 1.4)))
                    .allowsHitTesting(t < 0.05)
            }

            // الفيديو: مقاسه ثابت (بعرض الشاشة) ونصغّره بالتكبير/التصغير على كرت الشاشة —
            // ما ينعاد ترتيبه مع كل حركة إصبع = سحب ناعم بدون لاج
            let fullW = W, fullH = W * 9 / 16
            let s = max(0.01, v.width / fullW)
            // الزوايا تتقعّر من أول ما تسحب (ما تنتظر آخر الحركة)
            let radius = min(14, t * 120) / s
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
            .frame(width: fullW, height: fullH)
            .clipShape(RoundedRectangle(cornerRadius: radius, style: .continuous))
            .scaleEffect(s, anchor: .topLeading)
            .frame(width: v.width, height: v.height, alignment: .topLeading)
            .overlay { if t >= 1 { floatingOverlay } }           // أزرار النافذة بمقاسها الحقيقي (مو مصغّرة)
            .clipShape(RoundedRectangle(cornerRadius: min(14, t * 120), style: .continuous))
            .shadow(color: .black.opacity(t >= 1 ? 0.5 : 0), radius: 12, y: 5)   // الظل بس وهي واقفة (الظل المتحرك يثقّل)
            .offset(x: v.minX, y: v.minY)
            .gesture(floatDrag, including: t >= 1 ? .all : .subviews)

            // اللسان على الحافة لما تنخبى
            if t >= 1 && hiddenSide != 0 {
                handle.offset(x: hiddenSide < 0 ? 0 : W - 24, y: cornerOrigin(corner).y + fh / 2 - 30)
                    .transition(.opacity)
            }
        }
        .frame(width: W, height: H, alignment: .topLeading)
        .onAppear { reportFrame() }
        .onChange(of: t) { _, nt in reportFrame(); if nt < 1 { showBtns = false; move = .zero } }
    }

    // MARK: النافذة العائمة

    /// زر الإيقاف (يسار) والإغلاق (يمين) فوق الفيديو + خط التقدّم
    private var floatingOverlay: some View {
        ZStack(alignment: .top) {
            Color.black.opacity(showBtns ? 0.35 : 0.001)        // (شبه شفاف عشان يستقبل اللمس)
                .contentShape(Rectangle())
                .onTapGesture {
                    if showBtns { onExpand() } else { flashButtons() }
                }
            if showBtns {
                HStack {
                    smallButton(pc.isPlaying ? "pause.fill" : "play.fill", pc.isPlaying ? "إيقاف مؤقت" : "تشغيل") { pc.togglePlay(); flashButtons() }
                    Spacer()
                    smallButton("xmark", "إغلاق") { withAnimation(.easeOut(duration: 0.2)) { pc.stop() } }
                }
                .padding(5)
                .environment(\.layoutDirection, .leftToRight)
                .transition(.opacity)
            }
            if !pc.isLive, pc.duration > 0 {
                VStack { Spacer(); ProgressLine(value: pc.position / pc.duration, height: 2) }
                    .allowsHitTesting(false)
            }
        }
        .animation(.easeOut(duration: 0.18), value: showBtns)
    }

    private func smallButton(_ sym: String, _ label: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: sym).font(.system(size: 13, weight: .bold)).foregroundStyle(.white)
                .frame(width: 28, height: 28)
                .background(.black.opacity(0.5), in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }

    private func flashButtons() {
        showBtns = true
        btnTask?.cancel()
        btnTask = Task { try? await Task.sleep(for: .seconds(3)); if !Task.isCancelled { showBtns = false } }
    }

    /// تسحب النافذة لأي مكان: تلتصق بأقرب زاوية، أو ترميها على الحافة تنخبى
    private var floatDrag: some Gesture {
        DragGesture(minimumDistance: 6, coordinateSpace: .global)
            .onChanged { d in
                guard t >= 1 else { return }
                var tr = Transaction(); tr.disablesAnimations = true
                withTransaction(tr) { showBtns = false; move = d.translation }
            }
            .onEnded { d in
                guard t >= 1 else { return }
                let base = cornerOrigin(corner)
                let startX = hiddenSide == 0 ? base.x : (hiddenSide < 0 ? -fw - 6 : W + 6)
                let x = startX + d.translation.width, y = base.y + d.translation.height
                let px = startX + d.predictedEndTranslation.width
                withAnimation(.interpolatingSpring(mass: 1, stiffness: 230, damping: 24)) {
                    move = .zero
                    if x < -fw * 0.35 || px < -fw * 0.6 { hide(-1) }
                    else if x > W - fw * 0.65 || px > W - fw * 0.4 { hide(1) }
                    else {
                        unhide()
                        // أقرب زاوية
                        var best = corner, bd = CGFloat.greatestFiniteMagnitude
                        for c in 0..<4 {
                            let o = cornerOrigin(c)
                            let dd = hypot(o.x - x, o.y - y)
                            if dd < bd { bd = dd; best = c }
                        }
                        corner = best
                    }
                }
            }
    }

    private var handle: some View {
        Button { withAnimation(.spring(duration: 0.34, bounce: 0.08)) { unhide() } } label: {
            Image(systemName: hiddenSide < 0 ? "chevron.right" : "chevron.left")
                .font(.system(size: 15, weight: .bold)).foregroundStyle(.white)
                .frame(width: 24, height: 60)
                .background(Color(white: 0.17), in: UnevenRoundedRectangle(
                    topLeadingRadius: hiddenSide < 0 ? 0 : 12, bottomLeadingRadius: hiddenSide < 0 ? 0 : 12,
                    bottomTrailingRadius: hiddenSide < 0 ? 12 : 0, topTrailingRadius: hiddenSide < 0 ? 12 : 0))
                .shadow(color: .black.opacity(0.5), radius: 8)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("إظهار المشغّل")
    }

    /// تنخبى على الحافة ويوقف الفيديو (يكمل لما ترجّعها)
    private func hide(_ side: Int) {
        hiddenSide = side
        corner = side < 0 ? (corner < 2 ? 0 : 2) : (corner < 2 ? 1 : 3)
        if pc.isPlaying { pc.togglePlay(); pausedByHide = true }
    }

    private func unhide() {
        guard hiddenSide != 0 else { return }
        hiddenSide = 0
        if pausedByHide { pausedByHide = false; if !pc.isPlaying { pc.togglePlay() } }
    }

    /// مكان الفيديو بالصفحة الكاملة (منه تبدأ حركة ملء الشاشة)
    private func reportFrame() {
        if t == 0 { pc.inlineFrame = CGRect(x: 0, y: top, width: W, height: W * 9 / 16) }
    }
}

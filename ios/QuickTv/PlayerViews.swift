import AVKit
import SwiftUI

// MARK: - سطح الفيديو (AVPlayerLayer) + صورة داخل صورة

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// سطح الفيديو حسب المحرّك. active = هذي الشاشة اللي تعرض الحين (الصغيرة أو ملء الشاشة)
struct VideoSurface: View {
    @Environment(PlayerController.self) private var pc
    var active: Bool = true
    var body: some View {
        if pc.engine == .vlc { VLCSurface(view: pc.vlcView, active: active) }
        else { AVSurface(player: pc.player) }
    }
}

/// شاشة VLC: نفس الشاشة تنتقل بين الصغيرة وملء الشاشة (بدون إعادة تشغيل)
struct VLCSurface: UIViewRepresentable {
    let view: UIView
    let active: Bool
    func makeUIView(context: Context) -> UIView {
        let c = UIView(); c.backgroundColor = .black; c.clipsToBounds = true
        return c
    }
    func updateUIView(_ c: UIView, context: Context) {
        if active && view.superview !== c {
            view.removeFromSuperview()
            view.frame = c.bounds
            view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
            c.addSubview(view)
        }
    }
}

struct AVSurface: UIViewRepresentable {
    let player: AVPlayer
    var gravity: AVLayerVideoGravity = .resizeAspect

    func makeUIView(context: Context) -> PlayerLayerView {
        let v = PlayerLayerView()
        v.backgroundColor = .black
        v.playerLayer.player = player
        v.playerLayer.videoGravity = gravity
        PiP.shared.attach(v.playerLayer)
        return v
    }

    func updateUIView(_ v: PlayerLayerView, context: Context) {
        v.playerLayer.videoGravity = gravity
        if v.playerLayer.player !== player { v.playerLayer.player = player }
    }
}

/// صورة داخل صورة (الفيديو يكمل فوق التطبيقات الثانية)
@MainActor
final class PiP: NSObject {
    static let shared = PiP()
    private var ctrl: AVPictureInPictureController?

    func attach(_ layer: AVPlayerLayer) {
        guard AVPictureInPictureController.isPictureInPictureSupported() else { return }
        ctrl = AVPictureInPictureController(playerLayer: layer)
        ctrl?.canStartPictureInPictureAutomaticallyFromInline = true
    }

    var available: Bool { ctrl != nil }
    func start() { ctrl?.startPictureInPicture() }
}

/// زر AirPlay
struct AirPlayButton: UIViewRepresentable {
    func makeUIView(context: Context) -> AVRoutePickerView {
        let v = AVRoutePickerView()
        v.tintColor = .white
        v.activeTintColor = .white
        v.prioritizesVideoDevices = true
        return v
    }
    func updateUIView(_ v: AVRoutePickerView, context: Context) {}
}

// MARK: - الأزرار (نمط يوتيوب بزجاج Liquid Glass)

struct PlayerOverlay: View {
    @Environment(PlayerController.self) private var pc
    var compact: Bool                       // المشغّل الصغير فوق الصفحة
    var onFullscreen: () -> Void

    @State private var visible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrub: Double?        // وقت السحب
    @State private var tapSide: Int = 0      // -1 يسار، 1 يمين
    @State private var tapAccum = 0
    @State private var tapReset: Task<Void, Never>?
    @State private var showSettings = false

    var body: some View {
        ZStack {
            // اللمس: ضغطة = إظهار/إخفاء، ضغطتين يمين/يسار = ±10 (ورا بعض تتجمّع)
            HStack(spacing: 0) {
                tapZone(-1)
                tapZone(1)
            }
            .environment(\.layoutDirection, .leftToRight)

            // قوس التقديم/التأخير
            if tapSide != 0 { seekArc }

            if visible { controls.transition(.opacity) }

            // تخطي المقدمة / الحلقة التالية (منفصلة عن الأزرار)
            VStack {
                Spacer()
                HStack {
                    Spacer()
                    if pc.showSkipIntro {
                        floatButton("تخطي المقدمة", icon: "forward.end.fill") { pc.skipIntro() }
                    } else if pc.showNextUp {
                        floatButton("الحلقة التالية", icon: "forward.end.fill", count: pc.nextCountdown) { pc.learnManualNext(); pc.next() }
                    }
                }
                .padding(.trailing, 14)
                .padding(.bottom, visible ? (compact ? 64 : 96) : 18)
            }
            .environment(\.layoutDirection, .leftToRight)

            // الخط الأحمر لما الأزرار مخفية
            if !visible && !pc.isLive && pc.duration > 0 {
                VStack { Spacer(); ProgressLine(value: pc.position / pc.duration, height: 3) }
            }

            if pc.buffering && pc.failed == nil { ProgressView().controlSize(.large).tint(.white) }
            if let f = pc.failed {
                Text(f).font(.footnote).multilineTextAlignment(.center).padding(12).glass(14).padding(20)
            }
        }
        .animation(.easeOut(duration: 0.2), value: visible)
        .onAppear { scheduleHide() }
        .confirmationDialog("الإعدادات", isPresented: $showSettings) { settingsButtons }
    }

    // MARK: الطبقات

    private var controls: some View {
        ZStack {
            Color.black.opacity(0.35).allowsHitTesting(false)
            VStack(spacing: 0) {
                // فوق: تصغير + العنوان … ترجمة · إعدادات · صورة داخل صورة · AirPlay
                HStack(spacing: 10) {
                    if !compact {
                        iconButton("chevron.down") { onFullscreen() }
                    }
                    Text(titleLine).font(compact ? .footnote.weight(.semibold) : .subheadline.weight(.semibold)).lineLimit(1)
                    Spacer()
                    if !pc.isLive { iconButton("captions.bubble") { showSettings = true } }
                    if !compact, pc.engine == .av, PiP.shared.available { iconButton("pip.enter") { PiP.shared.start() } }
                    AirPlayButton().frame(width: 30, height: 30)
                }
                .padding(.horizontal, compact ? 8 : 20)
                .padding(.top, compact ? 6 : 14)
                .environment(\.layoutDirection, .leftToRight)

                Spacer()
                // الوسط: السابقة · تشغيل · التالية (فيلم/مباشر بدون حلقات: تأخير · تشغيل · تقديم)
                HStack(spacing: compact ? 34 : 64) {
                    if pc.queue != nil || pc.isLive {
                        roundButton("backward.end.fill", size: compact ? 44 : 56) { pc.prev() }.opacity(pc.isLive || pc.hasPrev ? 1 : 0.35)
                    } else {
                        roundButton("gobackward.10", size: compact ? 44 : 56) { pc.seek(by: -10) }
                    }
                    roundButton(pc.isPlaying ? "pause.fill" : "play.fill", size: compact ? 62 : 78) { pc.togglePlay(); scheduleHide() }
                    if pc.queue != nil || pc.isLive {
                        roundButton("forward.end.fill", size: compact ? 44 : 56) { pc.learnManualNext(); pc.next() }.opacity(pc.isLive || pc.hasNext ? 1 : 0.35)
                    } else {
                        roundButton("goforward.10", size: compact ? 44 : 56) { pc.seek(by: 10) }
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                Spacer()

                // تحت: الوقت (أو مباشر) … تكبير/تصغير + شريط التقدّم
                VStack(spacing: compact ? 4 : 10) {
                    HStack {
                        if pc.isLive {
                            Button { pc.goLive() } label: {
                                Label("مباشر", systemImage: "dot.radiowaves.left.and.right").font(.caption.weight(.bold)).foregroundStyle(Theme.red)
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                            }.glass()
                        } else {
                            Text("\(fmtTime(scrub ?? pc.position)) / \(fmtTime(pc.duration))").font(.caption.monospacedDigit())
                        }
                        Spacer()
                        iconButton(compact ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left") { onFullscreen() }
                    }
                    if !pc.isLive && pc.duration > 0 { scrubber }
                }
                .padding(.horizontal, compact ? 10 : 20)
                .padding(.bottom, compact ? 4 : 14)
                .background { if !compact { Color.clear.glass(22).padding(.horizontal, 8) } }
                .padding(.bottom, compact ? 0 : 8)
                .environment(\.layoutDirection, .leftToRight)
            }
            .foregroundStyle(.white)
        }
    }

    private var titleLine: String {
        guard let r = pc.req else { return "" }
        return [r.title, r.sub].filter { !$0.isEmpty }.joined(separator: " · ")
    }

    private var scrubber: some View {
        GeometryReader { g in
            let w = g.size.width
            let v = (scrub ?? pc.position) / max(pc.duration, 1)
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.3)).frame(height: scrub == nil ? 4 : 7)
                Capsule().fill(Theme.red).frame(width: w * v, height: scrub == nil ? 4 : 7)
                Circle().fill(.white).frame(width: scrub == nil ? 14 : 20).offset(x: w * v - (scrub == nil ? 7 : 10))
                if let s = scrub {
                    Text(fmtTime(s)).font(.caption.weight(.semibold)).foregroundStyle(.black)
                        .padding(.horizontal, 8).padding(.vertical, 3).background(.white, in: Capsule())
                        .offset(x: min(max(0, w * v - 28), w - 56), y: -26)
                }
            }
            .frame(height: 24)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { d in hideTask?.cancel(); scrub = max(0, min(1, d.location.x / w)) * pc.duration }
                .onEnded { _ in
                    if let s = scrub { pc.learnFromSeek(from: pc.position, to: s); pc.seek(to: s) }
                    scrub = nil; scheduleHide()
                })
        }
        .frame(height: 24)
    }

    private func tapZone(_ side: Int) -> some View {
        Color.clear.contentShape(Rectangle())
            .onTapGesture(count: 2) { doubleTap(side) }
            .onTapGesture(count: 1) {
                if tapSide == side && tapAccum > 0 { doubleTap(side); return }      // ضغطات ورا بعض = +10 كل وحدة
                if pc.showNextUp { pc.cancelAutoNext() }
                visible.toggle()
                if visible { scheduleHide() }
            }
    }

    private func doubleTap(_ side: Int) {
        guard !pc.isLive || (pc.engine == .av && pc.player.currentItem?.seekableTimeRanges.isEmpty == false) else { return }
        if side != tapSide { tapAccum = 0 }
        tapSide = side
        tapAccum += 10
        let from = pc.position
        pc.seek(by: Double(side * 10))
        pc.learnFromSeek(from: from, to: pc.position)
        tapReset?.cancel()
        tapReset = Task { try? await Task.sleep(for: .milliseconds(800)); if !Task.isCancelled { tapSide = 0; tapAccum = 0 } }
    }

    private var seekArc: some View {
        GeometryReader { g in
            let w = g.size.width * 0.4
            HStack(spacing: 0) {
                if tapSide > 0 { Spacer() }
                VStack(spacing: 4) {
                    Image(systemName: tapSide > 0 ? "forward.fill" : "backward.fill")
                    Text("\(tapAccum) ثانية").font(.caption.weight(.bold))
                }
                .frame(width: w, height: g.size.height)
                .background(.white.opacity(0.14), in: UnevenRoundedRectangle(
                    topLeadingRadius: tapSide > 0 ? g.size.height : 0, bottomLeadingRadius: tapSide > 0 ? g.size.height : 0,
                    bottomTrailingRadius: tapSide > 0 ? 0 : g.size.height, topTrailingRadius: tapSide > 0 ? 0 : g.size.height))
                if tapSide < 0 { Spacer() }
            }
            .environment(\.layoutDirection, .leftToRight)
        }
        .allowsHitTesting(false)
        .transition(.opacity)
    }

    // MARK: عناصر صغيرة

    private func roundButton(_ sym: String, size: CGFloat, _ action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: sym).font(.system(size: size * 0.42, weight: .semibold)).frame(width: size, height: size)
        }
        .buttonStyle(.plain)
        .glassCircle()
    }

    private func iconButton(_ sym: String, _ action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: sym).font(.system(size: 17, weight: .semibold)).frame(width: 36, height: 36)
        }
        .buttonStyle(.plain)
    }

    private func floatButton(_ t: String, icon: String, count: Int? = nil, _ action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let count { Text("\(count)").font(.caption.weight(.bold)).frame(width: 24, height: 24).overlay(Circle().stroke(.black, lineWidth: 2)) }
                Text(t).font(.subheadline.weight(.bold))
                Image(systemName: icon)
            }
            .foregroundStyle(.black)
            .padding(.horizontal, 14).frame(height: 44)
            .background(.white, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
        }
        .buttonStyle(.plain)
        .environment(\.layoutDirection, .rightToLeft)
    }

    @ViewBuilder private var settingsButtons: some View {
        Button("سرعة 1x") { pc.setRate(1) }
        Button("سرعة 1.25x") { pc.setRate(1.25) }
        Button("سرعة 1.5x") { pc.setRate(1.5) }
        Button("سرعة 2x") { pc.setRate(2) }
        Button("بدون ترجمة") { pc.selectTrack(nil, audio: false) }
        ForEach(pc.tracks(audio: false)) { t in Button("ترجمة: \(t.name)") { pc.selectTrack(t, audio: false) } }
        ForEach(pc.tracks(audio: true)) { t in Button("الصوت: \(t.name)") { pc.selectTrack(t, audio: true) } }
    }

    private func scheduleHide() {
        hideTask?.cancel()
        hideTask = Task {
            try? await Task.sleep(for: .seconds(3.5))
            if !Task.isCancelled && pc.isPlaying && scrub == nil { visible = false }
        }
    }
}

// MARK: - المشغّل الصغير (فوق صفحة الفيلم/المسلسل وصفحة المباشر)

struct InlinePlayer: View {
    @Environment(PlayerController.self) private var pc

    var body: some View {
        ZStack {
            Color.black
            VideoSurface(active: !pc.fullscreen)
            PlayerOverlay(compact: true) { enterFullscreen() }
        }
        .aspectRatio(16 / 9, contentMode: .fit)
    }

    private func enterFullscreen() {
        withAnimation(.spring(duration: 0.45, bounce: 0.05)) { pc.fullscreen = true }
    }
}

// MARK: - ملء الشاشة (يغطي التطبيق كله، ويلف الجوال أفقي)

struct FullscreenPlayer: View {
    @Environment(PlayerController.self) private var pc
    @Environment(\.horizontalSizeClass) private var hsize

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            VideoSurface(active: true).ignoresSafeArea()
            PlayerOverlay(compact: false) { exit() }
        }
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        .onAppear { if UIDevice.current.userInterfaceIdiom == .phone { Orientation.set(.landscape) } }
        .gesture(DragGesture(minimumDistance: 40).onEnded { d in if d.translation.height > 120 { exit() } })   // سحب لتحت = تصغير
    }

    private func exit() {
        if UIDevice.current.userInterfaceIdiom == .phone { Orientation.set(.portrait) }
        withAnimation(.spring(duration: 0.45, bounce: 0.05)) { pc.fullscreen = false }
        Task { try? await Task.sleep(for: .seconds(1)); Orientation.unlock() }
    }
}

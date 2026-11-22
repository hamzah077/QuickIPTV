import AVKit
import SwiftUI

// MARK: - سطح الفيديو (AVPlayerLayer) + صورة داخل صورة

final class PlayerLayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
}

/// سطح الفيديو حسب المحرّك. active = هذي الشاشة اللي تعرض الحين (الصغيرة أو ملء الشاشة)
/// شاشة الفيديو وحدة بس وتنتقل بين الصغيرة وملء الشاشة — ما تنعاد تتجهّز = بدون شاشة سودا وحركة التكبير ناعمة
struct VideoSurface: View {
    @Environment(PlayerController.self) private var pc
    var active: Bool = true
    var body: some View {
        if pc.engine == .vlc { HostedSurface(view: pc.vlcView, active: active) }
        else { HostedSurface(view: pc.avView, active: active) }
    }
}

/// حاوية تستضيف شاشة الفيديو المشتركة لما تكون هي الشاشة المعروضة
struct HostedSurface: UIViewRepresentable {
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
    var active: Bool { ctrl?.isPictureInPictureActive ?? false }
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
    /// سحب المشغّل لتحت (الجوال): المسافة، وهل خلص السحب
    var onDragDown: ((CGFloat, Bool) -> Void)? = nil
    var onFullscreen: () -> Void

    @State private var visible = true
    @State private var hideTask: Task<Void, Never>?
    @State private var scrub: Double?        // وقت السحب
    @State private var tapSide: Int = 0      // -1 يسار، 1 يمين
    @State private var tapAccum = 0
    @State private var tapReset: Task<Void, Never>?
    @State private var showSettings = false
    /// القوائم (نفس الأندرويد): الإعدادات ← السرعة / الصوت / الترجمة، وزر الترجمة لحاله
    enum PMenu: Identifiable { case main, speed, audio, subs; var id: Self { self } }
    @State private var menu: PMenu?
    @State private var showChannels = false
    @State private var toast: String?
    @State private var toastTask: Task<Void, Never>?
    @State private var draggingDown = false
    @State private var draggingUp = false

    var body: some View {
        ZStack {
            // اللمس: ضغطة = إظهار/إخفاء، ضغطتين يمين/يسار = ±10 (ورا بعض تتجمّع)
            HStack(spacing: 0) {
                tapZone(-1)
                tapZone(1)
            }
            .environment(\.layoutDirection, .leftToRight)
            // (المسافة بالنسبة للشاشة — المشغّل نفسه يتحرك ويصغر وأنت تسحب، فلو قسناها بالنسبة له تتذبذب)
            // المشغّل الصغير: السحب يبدأ يتبع إصبعك من أول حركة (نفس الأندرويد)
            .gesture(DragGesture(minimumDistance: compact ? 10 : 30, coordinateSpace: .global)
            .onChanged { d in
                guard compact else { return }
                let dx = d.translation.width, dy = d.translation.height
                // سحب لفوق = الفيديو يكبر ويلف مع إصبعك لين يملى الشاشة (نفس زر التكبير)
                if !draggingDown && !draggingUp && PlayerController.phone && dy < 0 && -dy > abs(dx) { draggingUp = true }
                if draggingUp {
                    // زي يوتيوب: الفيديو يكبر شوي مع إصبعك (بمقاومة، يثقل كل ما سحبت أكثر)
                    let up = max(0, -dy)
                    var t = Transaction(); t.disablesAnimations = true
                    withTransaction(t) { pc.fsPull = min(1, up / (up + 160)) }
                    return
                }
                // المشغّل فوق الصفحة: سحب لتحت = الصفحة تتبع إصبعك وتصغر (زي يوتيوب)
                guard let f = onDragDown else { return }
                if !draggingDown && dy > 0 && dy > abs(dx) { draggingDown = true }
                if draggingDown { f(max(0, dy), false) }
            }
            .onEnded { d in
                let dx = d.translation.width, dy = d.translation.height
                if draggingUp {
                    // زي يوتيوب: سحبة واضحة لفوق (أو سريعة) = ملء الشاشة، خفيفة = يرجع مكانه
                    draggingUp = false
                    let fast = d.predictedEndTranslation.height < -220
                    if -dy > 60 || fast {
                        var t = Transaction(); t.disablesAnimations = true
                        withTransaction(t) { pc.fsPull = 0 }
                        pc.autoFullscreen = false
                        pc.fullscreen = true                         // نفس حركة زر التكبير (يكبر ويلف)
                    } else {
                        withAnimation(.spring(duration: 0.35, bounce: 0.3)) { pc.fsPull = 0 }
                    }
                    return
                }
                if draggingDown, let f = onDragDown { draggingDown = false; f(max(0, dy), true); return }
                if !compact && dy > 110 && abs(dx) < 80 { onFullscreen(); return }          // سحب لتحت = تصغير
                guard abs(dx) > 90, abs(dy) < 60, pc.isLive || pc.queue != nil else { return }
                if dx < 0 { pc.learnManualNext(); pc.next() } else { pc.prev() }         // سحب = الحلقة/القناة التالية أو السابقة
            })

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
                VStack { Spacer(); PlaybackLine(height: 3) }
            }

            if pc.buffering && pc.failed == nil { ProgressView().controlSize(.large).tint(.white) }
            if let f = pc.failed {
                Text(f).font(.footnote).multilineTextAlignment(.center).padding(12).glass(14).padding(20)
            }
        }
        .overlay(alignment: .top) {
            if let toast {
                Text(toast).font(.footnote.weight(.semibold)).padding(.horizontal, 14).padding(.vertical, 9)
                    .glass(14).padding(.top, compact ? 10 : 40).transition(.opacity)
            }
        }
        // قائمة القنوات بملء الشاشة (تطلع من الجنب — نفس الأندرويد)
        .overlay {
            if showChannels {
                HStack(spacing: 0) { Spacer(minLength: 0); channelsPanel }
                    .environment(\.layoutDirection, .leftToRight)       // على يمين الشاشة
                    .transition(.move(edge: .trailing))
            }
        }
        .animation(.easeOut(duration: 0.2), value: visible)
        .animation(.easeOut(duration: 0.2), value: showChannels)
        .animation(.easeOut(duration: 0.2), value: toast)
        .onAppear { scheduleHide() }
        .confirmationDialog(menuTitle, isPresented: Binding(get: { menu != nil }, set: { if !$0 { menu = nil } }), titleVisibility: .visible) {
            menuButtons
        }
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
                    } else if let f = onDragDown, PlayerController.phone {
                        iconButton("chevron.down") { f(10_000, true) }          // صفحة التشغيل: تصغير للمشغّل الصغير
                    }
                    Text(titleLine).font(compact ? .footnote.weight(.semibold) : .subheadline.weight(.semibold)).lineLimit(1)
                    Spacer()
                    // نفس الأندرويد: زر الترجمة لحاله، وزر الإعدادات (السرعة، الصوت، الترجمة)
                    if !pc.isLive {
                        iconButton("captions.bubble") { openMenu(.subs) }
                        iconButton("gearshape") { menu = .main }
                    }
                    if !compact, pc.engine == .av, PiP.shared.available { iconButton("pip.enter") { PiP.shared.start() } }
                    AirPlayButton().frame(width: 30, height: 30).frame(width: 44, height: 44)
                        .accessibilityLabel("AirPlay")
                }
                .padding(.horizontal, compact ? 8 : 20)
                .padding(.top, compact ? 6 : 14)
                .environment(\.layoutDirection, .leftToRight)

                Spacer()
                // الوسط: السابقة · تشغيل · التالية (فيلم/مباشر بدون حلقات: تأخير · تشغيل · تقديم)
                HStack(spacing: compact ? 34 : 64) {
                    // نفس الأندرويد: المسلسل = الحلقة السابقة/التالية، الفيلم والمباشر = تأخير/تقديم 10 ثواني
                    if pc.queue != nil && !pc.isLive {
                        roundButton("backward.end.fill", size: compact ? 44 : 56) { pc.prev() }.opacity(pc.hasPrev ? 1 : 0.35)
                    } else {
                        roundButton("gobackward.10", size: compact ? 44 : 56) { seekTen(-1) }.opacity(pc.isLive && !pc.liveSeekable ? 0.4 : 1)
                    }
                    roundButton(pc.isPlaying ? "pause.fill" : "play.fill", size: compact ? 62 : 78) { pc.togglePlay(); scheduleHide() }
                    if pc.queue != nil && !pc.isLive {
                        roundButton("forward.end.fill", size: compact ? 44 : 56) { pc.learnManualNext(); pc.next() }.opacity(pc.hasNext ? 1 : 0.35)
                    } else {
                        roundButton("goforward.10", size: compact ? 44 : 56) { seekTen(1) }.opacity(pc.isLive && !pc.liveSeekable ? 0.4 : 1)
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                Spacer()

                // تحت: الوقت (أو مباشر) … تكبير/تصغير + شريط التقدّم
                VStack(spacing: compact ? 4 : 10) {
                    HStack {
                        if pc.isLive {
                            // ● مباشر (أحمر) = على اللحظة الحالية، ↻ مباشر (رمادي) = متأخر — تضغطه يرجعك للحظة الحالية
                            let behind = pc.liveBehind
                            Button { pc.goLive() } label: {
                                Text(behind ? "↻  مباشر" : "●  مباشر").font(.caption.weight(.bold))
                                    .foregroundStyle(behind ? Color(white: 0.79) : Theme.red)
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                            }.glass()
                        } else {
                            PlaybackTime(scrub: scrub)
                        }
                        Spacer()
                        if pc.isLive && !compact && !pc.channels.isEmpty {
                            Button { showChannels = true; visible = false } label: {
                                Label("القنوات", systemImage: "list.bullet").font(.caption.weight(.bold))
                                    .padding(.horizontal, 10).padding(.vertical, 6)
                            }.glass()
                        }
                        iconButton(compact ? "arrow.up.left.and.arrow.down.right" : "arrow.down.right.and.arrow.up.left") { onFullscreen() }
                    }
                    // المشغّل الصغير: الخط الأحمر على حافة الفيديو نفسها (نفس الأندرويد)
                    if !pc.isLive && pc.duration > 0 { scrubber.padding(.horizontal, compact ? -10 : 0).offset(y: compact ? 10 : 0) }
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
        PlaybackScrubber(scrub: $scrub, onBegin: { hideTask?.cancel() }, onEnd: { scheduleHide() })
    }

    // MARK: عناصر صغيرة

    private func tapZone(_ side: Int) -> some View {
        Color.clear.contentShape(Rectangle())
            .onTapGesture(count: 2) { doubleTap(side) }
            .onTapGesture(count: 1) {
                if showChannels { showChannels = false; return }                   // قائمة القنوات مفتوحة: ضغطة برا = تقفلها
                if tapSide == side && tapAccum > 0 { doubleTap(side); return }      // ضغطات ورا بعض = +10 كل وحدة
                if pc.showNextUp { pc.cancelAutoNext() }
                visible.toggle()
                if visible { scheduleHide() }
            }
    }

    private func doubleTap(_ side: Int) {
        // المباشر: ضغطتين = إظهار/إخفاء الأزرار (التأخير والتقديم بالأزرار) — نفس الأندرويد
        if pc.isLive { visible.toggle(); if visible { scheduleHide() }; return }
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

    private func roundButton(_ sym: String, size: CGFloat, _ action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: sym).font(.system(size: size * 0.42, weight: .semibold)).frame(width: size, height: size)
        }
        .buttonStyle(.plain)
        .glassCircle()
        .accessibilityIdentifier(sym)
        .accessibilityLabel(Self.label(sym))
    }

    /// الشكل 36، بس منطقة اللمس 44 (نصيحة آبل)
    private func iconButton(_ sym: String, _ action: @escaping () -> Void) -> some View {
        Button { action(); scheduleHide() } label: {
            Image(systemName: sym).font(.system(size: 17, weight: .semibold)).frame(width: 36, height: 36)
                .frame(width: 44, height: 44).contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier(sym)
        .accessibilityLabel(Self.label(sym))
    }

    /// قارئ الشاشة يقرا اسم الزر بالعربي بدل اسم الأيقونة
    static func label(_ sym: String) -> String {
        switch sym {
        case "chevron.down": "تصغير"
        case "captions.bubble": "الترجمة"
        case "gearshape": "الإعدادات"
        case "pip.enter": "صورة داخل صورة"
        case "arrow.up.left.and.arrow.down.right": "ملء الشاشة"
        case "arrow.down.right.and.arrow.up.left": "خروج من ملء الشاشة"
        case "backward.end.fill": "الحلقة السابقة"
        case "forward.end.fill": "الحلقة التالية"
        case "gobackward.10": "رجوع 10 ثواني"
        case "goforward.10": "تقديم 10 ثواني"
        case "play.fill": "تشغيل"
        case "pause.fill": "إيقاف مؤقت"
        default: sym
        }
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

    private func fmtSpeed(_ r: Float) -> String { r == 1 ? "عادي" : "\(r.formatted())x" }

    private var menuTitle: String {
        switch menu { case .speed: "سرعة التشغيل"; case .audio: "الصوت"; case .subs: "الترجمة"; default: "الإعدادات" }
    }

    /// الصوت/الترجمة: لو ما فيه خيارات نقول لك بدل قائمة فاضية (نفس الأندرويد)
    private func openMenu(_ m: PMenu) {
        if m == .audio && pc.tracks(audio: true).count < 2 { showToast("ما فيه أكثر من صوت"); return }
        if m == .subs && pc.tracks(audio: false).isEmpty { showToast("ما فيه ترجمة"); return }
        menu = m
    }

    @ViewBuilder private var menuButtons: some View {
        switch menu {
        case .speed:
            ForEach([Float(0.5), 0.75, 1, 1.25, 1.5, 2], id: \.self) { r in
                Button((pc.rate == r ? "✓ " : "") + fmtSpeed(r)) { pc.setRate(r) }
            }
        case .audio:
            ForEach(pc.tracks(audio: true)) { t in Button(t.name) { pc.selectTrack(t, audio: true) } }
        case .subs:
            Button("إيقاف") { pc.selectTrack(nil, audio: false) }
            ForEach(pc.tracks(audio: false)) { t in Button(t.name) { pc.selectTrack(t, audio: false) } }
        default:
            Button("سرعة التشغيل  (\(fmtSpeed(pc.rate)))") { reopen(.speed) }
            Button("الصوت") { reopen(.audio) }
            Button("الترجمة") { reopen(.subs) }
        }
        Button("إلغاء", role: .cancel) {}
    }

    /// من قائمة الإعدادات لقائمة فرعية (بعد ما تتقفل الأولى)
    private func reopen(_ m: PMenu) {
        Task { try? await Task.sleep(for: .milliseconds(350)); openMenu(m) }
    }

    private func showToast(_ t: String) {
        toast = t
        toastTask?.cancel()
        toastTask = Task { try? await Task.sleep(for: .seconds(2)); if !Task.isCancelled { toast = nil } }
    }

    /// ±10 ثواني (المباشر: لو البث ما يدعم، نقول لك)
    private func seekTen(_ dir: Double) {
        if pc.isLive && !pc.liveSeekable { showToast("هذا البث ما يدعم التقديم والتأخير"); return }
        let from = pc.position
        pc.seek(by: dir * 10)
        if !pc.isLive { pc.learnFromSeek(from: from, to: pc.position) }
    }

    /// قائمة القنوات بملء الشاشة: القناة الشغّالة مميّزة، تضغط قناة = تشتغل
    private var channelsPanel: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("القنوات  ·  \(pc.channels.count)").font(.headline)
                Spacer()
                Button { showChannels = false } label: { Image(systemName: "xmark").frame(width: 44, height: 44).contentShape(Rectangle()) }
                    .buttonStyle(.plain).accessibilityLabel("إغلاق القنوات")
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 2) {
                        ForEach(pc.channels) { ch in
                            let on = pc.req?.key == "live:\(ch.id)"
                            Button { pc.playChannel(ch) } label: {
                                HStack(spacing: 10) {
                                    Poster(url: ch.icon, radius: 8, contentMode: .fit).frame(width: 40, height: 40)
                                    Text("\(ch.num > 0 ? "\(ch.num). " : "")\(ch.name)").lineLimit(1)
                                    Spacer()
                                    if on { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Theme.muted) }
                                }
                                .padding(.horizontal, 12).padding(.vertical, 6)
                                .background(on ? Color.white.opacity(0.14) : .clear, in: RoundedRectangle(cornerRadius: 10))
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .id(ch.id)
                        }
                    }
                    .padding(.horizontal, 6).padding(.bottom, 12)
                }
                .onAppear { if let k = pc.req?.key { proxy.scrollTo(String(k.dropFirst(5)), anchor: .center) } }
            }
        }
        .foregroundStyle(.white)
        .frame(width: 340)
        .frame(maxHeight: .infinity)
        .background(.black.opacity(0.82))
        .environment(\.layoutDirection, .rightToLeft)
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
    /// سحب لتحت (الجوال): 0 مكانه … 1 النافذة العائمة
    @State private var t: CGFloat = 0
    @State private var from: CGRect = .zero
    @State private var to: CGRect = .zero

    var body: some View {
        // نفس حركة الأفلام: الفيديو يصغر مع إصبعك لمكان النافذة العائمة بالضبط وزواياه تتقعّر من أول ما تسحب
        let s = from.width > 0 ? 1 + (to.width / from.width - 1) * t : 1
        let r = min(14, t * 120) / max(0.01, s)
        ZStack {
            Color.black
            VideoSurface(active: !pc.fullscreen && !pc.mini)
            PlayerOverlay(compact: true, onDragDown: PlayerController.phone ? { dy, ended in dragDown(dy, ended) } : nil) {
                pc.autoFullscreen = false; pc.fullscreen = true
            }
            .opacity(Double(max(0, 1 - t * 4)))
        }
        .aspectRatio(16 / 9, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: r, style: .continuous))
        .scaleEffect(1 + pc.fsPull * 0.12)              // سحب لفوق: يكبر شوي مع إصبعك (زي يوتيوب)
        .scaleEffect(s, anchor: .topLeading)
        .offset(x: (to.minX - from.minX) * t, y: (to.minY - from.minY) * t)
        .zIndex(t > 0 ? 10 : 0)
        .opacity(pc.fullscreen ? 0 : 1)                 // وقت ملء الشاشة الفيديو "طالع" من هنا
        .background(GeometryReader { g in
            Color.clear
                .onAppear { pc.inlineFrame = g.frame(in: .global) }
                .onChange(of: g.frame(in: .global)) { _, f in if !pc.fullscreen { pc.inlineFrame = f } }
        })
    }
}

extension InlinePlayer {
    /// سحبته لتحت: يتبع إصبعك لين النافذة العائمة. تركته بعد ثلث المسافة = يكمل ويصير النافذة، وإلا يرجع مكانه.
    fileprivate func dragDown(_ dy: CGFloat, _ ended: Bool) {
        if from == .zero || (t == 0 && !ended) {
            from = pc.inlineFrame
            to = WatchPanel.floatRect(corner: pc.miniCorner)
        }
        let range = max(150, to.minY - from.minY)
        let v = min(1, max(0, dy / range))
        guard ended else {
            var tr = Transaction(); tr.disablesAnimations = true
            withTransaction(tr) { t = v }
            return
        }
        if v > 0.3 {
            withAnimation(PanelHost.ease(1 - v)) { t = 1 } completion: {
                var tr = Transaction(); tr.disablesAnimations = true
                withTransaction(tr) { pc.mini = true; t = 0; from = .zero }     // النافذة العائمة تطلع بنفس المكان بالضبط
            }
        } else {
            withAnimation(PanelHost.ease(v)) { t = 0 } completion: { from = .zero }
        }
    }
}

// MARK: - ملء الشاشة: يكبر من مكان المشغّل الصغير ويلف ربع لفّة (زي يوتيوب) — مرسوم داخل التطبيق = سلس 100٪

struct FullscreenPlayer: View {
    @Environment(PlayerController.self) private var pc
    @State private var shown = false
    @State private var angle: Double = 90

    var body: some View {
        GeometryReader { g in
            let W = g.size.width, H = g.size.height
            // الجوال عمودي: نلف الفيديو بأنفسنا (بدون لفّة النظام) — الجوال أفقي أو آيباد: بدون لف
            let rot = UIDevice.current.userInterfaceIdiom == .phone && H > W
            let fw = rot ? H : W, fh = rot ? W : H
            let f = pc.inlineFrame.width > 0 ? pc.inlineFrame : CGRect(x: 0, y: (H - W * 9 / 16) / 2, width: W, height: W * 9 / 16)
            // 0 = مكانه بالصفحة، 1 = يملى الشاشة — وأنت تسحب لفوق يتبع إصبعك
            let p = pc.fsDrag ?? (shown ? 1 : 0)
            let cw = f.width + (fw - f.width) * p, ch = f.height + (fh - f.height) * p
            ZStack {
                Color.black.opacity(min(1, p * 1.3))
                ZStack {
                    Color.black
                    VideoSurface(active: true)
                    if shown && pc.fsDrag == nil { PlayerOverlay(compact: false) { exit() }.transition(.opacity) }
                }
                .frame(width: cw, height: ch)
                .clipped()
                .rotationEffect(.degrees(rot ? angle * p : 0))
                .position(x: f.midX + (W / 2 - f.midX) * p, y: f.midY + (H / 2 - f.midY) * p)
            }
        }
        .ignoresSafeArea()
        .statusBarHidden()
        .persistentSystemOverlays(.hidden)
        // لفّيت الجوال للجهة الثانية وهو بملء الشاشة: الفيديو يلف معك (التطبيق نفسه يبقى عمودي)
        .onReceive(NotificationCenter.default.publisher(for: UIDevice.orientationDidChangeNotification)) { _ in
            let o = UIDevice.current.orientation
            guard shown, o == .landscapeLeft || o == .landscapeRight else { return }
            let a: Double = o == .landscapeRight ? -90 : 90
            if a != angle { withAnimation(.easeInOut(duration: 0.35)) { angle = a } }
        }
        .onAppear {
            // اتجاه اللفّة حسب مسكة الجوال
            angle = UIDevice.current.orientation == .landscapeRight ? -90 : 90
            if UIDevice.current.userInterfaceIdiom == .phone && !pc.autoFullscreen { Orientation.lock(.portrait) }
            // جاي من السحب لفوق: الحركة يقودها إصبعك (shown يصير جاهز بدون حركة)
            if pc.fsDrag != nil { shown = true }
            else { withAnimation(.spring(duration: 0.5, bounce: 0.06)) { shown = true } }
        }
    }

    private func exit() {
        withAnimation(.spring(duration: 0.45, bounce: 0.04)) { shown = false } completion: {
            pc.fullscreen = false
            pc.autoFullscreen = false
            // ما فيه صفحة تعرضه (مثل التشغيل من التحميلات)؟ يكمل في المشغّل الصغير تحت
            if PlayerController.phone, let r = pc.req, pc.watchVod == nil, !pc.hostVisible(r), !pc.mini { pc.mini = true }
            Orientation.unlockWhenPortrait()            // يرجع يلف عادي بس لما تمسك الجوال عمودي
        }
    }
}


// MARK: - أجزاء صغيرة تقرا وقت التشغيل (يتغيّر كل نص ثانية) — عشان ما ينعاد رسم المشغّل كله ولا الصفحة معه

/// الخط الأحمر الرفيع (لما الأزرار مخفية، وتحت النافذة العائمة)
struct PlaybackLine: View {
    @Environment(PlayerController.self) private var pc
    var height: CGFloat = 3
    var body: some View { ProgressLine(value: pc.duration > 0 ? pc.position / pc.duration : 0, height: height) }
}

/// "0:00 / 1:00:00"
struct PlaybackTime: View {
    @Environment(PlayerController.self) private var pc
    let scrub: Double?
    var body: some View { Text("\(fmtTime(scrub ?? pc.position)) / \(fmtTime(pc.duration))").font(.caption.monospacedDigit()) }
}

/// شريط التقديم (تسحبه وتطلع فقاعة الوقت)
struct PlaybackScrubber: View {
    @Environment(PlayerController.self) private var pc
    @Binding var scrub: Double?
    var onBegin: () -> Void
    var onEnd: () -> Void

    var body: some View {
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
                .onChanged { d in onBegin(); scrub = max(0, min(1, d.location.x / w)) * pc.duration }
                .onEnded { _ in
                    if let s = scrub { pc.learnFromSeek(from: pc.position, to: s); pc.seek(to: s) }
                    scrub = nil; onEnd()
                })
            .accessibilityElement()
            .accessibilityLabel("شريط التقديم")
            .accessibilityValue("\(fmtTime(pc.position)) من \(fmtTime(pc.duration))")
            .accessibilityAdjustableAction { dir in
                if dir == .increment { pc.seek(by: 10) } else if dir == .decrement { pc.seek(by: -10) }
            }
        }
        .frame(height: 24)
    }
}

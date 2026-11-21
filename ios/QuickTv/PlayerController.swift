import AVFoundation
import AVKit
import Observation
import SwiftUI
import VLCKitSPM

/// المحرّك: مشغّل آبل (HLS/MP4 — يدعم صورة داخل صورة و AirPlay) أو VLC (كل الصيغ الثانية)
enum Engine { case av, vlc }

/// مشغّل واحد مشترك: الشاشة الصغيرة وملء الشاشة يعرضون نفس المشغّل = التكبير والتصغير بدون إعادة تحميل
@MainActor
@Observable
final class PlayerController {
    static let shared = PlayerController()

    let player = AVPlayer()
    /// نسوي مشغّل VLC جديد مع كل تشغيل، والقديم نوقفه بالخلفية (إيقاف VLC يعلّق الواجهة ثواني لو السيرفر بطيء)
    private(set) var vlc = VLCMediaPlayer()
    @ObservationIgnored private let vlcStopQueue = DispatchQueue(label: "quicktv.vlc.stop", qos: .utility)
    /// شاشة VLC (وحدة، تنتقل بين الشاشة الصغيرة وملء الشاشة)
    let vlcView: UIView = { let v = UIView(); v.backgroundColor = .black; v.isUserInteractionEnabled = false; return v }()
    /// شاشة مشغّل آبل (وحدة، تنتقل بين الشاشة الصغيرة وملء الشاشة)
    let avView: PlayerLayerView = { let v = PlayerLayerView(); v.backgroundColor = .black; v.isUserInteractionEnabled = false; return v }()
    private(set) var engine: Engine = .av
    private(set) var req: PlayRequest?
    var queue: SeriesQueue?                 // حلقات المسلسل (السابقة/التالية)
    var channels: [Channel] = []            // قنوات التصنيف (المباشر: التالية/السابقة)

    // الحالة للواجهة
    private(set) var isPlaying = false
    private(set) var position: Double = 0
    private(set) var duration: Double = 0
    private(set) var buffering = false
    private(set) var failed: String?
    var fullscreen = false
    /// المشغّل الصغير تحت (الجوال): سحبت الصفحة لتحت أو طلعت من المباشر وهو شغّال
    var mini = false
    /// صفحة التشغيل (الجوال): الفيلم/المسلسل اللي صفحته فوق كل شي وتنسحب لتحت وتصير المشغّل الصغير
    var watchVod: Vod?
    /// زاوية النافذة العائمة: 0 فوق يسار، 1 فوق يمين، 2 تحت يسار، 3 تحت يمين
    var miniCorner = 3
    /// الصفحات اللي تعرض المشغّل الحين (عشان التكبير من الصغير يرجع لها لو هي ظاهرة)
    @ObservationIgnored private var hosts: [String: Int] = [:]
    /// دخل ملء الشاشة لأنك لفّيت الجوال (يطلع لما ترجعه عمودي)
    var autoFullscreen = false
    /// مكان المشغّل الصغير بالشاشة (تبدأ منه حركة التكبير وترجع له)
    @ObservationIgnored var inlineFrame: CGRect = .zero

    var isLive: Bool { req?.type == "live" }
    var hasPrev: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i > 0 }
    var hasNext: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i < q.list.count - 1 }

    private var timeObs: Any?
    @ObservationIgnored private var waitTask: Task<Void, Never>?
    // VLC: نعرف إنه شغّال فعلاً من تقدّم الوقت (حالة VLC تقول "buffering" حتى وهو شغّال)
    @ObservationIgnored private var lastVlcT: Double = -1
    @ObservationIgnored private var vlcStallTicks = 0
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var waitObs: NSKeyValueObservation?
    private var lastSave = Date.distantPast

    // مسارات الصوت والترجمة: تنحمّل مرة وحدة بالخلفية (السؤال المباشر عنها كان يعلّق الواجهة لين يتحمّل الملف)
    private(set) var audioTracks: [Track] = []
    private(set) var subTracks: [Track] = []
    @ObservationIgnored private var avGroups: [AVMediaCharacteristic: AVMediaSelectionGroup] = [:]
    @ObservationIgnored private var vlcTracksLoaded = false

    private var vlcTimer: Timer?

    private init() {
        vlc.drawable = vlcView
        avView.playerLayer.player = player
        avView.playerLayer.videoGravity = .resizeAspect
        PiP.shared.attach(avView.playerLayer)
        // لازم true: لو false، الـ AVPlayer إذا خلص البفر يوقف ويطلع تقطيع وما يكمّل لحاله
        player.automaticallyWaitsToMinimizeStalling = true
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        waitObs = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let st = p.timeControlStatus
            let noItem = p.reasonForWaitingToPlay == .noItemToPlay
            Task { @MainActor in
                guard let self, self.engine == .av else { return }
                self.isPlaying = st == .playing
                self.waitTask?.cancel()
                if st == .waitingToPlayAtSpecifiedRate && !noItem {
                    // علامة التحميل تطلع بس إذا الانتظار طوّل (نص ثانية) — ما ترمش مع كل لحظة تعبئة
                    self.waitTask = Task { @MainActor [weak self] in
                        try? await Task.sleep(for: .milliseconds(500))
                        guard let self, !Task.isCancelled, self.engine == .av,
                              self.player.timeControlStatus == .waitingToPlayAtSpecifiedRate else { return }
                        self.buffering = true
                    }
                } else {
                    self.buffering = false
                }
            }
        }
    }

    // MARK: التشغيل

    /// صيغ يشغّلها مشغّل آبل؛ الباقي (MKV/AVI/TS…) على VLC
    private static func engineFor(_ u: URL) -> Engine {
        let ext = u.pathExtension.lowercased()
        return ["m3u8", "mp4", "m4v", "mov", "mp3", "m4a"].contains(ext) ? .av : .vlc
    }

    func play(_ r0: PlayRequest) {
        var r = r0
        // محمّل بالجهاز؟ نشغّله منه (بدون إنترنت)
        if r.type != "live", !r.url.isFileURL, let local = Downloads.shared.localURL(r.key) { r.url = local }
        saveNow()
        failed = nil
        req = r
        AppModel.shared.addHistory(r)
        resetSkip()
        position = r.start; duration = 0
        isPlaying = false; buffering = true               // تحميل البداية: علامة التحميل لين يبدأ فعلاً
        waitTask?.cancel()
        lastVlcT = -1; vlcStallTicks = 0
        if Self.engineFor(r.url) == .vlc { playVLC(r); return }
        stopVLC()
        engine = .av
        let item = AVPlayerItem(url: r.url)
        // المباشر: النظام يحدد البفر حسب السيرفر (أثبت). الأفلام: بفر أكبر عشان ما يقطع على النت البطيء
        item.preferredForwardBufferDuration = 0          // النظام يحدد (يبدأ أسرع ويعبّي على قد النت)
        userPaused = false
        clearTracks()
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            let st = it.status
            Task { @MainActor in
                guard let self, self.engine == .av, self.player.currentItem === it else { return }
                // مشغّل آبل ما قدر عليه؟ نحوّل لـ VLC تلقائياً
                if st == .failed, var r = self.req {
                    // مشغّل آبل ما قدر على البث؟ نجرّب نفس القناة بصيغة TS على VLC (بعض السيرفرات HLS عندها خربان)
                    if r.type == "live", r.url.pathExtension.lowercased() == "m3u8" {
                        r.url = r.url.deletingPathExtension().appendingPathExtension("ts")
                        self.req = r
                    }
                    self.playVLC(r)
                }
                if st == .readyToPlay {
                    // جاهز؟ يبدأ على طول (بدون ما ينتظر يعبّي بفر كبير). وإذا خلص البفر بعدين، ينتظر ويكمل لحاله
                    let d = it.duration.seconds
                    if d.isFinite, d > 0 { self.duration = d }
                    if !self.userPaused { self.player.playImmediately(atRate: 1) }
                    await self.loadAVTracks(it)
                }
            }
        }
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        }
        seekTarget = nil
        player.replaceCurrentItem(with: item)
        if r.start > 0 {
            // نكمل من وين وقفت: سماح ثانيتين = يبدأ من أقرب نقطة جاهزة (أسرع بكثير من الثانية بالضبط)
            let tol = CMTime(seconds: 2, preferredTimescale: 600)
            player.seek(to: CMTime(seconds: r.start, preferredTimescale: 600), toleranceBefore: tol, toleranceAfter: tol)
        }
        player.playImmediately(atRate: 1)        // يبدأ أول ما يقدر، وإذا خلص البفر بعدين ينتظر ويكمل لحاله
    }

    // MARK: VLC

    private func playVLC(_ r: PlayRequest) {
        stopVLC()
        engine = .vlc
        clearTracks()
        player.pause(); player.replaceCurrentItem(with: nil)
        let m = VLCMedia(url: r.url)
        // بفر: كافي يمنع التقطيع، وصغير يخلي البداية سريعة
        m.addOption(r.type == "live" ? ":network-caching=2000" : ":network-caching=1500")
        m.addOption(":live-caching=2000")
        m.addOption(":http-reconnect")                                                    // ينقطع السيرفر؟ يرجع يتصل
        m.addOption(":http-user-agent=QuickTv/1.0")
        if r.start > 0 { m.addOption(":start-time=\(Int(r.start))") }
        vlc.media = m
        vlc.play()
        vlcTimer?.invalidate()
        vlcTimer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.vlcTick() }
        }
    }

    private func stopVLC() {
        vlcTimer?.invalidate(); vlcTimer = nil
        guard vlc.media != nil else { return }
        let old = vlc
        old.drawable = nil
        nonisolated(unsafe) let p = old
        vlcStopQueue.async { p.stop() }                 // بالخلفية: الواجهة ما تعلق
        vlc = VLCMediaPlayer()
        vlc.drawable = vlcView
    }

    private var vlcEndHandled = false

    private func vlcTick() {
        guard engine == .vlc else { return }
        let t = Double(vlc.time.intValue) / 1000
        if t.isFinite, t >= 0, seekTarget == nil { position = t }
        if let len = vlc.media?.length.intValue, len > 0 { duration = Double(len) / 1000 }
        isPlaying = vlc.isPlaying
        if isPlaying && !vlcTracksLoaded { vlcTracksLoaded = true; loadVLCTracks() }
        let st = vlc.state
        if st == .paused || st == .ended || st == .error || (st == .stopped && t > 0) {
            buffering = false; vlcStallTicks = 0
        } else if lastVlcT < 0 {
            buffering = t <= 0                               // أول قراءة (بداية أو بعد تقديم): لسه ما بدأ؟ تحميل
        } else if t > lastVlcT + 0.05 {
            buffering = false; vlcStallTicks = 0             // الوقت يتقدّم = شغّال (حتى لو VLC يقول buffering)
        } else {
            vlcStallTicks += 1                               // وقف التقدّم ثانية كاملة = فعلاً يحمّل
            if vlcStallTicks >= 2 { buffering = true }
        }
        if t > 0 { lastVlcT = t }
        if st == .error { failed = "ما اشتغل. جرّب مرة ثانية أو محتوى ثاني." }
        if st == .ended || st == .stopped && duration > 0 && position >= duration - 2 {
            if !vlcEndHandled { vlcEndHandled = true; ended() }
        } else { vlcEndHandled = false }
        if Date().timeIntervalSince(lastSave) > 10 { saveNow() }
        checkSkips()
    }

    static var phone: Bool { UIDevice.current.userInterfaceIdiom == .phone }

    func hostKey(_ r: PlayRequest) -> String { r.type == "episode" ? "series:\(r.seriesId)" : (r.type == "live" ? "live" : r.key) }
    func hostAppear(_ k: String) { hosts[k, default: 0] += 1 }
    func hostDisappear(_ k: String) { hosts[k] = max(0, (hosts[k] ?? 0) - 1) }
    func hostVisible(_ r: PlayRequest) -> Bool { (hosts[hostKey(r)] ?? 0) > 0 }

    /// طلعت من صفحة التشغيل وهو شغّال: الجوال = يكمل في المشغّل الصغير، الآيباد = يوقف
    func leftHost() {
        guard req != nil, !fullscreen, !PiP.shared.active, !mini else { return }
        if Self.phone { withAnimation(.spring(duration: 0.35, bounce: 0.05)) { mini = true } } else { stop() }
    }

    func stop() {
        saveNow()
        mini = false
        watchVod = nil
        stopVLC()
        player.pause()
        player.replaceCurrentItem(with: nil)
        req = nil
        fullscreen = false
        waitTask?.cancel()
        isPlaying = false; buffering = false
    }

    func togglePlay() {
        if engine == .vlc { if vlc.isPlaying { vlc.pause() } else { vlc.play() }; isPlaying = vlc.isPlaying; return }
        if player.timeControlStatus == .paused { userPaused = false; player.playImmediately(atRate: 1) }
        else { userPaused = true; player.pause() }
    }

    func setRate(_ r: Float) { if engine == .vlc { vlc.rate = r } else { player.rate = r } }

    /// وين رايح التقديم (الضغطات ورا بعض تنجمع على الهدف، مو على مكان الفيديو اللي لسه ما وصل)
    @ObservationIgnored private var seekTarget: Double?

    func seek(by s: Double) {
        seek(to: (seekTarget ?? position) + s)
    }

    func seek(to t0: Double) {
        let t = max(0, duration > 0 ? min(t0, duration - 1) : t0)
        position = t
        seekTarget = t
        if engine == .vlc {
            vlc.time = VLCTime(int: Int32(t * 1000))
            lastVlcT = -1; vlcStallTicks = 0
            Task { try? await Task.sleep(for: .milliseconds(700)); if seekTarget == t { seekTarget = nil } }
            return
        }
        // تقديم سريع: نلغي التقديم اللي قبله، ونسمح بثانية = يقفز لأقرب نقطة جاهزة بدل ما يفك الفيديو من أول
        player.currentItem?.cancelPendingSeeks()
        let tol = CMTime(seconds: 1, preferredTimescale: 600)
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: tol, toleranceAfter: tol) { [weak self] done in
            Task { @MainActor in
                guard let self, done, self.seekTarget == t else { return }
                self.seekTarget = nil
                // بعد التقديم يكمل على طول من اللي جاهز (ما ينتظر يعبّي بفر من جديد)
                if !self.userPaused { self.player.playImmediately(atRate: 1) }
            }
        }
    }

    /// المستخدم وقّف بنفسه؟ (عشان ما نشغّل غصب عنه بعد التقديم أو التحميل)
    @ObservationIgnored private var userPaused = false

    /// المباشر: يرجع للحظة الحالية
    func goLive() {
        if engine == .vlc { if let r = req { playVLC(r) }; return }
        userPaused = false
        guard let it = player.currentItem, let r = it.seekableTimeRanges.last?.timeRangeValue else { player.playImmediately(atRate: 1); return }
        player.seek(to: r.end) { [weak self] _ in Task { @MainActor in self?.player.playImmediately(atRate: 1) } }
    }

    func next() {
        if isLive { stepChannel(1); return }
        guard let q = queue, let r = req, let i = q.index(of: r.key), let n = q.request(i + 1) else { return }
        play(n)
    }

    func prev() {
        if isLive { stepChannel(-1); return }
        guard let q = queue, let r = req, let i = q.index(of: r.key), i > 0, let n = q.request(i - 1) else { return }
        play(n)
    }

    private func stepChannel(_ d: Int) {
        guard let r = req, let i = channels.firstIndex(where: { "live:\($0.id)" == r.key }), !channels.isEmpty,
              let api = AppModel.shared.api else { return }
        let ch = channels[(i + d + channels.count) % channels.count]
        if let u = api.liveURL(ch.id) { play(PlayRequest(url: u, key: "live:\(ch.id)", title: ch.name, sub: "", type: "live", image: ch.icon)) }
    }

    private func ended() {
        saveNow()
        if hasNext { next() }                     // المسلسل: الحلقة التالية لحالها
    }

    private func tick(_ t: CMTime) {
        guard engine == .av else { return }
        let p = t.seconds
        if p.isFinite, seekTarget == nil { position = p }     // أثناء التقديم: نخلي الشريط على الهدف (ما يرجع ويقفز)
        if player.timeControlStatus == .playing { if buffering { buffering = false }; if !isPlaying { isPlaying = true } }
        if let d = player.currentItem?.duration.seconds, d.isFinite, d > 0 { duration = d }
        if Date().timeIntervalSince(lastSave) > 10 { saveNow() }
        checkSkips()
    }

    func saveNow() {
        lastSave = Date()
        guard let r = req, !isLive, duration > 0 else { return }
        AppModel.shared.saveProgress(r.key, pos: position, dur: duration)
    }

    // MARK: تخطي المقدمة / الحلقة التالية مع التتر (يتعلّم لكل مسلسل)

    private(set) var showSkipIntro = false
    private(set) var showNextUp = false
    private(set) var nextCountdown = 0
    private var introSkipped = false
    private var autoCancelled = false
    private var countdownEnd: Date?

    private func resetSkip() {
        showSkipIntro = false; showNextUp = false; introSkipped = false; autoCancelled = false; countdownEnd = nil
    }

    private func checkSkips() {
        guard let r = req, r.type == "episode", !r.seriesId.isEmpty else { showSkipIntro = false; showNextUp = false; return }
        let m = AppModel.shared
        if let iv = m.intro(r.seriesId) { showSkipIntro = !introSkipped && position >= iv.0 && position < iv.1 - 3 } else { showSkipIntro = false }
        guard duration > 0, hasNext else { showNextUp = false; return }
        let left = duration - position
        let learned = m.credits(r.seriesId).flatMap { $0 < duration / 3 ? $0 : nil }
        showNextUp = left <= (learned ?? 30)
        if showNextUp, learned != nil {
            if countdownEnd == nil { countdownEnd = Date().addingTimeInterval(10) }
            nextCountdown = max(0, Int(ceil(countdownEnd!.timeIntervalSinceNow)))
            if nextCountdown == 0 && !autoCancelled && isPlaying { next() }
        } else if showNextUp {
            nextCountdown = Int(ceil(left))
        } else { countdownEnd = nil }
    }

    func skipIntro() {
        guard let r = req, let iv = AppModel.shared.intro(r.seriesId) else { return }
        introSkipped = true; showSkipIntro = false
        seek(to: iv.1)
    }

    func cancelAutoNext() { autoCancelled = true }

    /// تعلّم: تخطّيت المقدمة بنفسك (قفزة لقدّام بأول دقايق الحلقة)، أو ضغطت التالية وانت بالتتر
    func learnFromSeek(from: Double, to: Double) {
        guard let r = req, r.type == "episode", !r.seriesId.isEmpty else { return }
        let jump = to - from
        if from <= 480, to <= 600, (20...180).contains(jump) { AppModel.shared.learnIntro(r.seriesId, start: from, end: to) }
        else if duration > 0 {
            let left = duration - from
            if (10...360).contains(left), to >= duration - 20 { AppModel.shared.learnCredits(r.seriesId, left: left) }
        }
    }

    func learnManualNext() {
        guard let r = req, r.type == "episode", duration > 0 else { return }
        let left = duration - position
        if (10...360).contains(left) { AppModel.shared.learnCredits(r.seriesId, left: left) }
    }

    // MARK: الترجمة والصوت
    struct Track: Identifiable, Hashable { let id: String; let name: String }

    /// الترجمات أو الصوت المتاح (للمحرّكين)
    func tracks(audio: Bool) -> [Track] { audio ? audioTracks : subTracks }

    private func clearTracks() {
        audioTracks = []; subTracks = []; avGroups = [:]; vlcTracksLoaded = false
    }

    private func loadAVTracks(_ item: AVPlayerItem) async {
        let asset = item.asset
        let a = try? await asset.loadMediaSelectionGroup(for: .audible)
        let l = try? await asset.loadMediaSelectionGroup(for: .legible)
        guard player.currentItem === item else { return }
        avGroups = [:]
        if let a { avGroups[.audible] = a }
        if let l { avGroups[.legible] = l }
        audioTracks = (a?.options ?? []).enumerated().map { Track(id: "\($0.offset)", name: $0.element.displayName) }
        subTracks = (l?.options ?? []).enumerated().map { Track(id: "\($0.offset)", name: $0.element.displayName) }
    }

    private func loadVLCTracks() {
        func list(_ n: [Any]?, _ i: [Any]?) -> [Track] {
            zip((i as? [NSNumber]) ?? [], (n as? [String]) ?? []).filter { $0.0.int32Value >= 0 }
                .map { Track(id: "\($0.0.int32Value)", name: $0.1) }
        }
        audioTracks = list(vlc.audioTrackNames, vlc.audioTrackIndexes)
        subTracks = list(vlc.videoSubTitlesNames, vlc.videoSubTitlesIndexes)
    }

    func selectTrack(_ t: Track?, audio: Bool) {
        if engine == .vlc {
            let i = Int32(t?.id ?? "-1") ?? -1
            if audio { vlc.currentAudioTrackIndex = i } else { vlc.currentVideoSubTitleIndex = i }
            return
        }
        let c: AVMediaCharacteristic = audio ? .audible : .legible
        let all = options(c)
        select(t.flatMap { Int($0.id) }.flatMap { all.indices.contains($0) ? all[$0] : nil }, c)
    }

    func options(_ c: AVMediaCharacteristic) -> [AVMediaSelectionOption] { avGroups[c]?.options ?? [] }
    func select(_ o: AVMediaSelectionOption?, _ c: AVMediaCharacteristic) {
        guard let it = player.currentItem, let g = avGroups[c] else { return }
        it.select(o, in: g)
    }
}

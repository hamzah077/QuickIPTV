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
    let vlc = VLCMediaPlayer()
    /// شاشة VLC (وحدة، تنتقل بين الشاشة الصغيرة وملء الشاشة)
    let vlcView: UIView = { let v = UIView(); v.backgroundColor = .black; v.isUserInteractionEnabled = false; return v }()
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
    /// دخل ملء الشاشة لأنك لفّيت الجوال (يطلع لما ترجعه عمودي)
    var autoFullscreen = false
    /// مكان المشغّل الصغير بالشاشة (تبدأ منه حركة التكبير وترجع له)
    @ObservationIgnored var inlineFrame: CGRect = .zero

    var isLive: Bool { req?.type == "live" }
    var hasPrev: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i > 0 }
    var hasNext: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i < q.list.count - 1 }

    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var waitObs: NSKeyValueObservation?
    private var lastSave = Date.distantPast

    private var vlcTimer: Timer?

    private init() {
        vlc.drawable = vlcView
        // لازم true: لو false، الـ AVPlayer إذا خلص البفر يوقف ويطلع تقطيع وما يكمّل لحاله
        player.automaticallyWaitsToMinimizeStalling = true
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        waitObs = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let st = p.timeControlStatus
            Task { @MainActor in
                guard self?.engine == .av else { return }
                self?.isPlaying = st == .playing
                self?.buffering = st == .waitingToPlayAtSpecifiedRate
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
        if Self.engineFor(r.url) == .vlc { playVLC(r); return }
        stopVLC()
        engine = .av
        let item = AVPlayerItem(url: r.url)
        // المباشر: النظام يحدد البفر حسب السيرفر (أثبت). الأفلام: بفر أكبر عشان ما يقطع على النت البطيء
        item.preferredForwardBufferDuration = r.type == "live" ? 0 : 30
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            let failed = it.status == .failed
            Task { @MainActor in
                // مشغّل آبل ما قدر عليه؟ نحوّل لـ VLC تلقائياً
                if failed, let self, let r = self.req, self.engine == .av { self.playVLC(r) }
            }
        }
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        }
        player.replaceCurrentItem(with: item)
        if r.start > 0 { player.seek(to: CMTime(seconds: r.start, preferredTimescale: 600)) }
        player.play()
    }

    // MARK: VLC

    private func playVLC(_ r: PlayRequest) {
        engine = .vlc
        player.pause(); player.replaceCurrentItem(with: nil)
        let m = VLCMedia(url: r.url)
        m.addOption(":network-caching=3000")                                               // بفر ٣ ثواني يمنع التقطيع
        m.addOption(":live-caching=3000")
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
        if vlc.media != nil { vlc.stop(); vlc.media = nil }
    }

    private var vlcEndHandled = false

    private func vlcTick() {
        guard engine == .vlc else { return }
        let t = Double(vlc.time.intValue) / 1000
        if t.isFinite, t >= 0 { position = t }
        if let len = vlc.media?.length.intValue, len > 0 { duration = Double(len) / 1000 }
        isPlaying = vlc.isPlaying
        let st = vlc.state
        buffering = st == .buffering || st == .opening
        if st == .error { failed = "ما اشتغل. جرّب مرة ثانية أو محتوى ثاني." }
        if st == .ended || st == .stopped && duration > 0 && position >= duration - 2 {
            if !vlcEndHandled { vlcEndHandled = true; ended() }
        } else { vlcEndHandled = false }
        if Date().timeIntervalSince(lastSave) > 10 { saveNow() }
        checkSkips()
    }

    func stop() {
        saveNow()
        stopVLC()
        player.pause()
        player.replaceCurrentItem(with: nil)
        req = nil
        fullscreen = false
    }

    func togglePlay() {
        if engine == .vlc { if vlc.isPlaying { vlc.pause() } else { vlc.play() }; isPlaying = vlc.isPlaying; return }
        if player.timeControlStatus == .paused { player.play() } else { player.pause() }
    }

    func setRate(_ r: Float) { if engine == .vlc { vlc.rate = r } else { player.rate = r } }

    func seek(by s: Double) {
        let t = max(0, min(duration > 0 ? duration : .infinity, position + s))
        seek(to: t)
    }

    func seek(to t: Double) {
        position = t
        if engine == .vlc { vlc.time = VLCTime(int: Int32(t * 1000)); return }
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// المباشر: يرجع للحظة الحالية
    func goLive() {
        if engine == .vlc { if let r = req { playVLC(r) }; return }
        guard let it = player.currentItem, let r = it.seekableTimeRanges.last?.timeRangeValue else { player.play(); return }
        player.seek(to: r.end); player.play()
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
        if p.isFinite { position = p }
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
    func tracks(audio: Bool) -> [Track] {
        if engine == .vlc {
            let names = (audio ? vlc.audioTrackNames : vlc.videoSubTitlesNames) as? [String] ?? []
            let idx = (audio ? vlc.audioTrackIndexes : vlc.videoSubTitlesIndexes) as? [NSNumber] ?? []
            return zip(idx, names).filter { $0.0.int32Value >= 0 }.map { Track(id: "\($0.0.int32Value)", name: $0.1) }
        }
        return options(audio ? .audible : .legible).enumerated().map { Track(id: "\($0.offset)", name: $0.element.displayName) }
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

    func options(_ c: AVMediaCharacteristic) -> [AVMediaSelectionOption] {
        guard let it = player.currentItem, let g = it.asset.mediaSelectionGroup(forMediaCharacteristic: c) else { return [] }
        return g.options
    }
    func select(_ o: AVMediaSelectionOption?, _ c: AVMediaCharacteristic) {
        guard let it = player.currentItem, let g = it.asset.mediaSelectionGroup(forMediaCharacteristic: c) else { return }
        it.select(o, in: g)
    }
}

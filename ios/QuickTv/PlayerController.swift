import AVFoundation
import AVKit
import Observation
import SwiftUI

/// مشغّل واحد مشترك: الشاشة الصغيرة وملء الشاشة يعرضون نفس المشغّل = التكبير والتصغير بدون إعادة تحميل
@MainActor
@Observable
final class PlayerController {
    static let shared = PlayerController()

    let player = AVPlayer()
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

    var isLive: Bool { req?.type == "live" }
    var hasPrev: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i > 0 }
    var hasNext: Bool { guard let q = queue, let r = req, let i = q.index(of: r.key) else { return false }; return i < q.list.count - 1 }

    private var timeObs: Any?
    private var endObs: NSObjectProtocol?
    private var statusObs: NSKeyValueObservation?
    private var waitObs: NSKeyValueObservation?
    private var lastSave = Date.distantPast

    private init() {
        player.automaticallyWaitsToMinimizeStalling = false        // يبدأ أسرع (ما ينتظر بفر كبير)
        timeObs = player.addPeriodicTimeObserver(forInterval: CMTime(seconds: 0.5, preferredTimescale: 600), queue: .main) { [weak self] t in
            MainActor.assumeIsolated { self?.tick(t) }
        }
        waitObs = player.observe(\.timeControlStatus, options: [.new]) { [weak self] p, _ in
            let st = p.timeControlStatus
            Task { @MainActor in
                self?.isPlaying = st == .playing
                self?.buffering = st == .waitingToPlayAtSpecifiedRate
            }
        }
    }

    // MARK: التشغيل

    func play(_ r: PlayRequest) {
        saveNow()
        failed = nil
        req = r
        AppModel.shared.addHistory(r)
        let item = AVPlayerItem(url: r.url)
        item.preferredForwardBufferDuration = r.type == "live" ? 4 : 10
        statusObs = item.observe(\.status, options: [.new]) { [weak self] it, _ in
            let failed = it.status == .failed
            let msg = it.error?.localizedDescription
            Task { @MainActor in
                if failed { self?.failed = "ما اشتغل (\(msg ?? "صيغة غير مدعومة"))" }
            }
        }
        if let e = endObs { NotificationCenter.default.removeObserver(e) }
        endObs = NotificationCenter.default.addObserver(forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.ended() }
        }
        player.replaceCurrentItem(with: item)
        position = r.start; duration = 0
        if r.start > 0 { player.seek(to: CMTime(seconds: r.start, preferredTimescale: 600)) }
        player.play()
        resetSkip()
    }

    func stop() {
        saveNow()
        player.pause()
        player.replaceCurrentItem(with: nil)
        req = nil
        fullscreen = false
    }

    func togglePlay() { if player.timeControlStatus == .paused { player.play() } else { player.pause() } }

    func seek(by s: Double) {
        let t = max(0, min(duration > 0 ? duration : .infinity, position + s))
        seek(to: t)
    }

    func seek(to t: Double) {
        position = t
        player.seek(to: CMTime(seconds: t, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
    }

    /// المباشر: يرجع للحظة الحالية
    func goLive() {
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
            if nextCountdown == 0 && !autoCancelled && player.timeControlStatus == .playing { next() }
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
    func options(_ c: AVMediaCharacteristic) -> [AVMediaSelectionOption] {
        guard let it = player.currentItem, let g = it.asset.mediaSelectionGroup(forMediaCharacteristic: c) else { return [] }
        return g.options
    }
    func select(_ o: AVMediaSelectionOption?, _ c: AVMediaCharacteristic) {
        guard let it = player.currentItem, let g = it.asset.mediaSelectionGroup(forMediaCharacteristic: c) else { return }
        it.select(o, in: g)
    }
}

import Foundation
import UIKit
import Libmpv

// MARK: - مشغّل MPV (تجريبي) — يختاره المستخدم من الإعدادات ← محرك التشغيل
// مبني على MPVKit (libmpv + FFmpeg). الرسم عن طريق Metal (MoltenVK) زي تطبيق MPVKit التجريبي.

/// طبقة الرسم: MoltenVK أحياناً يحط مقاس الرسم 1×1 عشان ينهي العرض بالقوة = وميض، فنتجاهله
final class MPVMetalLayer: CAMetalLayer {
    override var drawableSize: CGSize {
        get { super.drawableSize }
        set { if Int(newValue.width) > 1 && Int(newValue.height) > 1 { super.drawableSize = newValue } }
    }
}

/// شاشة MPV (وحدة، تنتقل بين الشاشة الصغيرة وملء الشاشة مثل شاشات المشغّلات الثانية)
/// طبقة الرسم "طبقة فرعية" داخل الشاشة — نفس تطبيق MPVKit التجريبي بالضبط (مو طبقة الشاشة نفسها)
final class MPVView: UIView {
    let metalLayer = MPVMetalLayer()
    override init(frame: CGRect) {
        super.init(frame: frame)
        metalLayer.contentsScale = UIScreen.main.nativeScale
        layer.addSublayer(metalLayer)
    }
    required init?(coder: NSCoder) { fatalError() }
    override func layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin(); CATransaction.setDisableActions(true)
        metalLayer.frame = bounds
        CATransaction.commit()
    }
}

@MainActor
final class MPVEngine {
    let view: MPVView = {
        let v = MPVView()
        v.backgroundColor = .black
        v.isUserInteractionEnabled = false
        v.metalLayer.framebufferOnly = true
        v.metalLayer.backgroundColor = UIColor.black.cgColor
        return v
    }()

    /// خلص الملف (نهاية الفيلم/الحلقة) — المشغّل يكمل للحلقة الجاية
    var onEnd: (() -> Void)?
    /// فشل التشغيل
    var onError: (() -> Void)?

    nonisolated(unsafe) private var mpv: OpaquePointer?
    private let events = DispatchQueue(label: "urrox.mpv.events", qos: .userInitiated)

    init() {
        guard let h = mpv_create() else { return }
        mpv = h
        var layer: MPVMetalLayer = view.metalLayer
        mpv_set_option(h, "wid", MPV_FORMAT_INT64, &layer)
        let opts: [(String, String)] = [
            ("vo", "gpu-next"), ("gpu-api", "vulkan"), ("gpu-context", "moltenvk"),
            ("hwdec", "videotoolbox"),                         // فك الفيديو بقطعة الجوال (أخف على البطارية)
            ("video-rotate", "no"),
            ("keep-open", "no"),
            ("user-agent", "IPTVSmartersPro"),
            ("network-timeout", "15"),
            // بداية أسرع: ما ينتظر يعبّي بفر كبير قبل أول صورة
            ("cache", "yes"), ("cache-pause-initial", "no"), ("cache-secs", "20"),
            ("demuxer-readahead-secs", "5"),
            ("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=4"),
            ("subs-match-os-language", "yes"), ("subs-fallback", "yes"),
            ("audio-client-name", "URROX"),
            ("terminal", "no"), ("msg-level", "all=no"),
        ]
        for (k, v) in opts { mpv_set_option_string(h, k, v) }
        guard mpv_initialize(h) >= 0 else { mpv_terminate_destroy(h); mpv = nil; return }
        mpv_set_wakeup_callback(h, { ctx in
            guard let ctx else { return }
            let me = Unmanaged<MPVEngine>.fromOpaque(ctx).takeUnretainedValue()
            me.drain()
        }, Unmanaged.passUnretained(self).toOpaque())

        // بالخلفية: نوقف الصورة (وإلا ترجع شاشة سودا لما ترجع للتطبيق)
        NotificationCenter.default.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.setString("vid", "no") }
        }
        NotificationCenter.default.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.setString("vid", "auto") }
        }
    }

    var available: Bool { mpv != nil }

    // MARK: التحكم

    func load(_ url: URL, start: Double, live: Bool) {
        var o: [String] = []
        if start > 0 { o.append("start=+\(Int(start))") }
        if live { o.append("cache-secs=4") }
        command(["loadfile", url.isFileURL ? url.path : url.absoluteString, "replace", "-1", o.joined(separator: ",")])
        setFlag("pause", false)
    }

    func stop() { command(["stop"]) }
    func setPaused(_ p: Bool) { setFlag("pause", p) }
    func seek(to t: Double) { command(["seek", String(format: "%.2f", t), "absolute"]) }
    func setSpeed(_ r: Float) { var d = Double(r); if let h = mpv { mpv_set_property(h, "speed", MPV_FORMAT_DOUBLE, &d) } }

    // MARK: الحالة (نقراها كل نص ثانية من المشغّل)

    var time: Double { double("time-pos") }
    var duration: Double { double("duration") }
    var paused: Bool { flag("pause") }
    var buffering: Bool { flag("paused-for-cache") || flag("seeking") }
    var idle: Bool { flag("idle-active") }

    /// الصوت/الترجمة: (رقم المسار، الاسم)
    func tracks(_ type: String) -> [(id: String, name: String)] {
        let n = Int(int("track-list/count"))
        var out: [(String, String)] = []
        for i in 0..<max(0, n) where string("track-list/\(i)/type") == type {
            let id = string("track-list/\(i)/id") ?? "\(i)"
            let title = string("track-list/\(i)/title") ?? ""
            let lang = string("track-list/\(i)/lang") ?? ""
            let name = [title, lang].filter { !$0.isEmpty }.joined(separator: " · ")
            out.append((id, name.isEmpty ? "\(type == "audio" ? "صوت" : "ترجمة") \(id)" : name))
        }
        return out
    }
    func selectTrack(_ type: String, id: String?) { setString(type == "audio" ? "aid" : "sid", id ?? "no") }

    // MARK: الأحداث (نهاية الملف / خطأ)

    nonisolated private func drain() {
        events.async { [weak self] in
            guard let self, let h = self.mpv else { return }
            while true {
                guard let e = mpv_wait_event(h, 0) else { break }
                let id = e.pointee.event_id
                if id == MPV_EVENT_NONE { break }
                if id == MPV_EVENT_END_FILE, let d = e.pointee.data {
                    let ef = d.assumingMemoryBound(to: mpv_event_end_file.self).pointee
                    if ef.reason == MPV_END_FILE_REASON_EOF {
                        DispatchQueue.main.async { MainActor.assumeIsolated { self.onEnd?() } }
                    } else if ef.reason == MPV_END_FILE_REASON_ERROR {
                        DispatchQueue.main.async { MainActor.assumeIsolated { self.onError?() } }
                    }
                }
            }
        }
    }

    // MARK: أدوات libmpv

    private func command(_ args: [String]) {
        guard let h = mpv else { return }
        var c: [UnsafePointer<CChar>?] = args.map { UnsafePointer(strdup($0)) }
        c.append(nil)
        defer { for p in c where p != nil { free(UnsafeMutablePointer(mutating: p)) } }
        _ = c.withUnsafeMutableBufferPointer { mpv_command(h, $0.baseAddress) }
    }
    private func double(_ n: String) -> Double {
        guard let h = mpv else { return 0 }
        var v = 0.0
        return mpv_get_property(h, n, MPV_FORMAT_DOUBLE, &v) >= 0 && v.isFinite ? v : 0
    }
    private func int(_ n: String) -> Int64 {
        guard let h = mpv else { return 0 }
        var v: Int64 = 0
        return mpv_get_property(h, n, MPV_FORMAT_INT64, &v) >= 0 ? v : 0
    }
    private func flag(_ n: String) -> Bool {
        guard let h = mpv else { return false }
        var v: Int32 = 0
        return mpv_get_property(h, n, MPV_FORMAT_FLAG, &v) >= 0 && v != 0
    }
    private func setFlag(_ n: String, _ on: Bool) {
        guard let h = mpv else { return }
        var v: Int32 = on ? 1 : 0
        mpv_set_property(h, n, MPV_FORMAT_FLAG, &v)
    }
    private func string(_ n: String) -> String? {
        guard let h = mpv, let c = mpv_get_property_string(h, n) else { return nil }
        defer { mpv_free(c) }
        return String(cString: c)
    }
    private func setString(_ n: String, _ v: String) {
        guard let h = mpv else { return }
        mpv_set_property_string(h, n, v)
    }
}

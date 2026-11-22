import Foundation

/// اتصال سيرفر Xtream Codes (نفس طلبات نسخة الأندرويد)
final class Xtream: @unchecked Sendable {
    let acc: Account
    let base: String
    private let session: URLSession

    init(_ acc: Account) {
        self.acc = acc
        var b = acc.server.trimmingCharacters(in: .whitespacesAndNewlines)
        if !b.lowercased().hasPrefix("http") { b = "http://" + b }
        while b.hasSuffix("/") { b.removeLast() }
        base = b
        let cfg = URLSessionConfiguration.default
        cfg.timeoutIntervalForRequest = 45                  // قوائم الأفلام كبيرة على بعض السيرفرات
        cfg.httpMaximumConnectionsPerHost = 6
        cfg.httpAdditionalHeaders = ["User-Agent": "IPTVSmartersPro"]
        cfg.requestCachePolicy = .reloadIgnoringLocalCacheData
        session = URLSession(configuration: cfg)
    }

    private var up: String {
        let u = acc.username.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? acc.username
        let p = acc.password.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? acc.password
        return "\(u)/\(p)"
    }

    // MARK: روابط التشغيل
    func liveURL(_ id: String, fmt: String = "m3u8") -> URL? { URL(string: "\(base)/live/\(up)/\(id).\(fmt)") }
    func vodURL(_ id: String, ext: String) -> URL? { URL(string: "\(base)/movie/\(up)/\(id).\(ext)") }
    func episodeURL(_ id: String, ext: String) -> URL? { URL(string: "\(base)/series/\(up)/\(id).\(ext)") }

    // MARK: الطلبات
    /// عدّاد التحديث: كم نزل من القائمة (0…1، أو سالب = الحجم مو معروف وهذا كم بايت نزل)
    typealias Progress = @Sendable (Double) -> Void

    private func api(_ action: String?, _ extra: [String: String] = [:], progress: Progress? = nil) async throws -> Any {
        var c = URLComponents(string: "\(base)/player_api.php")!
        var q = [URLQueryItem(name: "username", value: acc.username), URLQueryItem(name: "password", value: acc.password)]
        if let action { q.append(URLQueryItem(name: "action", value: action)) }
        for (k, v) in extra { q.append(URLQueryItem(name: k, value: v)) }
        c.queryItems = q
        guard let url = c.url else { throw XError.badServer }
        // الطلبات العادية (تسجيل الدخول وغيره): طلب عادي بدون أي إضافات — العدّاد بس لقوائم التحديث
        guard let progress else {
            let (data, resp) = try await session.data(from: url)
            if let h = resp as? HTTPURLResponse, h.statusCode >= 400 { throw XError.http(h.statusCode) }
            return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
        }
        let grab = TaskGrab()
        let poll: Task<Void, Never>? = Optional(progress).map { p in
            Task {
                while !Task.isCancelled {
                    if let t = grab.task {
                        let got = t.countOfBytesReceived, all = t.countOfBytesExpectedToReceive
                        if got > 0 { p(all > 0 ? Double(got) / Double(all) : -Double(got)) }
                    }
                    try? await Task.sleep(for: .milliseconds(150))
                }
            }
        }
        defer { poll?.cancel() }
        let (data, resp) = try await session.data(from: url, delegate: grab)
        if let h = resp as? HTTPURLResponse, h.statusCode >= 400 { throw XError.http(h.statusCode) }
        return try JSONSerialization.jsonObject(with: data, options: [.fragmentsAllowed])
    }

    private func list(_ action: String, _ extra: [String: String] = [:], progress: Progress? = nil) async throws -> [[String: Any]] {
        (try await api(action, extra, progress: progress) as? [[String: Any]]) ?? []
    }

    /// تسجيل الدخول: يرجّع معلومات الاشتراك
    func login() async throws -> [String: Any] {
        guard let o = try await api(nil) as? [String: Any], let ui = o["user_info"] as? [String: Any] else { throw XError.badLogin }
        if (ui.str("auth") == "0") { throw XError.badLogin }
        return ui
    }

    func liveCategories() async throws -> [Category] {
        try await list("get_live_categories").map { Category(id: $0.str("category_id"), name: $0.str("category_name")) }
    }
    func liveStreams(progress: Progress? = nil) async throws -> [Channel] {
        try await list("get_live_streams", progress: progress).map {
            Channel(id: $0.str("stream_id"), num: $0.int("num"), name: $0.str("name"), icon: $0.str("stream_icon"),
                    categoryId: $0.str("category_id"), epgId: $0.str("epg_channel_id"))
        }
    }
    func vodCategories(series: Bool) async throws -> [Category] {
        try await list(series ? "get_series_categories" : "get_vod_categories").map { Category(id: $0.str("category_id"), name: $0.str("category_name")) }
    }
    func movies(progress: Progress? = nil) async throws -> [Vod] {
        try await list("get_vod_streams", progress: progress).map {
            Vod(id: $0.str("stream_id"), name: $0.str("name"), icon: $0.str("stream_icon"), categoryId: $0.str("category_id"),
                ext: $0.str("container_extension").isEmpty ? "mp4" : $0.str("container_extension"), rating: $0.str("rating"),
                isSeries: false, added: $0.int("added"))
        }
    }
    func series(progress: Progress? = nil) async throws -> [Vod] {
        try await list("get_series", progress: progress).map {
            Vod(id: $0.str("series_id"), name: $0.str("name"), icon: $0.str("cover"), categoryId: $0.str("category_id"),
                ext: "", rating: $0.str("rating"), isSeries: true, added: $0.int("last_modified"))
        }
    }

    /// معلومات المسلسل + الحلقات مرتّبة حسب الموسم
    func seriesInfo(_ id: String) async throws -> (info: [String: Any], seasons: [Int: [Episode]]) {
        guard let o = try await api("get_series_info", ["series_id": id]) as? [String: Any] else { return ([:], [:]) }
        let info = o["info"] as? [String: Any] ?? [:]
        var out: [Int: [Episode]] = [:]
        func add(_ season: Int, _ arr: [[String: Any]]) {
            for e in arr {
                let inf = e["info"] as? [String: Any] ?? [:]
                let s = e.int("season") > 0 ? e.int("season") : season
                out[s, default: []].append(Episode(
                    id: e.str("id"), season: s, num: e.int("episode_num"), title: e.str("title"),
                    ext: e.str("container_extension").isEmpty ? "mp4" : e.str("container_extension"),
                    plot: inf.str("plot"), image: inf.str("movie_image"), duration: inf.str("duration")))
            }
        }
        if let eps = o["episodes"] as? [String: Any] {
            for (k, v) in eps { add(Int(k) ?? 1, v as? [[String: Any]] ?? []) }
        } else if let eps = o["episodes"] as? [[[String: Any]]] {
            for (i, arr) in eps.enumerated() { add(i + 1, arr) }
        }
        for k in out.keys { out[k]?.sort { $0.num < $1.num } }
        return (info, out)
    }

    func vodInfo(_ id: String) async throws -> [String: Any] {
        guard let o = try await api("get_vod_info", ["vod_id": id]) as? [String: Any] else { return [:] }
        return o["info"] as? [String: Any] ?? [:]
    }

    /// برامج القناة (الدليل المختصر)
    func shortEpg(_ streamId: String, limit: Int = 4) async throws -> [EpgItem] {
        guard let o = try await api("get_short_epg", ["stream_id": streamId, "limit": "\(limit)"]) as? [String: Any],
              let arr = o["epg_listings"] as? [[String: Any]] else { return [] }
        return arr.compactMap { e in
            let st = Double(e.str("start_timestamp")) ?? 0, en = Double(e.str("stop_timestamp")) ?? 0
            guard st > 0, en > st else { return nil }
            let t = Data(base64Encoded: e.str("title")).flatMap { String(data: $0, encoding: .utf8) } ?? e.str("title")
            return EpgItem(title: t, start: Date(timeIntervalSince1970: st), end: Date(timeIntervalSince1970: en))
        }
    }
}

enum XError: LocalizedError {
    case badServer, badLogin, http(Int)
    var errorDescription: String? {
        switch self {
        case .badServer: return "عنوان السيرفر غير صحيح"
        case .badLogin: return "اسم المستخدم أو كلمة المرور غير صحيحة"
        case .http(let c): return "السيرفر رد بخطأ (\(c))"
        }
    }
}


/// يمسك طلب التنزيل نفسه (عشان نقرا كم نزل منه للعدّاد)
final class TaskGrab: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    private let lock = NSLock()
    private var t: URLSessionTask?
    var task: URLSessionTask? { lock.lock(); defer { lock.unlock() }; return t }
    func urlSession(_ session: URLSession, didCreateTask task: URLSessionTask) { lock.lock(); t = task; lock.unlock() }
}

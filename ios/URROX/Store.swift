import Foundation
import Observation

/// حالة التطبيق كلها (حسابات، قوائم المحتوى، السجل، المفضلة)
@MainActor
@Observable
final class AppModel {
    static let shared = AppModel()

    // MARK: الحسابات
    private(set) var accounts: [Account] = []
    private(set) var currentId: String = ""
    var current: Account? { accounts.first { $0.id == currentId } ?? accounts.first }
    private(set) var api: Xtream?

    // MARK: المحتوى (يتحمّل مرة وحدة ويبقى بالذاكرة)
    var liveCats: [Category] = []
    var channels: [Channel] = []
    var movieCats: [Category] = []
    var movies: [Vod] = []
    var seriesCats: [Category] = []
    var seriesList: [Vod] = []
    var loading = false
    var error: String?

    // MARK: تحديث القوائم بعدّاد (المباشر ← الأفلام ← المسلسلات)
    struct UpdStep: Identifiable {
        let id: Int; let title: String; let unit: String
        var state = 0            // 0 بالانتظار، 1 يتحدّث، 2 خلص، 3 تعذّر
        var frac = 0.0
        var bytes: Int64 = 0
        var count = 0
    }
    var updSteps: [UpdStep] = [
        UpdStep(id: 0, title: "البث المباشر", unit: "قناة"),
        UpdStep(id: 1, title: "الأفلام", unit: "فيلم"),
        UpdStep(id: 2, title: "المسلسلات", unit: "مسلسل")]
    private(set) var updating = false
    /// أول دخول: صفحة كاملة
    var updFull = false
    /// من الإعدادات: نافذة منبثقة (تقدر تخفيها والتحديث يكمل)
    var updPopup = false
    var updTotal: Int { Int(updSteps.map { $0.state >= 2 ? 1 : $0.frac }.reduce(0, +) / 3 * 100) }

    // MARK: التحديث التلقائي للقوائم (الإعدادات): كل كم يوم، 0 = إيقاف — الافتراضي كل يوم
    var autoUpdateDays: Int = UserDefaults.standard.object(forKey: "autoUpdDays") as? Int ?? 1 {
        didSet { UserDefaults.standard.set(autoUpdateDays, forKey: "autoUpdDays") }
    }
    private func markUpdated(_ acc: String) { UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "lastUpd:\(acc)") }
    /// جا موعد التحديث؟ (أول مرة: نعتبر المحفوظ جديد)
    private func autoDue(_ acc: String) -> Bool {
        guard autoUpdateDays > 0 else { return false }
        let last = UserDefaults.standard.double(forKey: "lastUpd:\(acc)")
        if last == 0 { markUpdated(acc); return false }
        return Date().timeIntervalSince1970 - last >= Double(autoUpdateDays) * 86_400
    }

    /// أول دخول بعد تسجيل الحساب
    func firstUpdate() { updFull = true; Task { await runUpdate() } }
    /// زر "تحديث المحتوى" بالإعدادات
    func popupUpdate() { updPopup = true; if !updating { Task { await runUpdate() } } }

    func runUpdate() async {
        guard let api, let acc = current, !updating else { updFull = false; return }
        updating = true
        for i in updSteps.indices { updSteps[i].state = 0; updSteps[i].frac = 0; updSteps[i].bytes = 0; updSteps[i].count = 0 }
        await step(0) { p in
            async let c = api.liveCategories(); let v = try await api.liveStreams(progress: p)
            let cats = (try? await c) ?? []
            await MainActor.run { if !cats.isEmpty { self.liveCats = cats }; if !v.isEmpty { self.channels = v } }
            return v.count
        }
        await step(1) { p in
            async let c = api.vodCategories(series: false); let v = try await api.movies(progress: p).sorted { $0.added > $1.added }
            let cats = (try? await c) ?? []
            await MainActor.run { if !cats.isEmpty { self.movieCats = cats }; if !v.isEmpty { self.movies = v } }
            return v.count
        }
        await step(2) { p in
            async let c = api.vodCategories(series: true); let v = try await api.series(progress: p).sorted { $0.added > $1.added }
            let cats = (try? await c) ?? []
            await MainActor.run { if !cats.isEmpty { self.seriesCats = cats }; if !v.isEmpty { self.seriesList = v } }
            return v.count
        }
        updating = false
        guard current?.id == acc.id else { return }
        if updSteps.contains(where: { $0.state == 2 }) { markUpdated(acc.id) }
        let c = ContentCache(liveCats: liveCats, channels: channels, movieCats: movieCats, movies: movies, seriesCats: seriesCats, seriesList: seriesList)
        let file = ContentCache.url(acc.id)
        Task.detached(priority: .utility) { ContentCache.write(c, file) }
        // خلص: الصفحة الكاملة تدخلك التطبيق، والنافذة تتقفل بعد لحظة
        try? await Task.sleep(for: .milliseconds(updFull ? 500 : 1200))
        updFull = false; updPopup = false
    }

    private func step(_ i: Int, _ load: @escaping (@escaping Xtream.Progress) async throws -> Int) async {
        updSteps[i].state = 1
        let p: Xtream.Progress = { [weak self] v in
            Task { @MainActor in
                guard let self else { return }
                // التنزيل = 90% من الخطوة. لو السيرفر ما قال الحجم: تقريب يقرب من 90% بالتدريج
                let f = v >= 0 ? v : 1 - exp(v / 4_000_000)
                self.updSteps[i].bytes = Int64(abs(v >= 0 ? 0 : v))
                self.updSteps[i].frac = min(0.9, max(self.updSteps[i].frac, f * 0.9))
            }
        }
        do { updSteps[i].count = try await load(p); updSteps[i].frac = 1; updSteps[i].state = 2 }
        catch { updSteps[i].state = 3 }
    }

    // MARK: السجل والمفضلة (لكل حساب لحاله)
    private(set) var history: [HistoryItem] = []
    private(set) var favs: Set<String> = []

    init() {
        accounts = Persist.load("accounts") ?? []
        currentId = UserDefaults.standard.string(forKey: "current") ?? ""
        if let a = current { open(a) }
    }

    private func saveAccounts() {
        Persist.save("accounts", accounts)
        UserDefaults.standard.set(currentId, forKey: "current")
    }

    func open(_ a: Account) {
        currentId = a.id
        UserDefaults.standard.set(currentId, forKey: "current")
        api = Xtream(a)
        history = Persist.load("history:\(a.id)") ?? []
        favs = Set(Persist.load("favs:\(a.id)") ?? [String]())
        liveCats = []; channels = []; movieCats = []; movies = []; seriesCats = []; seriesList = []
    }

    func addAccount(_ a: Account) async throws {
        _ = try await Xtream(a).login()
        accounts.removeAll { $0.id == a.id }
        accounts.append(a)
        saveAccounts()
        open(a)
        firstUpdate()           // أول دخول: صفحة التحديث بالعدّاد (المباشر ← الأفلام ← المسلسلات)
    }

    func removeAccount(_ a: Account) {
        accounts.removeAll { $0.id == a.id }
        if currentId == a.id { if let f = accounts.first { open(f) } else { currentId = ""; api = nil } }
        saveAccounts()
    }

    /// يحمّل القوائم (مرة وحدة) — بالخلفية
    func loadAll(force: Bool = false) async {
        guard let api, let acc = current, !updating, !updFull, force || channels.isEmpty || movies.isEmpty else { return }
        error = nil
        let file = ContentCache.url(acc.id)

        // ١) اللي محفوظ بالجهاز يطلع على طول (بدون انتظار السيرفر)
        if channels.isEmpty && movies.isEmpty && seriesList.isEmpty,
           let c = await Task.detached(priority: .userInitiated, operation: { ContentCache.read(file) }).value {
            apply(c)
        }
        let hadCache = !(channels.isEmpty && movies.isEmpty)
        loading = !hadCache
        // عندنا نسخة محفوظة وما جا موعد التحديث التلقائي: ما نطلب من السيرفر
        if hadCache && !force && !autoDue(acc.id) { loading = false; return }

        // ٢) من السيرفر بالخلفية: كل قائمة تطلع أول ما توصل (ما ننتظر الكل)
        await withTaskGroup(of: ContentPart.self) { g in
            g.addTask { .liveCats((try? await api.liveCategories()) ?? []) }
            g.addTask { .channels((try? await api.liveStreams()) ?? []) }
            g.addTask { .movieCats((try? await api.vodCategories(series: false)) ?? []) }
            g.addTask { .movies(((try? await api.movies()) ?? []).sorted { $0.added > $1.added }) }
            g.addTask { .seriesCats((try? await api.vodCategories(series: true)) ?? []) }
            g.addTask { .series(((try? await api.series()) ?? []).sorted { $0.added > $1.added }) }
            for await part in g {
                guard current?.id == acc.id else { continue }            // غيّرت الحساب بالنص؟ نتجاهل
                switch part {                                            // فاضي = فشل الطلب: نخلي المحفوظ
                case .liveCats(let v): if !v.isEmpty { liveCats = v }
                case .channels(let v): if !v.isEmpty { channels = v }
                case .movieCats(let v): if !v.isEmpty { movieCats = v }
                case .movies(let v): if !v.isEmpty { movies = v }
                case .seriesCats(let v): if !v.isEmpty { seriesCats = v }
                case .series(let v): if !v.isEmpty { seriesList = v }
                }
                if loading && (!channels.isEmpty || !movies.isEmpty || !seriesList.isEmpty) { loading = false }
            }
        }
        loading = false
        SearchNorm.warm(channels.map(\.name) + movies.map(\.name) + seriesList.map(\.name))   // أول بحث يكون سريع
        guard current?.id == acc.id else { return }
        if channels.isEmpty && movies.isEmpty && seriesList.isEmpty { error = "تعذّر تحميل المحتوى. تأكد من الاتصال والاشتراك."; return }

        markUpdated(acc.id)
        // ٣) نحفظ نسخة بالجهاز للمرة الجاية (بالخلفية)
        let c = ContentCache(liveCats: liveCats, channels: channels, movieCats: movieCats, movies: movies, seriesCats: seriesCats, seriesList: seriesList)
        Task.detached(priority: .utility) { ContentCache.write(c, file) }
    }

    private func apply(_ c: ContentCache) {
        liveCats = c.liveCats; channels = c.channels; movieCats = c.movieCats
        movies = c.movies; seriesCats = c.seriesCats; seriesList = c.seriesList
    }

    // MARK: المفضلة
    func isFav(_ key: String) -> Bool { favs.contains(key) }

    /// الأفلام والمسلسلات المفضلة (بحث بالرقم = سريع حتى مع عشرات الآلاف)
    var favoriteVods: [Vod] {
        if favs.isEmpty { return [] }
        let m = Set(favs.compactMap { $0.hasPrefix("movie:") ? String($0.dropFirst(6)) : nil })
        let s = Set(favs.compactMap { $0.hasPrefix("series:") ? String($0.dropFirst(7)) : nil })
        return (m.isEmpty ? [] : movies.filter { m.contains($0.id) }) + (s.isEmpty ? [] : seriesList.filter { s.contains($0.id) })
    }
    func toggleFav(_ key: String) {
        if favs.contains(key) { favs.remove(key) } else { favs.insert(key) }
        if let a = current { Persist.save("favs:\(a.id)", Array(favs)) }
    }

    // MARK: السجل
    private func saveHistory() { if let a = current { Persist.save("history:\(a.id)", history) } }

    func addHistory(_ r: PlayRequest) {
        let old = history.first { $0.key == r.key }
        var h = HistoryItem(key: r.key, type: r.type, title: r.title, sub: r.sub, image: r.image, url: r.url.absoluteString,
                            seriesId: r.seriesId, season: r.season, epNum: r.epNum)
        if let old { h.pos = old.pos; h.dur = old.dur }     // التشغيل من جديد ما يصفّر التقدّم
        history.removeAll { $0.key == r.key }
        history.insert(h, at: 0)
        if history.count > 200 { history.removeLast(history.count - 200) }
        saveHistory()
    }

    func saveProgress(_ key: String, pos: Double, dur: Double) {
        guard let i = history.firstIndex(where: { $0.key == key }), dur > 0, pos.isFinite else { return }
        var h = history.remove(at: i)
        h.pos = pos; h.dur = dur; h.ts = Date()
        history.insert(h, at: 0)
        saveHistory()
    }

    func resumePoint(_ key: String) -> Double {
        guard let h = history.first(where: { $0.key == key }), h.dur > 0, h.pos > 30, h.pos < h.dur - 60 else { return 0 }
        return h.pos
    }

    /// وين وصلت في المسلسل: أبعد حلقة (بالموسم والحلقة) شاهدتها فعلاً — تشغيل حلقة قديمة بالغلط ما يغيّره
    func seriesFrontier(_ seriesId: String) -> HistoryItem? {
        let eps = history.filter { $0.type == "episode" && $0.seriesId == seriesId }
        if eps.isEmpty { return nil }
        let real = eps.filter { $0.meaningful }
        if real.isEmpty { return eps.max { $0.ts < $1.ts } }
        return real.max { ($0.season, $0.epNum, $0.ts) < ($1.season, $1.epNum, $1.ts) }
    }

    /// السجل للعرض: مسلسل واحد = مدخل واحد
    var displayHistory: [HistoryItem] {
        var out: [HistoryItem] = []; var seen = Set<String>()
        for h in history {
            if h.type != "episode" || h.seriesId.isEmpty { out.append(h); continue }
            if !seen.insert(h.seriesId).inserted { continue }
            out.append(seriesFrontier(h.seriesId) ?? h)
        }
        return out
    }

    var continueWatching: [HistoryItem] {
        displayHistory.filter { $0.type != "live" && $0.dur > 0 && $0.pos > 30 && ($0.type == "episode" || $0.pos < $0.dur - 60) }
    }

    // MARK: تعلّم المقدمة والتتر لكل مسلسل (آخر 3 مرات، ناخذ الوسط)
    private func skipList(_ k: String) -> [Double] { UserDefaults.standard.array(forKey: k) as? [Double] ?? [] }
    private func pushSkip(_ k: String, _ v: Double) { UserDefaults.standard.set(Array((skipList(k) + [v]).suffix(3)), forKey: k) }
    private func median(_ l: [Double]) -> Double? { l.isEmpty ? nil : l.sorted()[l.count / 2] }

    func learnIntro(_ series: String, start: Double, end: Double) { pushSkip("introS:\(series)", start); pushSkip("introE:\(series)", end) }
    func intro(_ series: String) -> (Double, Double)? {
        guard let s = median(skipList("introS:\(series)")), let e = median(skipList("introE:\(series)")), e > s else { return nil }
        return (s, e)
    }
    func learnCredits(_ series: String, left: Double) { pushSkip("credits:\(series)", left) }
    func credits(_ series: String) -> Double? { median(skipList("credits:\(series)")) }

    // MARK: التحية
    var greeting: String {
        let h = Calendar.current.component(.hour, from: Date())
        let hello = (4...11).contains(h) ? "صباح الخير" : "مساء الخير"
        let who = current?.name.trimmingCharacters(in: .whitespaces) ?? ""
        return who.isEmpty ? hello : "\(hello)، \(who)"
    }
}

/// حفظ بسيط (JSON في UserDefaults)
enum Persist {
    static func save<T: Encodable>(_ k: String, _ v: T) {
        if let d = try? JSONEncoder().encode(v) { UserDefaults.standard.set(d, forKey: k) }
    }
    static func load<T: Decodable>(_ k: String) -> T? {
        guard let d = UserDefaults.standard.data(forKey: k) else { return nil }
        return try? JSONDecoder().decode(T.self, from: d)
    }
}

/// حلقات المسلسل الحالي بالترتيب (للسابقة/التالية)
struct SeriesQueue {
    var seriesId: String
    var name: String
    var image: String
    var list: [Episode]

    func index(of key: String) -> Int? { list.firstIndex { "ep:\($0.id)" == key } }

    @MainActor
    func request(_ i: Int) -> PlayRequest? {
        guard list.indices.contains(i), let api = AppModel.shared.api else { return nil }
        let e = list[i]
        guard let u = api.episodeURL(e.id, ext: e.ext) else { return nil }
        let key = "ep:\(e.id)"
        return PlayRequest(url: u, key: key, title: name, sub: "الموسم \(e.season) · الحلقة \(e.num)", type: "episode",
                           image: e.image.isEmpty ? image : e.image, seriesId: seriesId, season: e.season, epNum: e.num,
                           start: AppModel.shared.resumePoint(key))
    }
}


/// نسخة من القوائم محفوظة بالجهاز: الرئيسية تفتح على طول، والتحديث من السيرفر بالخلفية
struct ContentCache: Codable, Sendable {
    var liveCats: [Category]
    var channels: [Channel]
    var movieCats: [Category]
    var movies: [Vod]
    var seriesCats: [Category]
    var seriesList: [Vod]

    static func url(_ accountId: String) -> URL {
        let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
        let safe = accountId.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "acc"
        return dir.appendingPathComponent("content-\(safe).json")
    }
    static func read(_ u: URL) -> ContentCache? {
        guard let d = try? Data(contentsOf: u) else { return nil }
        return try? JSONDecoder().decode(ContentCache.self, from: d)
    }
    static func write(_ c: ContentCache, _ u: URL) {
        if let d = try? JSONEncoder().encode(c) { try? d.write(to: u, options: .atomic) }
    }
}

enum ContentPart: Sendable {
    case liveCats([Category]), channels([Channel]), movieCats([Category]), movies([Vod]), seriesCats([Category]), series([Vod])
}

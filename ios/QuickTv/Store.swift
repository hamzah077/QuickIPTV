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
    }

    func removeAccount(_ a: Account) {
        accounts.removeAll { $0.id == a.id }
        if currentId == a.id { if let f = accounts.first { open(f) } else { currentId = ""; api = nil } }
        saveAccounts()
    }

    /// يحمّل القوائم (مرة وحدة) — بالخلفية
    func loadAll(force: Bool = false) async {
        guard let api, force || channels.isEmpty || movies.isEmpty else { return }
        loading = true; error = nil
        async let lc = try? api.liveCategories()
        async let ch = try? api.liveStreams()
        async let mc = try? api.vodCategories(series: false)
        async let mv = try? api.movies()
        async let sc = try? api.vodCategories(series: true)
        async let sv = try? api.series()
        let r = await (lc, ch, mc, mv, sc, sv)
        liveCats = r.0 ?? []; channels = r.1 ?? []
        movieCats = r.2 ?? []; movies = (r.3 ?? []).sorted { $0.added > $1.added }
        seriesCats = r.4 ?? []; seriesList = (r.5 ?? []).sorted { $0.added > $1.added }
        if channels.isEmpty && movies.isEmpty && seriesList.isEmpty { error = "تعذّر تحميل المحتوى. تأكد من الاتصال والاشتراك." }
        loading = false
    }

    // MARK: المفضلة
    func isFav(_ key: String) -> Bool { favs.contains(key) }
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

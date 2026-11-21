import Foundation

// MARK: - الحساب

struct Account: Codable, Identifiable, Hashable {
    var id: String = UUID().uuidString
    var name: String            // أي اسم (يطلع في الترحيب)
    var server: String
    var username: String
    var password: String
}

// MARK: - المحتوى

struct Category: Identifiable, Hashable, Codable {
    let id: String
    let name: String
}

struct Channel: Identifiable, Hashable, Codable {
    let id: String
    let num: Int
    let name: String
    let icon: String
    let categoryId: String
    let epgId: String
}

/// فيلم أو مسلسل
struct Vod: Identifiable, Hashable, Codable {
    let id: String
    let name: String
    let icon: String
    let categoryId: String
    let ext: String
    let rating: String
    let isSeries: Bool
    let added: Int
}

struct Episode: Identifiable, Hashable {
    let id: String
    let season: Int
    let num: Int
    let title: String
    let ext: String
    let plot: String
    let image: String
    let duration: String
}

struct EpgItem: Identifiable, Hashable {
    var id: String { "\(start.timeIntervalSince1970)" }
    let title: String
    let start: Date
    let end: Date
    var isNow: Bool { let n = Date(); return start <= n && n < end }
}

// MARK: - سجل المشاهدة

struct HistoryItem: Codable, Identifiable, Hashable {
    var key: String             // movie:123 | ep:456 | live:789
    var type: String            // movie | episode | live
    var title: String
    var sub: String
    var image: String
    var url: String
    var seriesId: String = ""
    var pos: Double = 0         // ثواني
    var dur: Double = 0
    var ts: Date = Date()
    var season: Int = 0
    var epNum: Int = 0
    var id: String { key }

    /// خلّصها (أو قريب من النهاية)
    var finished: Bool { dur > 0 && pos >= dur - 60 }
    /// مشاهدة فعلية (مو تشغيل بالغلط): أكثر من دقيقتين أو خلّصها
    var meaningful: Bool { pos >= 120 || finished }
}

/// طلب تشغيل فيلم/حلقة/قناة
struct PlayRequest: Hashable, Identifiable {
    var url: URL
    var key: String
    var title: String
    var sub: String
    var type: String            // movie | episode | live
    var image: String = ""
    var seriesId: String = ""
    var season: Int = 0
    var epNum: Int = 0
    var start: Double = 0
    var id: String { key }
}

// MARK: - مساعدات JSON (سيرفرات Xtream ترجع أرقام أحياناً كنصوص والعكس)

extension Dictionary where Key == String, Value == Any {
    func str(_ k: String) -> String {
        switch self[k] {
        case let s as String: return s
        case let n as NSNumber: return n.stringValue
        default: return ""
        }
    }
    func int(_ k: String) -> Int { Int(str(k)) ?? Int(Double(str(k)) ?? 0) }
}

func fmtTime(_ secs: Double) -> String {
    guard secs.isFinite, secs >= 0 else { return "--:--" }
    let s = Int(secs)
    let h = s / 3600, m = (s % 3600) / 60, ss = s % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, ss) : String(format: "%d:%02d", m, ss)
}

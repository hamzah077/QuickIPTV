import Foundation
import Observation
import SwiftUI

/// التحميلات: تكمل بالخلفية (حتى لو قفلت التطبيق)، توقيف/استكمال، والتشغيل من الجهاز بدون إنترنت
@MainActor
@Observable
final class Downloads: NSObject {
    static let shared = Downloads()

    struct Item: Codable, Identifiable, Hashable {
        var key: String
        var title: String
        var sub: String
        var image: String
        var url: String             // الرابط الأصلي
        var file: String = ""       // اسم الملف بالجهاز (بعد ما يخلص)
        var type: String
        var seriesId: String = ""
        var season: Int = 0
        var epNum: Int = 0
        var status: String = "running"      // running | paused | done | failed
        var received: Int64 = 0
        var total: Int64 = 0
        var id: String { key }
        var frac: Double { total > 0 ? Double(received) / Double(total) : 0 }
    }

    private(set) var items: [Item] = Persist.load("downloads") ?? []
    @ObservationIgnored private var tasks: [String: URLSessionDownloadTask] = [:]
    @ObservationIgnored private var lastUI: [String: Date] = [:]
    @ObservationIgnored var bgCompletion: (() -> Void)?

    @ObservationIgnored private lazy var session: URLSession = {
        let c = URLSessionConfiguration.background(withIdentifier: "com.urrox.player.downloads")
        c.isDiscretionary = false
        c.sessionSendsLaunchEvents = true
        c.httpAdditionalHeaders = ["User-Agent": "IPTVSmartersPro"]
        return URLSession(configuration: c, delegate: self, delegateQueue: .main)
    }()

    nonisolated private static var dir: URL {
        let d = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Downloads", isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    override init() {
        super.init()
        // نربط المهام اللي كانت شغّالة قبل ما ينقفل التطبيق
        session.getAllTasks { all in
            Task { @MainActor in
                for t in all { if let k = t.taskDescription, let dt = t as? URLSessionDownloadTask { self.tasks[k] = dt } }
            }
        }
    }

    private func save() { Persist.save("downloads", items) }

    func item(_ key: String) -> Item? { items.first { $0.key == key } }

    /// الملف بالجهاز لو التحميل خلص
    func localURL(_ key: String) -> URL? {
        guard let it = item(key), it.status == "done", !it.file.isEmpty else { return nil }
        let u = Self.dir.appendingPathComponent(it.file)
        return FileManager.default.fileExists(atPath: u.path) ? u : nil
    }

    func start(_ r: PlayRequest) {
        if let it = item(r.key), it.status != "failed" { if it.status == "paused" { resume(r.key) }; return }
        items.removeAll { $0.key == r.key }
        items.insert(Item(key: r.key, title: r.title, sub: r.sub, image: r.image, url: r.url.absoluteString, type: r.type,
                          seriesId: r.seriesId, season: r.season, epNum: r.epNum), at: 0)
        save()
        let t = session.downloadTask(with: r.url)
        t.taskDescription = r.key
        tasks[r.key] = t
        t.resume()
    }

    func pause(_ key: String) {
        guard let t = tasks[key] else { return }
        t.cancel(byProducingResumeData: { data in
            Task { @MainActor in
                if let data { try? data.write(to: Self.resumeFile(key)) }
                self.update(key) { $0.status = "paused" }
            }
        })
        tasks[key] = nil
    }

    func resume(_ key: String) {
        guard let it = item(key) else { return }
        let rf = Self.resumeFile(key)
        let t: URLSessionDownloadTask
        if let data = try? Data(contentsOf: rf) { t = session.downloadTask(withResumeData: data); try? FileManager.default.removeItem(at: rf) }
        else if let u = URL(string: it.url) { t = session.downloadTask(with: u) }
        else { return }
        t.taskDescription = key
        tasks[key] = t
        update(key) { $0.status = "running" }
        t.resume()
    }

    func remove(_ key: String) {
        tasks[key]?.cancel(); tasks[key] = nil
        if let it = item(key), !it.file.isEmpty { try? FileManager.default.removeItem(at: Self.dir.appendingPathComponent(it.file)) }
        try? FileManager.default.removeItem(at: Self.resumeFile(key))
        items.removeAll { $0.key == key }
        save()
    }

    nonisolated private static func resumeFile(_ key: String) -> URL { dir.appendingPathComponent(safe(key) + ".resume") }
    nonisolated private static func safe(_ key: String) -> String { key.replacingOccurrences(of: ":", with: "_").replacingOccurrences(of: "/", with: "_") }

    private func update(_ key: String, _ f: (inout Item) -> Void) {
        guard let i = items.firstIndex(where: { $0.key == key }) else { return }
        f(&items[i])
        save()
    }

    // MARK: أحداث التحميل (من النظام)
    fileprivate func progress(_ key: String, _ got: Int64, _ total: Int64) {
        if let l = lastUI[key], Date().timeIntervalSince(l) < 0.5 { return }       // ما نحدّث الواجهة أكثر من مرتين بالثانية
        lastUI[key] = Date()
        guard let i = items.firstIndex(where: { $0.key == key }) else { return }
        items[i].received = got; items[i].total = total
    }

    fileprivate func finished(_ key: String, file: String, size: Int64) {
        update(key) { $0.status = "done"; $0.file = file; $0.received = size; $0.total = size }
        tasks[key] = nil
    }

    fileprivate func failed(_ key: String, resume: Data?) {
        if let resume { try? resume.write(to: Self.resumeFile(key)); update(key) { $0.status = "paused" } }
        else if item(key)?.status == "running" { update(key) { $0.status = "failed" } }
        tasks[key] = nil
    }

    nonisolated fileprivate static func moveFile(_ tmp: URL, key: String, url: String) -> (String, Int64)? {
        var ext = (URL(string: url)?.pathExtension ?? "").lowercased()
        if ext.isEmpty || ext.count > 5 { ext = "mp4" }
        let name = safe(key) + "." + ext
        let dest = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        do { try FileManager.default.moveItem(at: tmp, to: dest) } catch { return nil }
        let attrs = try? FileManager.default.attributesOfItem(atPath: dest.path)
        let size = (attrs?[.size] as? NSNumber)?.int64Value ?? 0
        return (name, size)
    }
}

extension Downloads: URLSessionDownloadDelegate {
    nonisolated func urlSession(_ s: URLSession, downloadTask t: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten w: Int64, totalBytesExpectedToWrite e: Int64) {
        guard let k = t.taskDescription else { return }
        MainActor.assumeIsolated { self.progress(k, w, e) }
    }

    nonisolated func urlSession(_ s: URLSession, downloadTask t: URLSessionDownloadTask, didFinishDownloadingTo loc: URL) {
        guard let k = t.taskDescription else { return }
        let url = t.originalRequest?.url?.absoluteString ?? ""
        // لازم ننقل الملف هنا قبل ما النظام يحذفه
        let moved = Downloads.moveFile(loc, key: k, url: url)
        MainActor.assumeIsolated {
            if let m = moved { self.finished(k, file: m.0, size: m.1) } else { self.failed(k, resume: nil) }
        }
    }

    nonisolated func urlSession(_ s: URLSession, task t: URLSessionTask, didCompleteWithError error: Error?) {
        guard let k = t.taskDescription, let error else { return }
        let resume = (error as NSError).userInfo[NSURLSessionDownloadTaskResumeData] as? Data
        MainActor.assumeIsolated { self.failed(k, resume: resume) }
    }

    nonisolated func urlSessionDidFinishEvents(forBackgroundURLSession s: URLSession) {
        MainActor.assumeIsolated { self.bgCompletion?(); self.bgCompletion = nil }
    }
}

// MARK: - زر التحميل (فيلم/حلقة)

struct DownloadButton: View {
    @Environment(Downloads.self) private var dl
    let req: () -> PlayRequest?
    let key: String
    var size: CGFloat = 50

    var body: some View {
        let it = dl.item(key)
        Button {
            switch it?.status {
            case "running": dl.pause(key)
            case "paused": dl.resume(key)
            case "done": break
            default: if let r = req() { dl.start(r) }
            }
        } label: {
            ZStack {
                switch it?.status {
                case "running":
                    Circle().stroke(.white.opacity(0.25), lineWidth: 3).frame(width: size * 0.5)
                    Circle().trim(from: 0, to: it?.frac ?? 0).stroke(.white, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90)).frame(width: size * 0.5)
                    Image(systemName: "pause.fill").font(.system(size: size * 0.18))
                case "paused": Image(systemName: "arrow.down.circle")
                case "done": Image(systemName: "checkmark.circle.fill")
                default: Image(systemName: "arrow.down.to.line")
                }
            }
            .font(.system(size: size * 0.4))
            .frame(width: size, height: size)
        }
        .buttonStyle(.plain)
    }
}

// MARK: - صفحة التحميلات

struct DownloadsView: View {
    @Environment(Downloads.self) private var dl
    @Environment(PlayerController.self) private var pc

    var body: some View {
        List {
            if dl.items.isEmpty {
                Text("ما فيه تحميلات. اضغط زر التحميل في صفحة أي فيلم أو حلقة.").foregroundStyle(Theme.muted)
            }
            ForEach(dl.items) { it in
                Button { play(it) } label: {
                    HStack(spacing: 12) {
                        Poster(url: it.image, radius: 8).frame(width: 96, height: 54)
                        VStack(alignment: .leading, spacing: 4) {
                            Text(it.title).lineLimit(1)
                            if !it.sub.isEmpty { Text(it.sub).font(.caption).foregroundStyle(Theme.muted) }
                            switch it.status {
                            case "done": Text("جاهز · \(ByteCountFormatter.string(fromByteCount: it.total, countStyle: .file))").font(.caption).foregroundStyle(.green.opacity(0.8))
                            case "failed": Text("فشل — اضغط إعادة").font(.caption).foregroundStyle(Theme.red)
                            default:
                                ProgressView(value: it.frac).tint(.white)
                                Text(it.status == "paused" ? "موقّف · \(Int(it.frac * 100))٪" : "\(Int(it.frac * 100))٪").font(.caption).foregroundStyle(Theme.muted)
                            }
                        }
                        Spacer()
                        if it.status != "done" {
                            Button { it.status == "running" ? dl.pause(it.key) : dl.resume(it.key) } label: {
                                Image(systemName: it.status == "running" ? "pause.circle" : "arrow.clockwise.circle").font(.title2)
                            }.buttonStyle(.plain)
                        }
                    }
                }
                .buttonStyle(.plain)
                .swipeActions { Button("حذف", role: .destructive) { dl.remove(it.key) } }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("التحميلات")
    }

    private func play(_ it: Downloads.Item) {
        guard it.status == "done", let u = dl.localURL(it.key) else { return }
        pc.queue = nil
        pc.watchVod = nil; pc.mini = false
        pc.play(PlayRequest(url: u, key: it.key, title: it.title, sub: it.sub, type: it.type, image: it.image,
                            seriesId: it.seriesId, season: it.season, epNum: it.epNum, start: AppModel.shared.resumePoint(it.key)))
        withAnimation { pc.fullscreen = true }
    }
}

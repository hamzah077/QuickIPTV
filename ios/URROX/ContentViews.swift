import SwiftUI

// MARK: - البحث بأي جزء من الاسم وبأي ترتيب للكلمات (نفس منطق الأندرويد)

enum SearchNorm {
    static func norm(_ s: String) -> String {
        var t = s.lowercased().folding(options: [.diacriticInsensitive, .widthInsensitive], locale: nil)
        let map: [Character: Character] = ["أ": "ا", "إ": "ا", "آ": "ا", "ة": "ه", "ى": "ي",
                                           "٠": "0", "١": "1", "٢": "2", "٣": "3", "٤": "4", "٥": "5", "٦": "6", "٧": "7", "٨": "8", "٩": "9"]
        t = String(t.map { map[$0] ?? $0 })
        return String(t.unicodeScalars.filter { CharacterSet.alphanumerics.contains($0) })
    }
    static func tokens(_ q: String) -> [String] {
        q.split(whereSeparator: { !$0.isLetter && !$0.isNumber }).map { norm(String($0)) }.filter { !$0.isEmpty }
    }
    static func matches(_ name: String, _ t: [String]) -> Bool {
        let n = cache.get(name); return t.allSatisfy { n.contains($0) }
    }
    /// الأسماء المطبّعة تنحفظ مرة وحدة (قبل: كل حرف تكتبه يعيد تطبيع آلاف الأسماء = تقطيع في الكتابة)
    static let cache = NormCache()
}

final class NormCache: @unchecked Sendable {
    private var map: [String: String] = [:]
    private let lock = NSLock()
    func get(_ name: String) -> String {
        lock.lock(); defer { lock.unlock() }
        if let n = map[name] { return n }
        if map.count > 120_000 { map.removeAll(keepingCapacity: true) }
        let n = SearchNorm.norm(name); map[name] = n; return n
    }
}

extension SearchNorm {
    /// الفلترة برّا الخيط الرئيسي: الكتابة والكيبورد ما يتأثرون مهما كبرت القوائم
    static func filter<T: Sendable>(_ items: [T], _ t: [String], limit: Int = .max, name: @escaping @Sendable (T) -> String) async -> [T] {
        await Task.detached(priority: .userInitiated) {
            var out: [T] = []
            for it in items where matches(name(it), t) {
                out.append(it); if out.count >= limit { break }
            }
            return out
        }.value
    }
    /// بعد تحميل القوائم: نجهّز الأسماء بالخلفية عشان أول بحث يكون سريع
    static func warm(_ names: [String]) {
        Task.detached(priority: .utility) { for n in names { _ = cache.get(n) } }
    }
}

/// البحث ينتظر لين توقف كتابة شوي (180ms) — المسح فوري
struct Debounced: ViewModifier {
    let text: String
    @Binding var out: String
    func body(content: Content) -> some View {
        content.task(id: text) {
            if text.isEmpty { out = ""; return }
            try? await Task.sleep(for: .milliseconds(180))
            if !Task.isCancelled { out = text }
        }
    }
}
extension View {
    func debounced(_ text: String, into out: Binding<String>) -> some View { modifier(Debounced(text: text, out: out)) }
}

// MARK: - المباشر: شاشة صغيرة فوق + التصنيفات + القنوات

struct LiveView: View {
    @State private var screenHidden = false
    @Environment(AppModel.self) private var model
    @Environment(PlayerController.self) private var pc
    @Environment(\.horizontalSizeClass) private var hsize
    var start: Channel? = nil
    @State private var cat: String = ""
    @State private var q = ""
    @State private var found: [Channel] = []    // نتايج البحث (تنحسب بالخلفية)
    @State private var current: Channel?
    @State private var epg: [EpgItem] = []

    private var visible: [Channel] {
        SearchNorm.tokens(q).isEmpty ? inCat : found
    }
    private var inCat: [Channel] { cat.isEmpty ? model.channels : model.channels.filter { $0.categoryId == cat } }

    var body: some View {
        Group {
            if hsize == .regular {
                HStack(alignment: .top, spacing: 20) {          // آيباد: القنوات جنب الشاشة
                    list.frame(width: 320)
                    ScrollView { VStack(alignment: .leading, spacing: 12) { screen; info }.padding(.trailing, 20) }
                }
            } else {
                VStack(spacing: 0) {                            // آيفون: الشاشة ثابتة فوق والقنوات تتحرك تحت
                    // القناة بالنافذة العائمة: الشاشة تختفي والقنوات تاخذ المكان كله (نفس الأندرويد)
                    if !screenHidden {
                        VStack(spacing: 0) {
                            screen
                            info.padding(.horizontal, 16).padding(.vertical, 8)
                        }
                        // سحبت القناة للنافذة العائمة: مكان الشاشة يختفي فوراً بنفس اللحظة (بدون مربع أسود)
                        // وبعدها القنوات تطلع لفوق بنعومة
                        .opacity(pc.mini && pc.isLive ? 0 : 1)
                        .transition(.opacity)
                    }
                    list
                }
                // القناة راحت للنافذة العائمة: الشاشة تختفي بحركة والقنوات تطلع لفوق بنعومة (بدل ما تقفز)
                .onChange(of: pc.mini && pc.isLive) { _, v in
                    withAnimation(.calm(.timingCurve(0.2, 0, 0, 1, duration: 0.32))) { screenHidden = v }
                }
                .onAppear { screenHidden = pc.mini && pc.isLive }
            }
        }
        .navigationTitle("المباشر")
        .navigationBarTitleDisplayMode(.inline)
        .modifier(TopBar(query: $q, prompt: "ابحث في القنوات"))
        .task(id: "\(q)|\(cat)|\(model.channels.count)") {
            let t = SearchNorm.tokens(q); guard !t.isEmpty else { return }
            let r = await SearchNorm.filter(inCat, t) { $0.name }
            if !Task.isCancelled { found = r }
        }
        .onAppear {
            pc.hostAppear("live")
            if let s = start, current == nil {
                if pc.req?.key == "live:\(s.id)" { current = s } else { play(s) }      // نفس القناة شغّالة؟ نكمل عليها
            }
            if pc.isLive {
                if current == nil { current = model.channels.first { "live:\($0.id)" == pc.req?.key } }
                if pc.mini { withAnimation(.calm(.spring(duration: 0.35, bounce: 0.05))) { pc.mini = false } }   // رجعت للمباشر: يكبر هنا
            }
        }
        // طلعت من صفحة المباشر: الجوال = يكمل في المشغّل الصغير تحت، الآيباد = يوقف
        .onDisappear {
            pc.hostDisappear("live")
            if pc.isLive { pc.leftHost() }
            if pc.req == nil { current = nil }
        }
    }

    @ViewBuilder private var screen: some View {
        if current != nil, pc.req?.type == "live", !pc.mini {
            InlinePlayer()
        } else if current != nil, pc.isLive, pc.mini {
            Color.clear.aspectRatio(16 / 9, contentMode: .fit)        // مكانه فاضي وهو يتقلّص (مو مربع)
        } else {
            Rectangle().fill(Theme.surface).aspectRatio(16 / 9, contentMode: .fit)
                .overlay(Text("اختر قناة").foregroundStyle(Theme.muted))
        }
    }

    @ViewBuilder private var info: some View {
        if let c = current {
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(c.name).font(.title3.weight(.semibold))
                    Spacer()
                    Button { model.toggleFav("live:\(c.id)") } label: {
                        Image(systemName: model.isFav("live:\(c.id)") ? "heart.fill" : "heart").frame(width: 44, height: 44)
                            .contentShape(Rectangle())
                    }.glassCircle()
                    .accessibilityLabel(model.isFav("live:\(c.id)") ? "إزالة من المفضلة" : "إضافة للمفضلة")
                    .sensoryFeedback(.selection, trigger: model.isFav("live:\(c.id)"))
                }
                if let now = epg.first(where: { $0.isNow }) {
                    Text("الآن: \(now.title)").font(.subheadline).foregroundStyle(Theme.muted)
                    if let nx = epg.first(where: { $0.start >= now.end }) {
                        Text("بعده: \(nx.title) \(nx.start.formatted(date: .omitted, time: .shortened))").font(.caption).foregroundStyle(Theme.muted)
                    }
                }
            }
        }
    }

    private var list: some View {
        VStack(spacing: 8) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("الكل", "")
                    ForEach(model.liveCats) { c in chip(c.name, c.id) }
                }.padding(.horizontal, 12)
            }
            List(visible) { ch in
                Button { play(ch) } label: {
                    HStack(spacing: 12) {
                        Poster(url: ch.icon, radius: 10, contentMode: .fit).frame(width: 46, height: 46).background(Theme.surface, in: RoundedRectangle(cornerRadius: 10))
                        Text("\(ch.num > 0 ? "\(ch.num). " : "")\(ch.name)").lineLimit(1)
                        Spacer()
                        if current?.id == ch.id { Image(systemName: "speaker.wave.2.fill").foregroundStyle(Theme.muted) }
                    }
                }
                .listRowBackground(current?.id == ch.id ? Theme.current : Color.clear)
                .accessibilityAddTraits(current?.id == ch.id ? .isSelected : [])
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .scrollDismissesKeyboard(.interactively)        // الكيبورد ينزل بس لما تسحب القائمة بنفسك
            .overlay {
                if visible.isEmpty {
                    if !q.isEmpty { ContentUnavailableView.search(text: q) }
                    else if !model.loading {
                        ContentUnavailableView("ما فيه قنوات", systemImage: "dot.radiowaves.left.and.right",
                                               description: Text(cat.isEmpty ? "حدّث القوائم من الإعدادات" : "هذا التصنيف فاضي"))
                    }
                }
            }
        }
    }

    private func chip(_ t: String, _ id: String) -> some View {
        Button { cat = id } label: {
            Text(t).font(.subheadline).padding(.horizontal, 14).padding(.vertical, 8)
                .foregroundStyle(cat == id ? .black : .white)
                .background { if cat == id { Capsule().fill(.white) } }
        }
        .buttonStyle(.plain)
        .glass()
        .accessibilityAddTraits(cat == id ? .isSelected : [])
    }

    private func play(_ ch: Channel) {
        guard let api = model.api, let u = api.liveURL(ch.id) else { return }
        current = ch
        pc.mini = false
        pc.watchVod = nil
        pc.queue = nil
        pc.channels = visible
        pc.play(PlayRequest(url: u, key: "live:\(ch.id)", title: ch.name, sub: "", type: "live", image: ch.icon))
        Task { epg = (try? await api.shortEpg(ch.id, limit: 4)) ?? [] }
    }
}

// MARK: - الأفلام / المسلسلات: تصنيفات + شبكة بوسترات

struct VodGridView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.horizontalSizeClass) private var hsize
    let series: Bool
    @State private var cat = ""
    @State private var q = ""
    @State private var found: [Vod] = []

    private var inCat: [Vod] {
        let all = series ? model.seriesList : model.movies
        return cat.isEmpty ? all : all.filter { $0.categoryId == cat }
    }
    /// البحث هنا خاص بالقسم (وبالتصنيف المختار) — النتايج تنحسب بالخلفية
    private var items: [Vod] { SearchNorm.tokens(q).isEmpty ? inCat : found }

    var body: some View {
        ScrollView {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("الكل", "")
                    ForEach(series ? model.seriesCats : model.movieCats) { c in chip(c.name, c.id) }
                }.padding(.horizontal, 16)
            }
            LazyVGrid(columns: [GridItem(.adaptive(minimum: hsize == .regular ? 150 : 104), spacing: 12)], spacing: 14) {
                ForEach(items) { v in
                    NavigationLink { DetailsView(vod: v) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Poster(url: v.icon).aspectRatio(2 / 3, contentMode: .fit).posterDepth()
                            Text(v.name).font(.caption).lineLimit(1).foregroundStyle(.white)
                        }
                    }
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(v.name)
                    .accessibilityAddTraits(.isButton)
                }
            }
            .padding(16)
            .padding(.bottom, 80)
        }
        .overlay {
            if items.isEmpty {
                if !q.isEmpty {
                    ContentUnavailableView.search(text: q)
                } else if !model.loading {
                    ContentUnavailableView(series ? "ما فيه مسلسلات" : "ما فيه أفلام", systemImage: series ? "tv" : "film",
                                           description: Text(cat.isEmpty ? "اسحب الرئيسية لتحت عشان تحدّث القوائم" : "هذا التصنيف فاضي"))
                }
            }
        }
        .navigationTitle(series ? "المسلسلات" : "الأفلام")
        .scrollDismissesKeyboard(.interactively)
        .modifier(TopBar(query: $q, prompt: series ? "ابحث في المسلسلات" : "ابحث في الأفلام"))
        .task(id: "\(q)|\(cat)|\((series ? model.seriesList : model.movies).count)") {
            let t = SearchNorm.tokens(q); guard !t.isEmpty else { return }
            let r = await SearchNorm.filter(inCat, t) { $0.name }
            if !Task.isCancelled { found = r }
        }
    }

    private func chip(_ t: String, _ id: String) -> some View {
        Button { cat = id } label: {
            Text(t).font(.subheadline).padding(.horizontal, 14).padding(.vertical, 8)
                .foregroundStyle(cat == id ? .black : .white)
                .background { if cat == id { Capsule().fill(.white) } }
        }
        .buttonStyle(.plain)
        .glass()
        .accessibilityAddTraits(cat == id ? .isSelected : [])
    }
}

// MARK: - صفحة الفيلم/المسلسل: المشغّل ثابت فوق، والتفاصيل والحلقات تتحرك تحته

struct DetailsView: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerController.self) private var pc
    let vod: Vod
    var autoplayKey: String? = nil
    /// داخل صفحة التشغيل (الجوال): المشغّل فوق تابع للصفحة نفسها، هنا التفاصيل والحلقات بس
    var inPanel = false
    @Environment(\.dismiss) private var dismiss

    @State private var plot = ""
    @State private var meta = ""
    @State private var seasons: [Int: [Episode]] = [:]
    @State private var season = 1
    @State private var loaded = false
    @State private var didLoad = false
    @State private var playTaps = 0

    private var key: String { vod.isSeries ? "series:\(vod.id)" : "movie:\(vod.id)" }
    /// المشغّل داخل الصفحة: الآيباد بس (الجوال: صفحة التشغيل فوق تعرضه)
    /// الجوال (زي الأندرويد): الفيديو يشتغل مكان الصورة فوق بنفس الصفحة — داخل اللوحة الفيديو تبع اللوحة نفسها
    private var showInline: Bool { !PlayerController.phone && playingHere }

    /// التفاصيل اللي تحمّلت: صفحة التشغيل تفتح فيها على طول (بدون ما تنعاد وترمش)
    @MainActor static var cache: [String: (plot: String, meta: String, seasons: [Int: [Episode]], season: Int)] = [:]

    init(vod: Vod, autoplayKey: String? = nil, inPanel: Bool = false) {
        self.vod = vod; self.autoplayKey = autoplayKey; self.inPanel = inPanel
        if let c = Self.cache[vod.isSeries ? "series:\(vod.id)" : "movie:\(vod.id)"] {
            _plot = State(initialValue: c.plot); _meta = State(initialValue: c.meta)
            _seasons = State(initialValue: c.seasons); _season = State(initialValue: c.season)
            _loaded = State(initialValue: true)
        }
    }
    private func saveCache() { Self.cache[key] = (plot, meta, seasons, season) }

    /// الجوال: التشغيل يفتح صفحة التشغيل فوق كل شي (تنسحب لتحت وتصير مشغّل صغير)، وهالصفحة تسكّر تحتها
    private func openWatch() {
        guard PlayerController.phone, !inPanel else { return }
        // صفحة التشغيل تطلع مكانها على طول (بدون حركة من تحت، وبنفس التفاصيل) — وتقدر تسحبها كاملة لتحت
        saveCache()
        var t = Transaction(); t.disablesAnimations = true
        withTransaction(t) { pc.watchVod = vod; pc.mini = false }
        // الصفحة اللي تحتها تتسكّر بهدوء (مخفية تحت صفحة التشغيل) عشان السحب لتحت يبيّن اللي قبلها
        Task { try? await Task.sleep(for: .milliseconds(300)); withTransaction(t) { dismiss() } }
    }
    private var playingHere: Bool {
        guard let r = pc.req else { return false }
        return vod.isSeries ? r.seriesId == vod.id : r.key == "movie:\(vod.id)"
    }

    var body: some View {
        VStack(spacing: 0) {
            // المشغّل ثابت فوق (ما يتحرك مع القائمة)
            if showInline { InlinePlayer() }
            // الجوال: الصورة ثابتة فوق بنفس مقاس الفيديو بالضبط — لما تضغط تشغيل الفيديو ياخذ مكانها
            // والكلام تحته ما يتحرك (نفس المكان ونفس التمرير)
            if PlayerController.phone && !inPanel {
                Color.black.aspectRatio(16 / 9, contentMode: .fit)
                    .overlay { Poster(url: vod.icon, radius: 0).opacity(0.55) }
                    .clipped()
            }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !showInline && !inPanel && !PlayerController.phone {
                        Poster(url: vod.icon, radius: 0).frame(height: 230).clipped().opacity(0.55)
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        if !meta.isEmpty { Text(meta).font(.subheadline).foregroundStyle(Theme.muted) }
                        Text(vod.name).font(.title.weight(.bold))
                        actions
                        if !plot.isEmpty { Text(plot).font(.body).foregroundStyle(.white.opacity(0.85)).frame(maxWidth: 680, alignment: .leading) }   // الآيباد: عرض قراءة مريح
                        if vod.isSeries { episodes }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 40)
            }
            .sharedScroll(key, restore: inPanel)
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        // الآيباد: رجعت أو رحت لقسم ثاني وهو شغّال = يوقف ويحفظ وين وقفت
        // (الجوال: التشغيل بصفحته الخاصة فوق كل شي، تسحبها لتحت تصير مشغّل صغير)
        .onDisappear {
            if !PlayerController.phone, playingHere { pc.leftHost() }
            // الجوال: رجعت من الصفحة وهو شغّال = يصير نافذة عائمة (زي الأندرويد)، وتكبيرها يفتح صفحته
            else if PlayerController.phone, !inPanel, playingHere, !pc.mini, !pc.fullscreen, pc.watchVod == nil {
                pc.watchVod = vod
                withAnimation(PanelHost.ease(1)) { pc.mini = true }
            }
        }
        // سحبت الفيديو لتحت وصار نافذة عائمة: الصفحة تتقفل والنافذة تكمل فوق التطبيق
        .onChange(of: season) { _, _ in if loaded { saveCache() } }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button {
                var t = Transaction(); t.disablesAnimations = true      // بدون حركة: الزر ما يطلع من تحت
                withTransaction(t) { primary() }
                playTaps += 1
            } label: {
                Label(primaryTitle, systemImage: "play.fill").font(.headline)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .foregroundStyle(.black)
            }
            .buttonStyle(PressGray())
            .sensoryFeedback(.impact(weight: .light), trigger: playTaps)      // تحس إن الضغطة وصلت
            if !vod.isSeries {
                DownloadButton(req: { movieRequest() }, key: "movie:\(vod.id)").glassCircle()
            }
            Button { model.toggleFav(key) } label: {
                Image(systemName: model.isFav(key) ? "heart.fill" : "heart").font(.title3).frame(width: 50, height: 50)
            }.glassCircle()
            .accessibilityLabel(model.isFav(key) ? "إزالة من المفضلة" : "إضافة للمفضلة")
            .sensoryFeedback(.selection, trigger: model.isFav(key))         // اهتزاز خفيف (إرشادات آبل)
        }
    }

    // MARK: الحلقات

    private var episodes: some View {
        VStack(alignment: .leading, spacing: 10) {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(seasons.keys.sorted(), id: \.self) { s in
                        Button { season = s } label: {
                            Text("الموسم \(s)").padding(.horizontal, 16).padding(.vertical, 8)
                                .foregroundStyle(season == s ? .black : .white)
                                .background { if season == s { Capsule().fill(.white) } }
                        }.buttonStyle(.plain).glass()
                        .accessibilityAddTraits(season == s ? .isSelected : [])
                    }
                }
            }
            if !loaded { ProgressView().frame(maxWidth: .infinity) }
            LazyVStack(spacing: 4) {
                ForEach(seasons[season] ?? []) { e in episodeRow(e) }
            }
        }
    }

    private func episodeRow(_ e: Episode) -> some View {
        let k = "ep:\(e.id)"
        let h = model.history.first { $0.key == k }
        let playing = pc.req?.key == k
        let current = playing || (pc.req == nil && model.seriesFrontier(vod.id)?.key == k)
        let watched = h?.finished ?? false
        return Button { playEpisode(e) } label: {
            HStack(spacing: 12) {
                ZStack(alignment: .bottom) {
                    Poster(url: e.image.isEmpty ? vod.icon : e.image, radius: 10).frame(width: 124, height: 70)
                    if let h, h.dur > 0, h.pos > 30, !watched { ProgressLine(value: h.pos / h.dur, height: 3).padding(.horizontal, 4).padding(.bottom, 4) }
                }
                .frame(width: 124, height: 70)      // خط التقدّم ما يوسّع الصورة (كان يزحزحها ويطلع برّاها)
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(e.num). \(e.title.isEmpty ? "الحلقة \(e.num)" : e.title)").font(.subheadline).lineLimit(2).foregroundStyle(.white)
                    Text(playing ? "▶ تعرض الآن" : current ? "آخر حلقة شاهدتها" : watched ? "✓ شاهدتها" : e.duration)
                        .font(.caption.monospacedDigit()).foregroundStyle(watched && !current ? Color.green.opacity(0.8) : Theme.muted)
                }
                Spacer()
                DownloadButton(req: { episodeRequest(e) }, key: k, size: 42)
            }
            .padding(8)
            .background(current ? Theme.current : .clear, in: RoundedRectangle(cornerRadius: 12, style: .continuous))     // رمادي خفيف
            .overlay { if current { RoundedRectangle(cornerRadius: 12, style: .continuous).stroke(.white.opacity(0.2), lineWidth: 1) } }
        }
        .buttonStyle(.plain)
    }

    // MARK: التشغيل

    private var primaryTitle: String {
        if vod.isSeries {
            guard let r = resumeEpisode() else { return "تشغيل" }
            let (e, done) = r
            return done ? "الحلقة التالية · م\(e.season) ح\(e.num)" : (model.seriesFrontier(vod.id) == nil ? "تشغيل · م\(e.season) ح\(e.num)" : "متابعة · م\(e.season) ح\(e.num)")
        }
        return model.resumePoint("movie:\(vod.id)") > 0 ? "متابعة" : "تشغيل"
    }

    /// وين وصلت: أبعد حلقة شاهدتها فعلاً (أو اللي بعدها لو خلّصتها)
    private func resumeEpisode() -> (Episode, Bool)? {
        let all = seasons.keys.sorted().flatMap { seasons[$0] ?? [] }
        guard !all.isEmpty else { return nil }
        guard let f = model.seriesFrontier(vod.id), let i = all.firstIndex(where: { "ep:\($0.id)" == f.key }) else { return (all[0], false) }
        if f.finished, i + 1 < all.count { return (all[i + 1], true) }
        return (all[i], false)
    }

    private func primary() {
        if vod.isSeries { if let r = resumeEpisode() { playEpisode(r.0) } }
        else { playMovie() }
    }

    private func movieRequest() -> PlayRequest? {
        guard let api = model.api, let u = api.vodURL(vod.id, ext: vod.ext.isEmpty ? "mp4" : vod.ext) else { return nil }
        return PlayRequest(url: u, key: "movie:\(vod.id)", title: vod.name, sub: "", type: "movie", image: vod.icon,
                           start: model.resumePoint("movie:\(vod.id)"))
    }

    private func episodeRequest(_ e: Episode) -> PlayRequest? {
        let all = seasons.keys.sorted().flatMap { seasons[$0] ?? [] }
        let q = SeriesQueue(seriesId: vod.id, name: vod.name, image: vod.icon, list: all)
        return q.index(of: "ep:\(e.id)").flatMap { q.request($0) }
    }

    private func playMovie() {
        guard let r = movieRequest() else { return }
        pc.mini = false
        pc.queue = nil
        pc.play(r)
        openWatch()
    }

    private func playEpisode(_ e: Episode) {
        let all = seasons.keys.sorted().flatMap { seasons[$0] ?? [] }
        let q = SeriesQueue(seriesId: vod.id, name: vod.name, image: vod.icon, list: all)
        pc.mini = false
        pc.queue = q
        if let i = q.index(of: "ep:\(e.id)"), let r = q.request(i) { pc.play(r); openWatch() }
    }

    private func load() async {
        guard let api = model.api, !didLoad else { return }
        didLoad = true
        if loaded { autoplay(); return }          // من الذاكرة (فتحت الصفحة قبل شوي): بدون تحميل ثاني
        if vod.isSeries {
            if let res = try? await api.seriesInfo(vod.id) {
                let info = res.info, s = res.seasons
                plot = info.str("plot")
                meta = ["مسلسل", String(info.str("releaseDate").prefix(4)), info.str("genre").components(separatedBy: ",").first ?? ""]
                    .filter { !$0.isEmpty }.joined(separator: " · ")
                seasons = s
                season = resumeEpisode()?.0.season ?? s.keys.sorted().first ?? 1
            }
        } else if let info = try? await api.vodInfo(vod.id) {
            plot = info.str("plot").isEmpty ? info.str("description") : info.str("plot")
            meta = ["فيلم", String(info.str("releasedate").prefix(4)), info.str("genre").components(separatedBy: ",").first ?? "", info.str("duration")]
                .filter { !$0.isEmpty }.joined(separator: " · ")
        }
        loaded = true
        if !plot.isEmpty || !meta.isEmpty || !seasons.isEmpty { saveCache() }
        autoplay()
    }

    /// "متابعة المشاهدة": يشتغل على طول
    private func autoplay() {
        if let k = autoplayKey, !playingHere {
            if vod.isSeries {
                if let f = model.seriesFrontier(vod.id), f.finished, let r = resumeEpisode() { playEpisode(r.0) }
                else if let e = seasons.values.flatMap({ $0 }).first(where: { "ep:\($0.id)" == k }) { playEpisode(e) }
            } else { playMovie() }
        }
    }
}

// MARK: - البحث

struct SearchView: View {
    @State private var q = ""
    @State private var fq = ""

    var body: some View {
        SearchResults(q: fq)
            .searchable(text: $q, prompt: "بحث")
            .debounced(q, into: $fq)
            .navigationTitle("بحث")
    }
}

/// نتايج البحث الشامل: قنوات · أفلام · مسلسلات
struct SearchResults: View {
    @Environment(AppModel.self) private var model
    let q: String
    @State private var hits: (q: String, ch: [Channel], mv: [Vod], sr: [Vod])?

    var body: some View {
        let t = SearchNorm.tokens(q)
        List {
            if t.isEmpty {
                Text("اكتب أي جزء من اسم الفيلم أو المسلسل أو القناة").foregroundStyle(Theme.muted)
            } else if let h = hits {
                // النتايج السابقة تبقى لين تجهز الجديدة (بدون ما تفضى وترمش)
                if h.ch.isEmpty && h.mv.isEmpty && h.sr.isEmpty {
                    ContentUnavailableView.search(text: h.q).listRowBackground(Color.clear)
                }
                if !h.ch.isEmpty {
                    SwiftUI.Section("القنوات") {
                        ForEach(h.ch) { c in
                            NavigationLink { LiveView(start: c) } label: {
                                HStack(spacing: 10) {
                                    Poster(url: c.icon, radius: 8, contentMode: .fit).frame(width: 40, height: 40).accessibilityHidden(true)
                                    Text(c.name)
                                }
                            }
                        }
                    }
                }
                if !h.mv.isEmpty { SwiftUI.Section("الأفلام") { ForEach(h.mv) { v in vodRow(v) } } }
                if !h.sr.isEmpty { SwiftUI.Section("المسلسلات") { ForEach(h.sr) { v in vodRow(v) } } }
            } else {
                ProgressView().frame(maxWidth: .infinity).listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.interactively)      // قبل: .immediately = أي تغيّر بالقائمة ينزّل الكيبورد
        .task(id: q) {
            let t = SearchNorm.tokens(q); guard !t.isEmpty else { return }
            let (chs, mvs, srs) = (model.channels, model.movies, model.seriesList)
            async let c = SearchNorm.filter(chs, t, limit: 50) { $0.name }
            async let m = SearchNorm.filter(mvs, t, limit: 60) { $0.name }
            async let r = SearchNorm.filter(srs, t, limit: 60) { $0.name }
            let res = (q: q, ch: await c, mv: await m, sr: await r)
            if !Task.isCancelled { hits = res }
        }
    }

    private func vodRow(_ v: Vod) -> some View {
        NavigationLink { DetailsView(vod: v) } label: {
            HStack(spacing: 10) { Poster(url: v.icon, radius: 6).frame(width: 40, height: 58).accessibilityHidden(true); Text(v.name).lineLimit(2) }
        }
    }
}

// MARK: - الشريط اللي فوق (آيفون): بحث خاص بالصفحة + إعدادات في كل صفحة + تحميلات في الرئيسية

struct TopBar: ViewModifier {
    @Environment(\.horizontalSizeClass) private var hsize
    @Binding var query: String
    var prompt: String
    var home = false
    /// اللي تكتبه: يتحدّث فوراً بالحقل، والصفحة تاخذه بعد ما توقف كتابة (الصفحة ما تنعاد مع كل حرف = بدون لاق)
    @State private var text = ""
    @State private var open = false
    @State private var showSettings = false
    @FocusState private var focused: Bool

    @ViewBuilder func body(content: Content) -> some View {
        if hsize == .regular {
            // آيباد: نفس اللي قبل (البحث الشامل والإعدادات في القائمة الجانبية)
            if home {
                content.toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        NavigationLink { DownloadsView() } label: { Image(systemName: "arrow.down.circle") }
                            .accessibilityLabel("التحميلات")
                        NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                            .accessibilityLabel("الإعدادات")
                    }
                }
            } else {
                content.searchable(text: $text, prompt: prompt)
                    .task(id: text) { await push() }
            }
        } else {
            content
                // الحقل داخل شريط التنقّل نفسه (مكان العنوان): ثابت بنفس المكان بكل الصفحات،
                // والمحتوى تحته ما يتحرك ولا ينعاد ترتيبه (قبل: ينزل تحت العنوان الكبير بالأفلام = مكان مختلف + تقطيع)
                .toolbar {
                    if open {
                        ToolbarItem(placement: .principal) { bar }
                    }
                }
                .toolbar {
                    ToolbarItemGroup(placement: .topBarTrailing) {
                        Button { toggle() } label: { Image(systemName: open ? "xmark" : "magnifyingglass") }
                            .accessibilityLabel("بحث")
                        if home {
                            NavigationLink { DownloadsView() } label: { Image(systemName: "arrow.down.circle") }
                                .accessibilityLabel("التحميلات")
                        }
                        Button { showSettings = true } label: { Image(systemName: "gearshape") }
                            .accessibilityLabel("الإعدادات")
                    }
                }
                .task(id: text) { await push() }
                .sheet(isPresented: $showSettings) {
                    NavigationStack {
                        SettingsView(inSheet: true).toolbar {
                            ToolbarItem(placement: .confirmationAction) { Button("تم") { showSettings = false } }
                        }
                    }
                    .presentationDetents([.medium, .large])
                    .presentationDragIndicator(.visible)
                }
        }
    }

    private var bar: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass").foregroundStyle(Theme.muted)
            TextField(prompt, text: $text)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { focused = false }
            if !text.isEmpty {
                Button { text = ""; query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted) }
                    .accessibilityLabel("مسح البحث")
                    .buttonStyle(.plain)
            }
        }
        .font(.body)
        .padding(.horizontal, 10)
        .frame(height: 36)                     // مقاس حقل البحث عند آبل (36 نقطة، زوايا 10)
        .frame(maxWidth: .infinity)
        .glass(10)
        .onAppear { Task { @MainActor in focused = true } }     // الكيبورد يطلع أول ما يظهر الحقل
    }

    /// بدون حركة على الصفحة كلها: الحقل يطلع بشريط التنقّل بس
    private func toggle() {
        if open { focused = false; text = ""; query = "" }
        open.toggle()
    }

    /// ينتظر 200ms من آخر حرف وبعدها يعطي الصفحة النص — المسح فوري
    private func push() async {
        if text.isEmpty { if !query.isEmpty { query = "" }; return }
        try? await Task.sleep(for: .milliseconds(200))
        if !Task.isCancelled, query != text { query = text }
    }
}

// MARK: - السجل والإعدادات

struct HistoryView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        List(model.displayHistory) { h in
            // الضغط يفتح العمل ويكمل من مكانك (قبل: السجل يعرض بس وما يفتح شي)
            if h.type == "live" {
                if let ch = model.channels.first(where: { "live:\($0.id)" == h.key }) {
                    NavigationLink { LiveView(start: ch) } label: { row(h) }
                } else { row(h) }
            } else {
                NavigationLink { HistoryDestination(h: h) } label: { row(h) }
            }
        }
        .scrollContentBackground(.hidden)
        .overlay {
            if model.displayHistory.isEmpty {
                ContentUnavailableView("السجل فاضي", systemImage: "clock.arrow.circlepath",
                                       description: Text("الأفلام والحلقات والقنوات اللي تشوفها تطلع هنا"))
            }
        }
        .navigationTitle("السجل")
    }

    private func row(_ h: HistoryItem) -> some View {
        HStack(spacing: 12) {
            ZStack(alignment: .bottom) {
                Poster(url: h.image, radius: 8, contentMode: h.type == "live" ? .fit : .fill).frame(width: 90, height: 52)
                if h.dur > 0, !h.finished { ProgressLine(value: h.pos / h.dur, height: 3).padding(.horizontal, 4).padding(.bottom, 3) }
            }
            .frame(width: 90, height: 52)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(h.title).lineLimit(1)
                if !h.sub.isEmpty { Text(h.sub).font(.caption).foregroundStyle(Theme.muted).lineLimit(1) }
            }
        }
    }
}

/// صفحة العمل من السجل / متابعة المشاهدة: يفتح ويكمل من مكانك
struct HistoryDestination: View {
    let h: HistoryItem
    var body: some View {
        if h.type == "episode" {
            DetailsView(vod: Vod(id: h.seriesId, name: h.title, icon: h.image, categoryId: "", ext: "", rating: "", isSeries: true, added: 0), autoplayKey: h.key)
        } else {
            DetailsView(vod: Vod(id: String(h.key.dropFirst(6)), name: h.title, icon: h.image, categoryId: "",
                                 ext: (h.url as NSString).pathExtension, rating: "", isSeries: false, added: 0), autoplayKey: h.key)
        }
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false
    /// مفتوحة كنافذة من تحت (الجوال): نافذة التحديث لازم تطلع داخلها عشان تبان فوقها
    var inSheet = false
    /// رقم الإصدار من التطبيق نفسه (قبل: "1.0" ثابت)
    static let version: String = {
        let i = Bundle.main.infoDictionary
        let v = i?["CFBundleShortVersionString"] as? String ?? "1.0"
        let b = i?["CFBundleVersion"] as? String ?? ""
        return b.isEmpty || b == v ? v : "\(v) (\(b))"
    }()

    var body: some View {
        List {
            SwiftUI.Section("الحسابات") {
                ForEach(model.accounts) { a in
                    Button { model.open(a); Task { await model.loadAll(force: true) } } label: {
                        HStack { Label(a.name, systemImage: "person.crop.circle"); Spacer(); if a.id == model.currentId { Image(systemName: "checkmark") } }
                    }
                    .swipeActions { Button("حذف", role: .destructive) { model.removeAccount(a) } }
                }
                Button { adding = true } label: { Label("إضافة حساب", systemImage: "person.badge.plus") }
            }
            SwiftUI.Section {
                NavigationLink { DownloadsView() } label: { Label("التحميلات", systemImage: "arrow.down.circle") }
                NavigationLink { HistoryView() } label: { Label("سجل المشاهدة", systemImage: "clock.arrow.circlepath") }
                Button { model.popupUpdate() } label: { Label("تحديث القوائم", systemImage: "arrow.clockwise") }
                Picker(selection: Bindable(model).autoUpdateDays) {
                    Text("إيقاف").tag(0)
                    Text("كل يوم").tag(1)
                    Text("كل يومين").tag(2)
                    Text("كل 3 أيام").tag(3)
                    Text("كل أسبوع").tag(7)
                    Text("كل شهر").tag(30)
                } label: { Label("التحديث التلقائي للقوائم", systemImage: "clock.arrow.2.circlepath") }
            }
            SwiftUI.Section { LabeledContent("URROX PLAYER", value: Self.version) }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("الإعدادات")
        .sheet(isPresented: $adding) { LoginView() }
        // نافذة تحديث القوائم فوق الإعدادات
        .overlay { if inSheet && model.updPopup { UpdatePopup().transition(.opacity) } }
        .animation(.easeOut(duration: 0.2), value: model.updPopup)
    }
}


/// زر أبيض: وأنت ضاغط عليه يصير رمادي (بدون حركة ولا تكبير)
struct PressGray: ButtonStyle {
    func makeBody(configuration c: Configuration) -> some View {
        c.label
            .background(c.isPressed ? Color(white: 0.7) : .white, in: Capsule())
            .contentShape(Capsule())
            .transaction { $0.animation = nil }
    }
}


/// مكان التمرير بصفحة الفيلم/المسلسل: صفحة التشغيل تفتح على نفس المكان اللي كنت فيه (ما تقفز لفوق)
enum DetailsScroll { @MainActor static var y: [String: CGFloat] = [:] }

extension View {
    @ViewBuilder func sharedScroll(_ key: String, restore: Bool) -> some View {
        if #available(iOS 18.0, *) { modifier(SharedScroll(key: key, restore: restore)) } else { self }
    }
}

@available(iOS 18.0, *)
private struct SharedScroll: ViewModifier {
    let key: String
    let restore: Bool
    @State private var pos = ScrollPosition(edge: .top)
    func body(content: Content) -> some View {
        content
            .scrollPosition($pos)
            .onScrollGeometryChange(for: CGFloat.self, of: { $0.contentOffset.y + $0.contentInsets.top }) { _, y in
                DetailsScroll.y[key] = max(0, y)
            }
            .onAppear {
                guard restore, let y = DetailsScroll.y[key], y > 0 else { return }
                var t = Transaction(); t.disablesAnimations = true
                withTransaction(t) { pos.scrollTo(y: y) }
                Task { @MainActor in withTransaction(t) { pos.scrollTo(y: y) } }     // بعد ما ينرسم المحتوى
            }
    }
}

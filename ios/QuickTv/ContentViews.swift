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
        let n = norm(name); return t.allSatisfy { n.contains($0) }
    }
}

// MARK: - المباشر: شاشة صغيرة فوق + التصنيفات + القنوات

struct LiveView: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerController.self) private var pc
    @Environment(\.horizontalSizeClass) private var hsize
    var start: Channel? = nil
    @State private var cat: String = ""
    @State private var q = ""
    @State private var current: Channel?
    @State private var epg: [EpgItem] = []

    private var visible: [Channel] {
        let inCat = cat.isEmpty ? model.channels : model.channels.filter { $0.categoryId == cat }
        let t = SearchNorm.tokens(q)
        return t.isEmpty ? inCat : inCat.filter { SearchNorm.matches($0.name, t) }
    }

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
                    if !(pc.mini && pc.isLive) {
                        screen
                        info.padding(.horizontal, 16).padding(.vertical, 8)
                    }
                    list
                }
                .animation(.easeOut(duration: 0.22), value: pc.mini)
            }
        }
        .navigationTitle("المباشر")
        .navigationBarTitleDisplayMode(.inline)
        .modifier(TopBar(query: $q, prompt: "ابحث في القنوات"))
        .onAppear {
            pc.hostAppear("live")
            if let s = start, current == nil {
                if pc.req?.key == "live:\(s.id)" { current = s } else { play(s) }      // نفس القناة شغّالة؟ نكمل عليها
            }
            if pc.isLive {
                if current == nil { current = model.channels.first { "live:\($0.id)" == pc.req?.key } }
                if pc.mini { withAnimation(.spring(duration: 0.35, bounce: 0.05)) { pc.mini = false } }   // رجعت للمباشر: يكبر هنا
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
                        Image(systemName: model.isFav("live:\(c.id)") ? "heart.fill" : "heart").frame(width: 40, height: 40)
                    }.glassCircle()
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
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
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

    private var items: [Vod] {
        let all = series ? model.seriesList : model.movies
        let inCat = cat.isEmpty ? all : all.filter { $0.categoryId == cat }
        let t = SearchNorm.tokens(q)                                    // البحث هنا خاص بالقسم (وبالتصنيف المختار)
        return t.isEmpty ? inCat : inCat.filter { SearchNorm.matches($0.name, t) }
    }

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
                            Poster(url: v.icon).aspectRatio(2 / 3, contentMode: .fit)
                            Text(v.name).font(.caption).lineLimit(1).foregroundStyle(.white)
                        }
                    }
                }
            }
            .padding(16)
            .padding(.bottom, 80)
        }
        .overlay {
            if items.isEmpty && !q.isEmpty { Text("ما فيه نتايج في هذا القسم").foregroundStyle(Theme.muted) }
        }
        .navigationTitle(series ? "المسلسلات" : "الأفلام")
        .modifier(TopBar(query: $q, prompt: series ? "ابحث في المسلسلات" : "ابحث في الأفلام"))
    }

    private func chip(_ t: String, _ id: String) -> some View {
        Button { cat = id } label: {
            Text(t).font(.subheadline).padding(.horizontal, 14).padding(.vertical, 8)
                .foregroundStyle(cat == id ? .black : .white)
                .background { if cat == id { Capsule().fill(.white) } }
        }
        .buttonStyle(.plain)
        .glass()
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

    private var key: String { vod.isSeries ? "series:\(vod.id)" : "movie:\(vod.id)" }
    /// المشغّل داخل الصفحة: الآيباد بس (الجوال: صفحة التشغيل فوق تعرضه)
    private var showInline: Bool { !PlayerController.phone && playingHere }

    /// الجوال: التشغيل يفتح صفحة التشغيل فوق كل شي (تنسحب لتحت وتصير مشغّل صغير)، وهالصفحة تسكّر تحتها
    private func openWatch() {
        guard PlayerController.phone, !inPanel else { return }
        withAnimation(.spring(duration: 0.38, bounce: 0.04)) { pc.watchVod = vod; pc.mini = false }
        Task { try? await Task.sleep(for: .milliseconds(350)); dismiss() }
    }
    private var playingHere: Bool {
        guard let r = pc.req else { return false }
        return vod.isSeries ? r.seriesId == vod.id : r.key == "movie:\(vod.id)"
    }

    var body: some View {
        VStack(spacing: 0) {
            // المشغّل ثابت فوق (ما يتحرك مع القائمة)
            if showInline { InlinePlayer() }
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    if !showInline && !inPanel {
                        Poster(url: vod.icon, radius: 0).frame(height: 230).clipped().opacity(0.55)
                    }
                    VStack(alignment: .leading, spacing: 12) {
                        if !meta.isEmpty { Text(meta).font(.subheadline).foregroundStyle(Theme.muted) }
                        Text(vod.name).font(.title.weight(.bold))
                        actions
                        if !plot.isEmpty { Text(plot).font(.body).foregroundStyle(.white.opacity(0.85)) }
                        if vod.isSeries { episodes }
                    }
                    .padding(.horizontal, 16)
                }
                .padding(.bottom, 40)
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        // الآيباد: رجعت أو رحت لقسم ثاني وهو شغّال = يوقف ويحفظ وين وقفت
        // (الجوال: التشغيل بصفحته الخاصة فوق كل شي، تسحبها لتحت تصير مشغّل صغير)
        .onDisappear {
            if !PlayerController.phone, playingHere { pc.leftHost() }
        }
    }

    private var actions: some View {
        HStack(spacing: 10) {
            Button { primary() } label: {
                Label(primaryTitle, systemImage: "play.fill").font(.headline)
                    .frame(maxWidth: .infinity).frame(height: 50)
                    .background(.white, in: Capsule()).foregroundStyle(.black)
            }
            if !vod.isSeries {
                DownloadButton(req: { movieRequest() }, key: "movie:\(vod.id)").glassCircle()
            }
            Button { model.toggleFav(key) } label: {
                Image(systemName: model.isFav(key) ? "heart.fill" : "heart").font(.title3).frame(width: 50, height: 50)
            }.glassCircle()
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
                VStack(alignment: .leading, spacing: 3) {
                    Text("\(e.num). \(e.title.isEmpty ? "الحلقة \(e.num)" : e.title)").font(.subheadline).lineLimit(2).foregroundStyle(.white)
                    Text(playing ? "▶ تعرض الآن" : current ? "آخر حلقة شاهدتها" : watched ? "✓ شاهدتها" : e.duration)
                        .font(.caption).foregroundStyle(watched && !current ? Color.green.opacity(0.8) : Theme.muted)
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
        guard let api = model.api, !loaded else { return }
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
        // "متابعة المشاهدة": يشتغل على طول
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

    var body: some View {
        SearchResults(q: q)
            .searchable(text: $q, prompt: "بحث")
            .navigationTitle("بحث")
    }
}

/// نتايج البحث الشامل: قنوات · أفلام · مسلسلات
struct SearchResults: View {
    @Environment(AppModel.self) private var model
    let q: String

    var body: some View {
        let t = SearchNorm.tokens(q)
        List {
            if t.isEmpty {
                Text("اكتب أي جزء من اسم الفيلم أو المسلسل أو القناة").foregroundStyle(Theme.muted)
            } else {
                let ch = model.channels.filter { SearchNorm.matches($0.name, t) }.prefix(50)
                let mv = model.movies.filter { SearchNorm.matches($0.name, t) }.prefix(60)
                let sr = model.seriesList.filter { SearchNorm.matches($0.name, t) }.prefix(60)
                if ch.isEmpty && mv.isEmpty && sr.isEmpty {
                    Text("ما فيه نتايج").foregroundStyle(Theme.muted)
                }
                if !ch.isEmpty {
                    SwiftUI.Section("القنوات") {
                        ForEach(Array(ch)) { c in
                            NavigationLink { LiveView(start: c) } label: {
                                HStack(spacing: 10) {
                                    Poster(url: c.icon, radius: 8, contentMode: .fit).frame(width: 40, height: 40)
                                    Text(c.name)
                                }
                            }
                        }
                    }
                }
                if !mv.isEmpty { SwiftUI.Section("الأفلام") { ForEach(Array(mv)) { v in vodRow(v) } } }
                if !sr.isEmpty { SwiftUI.Section("المسلسلات") { ForEach(Array(sr)) { v in vodRow(v) } } }
            }
        }
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.immediately)
    }

    private func vodRow(_ v: Vod) -> some View {
        NavigationLink { DetailsView(vod: v) } label: {
            HStack(spacing: 10) { Poster(url: v.icon, radius: 6).frame(width: 40, height: 58); Text(v.name).lineLimit(2) }
        }
    }
}

// MARK: - الشريط اللي فوق (آيفون): بحث خاص بالصفحة + إعدادات في كل صفحة + تحميلات في الرئيسية

struct TopBar: ViewModifier {
    @Environment(\.horizontalSizeClass) private var hsize
    @Binding var query: String
    var prompt: String
    var home = false
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
                        NavigationLink { SettingsView() } label: { Image(systemName: "gearshape") }
                    }
                }
            } else {
                content.searchable(text: $query, prompt: prompt)
            }
        } else {
            content
                .safeAreaInset(edge: .top, spacing: 0) {
                    if open { bar.transition(.move(edge: .top).combined(with: .opacity)) }
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
                .sheet(isPresented: $showSettings) {
                    NavigationStack {
                        SettingsView().toolbar {
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
            TextField(prompt, text: $query)
                .focused($focused)
                .submitLabel(.search)
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)
                .onSubmit { focused = false }
            if !query.isEmpty {
                Button { query = "" } label: { Image(systemName: "xmark.circle.fill").foregroundStyle(Theme.muted) }
                    .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 12)
        .frame(height: 40)
        .glass(14)
        .padding(.horizontal, 16)
        .padding(.vertical, 6)
        .background(Theme.bg)
    }

    private func toggle() {
        withAnimation(.snappy(duration: 0.25)) { open.toggle() }
        if open {
            Task { try? await Task.sleep(for: .milliseconds(80)); focused = true }
        } else {
            query = ""; focused = false
        }
    }
}

// MARK: - السجل والإعدادات

struct HistoryView: View {
    @Environment(AppModel.self) private var model
    var body: some View {
        List(model.displayHistory) { h in
            HStack(spacing: 12) {
                Poster(url: h.image, radius: 8).frame(width: 90, height: 52)
                VStack(alignment: .leading) {
                    Text(h.title).lineLimit(1)
                    Text(h.sub).font(.caption).foregroundStyle(Theme.muted)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("السجل")
    }
}

struct SettingsView: View {
    @Environment(AppModel.self) private var model
    @State private var adding = false

    var body: some View {
        List {
            SwiftUI.Section("الحسابات") {
                ForEach(model.accounts) { a in
                    Button { model.open(a); Task { await model.loadAll(force: true) } } label: {
                        HStack { Text(a.name); Spacer(); if a.id == model.currentId { Image(systemName: "checkmark") } }
                    }
                    .swipeActions { Button("حذف", role: .destructive) { model.removeAccount(a) } }
                }
                Button("إضافة حساب") { adding = true }
            }
            SwiftUI.Section {
                NavigationLink { DownloadsView() } label: { Label("التحميلات", systemImage: "arrow.down.circle") }
                NavigationLink { HistoryView() } label: { Label("سجل المشاهدة", systemImage: "clock.arrow.circlepath") }
                Button("تحديث المحتوى") { model.popupUpdate() }
            }
            SwiftUI.Section { LabeledContent("QuickTv", value: "1.0") }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("الإعدادات")
        .sheet(isPresented: $adding) { LoginView() }
    }
}

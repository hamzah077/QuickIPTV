import SwiftUI
import UIKit

/// الصفحات اللي شريطها مخفي (كبسولة البحث): سحبة الرجوع من الطرف تبقى شغّالة
extension UINavigationController: @retroactive UIGestureRecognizerDelegate {
    override open func viewDidLoad() {
        super.viewDidLoad()
        interactivePopGestureRecognizer?.delegate = self
    }
    public func gestureRecognizerShouldBegin(_ g: UIGestureRecognizer) -> Bool {
        g == interactivePopGestureRecognizer ? viewControllers.count > 1 : true
    }
}

enum AppSection: String, CaseIterable, Identifiable {
    case home, live, movies, series, downloads, history, settings
    var id: String { rawValue }
    var title: String {
        switch self {
        case .home: return "الرئيسية"
        case .live: return "المباشر"
        case .movies: return "الأفلام"
        case .series: return "المسلسلات"
        case .downloads: return "التحميلات"
        case .history: return "السجل"
        case .settings: return "الإعدادات"
        }
    }
    var icon: String {
        switch self {
        case .home: return "house"
        case .live: return "dot.radiowaves.left.and.right"
        case .movies: return "film"
        case .series: return "tv"
        case .downloads: return "arrow.down.circle"
        case .history: return "clock.arrow.circlepath"
        case .settings: return "gearshape"
        }
    }
    @ViewBuilder var screen: some View {
        switch self {
        case .home: HomeView()
        case .live: LiveView()
        case .movies: VodGridView(series: false)
        case .series: VodGridView(series: true)
        case .downloads: DownloadsView()
        case .history: HistoryView()
        case .settings: SettingsView()
        }
    }
}

// MARK: - آيفون: تبويبات زجاج عائمة (البحث والإعدادات فوق في كل صفحة)

struct PhoneShell: View {
    @Environment(PlayerController.self) private var pc
    @Environment(AppModel.self) private var model
    @State private var tab: AppSection = .home

    /// صفحة التشغيل/المشغّل الصغير ظاهر؟ (فيلم/مسلسل بصفحته، أو أي شي صغّرته)
    private var panelOn: Bool { pc.req != nil && (pc.watchVod != nil || pc.mini) }

    var body: some View {
        ZStack {
            tabs
            // صفحة الفيلم/المسلسل: تطلع من النص بتلاشي وتكبير خفيف (بدل الانزلاق من الجنب)
            if let it = nav.item {
                DetailsOverlay(item: it)
                    .id(it.id)
                    .transition(reduceMotion ? .opacity : .asymmetric(
                        insertion: .opacity.combined(with: .scale(scale: 0.94)),
                        removal: .opacity.combined(with: .scale(scale: 0.97))))
                    .zIndex(2)
            }
            // (السحب كله داخل PanelHost: إصبعك يتحرك = هي بس اللي تنرسم، مو الأقسام كلها — بدون لاج)
            if panelOn {
                PanelHost(tab: $tab)
                    // تطلع من تحت، وتختفي (إغلاق/رجوع للمباشر) بتصغير خفيف وتلاشي — نفس الأندرويد
                    .transition(reduceMotion ? .opacity : .asymmetric(insertion: .move(edge: .bottom), removal: .scale(scale: 0.85).combined(with: .opacity)))
                    .zIndex(5)
            }
            if pc.fullscreen { FullscreenPlayer().zIndex(10) }
        }
        .animation(PanelHost.spring, value: pc.watchVod?.id)
        // (الآيفون عمودي دايماً: ملء الشاشة بس لما تضغط زر التكبير)
    }

    @Environment(\.verticalSizeClass) private var vsize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    private let nav = DetailsNav.shared

    @ViewBuilder private var tabs: some View {
        if #available(iOS 18.0, *) {
            TabView(selection: $tab) {
                Tab(AppSection.home.title, systemImage: AppSection.home.icon, value: AppSection.home) { NavigationStack { HomeView() } }
                Tab(AppSection.live.title, systemImage: AppSection.live.icon, value: AppSection.live) { NavigationStack { LiveView() } }
                Tab(AppSection.movies.title, systemImage: AppSection.movies.icon, value: AppSection.movies) { NavigationStack { VodGridView(series: false) } }
                Tab(AppSection.series.title, systemImage: AppSection.series.icon, value: AppSection.series) { NavigationStack { VodGridView(series: true) } }
            }
        } else {
            TabView(selection: $tab) {
                ForEach([AppSection.home, .live, .movies, .series], id: \.self) { s in
                    NavigationStack { s.screen }.tabItem { Label(s.title, systemImage: s.icon) }.tag(s)
                }
            }
        }
    }
}

extension Animation {
    /// "تقليل الحركة" مفعّل: تلاشي قصير بدل النابض/الانزلاق
    static func calm(_ a: Animation) -> Animation { PanelHost.reduceMotion ? .easeInOut(duration: 0.2) : a }
}

// MARK: - طبقة صفحة التشغيل والنافذة العائمة (حالة السحب هنا لحالها)

struct PanelHost: View {
    @Environment(PlayerController.self) private var pc
    @Binding var tab: AppSection
    /// أثناء سحب صفحة التشغيل: وين وصلت (0 صفحة كاملة … 1 نافذة عائمة)
    @State private var dragT: CGFloat?
    /// مكان النافذة العائمة (زاوية) ومخبّية على الحافة؟
    @State private var miniHidden = 0

    /// نابض ناعم: يبدأ بسرعة إصبعك ويهدى بدون ارتداد مزعج
    /// "تقليل الحركة" مفعّل في الجهاز: تلاشي قصير هادي بدل النابض والانزلاق
    static var reduceMotion: Bool { UIAccessibility.isReduceMotionEnabled }
    static var spring: Animation { reduceMotion ? .easeInOut(duration: 0.2) : .interpolatingSpring(mass: 1, stiffness: 210, damping: 27) }
    /// نفس منحنى الأندرويد بالضبط: يبدأ بسرعة ويهدى بالتدريج، والمدة حسب المسافة الباقية
    static func ease(_ dist: CGFloat) -> Animation {
        reduceMotion ? .easeInOut(duration: 0.2) : .timingCurve(0.2, 0, 0, 1, duration: 0.3 + 0.18 * Double(min(1, abs(dist))))
    }

    var body: some View {
        GeometryReader { g in
            let ins = g.safeAreaInsets
            let W = g.size.width + ins.leading + ins.trailing
            let H = g.size.height + ins.top + ins.bottom
            let t = dragT ?? (pc.mini ? 1 : 0)
            ZStack(alignment: .topLeading) {
                // الصفحة اللي تحت تغمق شوي وهي مغطاة، وتوضح كل ما نزلت الصفحة
                Color.black.opacity(Double((1 - t) * 0.7)).allowsHitTesting(false)
                WatchPanel(W: W, H: H, top: ins.top, bottom: ins.bottom, t: t,
                           corner: Binding(get: { pc.miniCorner }, set: { pc.miniCorner = $0 }), hiddenSide: $miniHidden,
                           onDrag: { dy, ended in drag(dy, ended, range: WatchPanel.range(W: W, H: H, top: ins.top, bottom: ins.bottom)) },
                           onExpand: { expand() })
            }
            .frame(width: W, height: H, alignment: .topLeading)
            .environment(\.layoutDirection, .leftToRight)      // الحساب بالبكسل من اليسار؛ المحتوى نفسه عربي
            .offset(x: -ins.leading, y: -ins.top)
        }
    }

    /// سحب الصفحة الكاملة لتحت: تتبع الإصبع، ولما تترك: ثلث المسافة (أو سحبة سريعة) = نافذة عائمة
    private func drag(_ dy: CGFloat, _ ended: Bool, range: CGFloat) {
        let v = min(1, max(0, dy / range))
        guard ended else {
            var tr = Transaction(); tr.disablesAnimations = true          // يتبع الإصبع على طول (بدون تأخير)
            withTransaction(tr) { dragT = v }
            return
        }
        let to: CGFloat = v > 0.3 ? 1 : 0
        let cur = dragT ?? (pc.mini ? 1 : 0)          // زر التصغير (بدون سحب) يبدأ من الصفحة الكاملة
        withAnimation(Self.ease(to - cur)) {
            if to == 1 { pc.mini = true; miniHidden = 0 }
            dragT = nil
        }
    }

    /// تكبير: فيلم/مسلسل = صفحته ترجع كاملة، قناة = صفحة المباشر، بدون صفحة = ملء الشاشة
    private func expand() {
        guard let r = pc.req else { return }
        if pc.watchVod != nil {
            withAnimation(Self.ease(1)) { pc.mini = false }
        } else if r.type == "live" {
            tab = .live
            withAnimation(.easeOut(duration: 0.22)) { pc.mini = false }
        } else {
            pc.autoFullscreen = false
            withAnimation { pc.fullscreen = true }
        }
    }
}

// MARK: - آيباد: قائمة جانبية زجاج

struct IPadShell: View {
    @Environment(PlayerController.self) private var pc
    @State private var sel: AppSection? = .home

    var body: some View {
        ZStack {
            NavigationSplitView {
                List(selection: $sel) {
                    ForEach(AppSection.allCases) { s in Label(s.title, systemImage: s.icon).tag(s) }
                    NavigationLink { SearchView() } label: { Label("بحث", systemImage: "magnifyingglass") }
                }
                .navigationTitle("URROX")
            } detail: {
                NavigationStack { (sel ?? .home).screen }
            }
            if pc.fullscreen { FullscreenPlayer().zIndex(10) }
        }
    }
}

// MARK: - تسجيل الدخول: الاسم ← المستخدم ← المرور ← السيرفر

struct LoginView: View {
    @Environment(AppModel.self) private var model
    var edit: Account? = nil
    @State private var name = ""
    @State private var user = ""
    @State private var pass = ""
    @State private var server = ""
    @State private var busy = false
    @State private var err: String?
    /// الخانة اللي تكتب فيها: الصفحة تتمرّر لها فوق الكيبورد، و"التالي" ينقلك للي بعدها
    enum Field: Hashable { case name, user, pass, server }
    @FocusState private var focus: Field?

    var body: some View {
        ScrollViewReader { proxy in
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("URROX PLAYER").font(focus == nil ? .largeTitle.weight(.bold) : .title2.weight(.bold)).padding(.top, focus == nil ? 40 : 12)
                if focus == nil { Text("أضف حساب Xtream Codes للبدء").foregroundStyle(Theme.muted) }     // وقت الكتابة: مساحة أكثر
                field("الاسم", "أي اسم تبيه (يطلع في الترحيب)", $name)
                    .focused($focus, equals: .name).submitLabel(.next).onSubmit { focus = .user }.id(Field.name)
                field("اسم المستخدم", "اسم المستخدم", $user, ltr: true)
                    .focused($focus, equals: .user).submitLabel(.next).onSubmit { focus = .pass }.id(Field.user)
                field("كلمة المرور", "كلمة المرور", $pass, secure: true)
                    .focused($focus, equals: .pass).submitLabel(.next).onSubmit { focus = .server }.id(Field.pass)
                field("عنوان السيرفر", "http://example.com:8080 أو الصق رابط الاشتراك", $server, ltr: true)
                    .focused($focus, equals: .server)
                    .submitLabel(.go)
                    .onSubmit { focus = nil; Task { await submit() } }        // زر "انتقال" في الكيبورد يدخل مباشرة
                    .id(Field.server)
                if let err { Text(err).font(.footnote).foregroundStyle(Theme.red) }
                Button { focus = nil; Task { await submit() } } label: {
                    HStack { if busy { ProgressView() }; Text("دخول").font(.headline) }
                        .frame(maxWidth: .infinity).frame(height: 52)
                        .background(Color.primary, in: Capsule()).foregroundStyle(Theme.bg)
                }
                .disabled(busy)
                .padding(.top, 6)
                .id("go")
            }
            .padding(24)
            .frame(maxWidth: 520)
            .frame(maxWidth: .infinity)
        }
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: focus) { _, f in
            guard let f else { return }
            // آخر خانة: نبيّن زر الدخول تحتها بعد
            Task { try? await Task.sleep(for: .milliseconds(250))
                withAnimation(.easeOut(duration: 0.25)) {
                    if f == .server { proxy.scrollTo("go", anchor: .bottom) } else { proxy.scrollTo(f, anchor: .bottom) }
                } }
        }
        .animation(.easeOut(duration: 0.2), value: focus == nil)
        }
        .onAppear { if let e = edit { name = e.name; user = e.username; pass = e.password; server = e.server } }
    }

    private func field(_ label: String, _ hint: String, _ v: Binding<String>, ltr: Bool = false, secure: Bool = false) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.footnote).foregroundStyle(Theme.muted)
            Group {
                if secure { SecureField(hint, text: v) } else { TextField(hint, text: v) }
            }
            .textInputAutocapitalization(.never).autocorrectionDisabled()
            .environment(\.layoutDirection, ltr || secure ? .leftToRight : .rightToLeft)
            .padding(.horizontal, 16).frame(height: 50)
            .glass(14)
        }
    }

    /// يقبل لصق رابط الاشتراك كامل (get.php?username=…&password=…)
    private func splitLink() {
        guard let c = URLComponents(string: server), let q = c.queryItems,
              let u = q.first(where: { $0.name == "username" })?.value, let p = q.first(where: { $0.name == "password" })?.value else { return }
        user = u; pass = p
        server = "\(c.scheme ?? "http")://\(c.host ?? "")" + (c.port.map { ":\($0)" } ?? "")
    }

    private func submit() async {
        splitLink()
        guard !user.isEmpty, !pass.isEmpty, !server.isEmpty else { err = "عبّ كل الخانات"; return }
        busy = true; err = nil
        let a = Account(id: edit?.id ?? UUID().uuidString, name: name.isEmpty ? user : name, server: server, username: user, password: pass)
        do { try await model.addAccount(a) } catch { err = error.localizedDescription }
        busy = false
    }
}

// MARK: - الرئيسية

struct HomeView: View {
    @Environment(AppModel.self) private var model
    @Environment(PlayerController.self) private var pc
    @State private var q = ""

    var body: some View {
        Group {
            if SearchNorm.tokens(q).isEmpty { home } else { SearchResults(q: q) }     // البحث في الرئيسية شامل
        }
        .modifier(TopBar(query: $q, prompt: "ابحث في كل شي: قنوات، أفلام، مسلسلات", home: true))
    }

    private var home: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 22) {
                Text(model.greeting).font(.largeTitle.weight(.bold)).padding(.horizontal, 16).padding(.top, 8)
                if model.loading && model.movies.isEmpty { ProgressView().frame(maxWidth: .infinity) }
                if let e = model.error { Text(e).foregroundStyle(Theme.muted).padding(.horizontal, 16) }

                let cont = model.continueWatching.prefix(15)
                if !cont.isEmpty {
                    SectionHeader(title: "متابعة المشاهدة")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 12) { ForEach(Array(cont)) { h in ContinueCard(h: h) } }.padding(.horizontal, 16)
                    }
                }

                // المفضلة قليلة: نمر عليها هي بدل ما نفحص آلاف القنوات كل مرة تنرسم الصفحة
                let favIds = Set(model.favs.compactMap { $0.hasPrefix("live:") ? String($0.dropFirst(5)) : nil })
                let favCh = favIds.isEmpty ? [] : model.channels.filter { favIds.contains($0.id) }
                if !favCh.isEmpty {
                    SectionHeader(title: "القنوات المفضلة")
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 14) {
                            ForEach(favCh) { ch in
                                NavigationLink { LiveView(start: ch) } label: {
                                    Poster(url: ch.icon, radius: 32, contentMode: .fit).frame(width: 64, height: 64).padding(6).background(Theme.surface, in: Circle())
                                }
                                .accessibilityLabel(ch.name)
                            }
                        }.padding(.horizontal, 16)
                    }
                }

                posterRow("أحدث الأفلام", Array(model.movies.prefix(20)))
                posterRow("أحدث المسلسلات", Array(model.seriesList.prefix(20)))
                let favV = model.favoriteVods
                posterRow("مفضلتك", favV)
                if !model.loading && model.error == nil && model.movies.isEmpty && model.seriesList.isEmpty && model.channels.isEmpty {
                    ContentUnavailableView("ما فيه محتوى للحين", systemImage: "play.rectangle",
                                           description: Text("اسحب الصفحة لتحت عشان تحدّث القوائم"))
                        .padding(.top, 40)
                }
            }
            .padding(.bottom, 90)
        }
        .refreshable { await model.loadAll(force: true) }
        .scrollContentBackground(.hidden)
    }

    @ViewBuilder private func posterRow(_ title: String, _ items: [Vod]) -> some View {
        if !items.isEmpty {
            SectionHeader(title: title)
            ScrollView(.horizontal, showsIndicators: false) {
                LazyHStack(spacing: 10) {
                    ForEach(items) { v in
                        DetailsLink(vod: v) { Poster(url: v.icon).frame(width: 110, height: 162) }
                            .accessibilityLabel(v.name)
                    }
                }.padding(.horizontal, 16)
            }
        }
    }
}

struct ContinueCard: View {
    let h: HistoryItem
    @Environment(AppModel.self) private var model

    var body: some View {
        DetailsLink(vod: HistoryDestination.vod(h), autoplayKey: h.key) {
            VStack(alignment: .leading, spacing: 4) {
                ZStack(alignment: .bottom) {
                    Poster(url: h.image, radius: 14).frame(width: 170, height: 96)
                    if h.dur > 0 { ProgressLine(value: h.pos / h.dur, height: 4).clipShape(Capsule()).padding(.horizontal, 6).padding(.bottom, 6) }
                }
                .frame(width: 170, height: 96)
                Text(h.title).font(.subheadline).lineLimit(1)
                Text(sub).font(.caption.monospacedDigit()).foregroundStyle(Theme.muted).lineLimit(1)
            }
            .frame(width: 170, alignment: .leading)
            .contentShape(Rectangle())                       // الكرت كله ينضغط
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityHint("يكمل من وين وقفت")
    }

    private var sub: String {
        let s = h.sub.replacingOccurrences(of: "الموسم ", with: "م").replacingOccurrences(of: " · الحلقة ", with: " ح")
        if h.type == "episode" && h.finished { return [s, "الحلقة التالية"].filter { !$0.isEmpty }.joined(separator: " · ") }
        if h.dur > 0 { return [s, "متبقي \(fmtTime(h.dur - h.pos))"].filter { !$0.isEmpty }.joined(separator: " · ") }
        return s
    }
}

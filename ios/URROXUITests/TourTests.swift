import XCTest

/// جولة على التطبيق في المحاكي مع سيرفر Xtream وهمي، وتحفظ لقطات شاشة لكل خطوة.
/// كل خطوة "تحاول" بدون ما توقف الاختبار، عشان نطلع بأكبر عدد من اللقطات حتى لو خطوة فشلت.
final class TourTests: XCTestCase {
    var app: XCUIApplication!
    var n = 0
    var prefix = "iphone"
    let dir = ProcessInfo.processInfo.environment["SHOTS_DIR"] ?? "/tmp/urrox-shots"

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments += ["-AppleLanguages", "(ar)", "-AppleLocale", "ar_SA"]
        prefix = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
    }

    // MARK: أدوات

    func shot(_ name: String) {
        n += 1
        let s = XCUIScreen.main.screenshot()
        let file = String(format: "%@-%02d-%@", prefix, n, name)
        let a = XCTAttachment(screenshot: s); a.name = file; a.lifetime = .keepAlways; add(a)
        try? s.pngRepresentation.write(to: URL(fileURLWithPath: "\(dir)/\(file).png"))
        print("SHOT \(file)")
    }

    func el(_ label: String, exact: Bool = false) -> XCUIElement {
        let p = exact ? NSPredicate(format: "label == %@", label) : NSPredicate(format: "label CONTAINS %@", label)
        return app.descendants(matching: .any).matching(p).firstMatch
    }

    @discardableResult
    func tap(_ e: XCUIElement, wait: TimeInterval = 10) -> Bool {
        guard e.waitForExistence(timeout: wait) else { print("MISS \(e)"); return false }
        if e.isHittable { e.tap() } else { e.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        return true
    }

    @discardableResult
    func tapText(_ t: String, wait: TimeInterval = 10) -> Bool {
        let exact = el(t, exact: true)
        if exact.waitForExistence(timeout: wait) { return tap(exact, wait: 1) }
        return tap(el(t), wait: 2)
    }

    func goTab(_ name: String) {
        for q in [app.tabBars.buttons[name], app.buttons[name], app.cells[name], app.staticTexts[name]] where q.exists {
            tap(q, wait: 1); return
        }
        tap(el(name, exact: true))
    }

    func at(_ p: CGPoint) -> XCUICoordinate {
        app.coordinate(withNormalizedOffset: .zero).withOffset(CGVector(dx: p.x, dy: p.y))
    }

    func pause(_ s: Double) { Thread.sleep(forTimeInterval: s) }

    // MARK: الجولة

    func testTour() {
        app.launch()
        pause(2)
        shot("login")

        // تسجيل الدخول: الاسم ← المستخدم ← المرور ← السيرفر
        let tf = app.textFields
        if tf.firstMatch.waitForExistence(timeout: 10) {
            func fill(_ e: XCUIElement, _ t: String) {
                e.tap(); pause(0.6)
                if !(e.value(forKey: "hasKeyboardFocus") as? Bool ?? true) { e.tap(); pause(0.6) }
                e.typeText(t)
            }
            fill(tf.element(boundBy: 0), "Hamzah")
            fill(tf.element(boundBy: 1), "test")
            fill(app.secureTextFields.firstMatch, "test")
            fill(tf.element(boundBy: 2), "http://127.0.0.1:8000/get.php?username=test&password=test")   // لصق رابط الاشتراك كامل
            shot("login-filled")
            app.typeText("\n")                                     // زر "انتقال" يدخل مباشرة
            if !el("الخير").waitForExistence(timeout: 20) {         // احتياط: نسكّر الكيبورد ونضغط دخول
                app.staticTexts["URROX Player"].firstMatch.tap(); pause(1)
                tap(app.buttons["دخول"])
            }
        }

        // الرئيسية
        _ = el("الخير").waitForExistence(timeout: 90)
        pause(4)
        shot("home")
        app.swipeUp(); pause(1.5); shot("home-scrolled")
        app.swipeDown(); app.swipeDown(); pause(1)

        // المباشر
        goTab("المباشر"); pause(3)
        shot("live-list")
        tapText("قناة 1"); pause(7)
        shot("live-playing")
        tapText("قناة 2"); pause(5)
        shot("live-switched")

        // المسلسلات ← تفاصيل ← تشغيل
        goTab("المسلسلات"); pause(3)
        shot("series-grid")
        tapText("مسلسل 1"); pause(4)
        shot("series-details")
        let play = app.buttons.matching(NSPredicate(format: "label CONTAINS 'تشغيل' OR label CONTAINS 'متابعة'")).firstMatch
        tap(play); pause(2)

        // نحفظ مكان المشغّل من زر الإيقاف (في نص المشغّل)
        var center = CGPoint(x: 200, y: 230)
        let pp = app.buttons.matching(NSPredicate(format: "identifier == 'pause.fill' OR identifier == 'play.fill'")).firstMatch
        if pp.waitForExistence(timeout: 10) { center = CGPoint(x: pp.frame.midX, y: pp.frame.midY) }
        pause(6)
        shot("episode-playing")
        at(CGPoint(x: center.x, y: center.y - 55)).tap(); pause(1)
        shot("episode-controls")
        pause(4)                                                    // تختفي الأزرار
        let right = at(CGPoint(x: center.x + 150, y: center.y))
        right.doubleTap(); pause(0.15); right.tap(); pause(0.4)
        shot("double-tap-seek")
        pause(2)

        // سحب لليسار = الحلقة التالية
        at(CGPoint(x: center.x + 140, y: center.y)).press(forDuration: 0.05, thenDragTo: at(CGPoint(x: center.x - 140, y: center.y)))
        pause(3)
        shot("swipe-next-episode")

        // التكبير
        at(CGPoint(x: center.x, y: center.y - 55)).tap(); pause(0.6)
        if tap(app.buttons["arrow.up.left.and.arrow.down.right"], wait: 3) {
            pause(0.25); shot("fullscreen-animating")
            pause(1.5); shot("fullscreen")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).tap(); pause(0.8)
            shot("fullscreen-controls")
            if !tap(app.buttons["chevron.down"], wait: 3) { tap(app.buttons["arrow.down.right.and.arrow.up.left"], wait: 2) }
            pause(1.5); shot("back-to-inline")
        }

        // الأفلام
        goTab("الأفلام"); pause(3)
        shot("movies-grid")
        tapText("سهرة الخميس 1"); pause(4)
        shot("movie-details")

        // الرئيسية بعد المشاهدة (متابعة المشاهدة)
        goTab("الرئيسية"); pause(3)
        shot("home-after")
        tap(app.buttons["gearshape"], wait: 3); pause(2)
        shot("settings")
    }
}

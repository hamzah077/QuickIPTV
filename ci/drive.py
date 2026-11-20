"""يشغّل التطبيق على المحاكي: دخول ← أفلام ← مسلسلات ← تفاصيل، ويحفظ لقطات شاشة."""
import subprocess, time, re, sys, os
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
def sh(*a, check=False): 
    r = subprocess.run(["adb", *a], capture_output=True); return r.stdout
def shot(name):
    open(f"{OUT}/{name}.png", "wb").write(sh("exec-out", "screencap", "-p")); print("shot", name, flush=True)
def dump():
    sh("shell", "uiautomator", "dump", "/sdcard/ui.xml"); return sh("shell", "cat", "/sdcard/ui.xml").decode("utf-8", "ignore")
def nodes(x):
    for m in re.finditer(r'<node [^>]*?/?>', x):
        t = m.group(0)
        g = lambda k: (re.search(k + r'="([^"]*)"', t) or [None, ""])[1]
        b = re.findall(r'\d+', g("bounds"))
        if len(b) == 4: yield dict(text=g("text"), cls=g("class"), desc=g("content-desc"), b=list(map(int, b)))
def tap_node(n): x = (n["b"][0] + n["b"][2]) // 2; y = (n["b"][1] + n["b"][3]) // 2; sh("shell", "input", "tap", str(x), str(y))
def tap_text(t, contains=False, tries=10):
    for _ in range(tries):
        for n in nodes(dump()):
            if (t in n["text"] if contains else n["text"] == t) or n["desc"] == t: tap_node(n); return True
        time.sleep(1.5)
    print("NOT FOUND", t, flush=True); return False

sh("shell", "am", "start", "-n", "com.quickiptv.app/.MainActivity"); time.sleep(6); shot("01_login")
for idx, val in enumerate(["Hamzah", "test", "test", "http://10.0.2.2:8000"]):   # الاسم ← المستخدم ← المرور ← السيرفر
    eds = [n for n in nodes(dump()) if n["cls"] == "android.widget.EditText"]
    print("edittexts", len(eds), flush=True)
    tap_node(eds[idx]); time.sleep(0.8)
    sh("shell", "input", "text", val); time.sleep(0.8)
    sh("shell", "input", "keyevent", "111"); time.sleep(0.8)   # يخفي الكيبورد عشان الأماكن ما تتحرك
shot("01b_filled")
time.sleep(1); tap_text("دخول", contains=True); time.sleep(8); shot("02_home")
print("GREETING_OK" if "Hamzah" in dump() else "GREETING_MISSING", flush=True)
tap_text("أفلام"); time.sleep(8); shot("03_movies")
open(f"{OUT}/ui_movies_page.xml", "w").write(dump())
open(f"{OUT}/viewtree_movies.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
sh("shell", "input", "swipe", "500", "1400", "500", "600", "400"); time.sleep(3); shot("04_movies_scrolled")
tap_text("مسلسلات"); time.sleep(8); shot("05_series")
tap_text("الكل", contains=True); time.sleep(4); shot("06_series_all")
sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("مسلسل 1", contains=True); time.sleep(6); shot("07_series_details")
tap_text("تحميل الموسم"); time.sleep(4); shot("07b_series_downloading")
sh("shell", "input", "keyevent", "4"); time.sleep(2); sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("أفلام"); time.sleep(3)
tap_text("فيلم", contains=True); time.sleep(5)
tap_text("تحميل"); time.sleep(4); shot("08_movie_downloading")
sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("المزيد"); time.sleep(2); shot("10_more")
tap_text("بحث"); time.sleep(1); shot("09a_search_open")
for n in nodes(dump()):
    if n["cls"] == "android.widget.EditText": tap_node(n); break
time.sleep(4)   # وقت تجهيز الفهرس (أول مرة)
t0 = time.time(); sh("shell", "input", "text", "film%s12")   # جزء من وسط الاسم وبترتيب مختلف; time.sleep(0.8); shot("09b_search_typed")
sh("shell", "input", "text", "34"); time.sleep(0.8); shot("09c_search_more")
print("search typed in", round(time.time() - t0, 2), "s", flush=True)
open(f"{OUT}/ui_search.xml", "w").write(dump())
sh("shell", "input", "keyevent", "111"); time.sleep(0.5); sh("shell", "input", "keyevent", "4"); time.sleep(1)
tap_text("المزيد"); time.sleep(2)
tap_text("التحميلات"); time.sleep(5); shot("11_downloads_progress")
# زر الإيقاف في أول سطر (الشاشة تتحدّث باستمرار فـ uiautomator ما يلحق — نضغط بالمكان)
sh("shell", "input", "tap", "220", "746"); time.sleep(4); shot("12_one_paused")
sh("shell", "input", "tap", "220", "746"); time.sleep(4); shot("12b_resumed")
for i in range(30):
    x = dump()
    if "قيد التحميل" not in x and "جاري" not in x and "في الانتظار" not in x: break
    time.sleep(5)
shot("13_downloads_done")
open(f"{OUT}/ui_downloads.xml", "w").write(dump())
tap_text("تشغيل"); time.sleep(3); tap_text("حسنًا", tries=2); time.sleep(6); shot("14_play_offline")
sh("shell", "input", "tap", "540", "300"); time.sleep(1); shot("15_play_offline_controls")
open(f"{OUT}/ui_player.xml", "w").write(dump())

# ---------- الوضع الأفقي + الرئيسية الجديدة ----------
sh("shell", "input", "keyevent", "4"); time.sleep(2)
sh("shell", "input", "keyevent", "4"); time.sleep(1)
tap_text("الرئيسية"); time.sleep(3); shot("20_home_portrait")
sh("shell", "input", "swipe", "540", "1700", "540", "700", "300"); time.sleep(2); shot("20b_home_portrait_scrolled")
sh("shell", "settings", "put", "system", "accelerometer_rotation", "0")
sh("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4)
sh("shell", "input", "swipe", "1100", "800", "1100", "300", "300"); time.sleep(1)
sh("shell", "input", "swipe", "1100", "300", "1100", "800", "300"); time.sleep(2)
shot("21_home_land")
tap_text("أفلام"); time.sleep(4); shot("22_movies_land")
sh("shell", "input", "swipe", "1500", "500", "500", "520", "250"); time.sleep(1)     # سحب أفقي على صف
sh("shell", "input", "swipe", "1100", "850", "1100", "350", "250"); time.sleep(2)    # سحب رأسي
shot("22b_movies_land_scrolled")
tap_text("فيلم", contains=True); time.sleep(4); shot("23_movie_land")
sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("مسلسلات"); time.sleep(4)
tap_text("مسلسل 1", contains=True); time.sleep(5); shot("24_series_land")
sh("shell", "input", "swipe", "600", "850", "600", "350", "250"); time.sleep(2); shot("24b_series_land_scrolled")
sh("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(4); shot("25_series_portrait")
sh("shell", "input", "swipe", "540", "1800", "540", "600", "250"); time.sleep(2); shot("25b_series_portrait_scrolled")

# ---------- المباشر: عمودي ← أفقي ← ملء الشاشة ← قائمة القنوات ← رجوع ----------
sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("مباشر"); time.sleep(5); shot("30_live_portrait")
tap_text("قناة 1"); time.sleep(5); shot("30b_live_preview")
sh("shell", "settings", "put", "system", "user_rotation", "1"); time.sleep(4); shot("31_live_land")
tap_text("قناة 1"); time.sleep(6); shot("32_player_full")
# باللمس: نظهر الشريط ونضغط زر "القنوات" (مكانه من شجرة العرض: 422..673 × 46..156)
sh("shell", "input", "tap", "1170", "700"); time.sleep(1); shot("32b_player_bar")
sh("shell", "input", "tap", "547", "101"); time.sleep(2); shot("33_panel_touch")
open(f"{OUT}/tree_panel_touch.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
sh("shell", "input", "keyevent", "4"); time.sleep(2); shot("33b_panel_closed_by_back")
open(f"{OUT}/tree_after_back.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
# بالريموت: OK يفتح القائمة، تحت مرتين، OK = قناة 3
sh("shell", "input", "keyevent", "23"); time.sleep(2); shot("33c_panel_remote")
open(f"{OUT}/tree_panel_remote.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
sh("shell", "input", "keyevent", "20"); time.sleep(0.5); sh("shell", "input", "keyevent", "20"); time.sleep(0.5)
sh("shell", "input", "keyevent", "23"); time.sleep(4); shot("34_player_switched")
sh("shell", "input", "keyevent", "4"); time.sleep(4); shot("35_back_to_live"); time.sleep(3); shot("35b_back_to_live_later")
sh("shell", "input", "keyevent", "4"); time.sleep(3); shot("36_back_again")
sh("shell", "settings", "put", "system", "user_rotation", "0"); time.sleep(3)
tap_text("الرئيسية"); time.sleep(2); shot("37_home_again")
tap_text("أفلام"); time.sleep(1); shot("38_movies_instant")

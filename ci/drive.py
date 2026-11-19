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
for idx, val in enumerate(["http://10.0.2.2:8000", "test", "test"]):
    eds = [n for n in nodes(dump()) if n["cls"] == "android.widget.EditText"]
    print("edittexts", len(eds), flush=True)
    tap_node(eds[idx]); time.sleep(0.8)
    sh("shell", "input", "text", val); time.sleep(0.8)
    sh("shell", "input", "keyevent", "111"); time.sleep(0.8)   # يخفي الكيبورد عشان الأماكن ما تتحرك
shot("01b_filled")
time.sleep(1); tap_text("دخول", contains=True); time.sleep(8); shot("02_home")
tap_text("أفلام"); time.sleep(8); shot("03_movies")
open(f"{OUT}/ui_movies_page.xml", "w").write(dump())
open(f"{OUT}/viewtree_movies.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
sh("shell", "input", "swipe", "500", "1400", "500", "600", "400"); time.sleep(3); shot("04_movies_scrolled")
tap_text("مسلسلات"); time.sleep(8); shot("05_series")
tap_text("الكل", contains=True); time.sleep(4); shot("06_series_all")
sh("shell", "input", "keyevent", "4"); time.sleep(2)
tap_text("مسلسل 1", contains=True); time.sleep(6); shot("07_series_details")
open(f"{OUT}/ui_movies.xml", "w").write(dump())

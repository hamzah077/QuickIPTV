"""تجربة واجهة التلفزيون بالريموت بس (أسهم + OK + رجوع) على محاكي Android TV."""
import subprocess, time, sys, os
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
def sh(*a): return subprocess.run(["adb", *a], capture_output=True).stdout
def shot(n): open(f"{OUT}/{n}.png", "wb").write(sh("exec-out", "screencap", "-p")); print("shot", n, flush=True)
def tree(n): open(f"{OUT}/{n}.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
def key(k, n=1, gap=0.6):
    for _ in range(n): sh("shell", "input", "keyevent", str(k)); time.sleep(gap)
UP, DOWN, LEFT, RIGHT, OK, BACK = 19, 20, 21, 22, 23, 4

sh("shell", "am", "start", "-n", "com.urrox.player/.MainActivity", "-e", "server", "http://10.0.2.2:8000", "-e", "user", "test", "-e", "pass", "test")
time.sleep(10); shot("tv01_home"); tree("tv01_home")
key(RIGHT, 6); time.sleep(1); shot("tv02_rail_open"); tree("tv02_rail")       # RTL: القائمة يمين
key(DOWN, 3); key(OK); time.sleep(7); shot("tv03_live"); tree("tv03_live")      # الرئيسية→بحث→المفضلة→مباشر
key(OK); time.sleep(6); shot("tv04_live_preview_schedule"); time.sleep(3); shot("tv04b_preview_later")
# تكبير ← تصغير مرتين: نصوّر مرتين بينهم 3 ثواني (لو نفس الصورة = البث واقف)
key(OK); time.sleep(5); shot("tv05_full1"); time.sleep(3); shot("tv05_full1b")
key(BACK); time.sleep(4); shot("tv05_small1"); time.sleep(3); shot("tv05_small1b")
key(OK); time.sleep(5); shot("tv05_full2"); time.sleep(3); shot("tv05_full2b")
key(BACK); time.sleep(4); shot("tv05_small2"); time.sleep(3); shot("tv05_small2b")
key(OK); time.sleep(5); shot("tv05_player")
key(OK); time.sleep(2); shot("tv06_player_panel"); tree("tv06_panel")
key(DOWN, 2); key(OK); time.sleep(5); shot("tv07_switched")
key(BACK); time.sleep(4); shot("tv08_back_live")
key(RIGHT, 6); key(DOWN); key(OK); time.sleep(6); shot("tv09_movies")          # مباشر→أفلام
key(LEFT, 2); key(OK); time.sleep(6); shot("tv10_details"); tree("tv10_details")
key(BACK); time.sleep(2)
key(RIGHT, 6); key(DOWN, 2); key(OK); time.sleep(3); shot("tv11_settings")      # أفلام→مسلسلات→الإعدادات

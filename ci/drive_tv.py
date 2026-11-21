"""تجربة المباشر في التلفزيون: اختيار قناة → هل الصورة تتحرك؟ ثم يسار للمشغّل و OK للتكبير."""
import subprocess, time, sys, os
OUT = sys.argv[1]; os.makedirs(OUT, exist_ok=True)
def sh(*a): return subprocess.run(["adb", *a], capture_output=True).stdout
def shot(n): open(f"{OUT}/{n}.png", "wb").write(sh("exec-out", "screencap", "-p")); print("shot", n, flush=True)
def tree(n): open(f"{OUT}/{n}.txt", "wb").write(sh("shell", "dumpsys", "activity", "top"))
def key(k, n=1, gap=0.7):
    for _ in range(n): sh("shell", "input", "keyevent", str(k)); time.sleep(gap)
UP, DOWN, LEFT, RIGHT, OK, BACK = 19, 20, 21, 22, 23, 4
sh("logcat", "-c")
sh("shell", "am", "start", "-n", "com.quickiptv.app/.MainActivity", "-e", "server", "http://10.0.2.2:8000", "-e", "user", "test", "-e", "pass", "test")
time.sleep(12); shot("l01_home")
key(RIGHT); time.sleep(1); shot("l02_rail")
for i in range(1, 5):
    key(DOWN); time.sleep(1.5); tree(f"l03_rail{i}")
    if b"LiveScreen" in sh("shell", "dumpsys", "activity", "top") or True: pass
shot("l03_rail_moved")
# نرجع لأعلى القائمة ثم ننزل لين نوصل القنوات (نجرب كل قسم ونصوّر)
key(UP, 6)
key(DOWN); key(OK); time.sleep(6); shot("l04_after_rail_ok")
key(LEFT); time.sleep(1); shot("l05_focus_content")
key(OK); time.sleep(3); shot("l06_play_a")
time.sleep(3); shot("l06_play_b")
time.sleep(3); shot("l06_play_c")
tree("l06_tree")
key(LEFT); time.sleep(1.5); shot("l07_focus_screen")
time.sleep(3); shot("l07_focus_screen_b")
key(OK); time.sleep(5); shot("l08_full_a"); time.sleep(3); shot("l08_full_b"); tree("l08_tree")
key(BACK); time.sleep(4); shot("l09_back_a"); time.sleep(3); shot("l09_back_b")
open(f"{OUT}/logcat_player.txt", "wb").write(sh("logcat", "-d", "-v", "time"))

"""سيرفر Xtream وهمي للاختبار على المحاكي (بيانات عربية + صور حقيقية PNG)."""
import json, zlib, struct, random, sys
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse, parse_qs

random.seed(7)
HOST = "http://10.0.2.2:8000"

def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")

VOD_CATS = [("101", "سهرة الخميس"), ("102", "أفلام 4K"), ("103", "أفلام 3D"), ("104", "أفلام اجنبية مترجمة EN MOV 2026")]
SER_CATS = [("201", "مسلسلات عربية تعرض حاليا"), ("202", "مسلسلات عربية 2026"), ("203", "مسلسلات عربية 2025")]
vods, series = [], []
n = 1
for cid, cname in VOD_CATS:
    for i in range(20):
        vods.append({"num": n, "name": f"فيلم {cname} {i+1}", "stream_type": "movie", "stream_id": 5000 + n,
                     "stream_icon": f"{HOST}/img/{n}.png", "rating": random.choice(["7.5", "", 6.2, None, "0"]),
                     "rating_5based": 3.5, "added": str(1700000000 + n * 100), "is_adult": 0,
                     "category_id": cid if n % 3 else int(cid), "category_ids": [int(cid)],
                     "container_extension": "mp4", "custom_sid": None, "direct_source": ""})
        n += 1
for cid, cname in SER_CATS:
    for i in range(20):
        series.append({"num": n, "name": f"مسلسل {i+1}", "series_id": 9000 + n, "cover": f"{HOST}/img/{n}.png",
                       "plot": "قصة", "cast": "", "director": "", "genre": "دراما", "releaseDate": "2025-01-01",
                       "last_modified": str(1700000000 + n), "rating": "8", "rating_5based": 4,
                       "backdrop_path": [f"{HOST}/img/{n}.png"], "youtube_trailer": "", "episode_run_time": "45",
                       "category_id": cid, "category_ids": [int(cid)]})
        n += 1

def series_info(sid):
    eps = {"1": [{"id": str(sid * 10 + e), "episode_num": e, "title": f"الحلقة {e}", "container_extension": "mp4",
                  "season": 1, "info": {"movie_image": f"{HOST}/img/{e}.png", "duration_secs": 2700}} for e in range(1, 6)]}
    return {"seasons": [], "info": {"name": "مسلسل", "cover": f"{HOST}/img/1.png", "plot": "قصة المسلسل"}, "episodes": eps}

class H(BaseHTTPRequestHandler):
    def log_message(self, *a): sys.stderr.write("REQ " + self.path + "\n")
    def send(self, body, ctype="application/json"):
        if isinstance(body, (dict, list)): body = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(200); self.send_header("Content-Type", ctype); self.send_header("Content-Length", str(len(body))); self.end_headers(); self.wfile.write(body)
    def do_GET(self):
        u = urlparse(self.path); q = {k: v[0] for k, v in parse_qs(u.query).items()}
        if u.path.startswith("/img/"):
            k = int(u.path.split("/")[-1].split(".")[0])
            return self.send(png(200, 290, ((k * 47) % 255, (k * 91) % 255, (k * 23) % 255)), "image/png")
        a = q.get("action")
        if a is None: return self.send({"user_info": {"username": "test", "auth": 1, "status": "Active", "exp_date": "1900000000", "max_connections": "1", "active_cons": "0"},
                                         "server_info": {"url": "10.0.2.2", "port": "8000", "server_protocol": "http", "timezone": "Asia/Riyadh"}})
        if a == "get_live_categories": return self.send([{"category_id": "1", "category_name": "رياضة", "parent_id": 0}])
        if a == "get_live_streams": return self.send([{"num": i, "name": f"قناة {i}", "stream_id": i, "stream_icon": f"{HOST}/img/{i}.png", "epg_channel_id": "", "category_id": "1", "tv_archive": 0} for i in range(1, 30)])
        if a == "get_vod_categories": return self.send([{"category_id": c, "category_name": nm, "parent_id": 0} for c, nm in VOD_CATS])
        if a == "get_series_categories": return self.send([{"category_id": c, "category_name": nm, "parent_id": 0} for c, nm in SER_CATS])
        if a == "get_vod_streams": return self.send(vods)
        if a == "get_series": return self.send(series)
        if a == "get_series_info": return self.send(series_info(int(q.get("series_id", "1"))))
        if a == "get_vod_info": return self.send({"info": {"name": "فيلم", "plot": "قصة الفيلم", "movie_image": f"{HOST}/img/3.png"}, "movie_data": {"stream_id": q.get("vod_id")}})
        if a == "get_short_epg": return self.send({"epg_listings": []})
        return self.send([])

ThreadingHTTPServer(("0.0.0.0", 8000), H).serve_forever()

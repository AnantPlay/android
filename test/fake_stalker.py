#!/usr/bin/env python3
"""A fake Stalker / Ministra portal for test/StalkerTest.java: python3 test/fake_stalker.py 8013

Two portals on one port:
  /portal.php                                   Xtream-UI style: get_all_channels, absolute logos, cmd "ffmpeg http://localhost/ch/<id>_"
  /ministra/stalker_portal/server/load.php      Ministra style: no get_all_channels (paged get_ordered_list, 2 a page), relative logos
Checks the MAG headers, the MAC cookie and the Bearer token; tokens only work after get_profile.
GET /expire drops every token (an expired session), GET /stats tells how many handshakes happened.
"""
import json, secrets, sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

MAC = "00:1A:79:12:34:56"
tokens = {}          # token -> profile sent
stats = {"handshakes": 0}
GENRES = [{"id": "*", "title": "All"}, {"id": "1", "title": "News"}, {"id": "2", "title": "Sports \"Live\""}]
CHANNELS = [
    {"id": "11", "name": "News One", "logo": "http://img.example/news1.png", "tv_genre_id": "1", "cmd": "ffmpeg http://localhost/ch/11_"},
    {"id": "12", "name": "Sport Max", "logo": "", "tv_genre_id": "2", "cmd": "ffmpeg http://localhost/ch/12_"},
    {"id": "13", "name": "No Genre TV", "logo": "", "tv_genre_id": "99", "cmd": "ffrt http://cdn.example/live/13.ts"},
]
MINISTRA = [
    {"id": "21", "name": "Ministra A", "logo": "21.png", "tv_genre_id": "1", "cmd": "ffrt http://localhost/ch/21"},
    {"id": "22", "name": "Ministra B", "logo": "", "tv_genre_id": "2", "cmd": "ffrt http://localhost/ch/22"},
    {"id": "23", "name": "Ministra C", "logo": "23.png", "tv_genre_id": "1", "cmd": "ffrt http://localhost/ch/23"},
]


class H(BaseHTTPRequestHandler):
    def log_message(self, *a):
        pass

    def send(self, code, body, ctype="application/json"):
        b = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(b)))
        self.end_headers()
        self.wfile.write(b)

    def js(self, v):
        self.send(200, json.dumps({"js": v}))

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query, keep_blank_values=True).items()}
        if u.path == "/expire":
            tokens.clear()
            return self.send(200, "ok", "text/plain")
        if u.path == "/stats":
            return self.send(200, json.dumps(stats))
        if u.path.startswith("/play/"):
            return self.send(200, "G" * 188, "video/mp2t")
        ministra = u.path == "/ministra/stalker_portal/server/load.php"
        if u.path != "/portal.php" and not ministra:
            return self.send(404, "<html>not found</html>", "text/html")
        h = self.headers
        referer = "http://127.0.0.1:%d%s" % (self.server.server_port, "/ministra/stalker_portal/c/" if ministra else "/c/")
        if ("MAG200 stbapp" not in h.get("User-Agent", "") or h.get("X-User-Agent") != "Model: MAG250; Link: WiFi"
                or h.get("Referer") != referer or q.get("JsHttpRequest") != "1-xml"):
            return self.send(400, "bad client", "text/plain")
        cookie = h.get("Cookie", "")
        if "mac=" not in cookie or "stb_lang=en" not in cookie:
            return self.send(400, "no mac", "text/plain")
        mac = cookie.split("mac=")[1].split(";")[0]
        t, a = q.get("type"), q.get("action")
        if (t, a) == ("stb", "handshake"):
            stats["handshakes"] += 1
            tok = secrets.token_hex(16).upper()
            tokens[tok] = False
            return self.js({"token": tok, "random": "x"})
        tok = h.get("Authorization", "").replace("Bearer ", "")
        if tok not in tokens:
            return self.send(200, "Authorization failed.", "text/plain")
        if (t, a) == ("stb", "get_profile"):
            tokens[tok] = True
            return self.js({"id": "1", "status": 0 if mac == MAC else 1})
        if not tokens[tok] or mac != MAC:
            return self.send(200, "Authorization failed.", "text/plain")
        if (t, a) == ("itv", "get_genres"):
            return self.js(GENRES)
        if (t, a) == ("itv", "get_all_channels"):
            if ministra:
                return self.js([])
            return self.js({"total_items": len(CHANNELS), "max_page_items": len(CHANNELS), "data": CHANNELS})
        if (t, a) == ("itv", "get_ordered_list") and q.get("genre") == "*":
            p = int(q.get("p", "1"))
            return self.js({"total_items": str(len(MINISTRA)), "max_page_items": 2, "data": MINISTRA[(p - 1) * 2:p * 2]})
        if (t, a) == ("itv", "create_link"):
            cmd = q.get("cmd", "")
            ids = [c["id"] for c in CHANNELS + MINISTRA if c["cmd"] == cmd]
            if not ids:
                return self.js({"cmd": "", "error": "nothing_to_play"})
            if ids[0] == "13":
                return self.js({"cmd": "", "error": "link_fault"})   # the portal won't make one: the client falls back to the cmd's own address
            return self.js({"id": ids[0], "cmd": "ffmpeg http://127.0.0.1:%d/play/live.php?mac=%s&stream=%s&extension=ts&play_token=%s"
                            % (self.server.server_port, mac, ids[0], secrets.token_hex(4))})
        return self.js([])


if __name__ == "__main__":
    ThreadingHTTPServer(("127.0.0.1", int(sys.argv[1]) if len(sys.argv) > 1 else 8013), H).serve_forever()

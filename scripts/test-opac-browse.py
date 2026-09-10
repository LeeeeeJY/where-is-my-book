#!/usr/bin/env python3
"""opac-browse.py 가 **브라우저라서 되는 것**을 실제로 하는지, 그리고 여전히 걸러야 하는
것을 거르는지 확인합니다.

가짜 OPAC 을 여섯 종류 세웁니다. 앞의 둘이 이 스크립트가 존재하는 이유입니다.

    pathjs    자바스크립트가 `/KeywordSearchResult/<ISBN>` 을 만듭니다(노원 모양)
              → **HTML 에는 그 경로가 없습니다.** 폼을 읽는 방식으로는 원리적으로 못 찾고,
                브라우저는 주소창에서 그냥 읽습니다
    session   주소창에는 검색어가 남지만 **쿠키가 없으면 무시합니다**(제주·구미·용산 모양)
              → 같은 창에서 확인하면 통과합니다. 새 창에서 열어야 잡힙니다
    normal    평범한 GET 검색 폼                                     → 찾아야 합니다
    ignores   검색어를 무시하고 늘 전체 목록을 뿌립니다                 → 음성 대조가 잡아야 합니다
    flaky     첫 책만 아는 척하고 없는 ISBN 에는 조용합니다             → **재확인**이 잡아야 합니다
    postonly  검색이 POST 라 주소에 검색어가 남지 않습니다             → 버려야 합니다

실행: ./scripts/test-opac-browse.py
"""

from __future__ import annotations

import http.server
import importlib.util
import json
import os
import socket
import sys
import tempfile
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))


def load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


od = load("opac_discover", "opac-discover.py")
ob = load("opac_browse", "opac-browse.py")

BOOKS = {
    "9788937473135": "82년생 김지영",
    "9788996991342": "미움받을 용기",
    "9788936433598": "채식주의자",
}

FORM = """<html><head><meta charset="utf-8"></head><body>
<form method="{method}" action="/search">
  <input type="hidden" name="site" value="main">
  <input type="text" name="searchKeyword">
</form></body></html>"""

# **경로를 자바스크립트가 만듭니다.** HTML 어디에도 /KeywordSearchResult 가 없습니다.
PATHJS = """<html><head><meta charset="utf-8"></head><body>
<input type="text" id="searchKeyword">
<script>
document.getElementById('searchKeyword').addEventListener('keydown', function (e) {
  if (e.key === 'Enter') { location.href = '/KeywordSearchResult/' + encodeURIComponent(this.value); }
});
</script></body></html>"""


def results(hits: list[str]) -> str:
    body = "".join(f"<li>{t}</li>" for t in hits) or "<p>검색결과가 없습니다</p>"
    return f"<html><head><meta charset='utf-8'></head><body><ul>{body}</ul></body></html>"


def make_handler(flavor: str):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, body: str, cookie: str | None = None):
            raw = body.encode("utf-8", "replace")
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            if cookie:
                self.send_header("Set-Cookie", cookie)
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            if path == "/robots.txt":
                self.send("User-agent: *\nDisallow: /admin\n")
                return
            if path == "/":
                if flavor == "pathjs":
                    self.send(PATHJS)
                    return
                # session 은 홈페이지를 열 때 검색 세션을 발급합니다.
                self.send(FORM.format(method="post" if flavor == "postonly" else "get"),
                          "opacsid=1; Path=/" if flavor == "session" else None)
                return
            if flavor == "pathjs" and path.startswith("/KeywordSearchResult/"):
                term = urllib.parse.unquote(path.rsplit("/", 1)[-1])
                self.send(results([t for i, t in BOOKS.items() if term == i]))
                return
            if path != "/search":
                self.send_error(404)
                return

            term = (urllib.parse.parse_qs(query).get("searchKeyword") or [""])[0]
            if flavor == "session" and "opacsid=" not in (self.headers.get("Cookie") or ""):
                # **주소창에는 검색어가 남았는데 세션이 없으면 무시합니다.** 사용자에게는
                # 죽은 링크입니다. 같은 창에서 확인하면 이것을 놓칩니다.
                self.send(results([]))
                return
            if flavor == "ignores":
                hits = list(BOOKS.values())
            elif flavor == "flaky":
                # 첫 책에만 답합니다. 음성 대조는 통과하므로 **재확인 단계**만이 잡습니다.
                hits = [BOOKS["9788937473135"]] if term == "9788937473135" else []
            else:
                hits = [t for i, t in BOOKS.items() if term == i]
            self.send(results(hits))

        def do_POST(self):
            self.send(results([]))
    return Handler


def serve(flavor: str) -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    httpd = http.server.HTTPServer(("127.0.0.1", port), make_handler(flavor))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return port


# (맛, 규칙을 찾아야 하나, 왜)
CASES = [
    ("pathjs", True, "자바스크립트가 만든 경로를 주소창에서 읽어야 합니다"),
    ("normal", True, "평범한 GET 폼"),
    ("session", False, "새 창에서 열면 검색어를 무시합니다"),
    ("ignores", False, "음성 대조가 잡아야 합니다"),
    ("flaky", False, "재확인 단계가 잡아야 합니다"),
    ("postonly", False, "주소에 검색어가 남지 않습니다"),
]


def main() -> int:
    from playwright.sync_api import sync_playwright

    ports = {f: serve(f) for f, _, _ in CASES}
    libs = [{"libCode": f"90000{i}", "name": f, "homepageUrl": f"http://127.0.0.1:{ports[f]}/"}
            for i, (f, _, _) in enumerate(CASES)]
    probe = {l["libCode"]: list(BOOKS) for l in libs}
    pacer = od.Pacer(0.0)
    failures = []

    print("  브라우저 조사")
    with sync_playwright() as p:
        browser = ob.chromium(p)
        try:
            for lib, (flavor, want, why) in zip(libs, CASES):
                r = ob.investigate(browser, flavor, [lib], lib, list(BOOKS), pacer, 10.0)
                got = bool(r["rules"])
                ok = got == want
                detail = r["rules"][0]["url"] if got else r["note"][:40]
                print(f"  {'✓' if ok else '✗'} {flavor:9s} {'찾음' if got else '버림'}  "
                      f"{detail}")
                if not ok:
                    failures.append(f"조사:{flavor}({why})")
        finally:
            browser.close()

    # **폼을 읽는 방식은 pathjs 를 못 찾아야 합니다.** 못 찾는 것이 정상이고, 그것이 이
    # 스크립트가 따로 있는 이유입니다. 여기서 찾아 버리면 둘 중 하나가 잘못된 것입니다.
    print("\n  폼을 읽는 방식과 견주기")
    lib = libs[0]
    r = od.investigate({"key": "pathjs", "libraries": [lib]}, probe, pacer)
    ok = not r["rules"]
    print(f"  {'✓' if ok else '✗'} pathjs 는 폼만 읽어서는 못 찾습니다  {r['note'][:44]}")
    if not ok:
        failures.append("견주기:pathjs 를 폼으로 찾았습니다")

    # 찾은 규칙은 import 통로를 그대로 지나가야 합니다. 거기서 마지막으로 걸러집니다.
    print("\n  찾은 규칙이 import 를 통과하는지")
    work = tempfile.mkdtemp(prefix="opac-browse-test-")
    findings = os.path.join(work, "findings.txt")
    open(findings, "w").write(
        f"127.0.0.1:{ports['pathjs']} | ISBN_SEARCH | UTF-8 | "
        f"http://127.0.0.1:{ports['pathjs']}/KeywordSearchResult/{{isbn13}}\n")
    import contextlib, io
    buf, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
        od.import_findings(findings, [libs[0]])
    rows = [l for l in buf.getvalue().splitlines() if l.strip()]
    ok = len(rows) == 1
    print(f"  {'✓' if ok else '✗'} 줄 {len(rows)}개 (기대 1개)  {rows[0] if rows else err.getvalue()[:60]}")
    if not ok:
        failures.append("import 통과")

    if failures:
        print(f"\n실패: {', '.join(failures)}")
        return 1
    print("\n전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

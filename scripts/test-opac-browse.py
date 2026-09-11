#!/usr/bin/env python3
"""opac-browse.py 가 **브라우저라서 되는 것**을 실제로 하는지, 그리고 여전히 걸러야 하는
것을 거르는지 확인합니다.

가짜 OPAC 을 여러 종류 세웁니다. 앞의 둘이 이 스크립트가 존재하는 이유입니다.

    pathjs    자바스크립트가 `/KeywordSearchResult/<ISBN>` 을 만듭니다(노원 모양)
              → **HTML 에는 그 경로가 없습니다.** 폼을 읽는 방식으로는 원리적으로 못 찾고,
                브라우저는 주소창에서 그냥 읽습니다
    session   주소창에는 검색어가 남지만 **쿠키가 없으면 무시합니다**(제주·구미·용산 모양)
              → 같은 창에서 확인하면 통과합니다. 새 창에서 열어야 잡힙니다
    normal    평범한 GET 검색 폼                                     → 찾아야 합니다
    ignores   검색어를 무시하고 늘 전체 목록을 뿌립니다                 → 음성 대조가 잡아야 합니다
    flaky     첫 책만 아는 척하고 없는 ISBN 에는 조용합니다             → **재확인**이 잡아야 합니다
    postonly  POST 만 받고 같은 파라미터의 GET 으로는 안 열립니다      → 버려야 합니다
    postget   POST 폼이지만 같은 파라미터를 GET 으로도 받습니다        → GET 주소로 규칙을 얻어야 합니다
    posttoken POST 는 `_csrf` 를 검사하고 GET 은 토큰 없이 받습니다    → 토큰 칸을 뺀 주소를 얻어야 합니다
    isbnselect 전체 검색은 ISBN 을 못 찾고 ISBN 항목을 골라야 찾습니다 → 그 항목을 골라 규칙을 얻어야 합니다
    nobook    어떤 검색에도 그 책이 안 나옵니다                        → 버리되 「같은 창에서도」로 적어야 합니다
    detailisbn 상세 주소가 `/book/<ISBN>` 입니다                       → ISBN_DETAIL 까지 얻어야 합니다
    detailkey  상세 주소가 내부 키 `?bookkey=...` 입니다(노원·김해·대구 모양)
              → 자리표는 못 만들지만 **검색 결과에서 링크를 뽑는 패턴**(DETAIL_PATTERN)을
                얻어야 합니다. 서버가 누를 때 그 패턴으로 상세를 찾습니다
    detailspa  검색 결과를 자바스크립트가 그립니다. 브라우저에는 보이지만 원문 HTML 에는 없습니다
              → 검색 규칙은 얻되 **패턴은 버려야 합니다.** 서버는 자바스크립트를 돌리지 않습니다
    detailsession 상세 링크가 세션에 묶여 있어 새 창에서 열면 빈 페이지입니다
              → 패턴을 버려야 합니다. 사용자에게는 죽은 링크입니다
    detailfirst 그 책보다 앞에 같은 모양의 다른 링크(「최근 본 책」)가 있습니다
              → 패턴을 버려야 합니다. 서버는 첫 번째 링크를 쓰므로 엉뚱한 책으로 갑니다
    robotsall robots.txt 가 사이트 전체를 막았습니다                  → 홈페이지도 열지 않고 버려야 합니다
    detailignores 상세 주소가 ISBN 을 무시하고 늘 전부 뿌립니다
              → 상세 규칙도 패턴도 버려야 합니다. 새 창에서도 그 책은 보이므로 음성 대조와
                섞임 확인만이 잡습니다

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
  {extra}
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


# 내부 키. 책마다 다르고 ISBN 과 아무 관계가 없습니다. 실제 OPAC 이 이렇게 씁니다.
INNER_KEY = {"9788937473135": "150671418", "9788996991342": "200000279016535",
             "9788936433598": "DLN000064704"}


def results(hits: list[str], link: str | None = None) -> str:
    """`link` 를 주면 제목이 그 주소로 가는 링크가 됩니다. 상세 페이지가 있는 OPAC 흉내입니다."""
    body = "".join(f"<li><a href='{link.format(t=t)}'>{t}</a></li>" if link else f"<li>{t}</li>"
                   for t in hits) or "<p>검색결과가 없습니다</p>"
    return f"<html><head><meta charset='utf-8'></head><body><ul>{body}</ul></body></html>"


def isbn_of(title: str) -> str:
    return next(i for i, t in BOOKS.items() if t == title)


# 유형마다 받은 경로. robots.txt 가 막은 곳의 홈페이지를 열지 않았는지 봅니다.
VISITS: dict[str, list[str]] = {}


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
            VISITS.setdefault(flavor, []).append(path)
            if path == "/robots.txt":
                self.send("User-agent: *\nDisallow: /\n" if flavor == "robotsall"
                          else "User-agent: *\nDisallow: /admin\n")
                return
            if path == "/":
                if flavor == "pathjs":
                    self.send(PATHJS)
                    return
                # session 은 홈페이지를 열 때 검색 세션을 발급합니다. posttoken 은 세션에 묶인
                # 토큰을 폼에 심습니다(POST 만 검사합니다).
                method = "post" if flavor in ("postonly", "postget", "posttoken") else "get"
                extra, cookie = "", ("opacsid=1; Path=/" if flavor == "session" else None)
                if flavor == "posttoken":
                    extra = "<input type='hidden' name='_csrf' value='tok-7f3a'>"
                    cookie = "csrftok=tok-7f3a; Path=/"
                if flavor == "isbnselect":
                    extra = ("<select name='searchType'><option value='ALL'>전체</option>"
                             "<option value='ISBN'>ISBN</option></select>")
                self.send(FORM.format(method=method, extra=extra), cookie)
                return
            if flavor == "pathjs" and path.startswith("/KeywordSearchResult/"):
                term = urllib.parse.unquote(path.rsplit("/", 1)[-1])
                self.send(results([t for i, t in BOOKS.items() if term == i]))
                return
            if path == "/book" or path.startswith("/book/"):
                if flavor == "detailignores":
                    # 주소의 ISBN 을 무시하고 늘 전부 뿌리는 상세. 새 창에서도 그 책은 보이므로
                    # 음성 대조와 섞임 확인만이 잡습니다.
                    self.send(results(list(BOOKS.values())))
                    return
                if flavor == "detailisbn":
                    isbn = path.rsplit("/", 1)[-1]
                    self.send(results([BOOKS[isbn]] if isbn in BOOKS else []))
                    return
                if flavor == "detailsession" and "opacsid=" not in (self.headers.get("Cookie") or ""):
                    # 검색 화면에서 받은 세션이 있어야만 여는 상세입니다. 새 창에서는 빈 페이지입니다.
                    self.send(results([]))
                    return
                # 내부 키로만 찾습니다. ISBN 을 넣어도 아무것도 안 나옵니다.
                key = (urllib.parse.parse_qs(query).get("bookkey") or [""])[0]
                found = [t for i, t in BOOKS.items() if INNER_KEY[i] == key]
                self.send(results(found))
                return
            if path == "/api/search" and flavor == "detailspa":
                term = (urllib.parse.parse_qs(query).get("q") or [""])[0]
                data = json.dumps([[t, INNER_KEY[i]] for i, t in BOOKS.items() if term == i],
                                  ensure_ascii=False).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)
                return
            if path != "/search":
                self.send_error(404)
                return

            term = (urllib.parse.parse_qs(query).get("searchKeyword") or [""])[0]
            if flavor in ("postonly", "nobook"):
                # postonly 는 POST 만 받습니다. nobook 은 어떤 검색에도 그 책이 나오지 않습니다.
                self.send(results([]))
                return
            if flavor == "isbnselect" and (urllib.parse.parse_qs(query).get("searchType") or [""])[0] != "ISBN":
                # 전체 검색은 ISBN 을 색인하지 않습니다(당진·구미 모양). ISBN 항목을 골라야 찾습니다.
                self.send(results([]))
                return
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
            if flavor in ("detailisbn", "detailignores"):
                self.send("".join(
                    f"<html><head><meta charset='utf-8'></head><body><ul>" +
                    "".join(f"<li><a href='/book/{isbn_of(t)}'>{t}</a></li>" for t in hits) +
                    "</ul></body></html>") if hits else results([]))
            elif flavor in ("detailkey", "detailsession", "detailfirst"):
                # 「최근 본 책」이 목록보다 앞에 있으면 같은 모양의 링크가 먼저 잡힙니다.
                head = ("<p><a href='/book?bookkey=recent'>최근 본 책</a></p>"
                        if flavor == "detailfirst" else "")
                body = ("<html><head><meta charset='utf-8'></head><body>" + head + "<ul>" +
                        "".join(f"<li><a href='/book?bookkey={INNER_KEY[isbn_of(t)]}'>{t}</a></li>"
                                for t in hits) + "</ul></body></html>") if hits else results([])
                self.send(body, "opacsid=1; Path=/" if flavor == "detailsession" else None)
            elif flavor == "detailspa":
                # 원문에는 제목도 링크도 없습니다. 자바스크립트가 /api/search 를 불러 그립니다.
                # 서버가 받는 것은 이 원문이라 제목도 링크도 못 봅니다.
                self.send("<html><head><meta charset='utf-8'></head><body><ul id='r'></ul>"
                          "<script>fetch('/api/search?q=" + urllib.parse.quote(term) + "')"
                          ".then(function(r){return r.json();}).then(function(d){"
                          "var u=document.getElementById('r');"
                          "d.forEach(function(x){var li=document.createElement('li');"
                          "var a=document.createElement('a');a.href='/book?bookkey='+x[1];"
                          "a.textContent=x[0];li.appendChild(a);u.appendChild(li);});"
                          "if(!d.length){u.textContent='검색결과가 없습니다';}});"
                          "</script></body></html>")
            else:
                self.send(results(hits))

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            form = urllib.parse.parse_qs(self.rfile.read(length).decode("utf-8", "replace"))
            term = (form.get("searchKeyword") or [""])[0]
            if self.path.partition("?")[0] != "/search" or flavor not in ("postonly", "postget", "posttoken"):
                self.send(results([]))
                return
            if flavor == "posttoken":
                token = (form.get("_csrf") or [""])[0]
                if not token or f"csrftok={token}" not in (self.headers.get("Cookie") or ""):
                    # 세션과 맞지 않는 토큰. 실제 OPAC 은 403 을 줍니다(은평).
                    self.send(results([]))
                    return
            self.send(results([t for i, t in BOOKS.items() if term == i]))
    return Handler


def serve(flavor: str) -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    httpd = http.server.HTTPServer(("127.0.0.1", port), make_handler(flavor))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return port


# (맛, 얻어야 하는 규칙 종류. 비어 있으면 반드시 버려야 합니다, 왜[, 버릴 때 사유에 들어가야 하는 말])
CASES = [
    ("pathjs", {"ISBN_SEARCH"}, "자바스크립트가 만든 경로를 주소창에서 읽어야 합니다"),
    ("normal", {"ISBN_SEARCH"}, "평범한 GET 폼"),
    ("session", set(), "새 창에서 열면 검색어를 무시합니다", "세션에 묶임"),
    ("ignores", set(), "음성 대조가 잡아야 합니다"),
    ("flaky", set(), "재확인 단계가 잡아야 합니다"),
    ("postonly", set(), "POST 만 받고 GET 으로는 안 열립니다", "GET 으로 보내면"),
    ("postget", {"ISBN_SEARCH"}, "POST 폼이지만 같은 파라미터의 GET 으로 열립니다"),
    ("posttoken", {"ISBN_SEARCH"}, "POST 는 토큰을 검사하지만 GET 은 토큰 없이 열립니다"),
    ("isbnselect", {"ISBN_SEARCH"}, "ISBN 항목을 골라야 찾습니다"),
    ("nobook", set(), "같은 창에서도 안 나오는 곳을 세션 문제로 적으면 안 됩니다", "같은 창에서도"),
    ("detailisbn", {"ISBN_DETAIL", "ISBN_SEARCH"}, "상세 주소에 ISBN 이 있으면 거기까지 갑니다"),
    ("detailkey", {"ISBN_SEARCH", "DETAIL_PATTERN"}, "내부 키면 검색 결과에서 뽑는 패턴을 얻어야 합니다"),
    ("detailspa", {"ISBN_SEARCH"}, "자바스크립트가 그린 링크는 서버에 없으니 패턴을 버려야 합니다"),
    ("detailsession", {"ISBN_SEARCH"}, "세션에 묶인 상세 링크는 패턴을 버려야 합니다"),
    ("detailfirst", {"ISBN_SEARCH"}, "그 책보다 앞에 잡히는 링크가 있으면 패턴을 버려야 합니다"),
    ("detailignores", {"ISBN_SEARCH"}, "ISBN 을 무시하는 상세는 규칙도 패턴도 버려야 합니다"),
    ("robotsall", set(), "사이트 전체를 막은 robots.txt 는 홈페이지부터 지켜야 합니다", "robots.txt"),
]


def main() -> int:
    from playwright.sync_api import sync_playwright

    ports = {c[0]: serve(c[0]) for c in CASES}
    libs = [{"libCode": f"90000{i}", "name": f, "homepageUrl": f"http://127.0.0.1:{ports[f]}/"}
            for i, (f, *_) in enumerate(CASES)]
    probe = {l["libCode"]: list(BOOKS) for l in libs}
    pacer = od.Pacer(0.0)
    failures = []

    print("  브라우저 조사")
    with sync_playwright() as p:
        browser = ob.chromium(p)
        try:
            found_patterns, by_flavor = {}, {}
            for lib, (flavor, want, why, *note) in zip(libs, CASES):
                r = ob.investigate(browser, flavor, [lib], lib, list(BOOKS), pacer, 10.0)
                got = bool(r["rules"])
                kinds = {x["kind"] for x in r["rules"]}
                # 버릴 때는 사유도 맞아야 합니다. 같은 창에서도 안 나오는 곳을 세션 문제로 적으면
                # 엉뚱한 곳을 고치게 됩니다.
                ok = kinds == want and (not note or note[0] in (r["note"] or ""))
                by_flavor[flavor] = r
                shown = (r["rules"][0].get("url") or "") if got else r["note"][:40]
                for rule in r["rules"]:
                    if rule["kind"] == "DETAIL_PATTERN":
                        found_patterns[flavor] = rule
                        shown = rule["pattern"]
                if r.get("detailNote"):
                    shown = f"{shown}  (상세 패턴 없음: {r['detailNote'][:36]})"
                print(f"  {'✓' if ok else '✗'} {flavor:13s} {'찾음' if got else '버림'}  "
                      f"{'+'.join(sorted(kinds)) or '-':28s} {shown}")
                if not ok:
                    failures.append(f"조사:{flavor}({why})")

            # robots.txt 가 사이트 전체를 막았으면 robots.txt 말고는 아무것도 받지 않아야 합니다.
            touched = [x for x in VISITS.get("robotsall", []) if x != "/robots.txt"]
            ok = not touched
            print(f"  {'✓' if ok else '✗'} robotsall 은 robots.txt 말고 받은 것이 없습니다  {touched}")
            if not ok:
                failures.append("robots:홈페이지를 열었습니다")

            # 얻은 주소가 서버가 쓸 모양인지. 토큰 칸이 남으면 오늘만 되는 규칙이고, ISBN 항목을
            # 고른 값이 빠지면 전체 검색으로 돌아가 0건이 됩니다.
            for flavor, must, must_not in (("posttoken", "searchKeyword={isbn13}", "_csrf"),
                                           ("postget", "searchKeyword={isbn13}", None),
                                           ("isbnselect", "searchType=ISBN", None)):
                url = (((by_flavor.get(flavor) or {}).get("rules") or [{}])[0]).get("url", "")
                ok = must in url and not (must_not and must_not in url)
                print(f"  {'✓' if ok else '✗'} {flavor} 의 주소  {url}")
                if not ok:
                    failures.append(f"주소:{flavor}")

            # 찾은 패턴이 실제로 서버가 할 일을 하는지. 첫 번째로 잡히는 링크가 그 책이어야 하고,
            # 그 링크가 새 창에서 열려야 합니다. 조사 단계가 확인했지만 한 번 더 눈으로 봅니다.
            rule = found_patterns.get("detailkey")
            if rule:
                sample = "<a href='/book?bookkey=150671418'>82년생 김지영</a>"
                ok = od.first_href(rule["pattern"], sample) == "/book?bookkey=150671418"
                print(f"  {'✓' if ok else '✗'} detailkey 의 패턴이 링크를 잡습니다  {rule['pattern']}")
                if not ok:
                    failures.append("패턴이 링크를 못 잡습니다")
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

    # 패턴도 같은 통로를 지납니다. --emit 이 낸 줄을 --import-patterns 가 읽어 CSV 한 줄로 만듭니다.
    print("\n  찾은 패턴이 import 를 통과하는지")
    rule = found_patterns.get("detailkey")
    key = f"127.0.0.1:{ports['detailkey']}"
    lib = libs[[c[0] for c in CASES].index("detailkey")]
    open(findings, "w").write(f"{key} | DETAIL_PATTERN | {key} | {rule['pattern'] if rule else ''}\n")
    buf, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
        od.import_patterns(findings, [lib], os.path.join(work, "none.csv"))
    rows = [l for l in buf.getvalue().splitlines() if l.strip()]
    # 서버가 읽는 모양 그대로여야 합니다. 따옴표를 CSV 규칙으로 감싸면 서버가 뜨지 않습니다.
    ok = len(rows) == 1 and rule is not None and rows[0] == f"{key},{rule['pattern']}"
    print(f"  {'✓' if ok else '✗'} 줄 {len(rows)}개 (기대 1개)  {rows[0] if rows else err.getvalue()[:60]}")
    if not ok:
        failures.append("패턴 import 통과")

    # 이미 검색 규칙이 있는 도서관의 상세 단계만 돌리는 길. 사람이 채운 217줄이 여기로 갑니다.
    print("\n  규칙이 있는 도서관의 상세 패턴만 찾기 (--patterns)")
    pwork = tempfile.mkdtemp(prefix="opac-browse-patterns-")
    plibs = [libs[[c[0] for c in CASES].index(fl)] for fl in ("detailkey", "detailisbn", "detailspa")]
    json.dump(plibs, open(os.path.join(pwork, "libraries.json"), "w"))
    json.dump({l["libCode"]: list(BOOKS) for l in plibs}, open(os.path.join(pwork, "probe.json"), "w"))
    templates = os.path.join(pwork, "templates.csv")
    with open(templates, "w") as f:
        for l in plibs:
            f.write(f"{l['libCode']},ISBN_SEARCH,UTF-8,{l['homepageUrl']}search?site=main&searchKeyword={{isbn13}}\n")
    buf, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
        ob.find_patterns(pwork, templates, os.path.join(pwork, "none.csv"), 0.0, 10.0, 0)
        ob.emit(pwork)
    lines = [l for l in buf.getvalue().splitlines() if l and not l.startswith("#")]
    kinds = sorted(l.split("|")[1].strip() for l in lines)
    ok = kinds == ["DETAIL_PATTERN", "ISBN_DETAIL"]
    print(f"  {'✓' if ok else '✗'} detailkey 는 패턴, detailisbn 은 자리표, detailspa 는 없음  {kinds}")
    if not ok:
        failures.append("--patterns")
        print(err.getvalue()[-600:])

    # 이미 규칙이 있는 묶음은 다시 두드리지 않습니다. 큰 묶음부터 도는데 가장 큰 묶음들이
    # 사람이 채운 시스템이라, 거르지 않으면 --limit 의 셋에 하나가 거기에 쓰입니다.
    print("\n  규칙이 있는 묶음 거르기")
    ruled_csv = os.path.join(work, "ruled.csv")
    open(ruled_csv, "w").write("# 머리말\n900001,ISBN_SEARCH,UTF-8,http://x/{isbn13}\n"
                               "900002,ISBN_SEARCH,UTF-8,http://x/{isbn13}\n")
    rows = [("all", [{"libCode": "900001"}, {"libCode": "900002"}], None, []),
            ("some", [{"libCode": "900002"}, {"libCode": "900003"}], None, []),
            ("none", [{"libCode": "900004"}], None, [])]
    kept = [r[0] for r in ob.without_rules(rows, ob.ruled_codes(ruled_csv))]
    ok = kept == ["some", "none"]
    print(f"  {'✓' if ok else '✗'} 전부 규칙이 있는 묶음만 빠집니다  {kept}")
    if not ok:
        failures.append("규칙 있는 묶음 거르기")

    if failures:
        print(f"\n실패: {', '.join(failures)}")
        return 1
    print("\n전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

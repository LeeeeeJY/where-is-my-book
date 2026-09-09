#!/usr/bin/env python3
"""opac-discover.py 가 **잘못된 규칙을 걸러 내는지** 확인합니다.

찾아내는 것보다 **걸러 내는 것이 중요합니다.** 틀린 규칙은 HTTP 200 을 주면서 결과만
0건이 되어 「소장한다더니 그 책이 없네」로 보이고, 깨진 링크와 달리 눈에 띄지 않습니다.
그래서 실제 OPAC 에서 만나는 일곱 가지 모양을 가짜 서버로 세워 놓고 판정을 확인합니다.

    normal    GET 검색 폼이 있고 ISBN 으로 그 책이 나옵니다              → 규칙을 찾아야 합니다
    postonly  검색이 POST 라 주소에 검색어가 남지 않습니다                → 버려야 합니다
    ignores   검색어를 무시하고 늘 전체 목록을 뿌립니다                    → 음성 대조가 잡아야 합니다
    flaky     첫 책은 나오지만 다른 책은 못 찾습니다                       → 재확인이 잡아야 합니다
    euckr     본문이 EUC-KR 이고 ISBN 검색이 됩니다                       → 규칙을 찾아야 합니다
    titleonly ISBN 은 안 되고 제목만 되는데 질의어가 EUC-KR 이어야 합니다  → 인코딩을 맞춰야 합니다
    linked    홈페이지에는 검색창이 없고 「자료검색」 링크로만 갑니다        → 한 단계 따라가야 합니다

마지막 것이 특히 중요합니다. 한글 질의어를 EUC-KR 로 받는 OPAC 에 UTF-8 로 보내면
**200 이 오면서 결과만 0건**이 되는데, 그것이 이 프로젝트가 되풀이해서 경계하는 실패입니다.

실행: ./scripts/test-opac-discover.py
"""

from __future__ import annotations

import contextlib
import http.server
import importlib.util
import io
import json
import os
import socket
import sys
import tempfile
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("opac_discover", os.path.join(HERE, "opac-discover.py"))
od = importlib.util.module_from_spec(spec)
spec.loader.exec_module(od)

BOOKS = {                       # 가짜 도서관이 소장한 책
    "9788937473135": "82년생 김지영",
    "9788996991342": "미움받을 용기",
    "9788936433598": "채식주의자",
}
PAGE = """<html><head><meta charset="{charset}"></head><body>
<form method="{method}" action="/search">
  <input type="hidden" name="site" value="main">
  <input type="text" name="searchKeyword">
</form>{extra}</body></html>"""


def make_handler(flavor: str):
    class Handler(http.server.BaseHTTPRequestHandler):
        def log_message(self, *a):
            pass

        def send(self, body: str, charset: str = "utf-8"):
            raw = body.encode(charset, "replace")
            self.send_response(200)
            self.send_header("Content-Type", f"text/html; charset={charset}")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def do_GET(self):
            path, _, query = self.path.partition("?")
            charset = "euc-kr" if flavor in ("euckr", "titleonly") else "utf-8"
            if path == "/robots.txt":
                self.send("User-agent: *\nDisallow: /admin\n")
                return
            if path == "/":
                if flavor == "linked":
                    # 홈페이지에는 검색창이 없고 메뉴 링크만 있습니다. 실제 도서관에 흔합니다.
                    self.send("<html><head><meta charset='utf-8'></head><body>"
                              "<a href='/opac/search-page'>자료검색</a></body></html>")
                    return
                self.send(PAGE.format(charset=charset,
                                      method="post" if flavor == "postonly" else "get",
                                      extra=""), charset)
                return
            if path == "/opac/search-page" and flavor == "linked":
                self.send(PAGE.format(charset=charset, method="get", extra=""), charset)
                return
            if path != "/search":
                self.send_error(404)
                return

            raw = urllib.parse.parse_qs(query, encoding=charset, errors="replace")
            term = (raw.get("searchKeyword") or [""])[0]
            if flavor == "ignores":                     # 검색어를 무시하고 전체를 뿌립니다
                hits = list(BOOKS.values())
            elif flavor == "flaky":                     # 첫 책만 아는 척합니다
                hits = ["82년생 김지영"] if term else []
            elif flavor == "titleonly":
                # ISBN 으로는 못 찾고, 질의어를 EUC-KR 로 받았을 때만 제목이 맞습니다.
                # UTF-8 로 보내면 위의 parse_qs 가 깨진 글자를 만들어 0건이 됩니다.
                hits = [t for t in BOOKS.values() if term == t]
            else:
                hits = [t for i, t in BOOKS.items() if term == i or term == t]
            body = "".join(f"<li>{t}</li>" for t in hits) or "<p>검색결과가 없습니다</p>"
            self.send(f"<html><head><meta charset='{charset}'></head><body><ul>{body}</ul>"
                      f"</body></html>", charset)

        def do_POST(self):
            self.send("<html><body><p>검색결과가 없습니다</p></body></html>")
    return Handler


def serve(flavor: str) -> int:
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()
    httpd = http.server.HTTPServer(("127.0.0.1", port), make_handler(flavor))
    threading.Thread(target=httpd.serve_forever, daemon=True).start()
    return port


def main() -> int:
    flavors = ["normal", "postonly", "ignores", "flaky", "euckr", "titleonly", "linked"]
    ports = {f: serve(f) for f in flavors}

    work = tempfile.mkdtemp(prefix="opac-test-")
    libs = [{"libCode": f"90000{i}", "name": f, "homepageUrl": f"http://127.0.0.1:{ports[f]}/"}
            for i, f in enumerate(flavors)]
    json.dump(libs, open(os.path.join(work, "libraries.json"), "w"))
    probe = {l["libCode"]: list(BOOKS) for l in libs}
    json.dump(probe, open(os.path.join(work, "probe.json"), "w"))

    pacer = od.Pacer(0.0)
    failures = []
    for lib in libs:
        flavor = lib["name"]
        r = od.investigate({"key": flavor, "libraries": [lib]}, probe, pacer)
        rules = r["rules"]
        got = rules[0] if rules else None

        if flavor in ("normal", "linked"):
            ok = got and got["kind"] == "ISBN_SEARCH" and "{isbn13}" in got["template"]
            detail = got["template"] if got else r["note"]
        elif flavor == "euckr":
            # ISBN 은 숫자라 어느 인코딩이든 통합니다. 규칙만 찾으면 됩니다.
            ok = bool(got) and "{isbn13}" in (got["template"] if got else "")
            detail = got["template"] if got else r["note"]
        elif flavor == "titleonly":
            # 제목 검색으로 내려가되 EUC-KR 로 골라야 합니다. UTF-8 로 적으면 그 도서관은
            # 200 을 주면서 결과만 0건이 되고, 그것은 화면에서 보이지 않습니다.
            ok = (got and got["kind"] == "TITLE_SEARCH" and got["encoding"] == "euc-kr"
                  and "{title}" in got["template"])
            detail = (f'{got["encoding"]} {got["template"]}' if got else r["note"])
        else:
            ok = got is None                    # 나머지 셋은 반드시 버려야 합니다
            detail = (f"버려야 하는데 규칙을 만들었습니다: {got['template']}" if got
                      else r["note"])
        print(f"  {'✓' if ok else '✗'} {flavor:9s} {detail[:80]}")
        if not ok:
            failures.append(flavor)

    # 검증 단계가 실제로 세 번 도는지 (양성·음성·재확인) 확인합니다.
    lib = libs[0]
    r = od.investigate({"key": "normal", "libraries": [lib]}, probe, pacer)
    steps = [c["step"] for c in r["rules"][0]["checks"]]
    ok = steps == ["양성", "음성", "재확인"]
    print(f"  {'✓' if ok else '✗'} 검증 단계   {steps}")
    if not ok:
        failures.append("검증 단계")

    failures += check_bad_homepages(probe, pacer)
    failures += check_import(work)

    if failures:
        print(f"\n실패: {', '.join(failures)}")
        return 1
    print("\n전부 통과했습니다.")
    return 0


def check_bad_homepages(probe: dict, pacer) -> list[str]:
    """홈페이지 주소가 이상한 도서관이 조사를 멈춰 세우지 않는지.

    **실제로 이것 하나가 전체 실행을 죽였습니다.** 정보나루는 홈페이지가 없는 곳에 `-` 를
    주고 스킴을 빼고 주는 곳도 있는데, 그대로 urllib 에 넘기면 「모르는 주소 유형」으로
    예외가 나고 그 예외가 조사 전체를 멈춥니다. 그때까지 두드린 남의 서버 요청이 전부
    헛것이 되므로, 한 묶음의 실패는 그 묶음에서 멈춰야 합니다.
    """
    print("\n  이상한 홈페이지 주소")
    cases = [
        ("정보나루의 빈 값", "-", None),
        ("빈 문자열", "", None),
        ("스킴 없는 진짜 주소", "lib.yongin.go.kr/dongcheon", "http://lib.yongin.go.kr/dongcheon"),
        ("우리가 못 쓰는 스킴", "ftp://lib.example.kr", None),
        ("슬래시 없는 스킴", "mailto:lib@example.kr", None),
        ("포트가 붙은 주소", "lib.example.kr:8080/a", "http://lib.example.kr:8080/a"),
        ("호스트가 아닌 것", "http://localhost", None),
        ("멀쩡한 주소", "https://lib.example.go.kr/a", "https://lib.example.go.kr/a"),
    ]
    failures = []
    for label, given, want in cases:
        got = od.normalize_home(given)
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {label:18s} {given!r} → {got!r}")
        if not ok:
            failures.append(f"주소:{label}")

    # 그리고 그런 도서관을 실제로 조사시켜 봅니다. 예외 대신 사유가 남아야 합니다.
    for label, url in [("빈 값", "-"), ("없는 호스트", "http://이런호스트는없습니다.invalid/")]:
        lib = {"libCode": "800009", "name": "이상한도서관", "homepageUrl": url}
        probe = dict(probe, **{"800009": list(BOOKS)})
        try:
            r = od.investigate({"key": "bad", "libraries": [lib]}, probe, pacer)
            ok = not r["rules"] and bool(r["note"])
            detail = r["note"][:52]
        except Exception as e:
            ok, detail = False, f"예외가 새어 나왔습니다: {type(e).__name__}"
        print(f"  {'✓' if ok else '✗'} {label:18s} {detail}")
        if not ok:
            failures.append(f"조사:{label}")
    return failures


def check_import(work: str) -> list[str]:
    """조사해 온 결과를 받을 때 무엇을 거르는지.

    브라우저만 있는 자리에서 조사한 결과를 받아 들일 때, 실행해 보지 않고도 잡을 수 있는
    것들입니다. **다른 기관 도메인으로 보내는 줄**이 특히 중요합니다. 열어 보지 않고
    그럴듯한 주소를 지어냈을 때 가장 잘 걸리는 자국이기 때문입니다.
    """
    print("\n  받은 결과 거르기")
    libs = [
        {"libCode": "800001", "name": "가나도서관", "homepageUrl": "https://www.lib.example.go.kr/"},
        {"libCode": "800002", "name": "다라도서관", "homepageUrl": "https://www.lib.example.go.kr/"},
        {"libCode": "800003", "name": "마바도서관", "homepageUrl": "https://other.example.or.kr/"},
    ]
    cases = [
        ("정상", "www.lib.example.go.kr | ISBN_SEARCH | UTF-8 | https://www.lib.example.go.kr/s?q={isbn13}", 2),
        ("www 빠진 키", "lib.example.go.kr | ISBN_SEARCH | UTF-8 | https://www.lib.example.go.kr/s?q={isbn13}", 2),
        ("다른 서브도메인", "www.lib.example.go.kr | ISBN_SEARCH | UTF-8 | https://search.lib.example.go.kr/s?q={isbn13}", 2),
        ("다른 기관", "www.lib.example.go.kr | ISBN_SEARCH | UTF-8 | https://vendor.example.com/s?q={isbn13}", 0),
        ("자리표 없음", "www.lib.example.go.kr | ISBN_SEARCH | UTF-8 | https://www.lib.example.go.kr/s", 0),
        ("모르는 종류", "www.lib.example.go.kr | ISBN_XX | UTF-8 | https://www.lib.example.go.kr/s?q={isbn13}", 0),
        ("모르는 인코딩", "www.lib.example.go.kr | ISBN_SEARCH | 없는것 | https://www.lib.example.go.kr/s?q={isbn13}", 0),
        ("실패 줄", "www.lib.example.go.kr | 실패 | - | -", 0),
        ("표 머리글", "묶음키 | 종류 | 인코딩 | 주소", 0),
        ("제목인데 isbn 자리표", "www.lib.example.go.kr | TITLE_SEARCH | UTF-8 | https://www.lib.example.go.kr/s?q={isbn13}", 0),
    ]
    failures = []
    for label, line, want in cases:
        path = os.path.join(work, "findings.txt")
        open(path, "w").write(line + "\n")
        buf, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(buf), contextlib.redirect_stderr(err):
            od.import_findings(path, libs)
        got = len([l for l in buf.getvalue().splitlines() if l.strip()])
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {label:16s} 줄 {got}개 (기대 {want}개)")
        if not ok:
            failures.append(f"받기:{label}")
    return failures


def _unused(work: str) -> int:

    if failures:
        print(f"\n실패: {', '.join(failures)}")
        return 1
    print("\n전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

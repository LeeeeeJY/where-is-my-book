#!/usr/bin/env python3
"""opac-title-probe.py 의 고르기가 **서버(IsbnAnchor·IsbnVerify·PlainRows)와 같은 답**을 내는지 봅니다.

    python3 scripts/test-opac-title-probe.py

여기서 쓰는 목록은 `IsbnAnchorTest` 와 `DetailResolverTest` 의 것과 같습니다. 조사 도구와 서버가
서로 다른 줄을 고르면, 도구가 확인한 규칙이 서버에서는 다른 책으로 갑니다.

상세 화면의 ISBN 으로 고르는 것(`@verify:`)과 큰글자책 줄을 건너뛰는 것(`@plain:`)은 **서버 테스트와 같은
파일**을 읽습니다(`backend/src/test/resources/opac/cases/cases.json`, 서버 쪽은 `SharedPickCasesTest`). 따로
적은 사례는 한쪽만 고쳐도 둘 다 통과합니다. 화면의 글자를 읽는 규칙까지 맞아야 하는 것이라 한 파일로
묶었습니다.

결과를 **다른 주소의 프레임**으로 보여 주는 OPAC(`@frame:`)은 이 기계에 가짜 사이트를 띄워 봅니다.
검색 화면과 프레임을 서로 다른 포트에 두어, 포트가 다르면 robots.txt 를 따로 지키는지까지 봅니다.
바깥 네트워크는 쓰지 않습니다. **가짜 사이트의 HTML 은 지어낸 것입니다.** 충북교육청 계열의 실제
프레임이 어떤 마크업인지는 아직 모릅니다.
"""
import http.server
import importlib.util
import json
import os
import re
import sys
import threading
import urllib.parse

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("tp", os.path.join(HERE, "opac-title-probe.py"))
tp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tp)

LINK = re.compile(r'href="(/detail/\d+)"')
CASES_DIR = os.path.join(HERE, os.pardir, "backend", "src", "test", "resources", "opac", "cases")


def case_file(name: str) -> str:
    with open(os.path.join(CASES_DIR, name), encoding="utf-8") as f:
        return f.read()


def case_page(case: dict) -> str:
    """사례가 화면을 파일로 가리키면 그 파일, 아니면 사례에 적힌 HTML."""
    return case_file(case["file"]) if "file" in case else case["html"]


def regex_spans(page: str, regex: str):
    return [(m.start(), m.end(), m.group(1)) for m in re.finditer(regex, page)]


def shared_cases(check) -> None:
    """서버의 SharedPickCasesTest 와 같은 사례를 도구의 함수로 돌립니다."""
    cases = json.loads(case_file("cases.json"))
    for kind in ("visible", "mentions", "pickAll", "rows", "rules",
                 "frames", "frameProblems", "cookies", "scriptOnly", "sites"):
        check(f"사례 파일에 {kind} 사례가 있다", len(cases.get(kind, [])) >= 5, True)

    print("\n화면의 글자 (서버의 RowText)")
    for c in cases["visible"]:
        check(c["name"], tp.visible_text(c["html"]), c["text"])

    print("\n상세 화면에 그 ISBN 이 적혀 있는가 (서버의 IsbnVerify.mentions)")
    for c in cases["mentions"]:
        check(c["name"], tp.mentions(case_page(c), c["isbn"]), c["expect"])

    print("\n그 ISBN 의 줄 전부 (서버의 IsbnAnchor.pickAll)")
    for c in cases["pickAll"]:
        page = case_page(c)
        spans = regex_spans(page, c["regex"])
        found, _ = tp.pick_all(page, spans, c["isbn"], c["linkFirst"])
        check(c["name"], [s[2] for s in found], c["expect"])
        # 첫 자리는 닻 규칙이 고르는 줄과 같아야 합니다.
        check(c["name"] + " — 첫 자리가 pick 과 같다",
              tp.pick(page, spans, c["isbn"], c["linkFirst"])[0], c["expect"][0] if c["expect"] else None)

    print("\n줄마다의 글자와 판 표기 (서버의 PlainRows.rows)")
    for c in cases["rows"]:
        page = case_page(c)
        rows = tp.plain_rows(page, regex_spans(page, c["regex"]))
        ok = len(rows) == len(c["expect"])
        for (span, text, marked), want in zip(rows, c["expect"]):
            ok = ok and marked == want["marked"]
            ok = ok and ("has" not in want or want["has"] in text)
            ok = ok and ("lacks" not in want or want["lacks"] not in text)
            ok = ok and ("text" not in want or want["text"] == text)
        check(c["name"], ok or [(t, m) for _, t, m in rows], True)

    print("\n규칙 한 줄을 통째로 (서버의 DetailResolver, 도구의 run_rule)")
    for c in cases["rules"]:
        details = {url: case_file(name) for url, name in cases["detailSets"].get(c.get("details"), {}).items()}
        missing = set(c.get("missing", []))
        fetched: list[str] = []

        def fetch_detail(url, details=details, missing=missing, fetched=fetched):
            fetched.append(url)
            return None if url in missing else details.get(url)

        url, how = tp.run_rule(case_page(c), c["url"], c["rule"], c["isbns"], 5, {}, fetch_detail, c["url"])
        check(c["name"], (url, fetched), (c["expect"], c["fetched"]))
        check(c["name"] + " — 서버가 받아 주는 규칙이다", tp.od.pattern_problem(c["rule"]), None)

    # ── 받기 방식 둘: @frame:POST, @frame:COOKIE ──────────────────────────────

    print("\n프레임 규칙이 받는 요청 (서버의 frameUrlFor·frameBodyFor)")
    for c in cases["frames"]:
        request = tp.frame_request(c["rule"], c["title"], c["isbn"], c["libCode"])
        got = {k: request[k] for k in ("method", "url", "body", "contentType", "cookie")}
        check(c["name"], got, c["expect"])
        check(c["name"] + " — 서버가 받아 주는 규칙이다", tp.od.pattern_problem(c["rule"]), None)
        check(c["name"] + " — 되돌려 말한 한 줄", tp.frame_rule_of(tp.od.parse_frame(c["rule"])), c["describe"])

    print("\n받아 주지 않는 프레임 규칙 (서버는 뜨지 않고, 도구는 넣지 않습니다)")
    for c in cases["frameProblems"]:
        check(c["name"], tp.od.pattern_problem(c["rule"]) is not None, True)

    print("\n응답 머리의 쿠키를 담고 싣기 (서버의 SessionCookies)")
    for c in cases["cookies"]:
        jar: dict = {}
        changed = [tp.cookie_store(jar, step) for step in c["steps"]]
        check(c["name"], (changed, tp.cookie_header(jar)), (c["changed"], c["header"]))

    print("\n글자 없이 스크립트만 있는 화면인가 (서버의 SessionCookies.scriptOnly)")
    for c in cases["scriptOnly"]:
        check(c["name"], tp.script_only(case_page(c)), c["expect"])

    print("\n가짜 사이트에 대고 프레임 규칙 한 줄을 통째로: 요청의 차례와 끝 (서버의 DetailResolver)")
    for c in cases["sites"]:
        requests: list[str] = []

        def send(url, method, body, cookie, session, site=c["site"], requests=requests):
            """사례에 적힌 가짜 사이트. 서버 테스트의 FakeSite 와 같은 답을 냅니다."""
            requests.append(f"{method} {url}" + (f" body={body}" if body is not None else "")
                            + (f" cookie={cookie}" if cookie is not None else ""))
            need = site.get("cookie")
            if need and need["need"] not in (cookie or "").split("; "):
                return 200, need["stub"], url, (list(need["set"]) if session else [])
            for page in site["pages"]:
                if page["method"] == method and page["url"] == url and page.get("body") == body:
                    return 200, case_file(page["file"]), url, []
            return 404, "", url, []

        url, how, status = tp.run_frame(c["rule"], c["title"], c["isbns"], c["libCode"], send, c["searchUrl"])
        check(c["name"], (requests, status, url), (c["requests"], c["expect"]["status"], c["expect"]["url"]))
        # 규칙 줄에 이미 적힌 글자(thisLib 의 값이 locaInfo 쿠키의 값과 같습니다)는 쿠키에서 온 것이 아닙니다.
        values = [h.split(";")[0].split("=", 1)[1] for h in (c["site"].get("cookie") or {}).get("set", [])]
        check(c["name"] + " — 사유에 쿠키의 값을 적지 않는다",
              [v for v in values if len(v) > 1 and v not in c["rule"] and v in how], [])


def links(page):
    return [(m.start(), m.end(), m.group(1)) for m in LINK.finditer(page)]


LINK_THEN_ISBN = """
<li><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span><a href="#">담기</a></li>
<li><a href="/detail/2">미움받을 용기 2</a><span>ISBN: 9791186560136</span><a href="#">담기</a></li>
<li><a href="/detail/3">만화 미움받을 용기</a><span>ISBN: 9788901222127</span></li>
"""
ISBN_THEN_LINK = """
<li><img data-isbn="9788996991342"><a href="/detail/1">미움받을 용기</a></li>
<li><img data-isbn="9791186560136"><a href="/detail/2">미움받을 용기 2</a></li>
<li><img data-isbn="9788901222127"><a href="/detail/3">만화 미움받을 용기</a></li>
"""
COVER_FIRST = """
<li><img src="/cover/9788996991342"><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span></li>
<li><img src="/cover/9791186560136"><a href="/detail/2">미움받을 용기 2</a><span>ISBN: 9791186560136</span></li>
"""
NO_LINK_ROW = """
<li><a href="/detail/1">미움받을 용기</a><span>ISBN: 9788996991342</span></li>
<li><span>미움받을 용기 2 (전자책)</span><span>ISBN: 9791186560136</span></li>
<li><a href="/detail/3">만화 미움받을 용기</a><span>ISBN: 9788901222127</span></li>
"""
PLUS_TITLE_PAGE = """
<html><head><script>
function fnSearchResultDetail(recKey, bookKey, publishFormCode){
    var form = document.paramForm;
    form.recKey.value = recKey;
    form.bookKey.value = bookKey;
    form.publishFormCode.value = publishFormCode;
    form.action = "/info/menu/10142/program/30010/plusSearchResultDetail.do";
    form.submit();
}
</script></head><body>
<form name="paramForm" id="paramForm" method="get">
    <input type="hidden" name="searchType" value="SIMPLE">
    <input type="hidden" name="recKey" value="">
    <input type="hidden" name="bookKey" value="">
    <input type="hidden" name="publishFormCode" value="">
</form>
<ul>
 <li><a href="#link" onclick="javascript:fnSearchResultDetail(111,112,'BO'); return false;">미움받을 용기 2</a>
     <span>ISBN: 9791186560136</span></li>
 <li><a href="#link" onclick="javascript:fnSearchResultDetail(210791177,212537932,'BO'); return false;">미움받을 용기</a>
     <span>ISBN: 9788996991342</span></li>
 <li><a href="#link" onclick="javascript:fnSearchResultDetail(333,334,'BO'); return false;">만화 미움받을 용기</a>
     <span>ISBN: 9788901222127</span></li>
</ul></body></html>
"""
BASE = "https://www.l4d.or.kr/info/plusSearchResultList.do?searchKeyword=x"


# ── 결과가 프레임에 있는 가짜 OPAC ────────────────────────────────────────────

BOOKS = {"9788937473135": "82년생 김지영", "9788996991342": "미움받을 용기"}


class FakeFramedOpac:
    """검색 화면(바깥)과 결과 프레임을 서로 다른 포트에 둔 사이트. 받은 요청을 포트별로 적어 둡니다.

    `iframe` 이 거짓이면 프레임 주소를 스크립트가 만드는 화면을 흉내 냅니다(HTML 에 주소가 없습니다).
    `frame_robots` 는 프레임 쪽 robots.txt 의 본문입니다.
    """

    def __init__(self, iframe: bool = True, frame_robots: str = "User-agent: *\nAllow: /\n",
                 plus_for_space: bool = False):
        self.iframe = iframe
        self.frame_robots = frame_robots
        self.plus_for_space = plus_for_space
        self.outer_hits: list[str] = []
        self.frame_hits: list[str] = []
        self.outer = self._serve(self._outer_page, self.outer_hits)
        self.frame = self._serve(self._frame_page, self.frame_hits)

    def _serve(self, render, hits):
        owner = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                hits.append(self.path)
                status, body = render(self.path)
                raw = body.encode("utf-8")
                self.send_response(status)
                self.send_header("Content-Type", "text/html; charset=utf-8")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            def log_message(self, *args):
                pass

        httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        return httpd

    @property
    def outer_port(self): return self.outer.server_address[1]

    @property
    def frame_port(self): return self.frame.server_address[1]

    def close(self):
        self.outer.shutdown()
        self.frame.shutdown()

    def search_template(self) -> str:
        return f"http://127.0.0.1:{self.outer_port}/srch/smart2.do?cmd=SEARCH&menuId=MNU_1&searchKeyword={{title}}"

    def frame_template(self) -> str:
        return (f"http://127.0.0.1:{self.frame_port}/dls_le/index.php?mod=wdDataSearch&act=searchIList"
                f"&word={{title}}&searchType=&manageCode=MA&siteId=SITE_3")

    def _outer_page(self, path):
        if path == "/robots.txt":
            return 200, "User-agent: *\nAllow: /\n"
        query = urllib.parse.parse_qs(urllib.parse.urlparse(path).query)
        word = query.get("searchKeyword", [""])[0]
        quoted = urllib.parse.quote(word.encode("utf-8"), safe="")
        if self.plus_for_space:
            quoted = quoted.replace("%20", "+")
        src = self.frame_template().replace("{title}", quoted).replace("&", "&amp;")
        frame = f'<iframe id="resultFrame" src="{src}"></iframe>' if self.iframe else \
            '<iframe id="resultFrame"></iframe><script>loadResult();</script>'
        # 검색 화면에는 결과가 없습니다. 머리와 메뉴, 그리고 프레임뿐입니다.
        return 200, f"<html><body><h1>도서관</h1><iframe src='/banner.html'></iframe>{frame}</body></html>"

    def _frame_page(self, path):
        if path == "/robots.txt":
            return 200, self.frame_robots
        parsed = urllib.parse.urlparse(path)
        query = urllib.parse.parse_qs(parsed.query)
        if query.get("act") == ["dataView"]:
            key = query.get("key", [""])[0]
            isbn = {"11": "9788937473135", "12": "9788937473999", "21": "9788996991342", "22": "9791186560136"}.get(key)
            return (200, f"<h2>상세</h2><p>ISBN {isbn}</p>") if isbn else (404, "없음")
        word = query.get("word", [""])[0]
        rows = {
            "82년생 김지영": [("12", "82년생 김지영 (큰글자책)", "9788937473999"), ("11", "82년생 김지영", "9788937473135")],
            "미움받을 용기": [("22", "미움받을 용기 2", "9791186560136"), ("21", "미움받을 용기", "9788996991342")],
        }.get(word, [])
        items = "".join(
            f'<li><a href="index.php?mod=wdDataSearch&amp;act=dataView&amp;key={key}&amp;manageCode=MA">{title}</a>'
            f' <span>ISBN {isbn}</span></li>' for key, title, isbn in rows)
        return 200, f"<ul>{items}</ul>" if rows else "<p>검색 결과가 없습니다</p>"


def probe_framed(site: FakeFramedOpac, frame=None):
    lib = {"libCode": "143013", "name": "가짜교육도서관", "homepageUrl": f"http://127.0.0.1:{site.outer_port}/"}
    members = [(lib, site.search_template(), "UTF-8")]
    tp._ROBOTS.clear()
    return tp.probe_host("127.0.0.1", members, {"143013": list(BOOKS)}, tp.od.Pacer(0), 5, frame)


class CookieSite:
    """쿠키가 있어야 화면을 주는 가짜 사이트를 이 기계에 띄웁니다. 받은 요청을 한 줄씩 적어 둡니다.

    `/frame` 은 쿠키 없이 받으면 `<script>location.reload();</script>` 와 `Set-Cookie` 를 주고(대전 서구가 준
    모양입니다), 그 쿠키를 실은 요청에 목록을 줍니다. `/list.do` 는 POST 로 받은 본문을 되돌려 적고,
    `/redirect` 는 다른 주소로 넘겨 줍니다. **HTML 은 지어낸 것입니다.**
    """

    def __init__(self):
        site = self
        self.seen: list[str] = []

        class Handler(http.server.BaseHTTPRequestHandler):
            def _answer(self):
                length = int(self.headers.get("Content-Length") or 0)
                body = self.rfile.read(length).decode("utf-8") if length else ""
                site.seen.append(f"{self.command} {self.path} | type={self.headers.get('Content-Type')} | "
                                 f"accept={self.headers.get('Accept')} | cookie={self.headers.get('Cookie')} | body={body}")
                path = self.path.split("?")[0]
                if path == "/redirect":
                    self.send_response(302)
                    self.send_header("Set-Cookie", "moved=1; Path=/")
                    self.send_header("Location", "/elsewhere")
                    self.end_headers()
                    return
                carried = "PHPSESSID=local1" in (self.headers.get("Cookie") or "").split("; ")
                if path == "/frame" and not carried:
                    page = "<script>location.reload();</script>"
                elif path == "/frame":
                    page = '<ol><li><a href="/detail?c=1">책</a> ISBN 9788937473135</li></ol>'
                else:
                    page = f"<p>{path} {body}</p>"
                raw = page.encode("utf-8")
                self.send_response(200)
                if path == "/frame" and not carried:
                    self.send_header("Set-Cookie", "PHPSESSID=local1; path=/")
                    self.send_header("Set-Cookie", "skin=default")
                self.send_header("Content-Type", "text/html; charset=UTF-8")
                self.send_header("Content-Length", str(len(raw)))
                self.end_headers()
                self.wfile.write(raw)

            do_GET = do_POST = _answer

            def log_message(self, *args):
                pass

        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def url(self, path: str) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}{path}"

    def close(self):
        self.server.shutdown()


def real_fetch_cases(check) -> None:
    """도구의 실제 받기(`od.fetch_page`)가 서버의 실제 받기와 같은 요청을 내는지. 서버 쪽은 `FrameFetchTest`."""
    print("\n실제 받기: POST 본문과 쿠키 (이 기계에 띄운 가짜 사이트)")
    site = CookieSite()
    pacer = tp.od.Pacer(0)
    try:
        status, page, _, cookies = tp.od.fetch_page(site.url("/list.do"), pacer, 5, "POST",
                                                    "manageCode=AG&searchIsbnIssn=9788937473135")
        check("POST 는 폼 본문과 HTML 을 받겠다는 머리로 나가고 쿠키는 싣지 않는다",
              (status, page, cookies, site.seen),
              (200, "<p>/list.do manageCode=AG&searchIsbnIssn=9788937473135</p>", [],
               ["POST /list.do | type=application/x-www-form-urlencoded | accept=text/html,application/xhtml+xml | "
                "cookie=None | body=manageCode=AG&searchIsbnIssn=9788937473135"]))

        site.seen.clear()

        def send(url, method, body, cookie, session):
            return tp.od.fetch_page(url, pacer, 5, method, body, cookie, session)

        press = tp.Press()
        origin = tp.origin_of(site.url("/frame"))
        page, final, why = tp.fetch_carrying(send, site.url("/frame"), "GET", None, origin, press)
        check("쿠키 없이 받은 화면이 스크립트뿐이면 응답 머리의 쿠키를 들고 한 번 다시 받는다",
              (page, why, [line.split(" | ")[3] for line in site.seen], press.refetches_left),
              ('<ol><li><a href="/detail?c=1">책</a> ISBN 9788937473135</li></ol>', "",
               ["cookie=None", "cookie=PHPSESSID=local1; skin=default"], 0))

        site.seen.clear()
        page, _, why = tp.fetch_carrying(send, site.url("/frame"), "GET", None, None, tp.Press())
        check("쿠키를 드는 규칙이 아니면 한 번만 받고 응답의 쿠키를 읽지 않는다",
              (tp.script_only(page), [line.split(" | ")[3] for line in site.seen]), (True, ["cookie=None"]))

        site.seen.clear()
        status, _, _, cookies = tp.od.fetch_page(site.url("/redirect"), pacer, 5, "GET", None, "PHPSESSID=local1", True)
        check("쿠키를 드는 요청은 넘겨 주는 응답(3xx)을 따라가지 않는다",
              (status, [line.split(" | ")[0] for line in site.seen]), (302, ["GET /redirect"]))

        site.seen.clear()
        status, page, _, cookies = tp.od.fetch_page(site.url("/redirect"), pacer, 5)
        check("나머지 요청은 예전처럼 따라가고, 쿠키를 싣지도 읽지도 않는다",
              (status, page, cookies, [line.split(" | ")[0] + " " + line.split(" | ")[3] for line in site.seen]),
              (200, "<p>/elsewhere </p>", [], ["GET /redirect cookie=None", "GET /elsewhere cookie=None"]))
    finally:
        site.close()


def main() -> int:
    failures = []

    def check(name, got, want):
        ok = got == want
        print(f"  {'✓' if ok else '✗'} {name}" + ("" if ok else f"   받은 것: {got!r}  바란 것: {want!r}"))
        if not ok:
            failures.append(name)

    print("고르기 (서버의 IsbnAnchorTest 와 같은 목록)")
    check("링크가 앞: 둘째 줄", tp.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9791186560136", True)[0], "/detail/2")
    check("링크가 앞: 셋째 줄", tp.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788901222127", True)[0], "/detail/3")
    check("링크가 앞: 첫 줄", tp.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788996991342", True)[0], "/detail/1")
    check("링크가 뒤: 둘째 줄", tp.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9791186560136", False)[0], "/detail/2")
    check("링크가 뒤: 셋째 줄", tp.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9788901222127", False)[0], "/detail/3")
    check("방향을 거꾸로 적으면 윗줄 책 (그래서 상세의 ISBN 으로 확인합니다)",
          tp.pick(ISBN_THEN_LINK, links(ISBN_THEN_LINK), "9791186560136", True)[0], "/detail/1")
    check("표지 주소의 ISBN 이 먼저 나와도 그 줄의 링크",
          tp.pick(COVER_FIRST, links(COVER_FIRST), "9791186560136", True)[0], "/detail/2")
    check("그 줄에 링크가 없으면 옆 줄 것을 빌리지 않음",
          tp.pick(NO_LINK_ROW, links(NO_LINK_ROW), "9791186560136", True)[0], None)
    check("결과에 그 ISBN 이 없으면 고르지 않음",
          tp.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "9788937473135", True)[0], None)
    longer = '<li><a href="/detail/1">책</a><span>바코드 19788996991342000</span></li>'
    check("더 긴 숫자의 일부는 ISBN 이 아님", tp.pick(longer, links(longer), "9788996991342", True)[0], None)
    check("ISBN-13 이 아닌 값", tp.pick(LINK_THEN_ISBN, links(LINK_THEN_ISBN), "미움받을 용기", True)[0], None)
    carried = ("<li><a onclick=\"fnDetail('11','9788996991342','MO')\">가</a></li>"
               "<li><a onclick=\"fnDetail('22','9791186560136','MO')\">나</a></li>")
    for link_first in (True, False):
        got = tp.pick(carried, tp.calls(carried, "fnDetail"), "9791186560136", link_first)[0]
        check(f"링크 안에 ISBN 이 있으면 방향과 무관 ({'앞' if link_first else '뒤'})", got and got[0], "22")

    shared_cases(check)
    real_fetch_cases(check)

    print("\n받아 본 후보 가운데 고르기 (서버의 IsbnVerify.pick)")
    ours, other = "<p>ISBN 9788937473135</p>", "<p>ISBN 9788996991342</p>"
    check("그 ISBN 이 적힌 후보", tp.verify_pick([("a", other), ("b", ours)], ["9788937473135"])[0], "b")
    check("앞의 ISBN(그 도서관이 가진 판)이 먼저",
          tp.verify_pick([("a", other), ("b", ours)], ["9788937473135", "9788996991342"])[0], "b")
    check("어느 후보에도 없으면 첫 후보로 내려가지 않는다",
          tp.verify_pick([("a", other), ("b", other)], ["9788937473135"])[0], None)
    check("받지 못한 후보는 없다고 치지도 있다고 치지도 않는다",
          tp.verify_pick([("a", None), ("b", ours)], ["9788937473135"])[0], "b")
    check("확인할 ISBN 이 없으면 고르지 않는다", tp.verify_pick([("a", ours)], [])[0], None)
    check("같은 ISBN 이 적힌 후보가 여럿이면 표기 없는 줄이 먼저",
          tp.verify_pick([("a", ours, True), ("b", other, False), ("c", ours, False)], ["9788937473135"])[0], "c")
    check("전부 표기 있는 줄이면 앞선 후보",
          tp.verify_pick([("a", ours, True), ("b", ours, True)], ["9788937473135"])[0], "a")
    check("표기 없는 줄에 그 ISBN 이 없으면 표기 있는 줄이라도 그 책이다",
          tp.verify_pick([("a", ours, True), ("b", other, False)], ["9788937473135"])[0], "a")
    check("같은 내용의 자리는 처음 것만 남긴다",
          [s[2] for s in tp.distinct([(0, 1, "x"), (2, 3, "x"), (4, 5, "y"), (6, 7, "x")])], ["x", "y"])

    print("\n새 규칙의 문법 (서버의 DetailRule.parse 와 같은 조건)")
    for rule, fine in [
        ("@verify:@onclick:fnDetail", True),
        ('@verify:href="(/v/[^"]+)"', True),
        ('@plain:href="(/v/[^"]+)"', True),
        ("@plain:@onclick:viewDetail", True),
        ("@plain:@anchor-after:@onclick:fnSearchResultDetail", True),
        ('@frame:https://x.kr/l?isbn={isbn13} => @plain:@anchor:href="(/v/[^"]+)"', True),
        ('@frame:https://x.kr/l?q={title} => @verify:@onclick:fnDetail', True),
        ("@verify:@anchor:@onclick:fnDetail", False),      # 닻은 목록의 ISBN 으로 이미 한 줄을 골랐습니다
        ("@verify:@plain:@onclick:fnDetail", True),       # 같은 ISBN 이 적힌 후보 가운데 표기 없는 줄을 먼저
        ("@verify:@plain:@anchor:@onclick:fnDetail", False),
        ("@verify:@plain:@plain:@onclick:fnDetail", False),
        ("@plain:@verify:@onclick:fnDetail", False),
        ("@plain:@plain:@onclick:fnDetail", False),
        ("@anchor:@plain:@onclick:fnDetail", False),
        ("@anchor:@verify:@onclick:fnDetail", False),
        ("@verify:", False),
        ("@plain:", False),
    ]:
        check(f"{rule} 는 {'받는다' if fine else '받지 않는다'}", tp.od.pattern_problem(rule) is None, fine)
    check("ISBN 으로 줄을 고르는 규칙인가: @verify:", tp.picks_by_isbn("@verify:@onclick:f"), True)
    check("ISBN 으로 줄을 고르는 규칙인가: @plain:@anchor:", tp.picks_by_isbn('@plain:@anchor:href="(/v/[^"]+)"'), True)
    check("ISBN 으로 줄을 고르는 규칙인가: @plain:정규식 은 아니다", tp.picks_by_isbn('@plain:href="(/v/[^"]+)"'), False)
    check("ISBN 으로 줄을 고르는 규칙인가: @frame: 은 안쪽을 따른다",
          tp.picks_by_isbn("@frame:https://x.kr/l?q={title} => @verify:@onclick:f"), True)

    print("\n규칙 한 줄을 서버처럼 실행 (DetailResolverTest 와 같은 페이지)")
    url, how = tp.resolve(PLUS_TITLE_PAGE, BASE, "@anchor:@onclick:fnSearchResultDetail", "9788996991342", 5, {})
    check("그 ISBN 의 줄에서 폼 전송 주소를 만든다", url,
          "https://www.l4d.or.kr/info/menu/10142/program/30010/plusSearchResultDetail.do"
          "?searchType=SIMPLE&recKey=210791177&bookKey=212537932&publishFormCode=BO")
    url, how = tp.resolve(PLUS_TITLE_PAGE, BASE, "@anchor:@onclick:fnSearchResultDetail", "9788937473135", 5, {})
    check("결과에 없는 ISBN 은 첫 줄로 내려가지 않는다", url, None)
    after = ('<li><img data-isbn="9788937460449"><a href="/book/view?key=100&amp;m=1">민음사</a></li>'
             '<li><img data-isbn="9788954615860"><a href="/book/view?key=200&amp;m=1">문학동네</a></li>')
    url, how = tp.resolve(after, "https://lib.example.kr/search?title=x",
                          '@anchor-after:href="(/book/view\\?key=[^"]+)"', "9788954615860", 5, {})
    check("@anchor-after: 정규식", url, "https://lib.example.kr/book/view?key=200&m=1")
    check("닻 규칙이 아니면 실행하지 않는다",
          tp.resolve(PLUS_TITLE_PAGE, BASE, "@onclick:fnSearchResultDetail", "9788996991342", 5, {})[0], None)

    print("\n후보 만들기")
    pages = {"9788996991342": PLUS_TITLE_PAGE, "9791186560136": PLUS_TITLE_PAGE}
    check("onclick 함수 후보", tp.candidates(pages, True)[:1], ["@anchor:@onclick:fnSearchResultDetail"])
    href_pages = {"9788996991342": LINK_THEN_ISBN, "9791186560136": LINK_THEN_ISBN}
    check("href 정규식 후보", tp.candidates(href_pages, True), ['@anchor:href=["\'](/detail/[^"\']*)["\']'])

    print("\n열쇠에 붙일 분관 칸")
    check("분관 부호가 붙은 주소가 있으면 그 칸 이름",
          tp.branch_param(["https://lib.paju.go.kr/gclib/list.do?searchLibrary=ALL&q={title}",
                           "https://lib.paju.go.kr/grlib/list.do?searchLibraryArr=MM&q={title}"]), "searchLibraryArr")
    check("전부 통합 검색이면 없음 (그런 호스트에는 규칙을 넣지 않습니다)",
          tp.branch_param(["https://www.bppl.or.kr/galsan/list.do?searchLibrary=ALL&searchWord={title}",
                           "https://mplib.mapo.go.kr/libsmall/list.do?searchLibraryArr=&searchKeyword={title}"]), None)
    check("manage_code", tp.branch_param(["https://lib.sejong.go.kr/goun/s.do?manage_code=MJ&search_txt={title}"]), "manage_code")
    # 울진의 실제 검색 주소입니다. 이 칸을 모르던 때에는 도구가 호스트만 적은 열쇠를 냈습니다(2026-11-17).
    check("sh_MGC (울진)",
          tp.branch_param(["https://lib.uljin.go.kr/content/01search/01_01.php?TAG1_cmd=IAL&TAG1_keyword={title}&sh_MGC=SD&x=0&y=0"]),
          "sh_MGC")

    print("\n프레임 규칙 (@frame:) — 서버의 FrameRuleTest 와 같은 값")
    frame_tpl = ("https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=searchIList"
                 "&word={title}&searchType=&manageCode=MA&siteId=SITE_000000000000003")
    inner = '@anchor:href="(index\\.php\\?mod=wdDataSearch&amp;act=dataView[^"]*)"'
    check("한 줄로 적은 모양이 서버의 describe 와 같다",
          tp.frame_rule("UTF-8", frame_tpl, inner), f"@frame:{frame_tpl} => {inner}")
    check("UTF-8 이 아니면 인코딩을 앞에 적는다",
          tp.frame_rule("EUC-KR", frame_tpl, inner), f"@frame:EUC-KR {frame_tpl} => {inner}")
    check("도구가 낸 줄을 다시 읽을 수 있다",
          tp.od.split_frame(tp.frame_rule("EUC-KR", frame_tpl, inner)), ("EUC-KR", frame_tpl, inner))
    check("주소 채우기: 공백은 %20 (UTF-8)",
          tp.od.fill_frame("https://x.kr:7443/a?word={title}&m=MA", "UTF-8", "마의 산"),
          "https://x.kr:7443/a?word=%EB%A7%88%EC%9D%98%20%EC%82%B0&m=MA")
    check("주소 채우기: EUC-KR",
          tp.od.fill_frame("https://x.kr:7443/a?word={title}&m=MA", "EUC-KR", "마의 산"),
          "https://x.kr:7443/a?word=%B8%B6%C0%C7%20%BB%EA&m=MA")
    # 자바의 URLEncoder 와 파이썬의 quote 는 그대로 두는 글자가 다릅니다(「~」). 서버가 내는 글자를 적어 둡니다.
    check("주소 채우기: 기호까지 서버와 같은 글자",
          tp.od.fill_frame("{title}", "UTF-8", "C++ 입문 (개정판) a_b-c.d*e~f"),
          "C%2B%2B%20%EC%9E%85%EB%AC%B8%20%28%EA%B0%9C%EC%A0%95%ED%8C%90%29%20a_b-c.d*e%7Ef")
    two_editions = ('<ul class="result">'
                    '<li><a href="index.php?mod=wdDataSearch&amp;act=dataView&amp;key=111&amp;manageCode=MA">이방인 (큰글자책)</a>'
                    '<span class="isbn">ISBN 9788937437021</span></li>'
                    '<li><a href="index.php?mod=wdDataSearch&amp;act=dataView&amp;key=222&amp;manageCode=MA">이방인</a>'
                    '<span class="isbn">ISBN 9788937462665</span></li></ul>')
    url, how = tp.resolve(two_editions, tp.od.fill_frame(frame_tpl, "UTF-8", "이방인"), inner, "9788937462665", 5, {})
    check("프레임의 목록에서 그 ISBN 의 줄을 고르고, 상대 링크를 프레임 주소 기준으로 푼다", url,
          "https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=dataView&key=222&manageCode=MA")

    print("\n검색 화면에서 프레임 주소 읽기")
    outer = "https://www.cbelib.go.kr/srch/smart2.do?cmd=SEARCH&searchKeyword=x"
    page = ('<iframe src="/banner.html"></iframe>'
            '<iframe src="https://www.cbelib.go.kr:7443/dls_le/index.php?act=searchIList&amp;word=%EB%A7%88%EC%9D%98%20%EC%82%B0&amp;manageCode=MA"></iframe>')
    check("검색어가 든 프레임을 고르고 그 자리를 {title} 로 바꾼다", tp.frame_template(page, outer, "마의 산"),
          ("UTF-8", "https://www.cbelib.go.kr:7443/dls_le/index.php?act=searchIList&word={title}&manageCode=MA"))
    check("공백을 + 로 적은 주소도 읽는다",
          tp.frame_template(page.replace("%20", "+"), outer, "마의 산"),
          ("UTF-8", "https://www.cbelib.go.kr:7443/dls_le/index.php?act=searchIList&word={title}&manageCode=MA"))
    check("EUC-KR 로 적힌 검색어면 인코딩을 그렇게 적는다",
          tp.frame_template('<iframe src="//www.cbelib.go.kr:7443/l.php?word=%b8%b6%c0%c7%20%bb%ea"></iframe>', outer, "마의 산"),
          ("EUC-KR", "https://www.cbelib.go.kr:7443/l.php?word={title}"))
    check("상대 주소는 검색 화면 기준으로 푼다",
          tp.frame_template('<iframe src="list.do?word=%EB%8D%B0%EB%AF%B8%EC%95%88"></iframe>', outer, "데미안"),
          ("UTF-8", "https://www.cbelib.go.kr/srch/list.do?word={title}"))
    check("검색어가 든 프레임이 없으면 짐작하지 않는다",
          tp.frame_template('<iframe src="/banner.html"></iframe><iframe id="r"></iframe>', outer, "데미안"), None)

    print("\n가짜 사이트: 검색 화면과 프레임이 다른 포트")
    site = FakeFramedOpac()
    rule, why = probe_framed(site)
    want = (f"@frame:{site.frame_template()} => "
            "@anchor:href=[\"'](index\\.php\\?mod=wdDataSearch&amp;act=dataView&amp;key=[^\"']*)[\"']")
    check("프레임을 찾아 @frame: 규칙을 낸다", rule, want)
    check("낸 규칙을 서버가 받아 준다", tp.od.pattern_problem(rule or ""), None)
    check("검색 화면은 프레임 주소를 읽을 때 한 번만 받는다",
          [h for h in site.outer_hits if h.startswith("/srch/")].__len__(), 1)
    check("프레임 쪽 robots.txt 를 따로 받는다", site.frame_hits.count("/robots.txt"), 1)
    site.close()

    site = FakeFramedOpac(plus_for_space=True)
    rule, why = probe_framed(site)
    check("검색 화면이 공백을 + 로 적어도 같은 규칙", rule, (
        f"@frame:{site.frame_template()} => "
        "@anchor:href=[\"'](index\\.php\\?mod=wdDataSearch&amp;act=dataView&amp;key=[^\"']*)[\"']"))
    site.close()

    site = FakeFramedOpac(frame_robots="User-agent: *\nDisallow: /dls_le/\n")
    rule, why = probe_framed(site)
    check("프레임의 robots.txt 가 막으면 규칙을 내지 않는다", (rule, "robots.txt" in why), (None, True))
    check("막힌 프레임 경로로 요청이 한 건도 나가지 않는다",
          [h for h in site.frame_hits if h.startswith("/dls_le/")], [])
    site.close()

    site = FakeFramedOpac(iframe=False)
    rule, why = probe_framed(site)
    check("프레임 주소가 HTML 에 없으면 내지 않고 --frame 을 안내한다", (rule, "--frame" in why), (None, True))
    check("주소를 짐작해 프레임 쪽을 두드리지 않는다", site.frame_hits, [])
    rule, why = probe_framed(site, ("UTF-8", site.frame_template()))
    check("사람이 알려 준 프레임 주소로 확인한다", rule, (
        f"@frame:{site.frame_template()} => "
        "@anchor:href=[\"'](index\\.php\\?mod=wdDataSearch&amp;act=dataView&amp;key=[^\"']*)[\"']"))
    check("그때 검색 화면은 받지 않는다 (서버도 받지 않습니다)",
          [h for h in site.outer_hits if h.startswith("/srch/")].__len__(), 2)     # 위의 자동 탐색 두 번뿐입니다
    site.close()

    print()
    if failures:
        print(f"실패 {len(failures)}건: {', '.join(failures)}")
        return 1
    print("전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

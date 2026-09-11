#!/usr/bin/env python3
"""브라우저를 띄워 OPAC 검색 주소를 알아내고, 실제 응답으로 검증합니다.

**`opac-discover.py` 가 원리적으로 못 하던 것을 하기 위한 것입니다.** 그쪽은 HTML 의 검색
폼을 읽어서 주소를 만드는데, 국내 OPAC 상당수는 **자바스크립트가 주소를 조립**합니다.
노원의 `/KeywordSearchResult/{isbn13}` 이나 부천의 `/search/keyword/{isbn13}` 은 HTML
어디에도 그 형태가 적혀 있지 않아, 폼을 아무리 잘 읽어도 나오지 않습니다. 2026-09-09 의
자동 조사가 **0/30** 이었던 가장 큰 이유입니다.

브라우저는 그것을 **주소창에서 그냥 읽습니다.** 사람이 5초면 아는 것과 같은 방법입니다.

## 반드시 국내에서 돌리세요

두 번째 이유는 **해외 IP 차단**이었습니다. 국내 공공도서관 상당수가 막고 있어서 러너에서는
30묶음 가운데 18묶음이 접속조차 되지 않았습니다. 브라우저를 써도 이건 안 풀립니다.
**이 스크립트는 국내 회선에서 돌아야 뜻이 있습니다.**

## 판정은 그대로 세 겹입니다

찾아내는 것보다 **걸러 내는 것이 중요합니다.** 틀린 규칙은 HTTP 200 을 주면서 결과만 0건이
되어 「소장한다더니 그 책이 없네」로 보이고, 깨진 링크와 달리 눈에 띄지 않습니다.

  1. 양성   그 도서관이 **실제로 소장한** 책의 ISBN 으로 그 책이 나오는지
  2. 음성   **존재하지 않는 ISBN** 으로 그 책이 나오지 않는지. 검색어를 무시하고 늘 목록을
            뿌리는 OPAC 을 여기서 잡습니다. **이 단계를 빼지 마세요.**
  3. 재확인 그 도서관이 소장한 **다른** 책으로 한 번 더

그리고 **2·3 은 쿠키가 없는 새 창에서 엽니다.** 주소창에 검색어가 남는데도 새 창에서 열면
무시하는 OPAC 이 실제로 있습니다(제주·구미·용산). 검색어를 세션에 담아 두는 방식이라,
같은 창에서 확인하면 통과하지만 **사용자에게는 죽은 링크가 나갑니다.**

## 주소에 검색어가 안 남거나 책이 안 보일 때

**같은 창부터 봅니다.** 같은 창에서도 책이 안 나오면 새 창은 열지 않고 그렇게 적습니다.
예전에는 새 창부터 봐서 그런 곳이 전부 「새 창에서 열면 그 책이 나오지 않습니다」로 찍혀 세션
문제로 읽혔는데, 2026-09-11 에 따로 확인해 보니 세션 문제로 확인된 곳은 한 곳도 없었습니다.

주소창에 검색어가 없으면 **검색이 무엇으로 나갔는지** 봅니다. 넘어가기 전 GET 주소가 있으면
그것을, POST 폼이면 같은 파라미터를 붙인 GET 주소를 후보로 삼아 같은 세 겹으로 확인합니다.
그날 POST 로 책이 나온 여덟 묶음이 전부 이렇게 열렸습니다. 토큰 칸(`_csrf` 등)은 어느 길로
얻은 주소에서든 뺍니다. 값이 남은 주소는 오늘 되고 내일 안 됩니다.

검색 항목 선택지에 ISBN 이 있으면 그것을 고릅니다. 전체 검색이 ISBN 을 색인하지 않는 OPAC 이
있고(당진·구미 모양), 선택지는 사이트가 준 값이라 짐작이 아닙니다.

조사 도중 **이 기계의 네트워크가 끊기면** 그 묶음을 기록하지 않고, 우리 서버에 다시 닿을 때까지
기다린 뒤 한 번 더 봅니다. 기록해 버리면 다시 돌려도 건너뛰어 그 묶음을 영영 보지 못합니다.

## 상세 페이지까지

검색 규칙을 찾으면 상세도 함께 시도합니다. 검색 결과에서 책을 눌러 들어간 주소에 ISBN 이
그대로 있으면 `ISBN_DETAIL` 자리표 규칙이 되고, **대부분처럼 내부 키를 쓰면 자리표 대신
「검색 결과에서 상세 링크를 뽑는 패턴」(`DETAIL_PATTERN`)을 만듭니다.** 서버가 누를 때
ISBN 검색 결과를 받아 그 패턴으로 링크를 꺼내 상세로 보냅니다(`DetailResolver`).

패턴은 **서버와 같은 방식으로 받은 HTML** 에 대고 검증합니다. 브라우저에는 자바스크립트가
그린 링크까지 보이지만 서버에는 없기 때문입니다. 첫 번째로 잡히는 링크가 그 책이어야 하고,
없는 ISBN 에는 아무것도 잡히지 않아야 하며, 뽑은 링크가 쿠키 없는 새 창에서 열려야 합니다.

`--patterns` 는 **이미 검색 규칙이 있는 도서관**(templates.csv)에 대해 이 상세 단계만
돌립니다. 사람이 채운 규칙 217줄이 13개 시스템이라, 패턴 열몇 줄이면 전부 상세로 갑니다.

## 쓰는 법

    ./scripts/opac-discover.py --probe          # 검증용 소장 데이터 (우리 API 를 씁니다)
    ./scripts/opac-browse.py --patterns         # 규칙 있는 도서관의 상세 패턴부터
    ./scripts/opac-browse.py --limit 30         # 큰 묶음부터 30개 (검색 규칙 + 상세)
    ./scripts/opac-browse.py --gaps             # 규칙 있는 기관에서 빠진 도서관만 확인
    ./scripts/opac-browse.py --emit > findings.txt
    ./scripts/opac-discover.py --import-file findings.txt >> \\
        backend/src/main/resources/opac/templates.csv
    ./scripts/opac-discover.py --import-patterns findings.txt >> \\
        backend/src/main/resources/opac/detail-patterns.csv

**`--emit` 은 `--import-file`/`--import-patterns` 가 읽는 형식으로 냅니다.** 거기서
자리표·인코딩·다른 기관 도메인·정규식 조건을 한 번 더 거르므로 그 통로를 우회하지 마세요.

## 외부 서버에 대한 예의

같은 호스트에는 한 번에 하나씩, 요청 사이에 간격을 둡니다(`--host-delay`, 기본 3초).
robots.txt 를 받아 거부된 경로는 건드리지 않습니다. 한 묶음이 그 서버에 보내는 요청은
검색 한 번과 확인 두 번, 많아야 대여섯 번입니다. **이 값을 올리기 전에 CLAUDE.md 의
「외부 서버에 대한 예의」를 읽으세요.**
"""

from __future__ import annotations

import argparse
import csv
import importlib.util
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("opac_discover", os.path.join(HERE, "opac-discover.py"))
od = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(od)

# 이미 있는 크로미움을 씁니다. 새로 내려받게 두면 국내 회선에서 몇 분씩 걸리고, 관리자
# 권한이 없는 기계에서는 아예 실패합니다.
CHROMIUM_CANDIDATES = [
    os.environ.get("WIMB_CHROMIUM"),
    "/opt/pw-browsers/chromium-1194/chrome-linux/chrome",
]

# 검색창으로 볼 만한 입력 칸. 로그인 칸을 건드리면 엉뚱한 화면으로 갑니다.
BOX_HINTS = ("search", "keyword", "kwd", "query", "검색", "자료", "schword", "text1", "sw")
BOX_AVOID = ("id", "pw", "pass", "login", "userid", "member", "아이디", "비밀")


def playwright():
    """없으면 무엇을 깔아야 하는지 말해 줍니다. ImportError 만 던지면 무엇이 빠졌는지
    바로 알기 어렵고, 이 스크립트를 처음 쓰는 사람이 가장 먼저 만나는 자리입니다."""
    try:
        from playwright.sync_api import sync_playwright
    except ImportError:
        print("playwright 가 필요합니다:\n"
              "    pip install playwright && playwright install chromium\n"
              "이미 크로미움이 있으면 WIMB_CHROMIUM 에 실행 파일 경로를 넣으세요.",
              file=sys.stderr)
        raise SystemExit(1)
    return sync_playwright


def chromium(p):
    """설치된 크로미움으로 띄웁니다. 못 찾으면 playwright 기본값에 맡깁니다."""
    for path in CHROMIUM_CANDIDATES:
        if path and os.path.exists(path):
            return p.chromium.launch(executable_path=path)
    return p.chromium.launch()


def looks_like_box(name: str, ident: str, placeholder: str, aria: str) -> bool:
    blob = " ".join(x.lower() for x in (name, ident, placeholder, aria) if x)
    if any(bad in blob for bad in BOX_AVOID):
        return False
    return any(hint in blob for hint in BOX_HINTS)


def search_boxes(page) -> list:
    """검색창일 법한 입력 칸을 그럴듯한 순서로. 힌트에 걸린 것을 먼저 봅니다."""
    hinted, plain = [], []
    for box in page.locator("input[type=text], input[type=search], input:not([type])").all():
        try:
            if not box.is_visible():
                continue
            attrs = [box.get_attribute(k) or "" for k in ("name", "id", "placeholder", "aria-label")]
        except Exception:
            continue
        (hinted if looks_like_box(*attrs) else plain).append(box)
    return hinted + plain[:2]


def submit(page, box, value: str, timeout: float) -> str:
    """검색어를 넣고 보낸 뒤 **주소창의 값**을 돌려줍니다. 이것이 이 스크립트의 전부입니다.

    자바스크립트가 조립하는 주소는 HTML 에 없고 주소창에만 나타납니다.
    """
    before = page.url
    try:
        box.fill(value)
        box.press("Enter")
    except Exception:
        return before
    # 새 창으로 결과를 여는 OPAC 이 있습니다. 열리면 그쪽 주소가 답입니다.
    try:
        page.wait_for_load_state("networkidle", timeout=timeout * 1000)
    except Exception:
        pass
    for other in page.context.pages:
        if other is not page and value in other.url:
            return other.url
    return page.url


def templatize(url: str, value: str) -> str | None:
    """주소에서 검색어 자리를 찾아 자리표로 바꿉니다. 못 찾으면 규칙이 될 수 없습니다."""
    for form in (value, urllib.parse.quote(value), urllib.parse.quote_plus(value)):
        if form and form in url:
            return url.replace(form, "{isbn13}")
    return None


def opens_fresh(browser, template: str, isbn: str, title: str, pacer, timeout: float):
    """**쿠키 없는 새 창**에서 열어 봅니다. (그 책이 나왔나, 없다고 했나) 를 돌려줍니다.

    같은 창에서 확인하면 검색어를 세션에 담아 두는 OPAC 이 통과해 버립니다. 그런 규칙은
    사용자에게 죽은 링크로 나갑니다.
    """
    url = template.replace("{isbn13}", isbn)
    context = browser.new_context()
    try:
        page = context.new_page()
        pacer.wait(urllib.parse.urlparse(url).netloc)
        page.goto(url, timeout=timeout * 1000, wait_until="domcontentloaded")
        try:
            page.wait_for_load_state("networkidle", timeout=timeout * 1000)
        except Exception:
            pass
        html = page.content()
        return od.page_has(html, title), od.says_no_result(html), html
    except Exception as e:
        # 오류 문구까지 싣습니다. 이 기계의 네트워크가 끊긴 것(net::ERR_INTERNET_DISCONNECTED)과
        # 그 도서관이 안 열린 것을 가려야 하기 때문입니다.
        return False, False, f"열지 못했습니다: {type(e).__name__}: {e}"
    finally:
        context.close()


def detail_rule(browser, template: str, books: list[str], pacer, timeout: float) -> str | None:
    """검색 결과에서 책을 눌러 들어간 주소에 **ISBN 이 그대로 있으면** 상세 규칙이 됩니다.

    **대부분은 안 됩니다.** 손으로 열어 본 세 곳이 전부 도서관 내부 키를 쓰고 있었습니다.
    노원은 앞에 키 목록이 스무 개씩 붙고(`/bookDetail/MO/629661,530431,.../{isbn13}`),
    김해는 `book_key=150671418`, 대구는 `regNo`·`bookkey` 입니다. 책마다 달라지는 값이라
    우리가 미리 알 방법이 없으므로 **자리표를 만들 수 없습니다.**

    그래도 확인은 기계가 합니다. 사람이 도서관마다 눌러 보는 것보다 싸고, 되는 곳이 하나라도
    있으면 그 도서관은 검색 결과를 한 번 더 거치지 않습니다.

    **찾았다고 그냥 쓰지 않습니다.** 다른 책의 ISBN 으로 바꿔 새 창에서 열어 그 책이 나오는지
    봅니다. 그러지 않으면 첫 책에만 우연히 맞는 주소가 규칙이 됩니다.
    """
    context = browser.new_context()
    try:
        page = context.new_page()
        url = template.replace("{isbn13}", books[0])
        pacer.wait(urllib.parse.urlparse(url).netloc)
        page.goto(url, timeout=timeout * 1000, wait_until="domcontentloaded")
        try:
            page.wait_for_load_state("networkidle", timeout=timeout * 1000)
        except Exception:
            pass

        want = od.squash(od.PROBE_BOOKS[books[0]])
        for link in page.locator("a").all()[:120]:
            try:
                if want not in od.squash(link.inner_text()):
                    continue
                link.click(timeout=timeout * 1000)
            except Exception:
                continue
            try:
                page.wait_for_load_state("networkidle", timeout=timeout * 1000)
            except Exception:
                pass
            here = next((o.url for o in context.pages if books[0] in o.url and o is not page),
                        page.url)
            detail = templatize(here, books[0])
            # 검색 결과 주소와 같으면 상세로 간 것이 아닙니다.
            if not detail or detail == template:
                return None
            # 다른 책으로 바꿔 새 창에서 열어 그 책이 나와야 하고, **처음 책의 제목이 섞이면 안
            # 됩니다.** 주소의 ISBN 을 무시하고 목록을 뿌리는 페이지면 둘 다 보이기 때문입니다.
            hit, _, page_html = opens_fresh(browser, detail, books[1], od.PROBE_BOOKS[books[1]],
                                            pacer, timeout)
            if not hit or od.page_has(page_html, od.PROBE_BOOKS[books[0]]):
                return None
            # 음성 — 없는 ISBN 의 상세에는 어느 책도 나오면 안 됩니다. 검색 규칙에서 음성 대조를
            # 빼지 않는 것과 같은 이유이고, 예전에는 이 확인이 없어 춘천 규칙을 손으로 확인했습니다.
            _, _, ghost_html = opens_fresh(browser, detail, od.ABSENT_ISBN, "", pacer, timeout)
            if any(od.page_has(ghost_html, od.PROBE_BOOKS[b]) for b in books[:2]):
                return None
            return detail
        return None
    except Exception:
        return None
    finally:
        context.close()


def detail_pattern(browser, template: str, books: list[str], pacer, timeout: float):
    """상세 주소가 내부 키라 자리표를 못 만들 때, **검색 결과에서 상세 링크를 뽑는 패턴**을
    만들어 검증합니다. (패턴 정보 또는 None, 사유) 를 돌려줍니다.

    서버(DetailResolver)가 누를 때 하는 일을 여기서 미리 해 봅니다. 그래서 **브라우저가 아니라
    서버와 같은 방식**(자바스크립트 없이, 같은 UA 의 fetch)으로 검색 결과를 받습니다.
    브라우저로 보면 자바스크립트가 그린 링크까지 보이는데 서버에는 없어서, 여기서 통과한
    패턴이 서버에서는 잡히지 않게 됩니다.

    세 겹은 그대로입니다.
      양성   두 책의 검색 결과에서 패턴이 **첫 번째로** 잡는 링크가 그 책의 링크여야 합니다.
             서버는 첫 번째 것을 쓰므로, 「최근 본 책」 같은 링크가 먼저 잡히면 엉뚱한 책으로
             갑니다.
      음성   없는 ISBN 의 검색 결과에서는 아무것도 잡히지 않아야 합니다.
      재확인 뽑은 링크를 **쿠키 없는 새 창**에서 열어 그 책이 나와야 합니다. 세션에 묶인
             내부 키라면 여기서 걸립니다.
    """
    hrefs, pages = [], {}
    for isbn in books[:2]:
        title = od.PROBE_BOOKS[isbn]
        status, page_html, final = od.fetch(template.replace("{isbn13}", isbn), pacer, timeout)
        if status != 200:
            # 못 받은 것과 받았는데 링크가 없는 것은 다른 사유입니다. 앞은 다시 돌리면 될 수
            # 있고(느린 서버, 순간 장애), 뒤는 자바스크립트가 그리는 시스템이라 다시 돌려도
            # 같습니다. 한 문구로 뭉뚱그리면 다시 돌려 볼 곳을 못 고릅니다.
            why = page_html[:60] if status == 0 else f"HTTP {status}"
            return None, f"검색 결과를 서버 방식으로 받지 못했습니다 ({why})"
        if not od.page_has(page_html, title):
            return None, "자바스크립트 없이 받으면 그 책이 보이지 않습니다 (서버도 못 봅니다)"
        href = od.title_link(page_html, title)
        if not href:
            return None, "검색 결과의 제목에 링크가 없습니다"
        hrefs.append(href)
        pages[isbn] = (page_html, final)
    if hrefs[0] == hrefs[1]:
        return None, "두 책의 상세 링크가 같습니다. 책마다 다른 주소가 아닙니다"

    regex = od.pattern_from_hrefs(hrefs)
    if not regex:
        return None, "두 상세 링크에 공통 앞머리가 없습니다"
    if od.pattern_problem(regex):
        return None, f"만든 패턴이 서버 조건에 맞지 않습니다 ({od.pattern_problem(regex)})"

    # ① 양성 — 첫 번째로 잡히는 링크가 그 책이어야 합니다
    for isbn in books[:2]:
        page_html, _ = pages[isbn]
        if od.first_href(regex, page_html) != od.title_link(page_html, od.PROBE_BOOKS[isbn]):
            return None, "패턴이 그 책보다 다른 링크를 먼저 잡습니다"

    # ② 음성 — 없는 ISBN 에는 아무것도 잡히지 않아야 합니다
    status, ghost, _ = od.fetch(template.replace("{isbn13}", od.ABSENT_ISBN), pacer, timeout)
    if status == 200 and od.first_href(regex, ghost):
        return None, "없는 ISBN 의 결과에서도 링크가 잡힙니다"

    # ③ 재확인 — 뽑은 링크를 새 창에서 열어 그 책이 나와야 합니다
    page_html, final = pages[books[1]]
    # html.unescape 가 아닙니다. &regNo= 를 ®No= 로 바꿔 순천의 상세 링크를 망가뜨렸습니다.
    detail_url = urllib.parse.urljoin(final, od.attr_unescape(od.first_href(regex, page_html)))
    if detail_url == template.replace("{isbn13}", books[1]):
        return None, "뽑은 링크가 검색 결과 자기 주소입니다"
    # 자리표가 없는 주소라 opens_fresh 는 그것을 그대로 엽니다.
    hit, _, detail_html = opens_fresh(browser, detail_url, books[1], od.PROBE_BOOKS[books[1]],
                                      pacer, timeout)
    if not hit:
        return None, "뽑은 상세 링크를 새 창에서 열면 그 책이 나오지 않습니다 (세션에 묶인 키)"
    # 그 책의 상세라면 다른 책 제목은 없어야 합니다. 링크의 열쇠를 무시하고 목록을 뿌리는
    # 페이지는 새 창에서도 그 책이 보여 위의 확인을 통과합니다.
    if od.page_has(detail_html, od.PROBE_BOOKS[books[0]]):
        return None, "뽑은 상세 링크에 다른 책 제목이 함께 나옵니다 (그 책의 상세가 아닐 수 있습니다)"

    host_key = urllib.parse.urlparse(template).netloc.lower()
    return {"kind": "DETAIL_PATTERN", "hostKey": host_key, "pattern": regex,
            "examples": hrefs}, ""


# 주소에 남으면 규칙이 오늘만 되는 칸. 어느 길로 얻은 주소든 뺍니다.
TOKEN_FIELD = re.compile(r"csrf|token|nonce|viewstate|eventvalidation", re.I)
# 검색어가 실렸어도 검색 자체는 아닌 요청(자동 완성, 방문 통계).
NOISE_REQUEST = re.compile(r"auto.?complete|suggest|collect\?|/weblog", re.I)
# 이 기계의 네트워크가 끊겨 난 오류. 그 도서관의 사유가 아닙니다.
LOCAL_NETWORK_ERRORS = ("ERR_INTERNET_DISCONNECTED", "ERR_NETWORK_CHANGED", "ERR_NETWORK_IO_SUSPENDED")

ISBN_OPTION_JS = """(el) => {
  let scope = el.form;
  if (!scope) { scope = el; for (let i = 0; i < 4 && scope.parentElement; i++) scope = scope.parentElement; }
  for (const sel of scope.querySelectorAll('select')) {
    for (const opt of sel.options) {
      if (/isbn/i.test(opt.textContent || '') || /^isbn$/i.test(opt.value || '')) {
        sel.value = opt.value;
        sel.dispatchEvent(new Event('change', {bubbles: true}));
        return (sel.name || sel.id || '') + '=' + opt.value;
      }
    }
  }
  return null;
}"""

SEARCH_BUTTON_JS = """(el) => {
  const want = /검색|search|조회|찾기/i;
  let node = el;
  for (let i = 0; i < 4 && node; i++) {
    node = node.parentElement;
    if (!node) break;
    for (const c of node.querySelectorAll('button, a, input[type=submit], input[type=image], input[type=button]')) {
      const t = [c.innerText, c.value, c.alt, c.title, c.getAttribute('aria-label'), c.className].join(' ');
      if (want.test(t) && c.offsetParent !== null && c !== el) return c;
    }
  }
  return null;
}"""


def network_failure(text: str | None) -> str | None:
    """이 기계의 네트워크가 끊겨 실패한 것이면 그 오류 이름. 그 도서관 탓이 아닙니다."""
    return next((e for e in LOCAL_NETWORK_ERRORS if e in (text or "")), None)


class SearchRequests:
    """검색어를 싣고 나간 요청을 모읍니다. 주소창에 검색어가 안 남을 때 무엇으로 나갔는지 봅니다.

    본문은 바이트를 latin-1 로 그대로 옮겨 듭니다. UTF-8 로 읽으면 EUC-KR 이나 압축된 본문에서
    예외가 나고, 그 요청을 놓치면 POST 검색을 「검색이 안 나갔다」로 잘못 적습니다.
    """

    def __init__(self, context, value: str, org: str):
        self.value, self.org, self.seen = value, org, []
        context.on("request", self._record)

    def _record(self, req) -> None:
        try:
            raw = req.post_data_buffer or b""
        except Exception:
            raw = b""
        body = raw.decode("latin-1")
        if self.value not in req.url and self.value not in body:
            return
        if od.registrable_domain(urllib.parse.urlparse(req.url).netloc) != self.org:
            return
        self.seen.append({"type": req.resource_type, "method": req.method, "url": req.url,
                          "body": body, "ctype": (req.headers or {}).get("content-type", "")})


def shows_anywhere(context, title: str) -> bool:
    """같은 창(새로 열린 창 포함)에 그 책이 보이는지."""
    for pg in context.pages:
        try:
            if od.page_has(pg.content(), title):
                return True
        except Exception:
            pass
    return False


def choose_isbn_option(page, box) -> str | None:
    """검색 항목 선택지에 ISBN 이 있으면 고르고 `이름=값` 을 돌려줍니다. 사이트가 준 값이라
    짐작이 아닙니다. 전체 검색이 ISBN 을 색인하지 않는 OPAC 이 있습니다(당진·구미 모양)."""
    try:
        return page.evaluate(ISBN_OPTION_JS, box.element_handle())
    except Exception:
        return None


def press_search_button(page, box, value: str, timeout: float) -> bool:
    """엔터로는 안 나가는 검색창. 사람이 하듯 옆의 검색 단추를 누릅니다."""
    try:
        box.fill(value)
        button = page.evaluate_handle(SEARCH_BUTTON_JS, box.element_handle()).as_element()
        if not button:
            return False
        button.click(timeout=timeout * 1000)
    except Exception:
        return False
    try:
        page.wait_for_load_state("networkidle", timeout=timeout * 1000)
    except Exception:
        pass
    return True


def strip_tokens(url: str) -> str:
    """질의 문자열에서 토큰 칸을 뺍니다. 값이 남은 주소는 오늘 되고 내일 안 됩니다(대구의 `_csrf`).
    `#` 뒤는 건드리지 않습니다. 구로처럼 자바스크립트가 읽는 자리입니다."""
    base, hashmark, fragment = url.partition("#")
    path, question, query = base.partition("?")
    if not question:
        return url
    kept = [p for p in query.split("&") if p and not TOKEN_FIELD.search(p.split("=", 1)[0])]
    return path + ("?" + "&".join(kept) if kept else "") + hashmark + fragment


def post_as_get(req: dict, value: str) -> str | None:
    """POST 검색 요청을 같은 파라미터의 GET 주소로 바꿉니다. 폼 본문이 아니면 None 입니다.

    2026-09-11 에 POST 로 책이 나온 여덟 묶음이 전부 이렇게 열렸습니다. 본문은 이미 퍼센트
    인코딩된 글자라 풀었다 다시 싸지 않고 그대로 이어 붙입니다. 풀면 EUC-KR OPAC 의 한글 값이
    깨집니다. 토큰 칸은 search_candidates 가 strip_tokens 로 뺍니다.
    """
    ctype = (req.get("ctype") or "").lower()
    if ctype and "application/x-www-form-urlencoded" not in ctype:
        return None
    body = req["body"].strip("&")
    if value not in body:
        return None
    base = req["url"].partition("#")[0]
    return (base + ("&" if "?" in base else "?") + body).replace(value, "{isbn13}")


def search_candidates(url: str, seen: list[dict], value: str) -> list[tuple[str, str]]:
    """검색 규칙 후보와 얻은 길. 주소창, 넘어가기 전 GET 주소, POST 를 GET 으로 바꾼 주소 순입니다."""
    out: list[tuple[str, str]] = []

    def add(template: str | None, via: str) -> None:
        if not template:
            return
        template = strip_tokens(template)
        if "{isbn13}" in template and all(template != t for t, _ in out):
            out.append((template, via))

    add(templatize(url, value), "주소창")
    for req in seen:
        if req["type"] != "document":
            continue
        if req["method"] == "GET":
            add(templatize(req["url"], value), "넘어가기 전 주소")
        elif req["method"] == "POST":
            add(post_as_get(req, value), "POST 를 GET 으로")
    return out


def no_candidate_note(seen: list[dict]) -> str:
    if any(r["type"] in ("xhr", "fetch") and not NOISE_REQUEST.search(r["url"]) for r in seen):
        return "주소에 검색어가 남지 않습니다 (자바스크립트가 결과를 받아 그립니다)"
    if not seen:
        return "검색이 나가지 않았습니다 (엔터와 검색 단추로 보내지 못했습니다)"
    return "주소에 검색어가 남지 않습니다"


FRESH_MISS_NOTE = {
    "주소창": "새 창에서 열면 그 책이 나오지 않습니다 (같은 창에서는 나옴: 세션에 묶임)",
    "넘어가기 전 주소": "넘어가기 전 주소를 새 창에서 열면 그 책이 나오지 않습니다",
    "POST 를 GET 으로": "POST 검색을 같은 파라미터의 GET 으로 보내면 그 책이 나오지 않습니다",
}
# 칸을 여럿 시도해 사유가 여럿이면 가장 많이 말해 주는 것을 남깁니다.
NOTE_PRIORITY = ("세션에 묶임", "GET 으로 보내면", "넘어가기 전 주소", "robots.txt", "같은 창에서도",
                 "자바스크립트가 결과를", "주소에 검색어가 남지 않습니다", "검색이 나가지 않았습니다")


def best_note(notes: list[str]) -> str:
    for key in NOTE_PRIORITY:
        for note in notes:
            if key in note:
                return note
    return notes[-1] if notes else "주소에 검색어가 남지 않습니다"


def investigate(browser, key: str, members: list[dict], rep: dict, books: list[str],
                pacer, timeout: float) -> dict:
    """묶음 하나. 예외가 새어 나가면 수백 묶음짜리 조사가 통째로 죽습니다."""
    out = {"key": key, "libCodes": [c["libCode"] for c in members],
           "rep": rep["name"], "rules": [], "note": ""}
    home = od.normalize_home(rep.get("homepageUrl"))
    if not home:
        out["note"] = "쓸 수 있는 홈페이지 주소가 아닙니다"
        return out
    if len(books) < 2:
        out["note"] = "검증용으로 쓸 소장 도서가 모자랍니다"
        return out

    host = urllib.parse.urlparse(home).netloc
    disallows = od.robots_disallows(f"{urllib.parse.urlparse(home).scheme}://{host}/", pacer)
    if od.blocked(home, disallows):
        # 프로그램이 도는 조사라 **홈페이지부터** 지킵니다. 예전에는 홈페이지를 열고 검색까지 해
        # 본 뒤에야 결과 주소가 막힌 것을 알았습니다(이천·동작·김해·연수·거제). 그런 곳은 사람이
        # 확인해 넣는 길이 따로 있습니다(docs/도서관-주소-규칙-채우기.md).
        out["note"] = "robots.txt 가 막은 경로입니다 (홈페이지)"
        return out

    context = browser.new_context()
    try:
        page = context.new_page()
        pacer.wait(host)
        page.goto(home, timeout=timeout * 1000, wait_until="domcontentloaded")
        # 자바스크립트 앱(강서·시흥)은 첫 HTML 에 검색창이 없고 그린 뒤에야 생깁니다. 조용해질
        # 때까지 기다린 뒤에 봅니다. 기다리다 넘겨도 그때까지 그린 것으로 봅니다.
        try:
            page.wait_for_load_state("networkidle", timeout=timeout * 1000)
        except Exception:
            pass
        boxes = search_boxes(page)
        if not boxes:
            # 첫 화면에 검색창이 없는 홈페이지가 있습니다(김해는 「자료검색」 메뉴 뒤에 있습니다).
            # 폼을 읽는 조사(opac-discover.py)가 하듯 검색 페이지로 보이는 링크를 몇 개 따라가
            # 봅니다. 그래도 없으면 그때 포기합니다.
            for link in od.search_page_links(page.content(), page.url, 3):
                if od.blocked(link, disallows):
                    continue
                pacer.wait(host)
                try:
                    page.goto(link, timeout=timeout * 1000, wait_until="domcontentloaded")
                except Exception:
                    continue
                boxes = search_boxes(page)
                if boxes:
                    home = page.url
                    break
        if not boxes:
            out["note"] = "검색창을 찾지 못했습니다"
            return out

        org = od.registrable_domain(host)
        recorder = SearchRequests(context, books[0], org)
        title = od.PROBE_BOOKS[books[0]]
        notes: list[str] = []
        for n, box in enumerate(boxes[:3]):
            if n:
                # 앞의 칸으로 이미 화면을 옮겼으므로 다시 돌아와야 합니다. 안 그러면 남은
                # 칸들은 사라진 자리를 가리켜 전부 조용히 실패합니다.
                pacer.wait(host)
                page.goto(home, timeout=timeout * 1000, wait_until="domcontentloaded")
                boxes = search_boxes(page)
                if n >= len(boxes):
                    break
                box = boxes[n]
            recorder.seen.clear()
            option = choose_isbn_option(page, box)
            before = page.url
            url = submit(page, box, books[0], timeout)
            if not recorder.seen and page.url == before:
                if press_search_button(page, box, books[0], timeout):
                    url = next((o.url for o in context.pages if o is not page and books[0] in o.url),
                               page.url)

            candidates = search_candidates(url, recorder.seen, books[0])
            if not candidates:
                notes.append(no_candidate_note(recorder.seen))
                continue
            # **같은 창부터 봅니다.** 같은 창에서도 책이 안 나오면 새 창을 볼 까닭이 없고, 그것을
            # 「새 창에서 열면 안 나온다」로 적으면 세션 문제로 읽혀 엉뚱한 곳을 고치게 됩니다.
            # 2026-09-11 에 그 사유로 끝난 14묶음 가운데 세션 문제로 확인된 곳은 없었습니다.
            if not shows_anywhere(context, title):
                notes.append("같은 창에서도 그 책이 나오지 않습니다 "
                             "(검색 항목이나 범위, 또는 그 OPAC 에 자료가 없음)")
                continue

            for template, via in candidates:
                if od.blocked(template, disallows):
                    notes.append("robots.txt 가 막은 경로입니다")
                    continue

                # ① 양성 — 그 도서관이 실제로 소장한 책
                hit, _, fresh = opens_fresh(browser, template, books[0], title, pacer, timeout)
                if network_failure(fresh):
                    out["note"] = f"이 기계의 네트워크가 끊겼습니다 ({network_failure(fresh)})"
                    return out
                if not hit:
                    notes.append(FRESH_MISS_NOTE[via])
                    continue

                # ② 음성 — 있을 수 없는 ISBN 으로 그 책이 나오면 검색어를 무시하는 OPAC 입니다
                ghost, _, ghost_html = opens_fresh(browser, template, od.ABSENT_ISBN, title,
                                                   pacer, timeout)
                if network_failure(ghost_html):
                    out["note"] = f"이 기계의 네트워크가 끊겼습니다 ({network_failure(ghost_html)})"
                    return out
                if ghost:
                    out["note"] = "검색어를 무시하고 목록을 뿌립니다"
                    return out

                # ③ 재확인 — 다른 책으로 한 번 더. 첫 번째만 우연히 맞는 경우가 있습니다
                again, _, again_html = opens_fresh(browser, template, books[1],
                                                   od.PROBE_BOOKS[books[1]], pacer, timeout)
                if network_failure(again_html):
                    out["note"] = f"이 기계의 네트워크가 끊겼습니다 ({network_failure(again_html)})"
                    return out
                if not again:
                    out["note"] = "다른 책으로는 확인되지 않았습니다"
                    return out

                rule = {"kind": "ISBN_SEARCH", "encoding": "UTF-8", "url": template, "via": via}
                if option:
                    rule["option"] = option
                out["rules"] = [rule]

                # ④ 상세 페이지까지 갈 수 있으면 더 좋습니다. 대부분은 내부 키라 안 됩니다.
                detail = detail_rule(browser, template, books, pacer, timeout)
                if detail:
                    out["rules"].insert(0, {"kind": "ISBN_DETAIL", "encoding": "UTF-8",
                                            "url": detail})
                    return out

                # ⑤ 내부 키면 자리표 대신 **패턴**입니다. 서버가 누를 때 검색 결과에서 뽑습니다.
                pattern, why = detail_pattern(browser, template, books, pacer, timeout)
                if pattern:
                    out["rules"].append(pattern)
                else:
                    out["detailNote"] = why
                return out

        out["note"] = best_note(notes)
        return out
    except Exception as e:
        out["note"] = f"{type(e).__name__}: {e}"
        return out
    finally:
        context.close()


def groups_of(libs: list[dict], probe: dict) -> list[tuple]:
    groups: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        if od.normalize_home(l.get("homepageUrl")):
            groups[od.group_key(l["homepageUrl"])].append(l)
    rows = []
    for key, members in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        rep = od.pick_representative(members, probe)
        books = [i for i in probe.get(rep["libCode"], []) if i in od.PROBE_BOOKS][:3]
        rows.append((key, members, rep, books))
    return rows


def ruled_codes(csv_path: str) -> set[str]:
    """templates.csv 에 이미 규칙이 있는 도서관부호."""
    out: set[str] = set()
    if not os.path.exists(csv_path):
        return out
    for row in csv.reader(open(csv_path, encoding="utf-8")):
        if row and not row[0].lstrip().startswith("#") and len(row) >= 4:
            out.add(row[0].strip())
    return out


def without_rules(rows: list[tuple], ruled: set[str]) -> list[tuple]:
    """**구성원 전부에 이미 규칙이 있는 묶음은 조사하지 않습니다.**

    큰 묶음부터 도는데 가장 큰 묶음들이 바로 사람이 채운 열세 개 시스템이라, 거르지 않으면
    `--limit 30` 의 셋에 하나가 이미 규칙이 있는 곳을 다시 두드리는 데 쓰입니다. 남의 서버에
    같은 요청을 다시 보내는 것이고, `--emit` 이 낸 줄은 이미 있는 줄과 겹칩니다. 일부만
    규칙이 있는 묶음은 그대로 조사합니다. 빠진 쪽이 규칙을 받아야 하기 때문입니다.
    """
    return [r for r in rows if not all(m["libCode"] in ruled for m in r[1])]


# 연결 단계의 실패. 그 도서관이 느리거나 우리를 막은 것이라 곧바로 다시 두드리지 않고,
# 나중에 --retry-failed 로 다시 봅니다. 이 기계의 네트워크가 끊긴 것(LOCAL_NETWORK_ERRORS)과는 다릅니다.
CONNECTION_FAILURE = re.compile(r"TimeoutError|ERR_CONNECTION|ERR_TIMED_OUT|ERR_NAME_NOT_RESOLVED|"
                                r"ERR_ADDRESS_UNREACHABLE|ERR_EMPTY_RESPONSE")
HOST_FAILURE_LIMIT = 2


def connection_failure(note: str | None) -> bool:
    return bool(CONNECTION_FAILURE.search(note or ""))


def interleave_by_host(rows: list[tuple]) -> list[tuple]:
    """같은 호스트의 묶음이 줄줄이 붙지 않게 호스트를 돌아가며 섞습니다. 호스트 안의 순서는 지킵니다.

    호스트 간격(3초)을 지켜도, 분관 묶음이 수십 개인 호스트를 연달아 보면 그 서버에는 긴 시간
    요청이 쌓입니다. 브라우저는 페이지 하나에 그림과 스크립트까지 수십 건을 받기 때문입니다.
    2026-09-11 에 강원교육청(lib.gwe.go.kr)과 화성(www.hscitylib.or.kr)이 우리 조사 중에 열 번
    가까이 연달아 답하지 않게 됐습니다.
    """
    queues: dict[str, list[tuple]] = {}
    for row in rows:
        queues.setdefault(row[0].split("/")[0], []).append(row)
    out: list[tuple] = []
    while queues:
        for host in list(queues):
            out.append(queues[host].pop(0))
            if not queues[host]:
                del queues[host]
    return out


class HostBreaker:
    """한 호스트에서 연결 실패가 연달아 나면 그 호스트의 남은 묶음을 이번 조사에서 건너뜁니다."""

    def __init__(self, limit: int = HOST_FAILURE_LIMIT):
        self.limit, self.streak = limit, {}

    def open_for(self, host: str) -> bool:
        return self.streak.get(host, 0) < self.limit

    def record(self, host: str, failed: bool) -> None:
        self.streak[host] = self.streak.get(host, 0) + 1 if failed else 0


def done_keys(results_path: str, retry_failed: bool) -> set[str]:
    """이미 본 묶음. 연결 실패로 끝난 묶음(retryable)은 --retry-failed 일 때만 다시 볼 대상으로 둡니다.
    막 우리를 막은 서버를 곧바로 다시 두드리지 않기 위해서입니다."""
    done: set[str] = set()
    if not os.path.exists(results_path):
        return done
    for line in open(results_path):
        rec = json.loads(line)
        if retry_failed and rec.get("retryable"):
            continue
        done.add(rec["key"])
    return done


def wait_for_network(minutes: float = 30.0) -> bool:
    """우리 서버에 닿을 때까지 기다립니다. 도서관이 아니라 우리 서버로 확인합니다."""
    deadline = time.time() + minutes * 60
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(od.API + "/api/version", timeout=10):
                return True
        except Exception:
            time.sleep(10)
    return False


def run(work: str, limit: int, host_delay: float, timeout: float,
        csv_path: str = od.DEFAULT_CSV, only: list[str] | None = None,
        retry_failed: bool = False) -> int:
    """`only` 에 묶음키를 주면 그 묶음만 봅니다. 큰 묶음부터 도는 순서로는 닿지 않는 자리가
    있습니다. 고양시립·안산시립처럼 분관마다 홈페이지 경로가 달라 **한 곳짜리 묶음으로 갈린
    큰 도서관들**인데, `--gaps` 가 「따로 받아야 한다」고 답한 곳이 바로 그 자리입니다."""
    sync_playwright = playwright()

    libs = od.load_libraries(work)
    probe_path = os.path.join(work, "probe.json")
    if not os.path.exists(probe_path):
        print("probe.json 이 없습니다. 먼저 ./scripts/opac-discover.py --probe 를 돌리세요.",
              file=sys.stderr)
        return 1
    probe = json.load(open(probe_path))
    results_path = os.path.join(work, "browse.jsonl")
    done = done_keys(results_path, retry_failed)

    rows = without_rules([r for r in groups_of(libs, probe) if r[0] not in done],
                         ruled_codes(csv_path))
    if only:
        # 묶음키의 경로 조각은 대소문자를 살립니다(`www.goyanglib.or.kr/MU`). 견줄 때만 낮춥니다.
        def plain(key: str) -> str:
            return key.strip().lower().removeprefix("www.")
        wanted = {plain(k) for k in only if k.strip()}
        rows = [r for r in rows if plain(r[0]) in wanted]
        missing = wanted - {plain(r[0]) for r in rows}
        if missing:
            print(f"모르거나 이미 조사한 묶음입니다: {', '.join(sorted(missing))}", file=sys.stderr)
    if limit:
        rows = rows[:limit]
    rows = interleave_by_host(rows)
    breaker = HostBreaker()
    pacer = od.Pacer(host_delay)
    found = 0
    with sync_playwright() as p:
        browser = chromium(p)
        try:
            with open(results_path, "a") as sink:
                for i, (key, members, rep, books) in enumerate(rows, 1):
                    host = key.split("/")[0]
                    if not breaker.open_for(host):
                        r = {"key": key, "libCodes": [m["libCode"] for m in members], "rep": rep["name"],
                             "rules": [], "retryable": True,
                             "note": "호스트가 연달아 답하지 않아 이번 조사에서는 건너뜁니다"}
                        sink.write(json.dumps(r, ensure_ascii=False) + "\n")
                        sink.flush()
                        print(f"[{i}/{len(rows)}]  쉼  {key:34s} 호스트가 연달아 답하지 않았습니다",
                              file=sys.stderr)
                        continue
                    r = investigate(browser, key, members, rep, books, pacer, timeout)
                    if network_failure(r["note"]):
                        # 이 기계의 네트워크가 끊긴 것은 그 도서관의 사유가 아닙니다. 「끝남」으로
                        # 적으면 다시 돌려도 건너뛰어 그 묶음을 영영 보지 못합니다. 2026-09-11 에
                        # 진단한 서른세 묶음 가운데 스물다섯이 이렇게 끝났습니다.
                        if not wait_for_network():
                            print("네트워크가 돌아오지 않아 멈춥니다. 다시 돌리면 이어서 합니다.",
                                  file=sys.stderr)
                            break
                        r = investigate(browser, key, members, rep, books, pacer, timeout)
                        if network_failure(r["note"]):
                            print(f"[{i}/{len(rows)}] 네트워크가 흔들려 적지 않고 넘어갑니다  {key}",
                                  file=sys.stderr)
                            continue
                    failed = connection_failure(r["note"]) and not r["rules"]
                    if failed:
                        r["retryable"] = True
                    breaker.record(host, failed)
                    sink.write(json.dumps(r, ensure_ascii=False) + "\n")
                    sink.flush()
                    mark = "찾음" if r["rules"] else "  — "
                    found += 1 if r["rules"] else 0
                    kinds = "+".join(x["kind"] for x in r["rules"])
                    detail = (r["rules"][0].get("url") or r["rules"][0].get("pattern")) if r["rules"] else r["note"][:44]
                    print(f"[{i}/{len(rows)}] {mark} {key:34s} {len(members):3d}곳  {kinds:36s} {detail}",
                          file=sys.stderr)
                    if r.get("detailNote"):
                        print(f"          상세 패턴 없음: {r['detailNote']}", file=sys.stderr)
        finally:
            browser.close()
    print(f"\n묶음 {found}/{len(rows)} 에서 규칙을 찾았습니다", file=sys.stderr)
    return 0


def emit(work: str) -> int:
    """`--import-file`/`--import-patterns` 가 읽는 형식으로. 거기서 한 번 더 걸러집니다."""
    paths = [p for p in (os.path.join(work, "browse.jsonl"), os.path.join(work, "patterns.jsonl"))
             if os.path.exists(p)]
    if not paths:
        print("조사 결과가 없습니다.", file=sys.stderr)
        return 1
    print("# opac-browse.py 가 브라우저로 확인한 주소입니다. 세 겹 검증을 통과한 것만 있습니다.")
    print("# DETAIL_PATTERN 줄은 --import-patterns 로, 나머지는 --import-file 로 넣습니다.")
    for path in paths:
        for line in open(path):
            r = json.loads(line)
            for rule in r["rules"]:
                if rule["kind"] == "DETAIL_PATTERN":
                    # 정규식에 | 가 있을 수 있어 넷째 칸이 마지막입니다. 메모를 붙이지 마세요.
                    print(f"{r['key']} | DETAIL_PATTERN | {rule['hostKey']} | {rule['pattern']}")
                else:
                    for key in r.get("keys", [r["key"]]):
                        print(f"{key} | {rule['kind']} | {rule['encoding']} | {rule['url']}")
    return 0


def find_patterns(work: str, csv_path: str, patterns_csv: str, host_delay: float,
                  timeout: float, limit: int) -> int:
    """이미 검색 규칙이 있는 도서관들에 대해 **상세 단계만** 돌립니다.

    사람이 채운 규칙 217줄은 열세 개 시스템이라, 시스템마다 패턴 한 줄이면 그 도서관 전체가
    검색 결과가 아니라 상세로 갑니다. 검색 규칙을 다시 찾을 필요는 없으므로 규칙 주소로
    바로 결과를 받아 봅니다. 열쇠(호스트)에 이미 패턴이 있으면 건너뜁니다.

    결과는 `patterns.jsonl` 에 남고 `--emit` 이 함께 냅니다. 상세 주소에 ISBN 이 그대로
    있는 곳은 `ISBN_DETAIL` 규칙으로 나옵니다(그 주소를 함께 쓰는 묶음마다 한 줄).
    """
    sync_playwright = playwright()
    libs = od.load_libraries(work)
    probe_path = os.path.join(work, "probe.json")
    if not os.path.exists(probe_path):
        print("probe.json 이 없습니다. 먼저 ./scripts/opac-discover.py --probe 를 돌리세요.",
              file=sys.stderr)
        return 1
    probe = json.load(open(probe_path))
    by_code = {l["libCode"]: l for l in libs}

    by_template: dict[str, list[dict]] = defaultdict(list)
    for row in csv.reader(open(csv_path, encoding="utf-8")):
        if not row or row[0].lstrip().startswith("#") or len(row) < 4:
            continue
        if row[1].strip() == "ISBN_SEARCH" and row[0].strip() in by_code:
            by_template[row[3].strip()].append(by_code[row[0].strip()])

    existing = od.existing_patterns(patterns_csv)
    results_path = os.path.join(work, "patterns.jsonl")
    done = {json.loads(l)["template"] for l in open(results_path)} if os.path.exists(results_path) else set()
    todo = [(t, m) for t, m in sorted(by_template.items(), key=lambda kv: -len(kv[1]))
            if t not in done and urllib.parse.urlparse(t).netloc.lower() not in existing]
    if limit:
        todo = todo[:limit]
    print(f"검색 규칙이 있는 주소 {len(by_template)}개 가운데 {len(todo)}개의 상세를 봅니다",
          file=sys.stderr)

    pacer = od.Pacer(host_delay)
    found = 0
    hosts_done: set[str] = set()
    with sync_playwright() as p:
        browser = chromium(p)
        try:
            with open(results_path, "a") as sink:
                for i, (template, members) in enumerate(todo, 1):
                    host = urllib.parse.urlparse(template).netloc.lower()
                    rep = od.pick_representative(members, probe)
                    books = [b for b in probe.get(rep["libCode"], []) if b in od.PROBE_BOOKS][:3]
                    keys = sorted({od.group_key(m["homepageUrl"]) for m in members
                                   if od.normalize_home(m.get("homepageUrl"))})
                    out = {"template": template, "key": keys[0] if keys else host, "keys": keys,
                           "libCodes": [m["libCode"] for m in members], "rep": rep["name"],
                           "rules": [], "note": ""}
                    if host in hosts_done:
                        out["note"] = "같은 호스트의 패턴을 이미 찾았습니다"
                    elif len(books) < 2:
                        out["note"] = "검증용으로 쓸 소장 도서가 모자랍니다"
                    else:
                        try:
                            detail = detail_rule(browser, template, books, pacer, timeout)
                            if detail:
                                out["rules"].append({"kind": "ISBN_DETAIL", "encoding": "UTF-8",
                                                     "url": detail})
                            else:
                                pattern, why = detail_pattern(browser, template, books, pacer, timeout)
                                if pattern:
                                    out["rules"].append(pattern)
                                    hosts_done.add(host)
                                else:
                                    out["note"] = why
                        except Exception as e:
                            out["note"] = f"{type(e).__name__}: {e}"
                    sink.write(json.dumps(out, ensure_ascii=False) + "\n")
                    sink.flush()
                    found += 1 if out["rules"] else 0
                    shown = ((out["rules"][0].get("url") or out["rules"][0].get("pattern"))
                             if out["rules"] else out["note"][:60])
                    print(f"[{i}/{len(todo)}] {'찾음' if out['rules'] else '  — '} {host:32s} "
                          f"{len(members):3d}곳  {shown}", file=sys.stderr)
        finally:
            browser.close()
    print(f"\n주소 {found}/{len(todo)} 에서 상세로 가는 길을 찾았습니다. --emit 으로 뽑으세요.",
          file=sys.stderr)
    return 0


def check_gaps(work: str, csv_path: str, host_delay: float, timeout: float) -> int:
    """규칙이 있는 기관에서 빠진 도서관이 **그 검색 화면에 실제로 나오는지** 봅니다.

    나오면 그 주소가 그 도서관까지 덮는다는 뜻이라 묶음키에 `*` 를 붙여 넓힐 수 있습니다.
    안 나오면 분관마다 장서가 따로인 시스템이므로 **넓히면 안 됩니다.** 어느 책을 눌러도
    남의 도서관 목록이 뜨게 됩니다.
    """
    sync_playwright = playwright()

    libs = od.load_libraries(work)
    rules: dict[str, str] = {}
    for row in csv.reader(open(csv_path, encoding="utf-8")):
        if row and not row[0].lstrip().startswith("#") and len(row) >= 4:
            rules[row[0].strip()] = row[3]

    org_rule, org_key, by_org = {}, {}, defaultdict(list)
    for l in libs:
        home = od.normalize_home(l.get("homepageUrl"))
        if not home:
            continue
        org = od.registrable_domain(urllib.parse.urlparse(home).netloc)
        by_org[org].append(l)
        if l["libCode"] in rules and org not in org_rule:
            org_rule[org] = rules[l["libCode"]]
            org_key[org] = od.group_key(l["homepageUrl"])

    isbn = next(iter(od.PROBE_BOOKS))
    pacer = od.Pacer(host_delay)
    with sync_playwright() as p:
        browser = chromium(p)
        try:
            for org, members in sorted(by_org.items(), key=lambda kv: -len(kv[1])):
                without = [l for l in members if l["libCode"] not in rules]
                if org not in org_rule or not without:
                    continue
                url = org_rule[org].replace("{isbn13}", isbn)
                context = browser.new_context()
                try:
                    page = context.new_page()
                    pacer.wait(urllib.parse.urlparse(url).netloc)
                    page.goto(url, timeout=timeout * 1000, wait_until="domcontentloaded")
                    try:
                        page.wait_for_load_state("networkidle", timeout=timeout * 1000)
                    except Exception:
                        pass
                    html = page.content()
                except Exception as e:
                    print(f"\n[{org}] 열지 못했습니다: {type(e).__name__}", file=sys.stderr)
                    continue
                finally:
                    context.close()
                seen = [l for l in without if od.page_has(html, l["name"])]
                print(f"\n[{org}] 빠진 {len(without)}곳 가운데 검색 화면에 "
                      f"{len(seen)}곳이 보입니다")
                for l in without:
                    print(f"   {'보임' if l in seen else '없음'}  {l['libCode']}  {l['name']}")
                if len(seen) == len(without):
                    print(f"   → 넓혀도 됩니다:  {org_key[org]}* | ISBN_SEARCH | UTF-8 | "
                          f"{org_rule[org]}")
                elif seen:
                    print("   → 일부만 보입니다. 보이는 곳만 부호로 직접 넣으세요.")
                else:
                    print("   → 넓히지 마세요. 그 도서관들의 검색 주소를 따로 받아야 합니다.")
        finally:
            browser.close()
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=".opac-work")
    ap.add_argument("--limit", type=int, default=0, help="큰 묶음부터 이만큼만. 0 이면 전부")
    ap.add_argument("--only", metavar="KEY[,KEY...]",
                    help="이 묶음키들만 조사합니다. --gaps 가 「따로 받아야 한다」고 한 도서관의 묶음키")
    ap.add_argument("--retry-failed", action="store_true",
                    help="연결 실패로 끝났던 묶음도 다시 봅니다. 막혔던 서버를 곧바로 두드리지 않도록 기본은 건너뜁니다")
    ap.add_argument("--emit", action="store_true", help="찾은 규칙을 import 형식으로 출력")
    ap.add_argument("--gaps", metavar="CSV", nargs="?", const=od.DEFAULT_CSV,
                    help="규칙 있는 기관에서 빠진 도서관이 그 검색 화면에 나오는지 확인")
    ap.add_argument("--patterns", action="store_true",
                    help="이미 검색 규칙이 있는 도서관(templates.csv)의 상세 패턴만 찾습니다")
    ap.add_argument("--templates-csv", default=od.DEFAULT_CSV)
    ap.add_argument("--patterns-csv", default=od.DEFAULT_PATTERNS_CSV)
    ap.add_argument("--host-delay", type=float, default=3.0, help="같은 호스트의 요청 간격(초)")
    ap.add_argument("--timeout", type=float, default=20.0)
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    if args.emit:
        return emit(args.work)
    if args.gaps:
        return check_gaps(args.work, args.gaps, args.host_delay, args.timeout)
    if args.patterns:
        return find_patterns(args.work, args.templates_csv, args.patterns_csv,
                             args.host_delay, args.timeout, args.limit)
    return run(args.work, args.limit, args.host_delay, args.timeout, args.templates_csv,
               args.only.split(",") if args.only else None, args.retry_failed)


if __name__ == "__main__":
    sys.exit(main())

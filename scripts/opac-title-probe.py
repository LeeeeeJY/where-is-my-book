#!/usr/bin/env python3
"""**제목 검색에서 멈추는 OPAC** 을 상세 페이지까지 보낼 수 있는지 조사해, 서버의 `@anchor:` 규칙으로
쓸 수 있는 것을 import 형식으로 냅니다.

    ./scripts/opac-title-probe.py                    # 제목 규칙만 있고 닻 규칙이 없는 호스트 전부
    ./scripts/opac-title-probe.py --only www.l4d.or.kr,lib.yongin.go.kr
    ./scripts/opac-title-probe.py > 결과.txt && ./scripts/opac-discover.py --import-patterns 결과.txt

왜 따로 있는가. ISBN 으로 찾아지는 OPAC 은 결과의 첫 줄이 그 책이라 「첫 링크」로 상세를 뽑습니다
(`opac-browse.py --patterns`, `opac-onclick-probe.py`). 제목으로만 찾아지는 OPAC 은 그렇게 못 합니다.
「미움받을 용기」로 찾으면 2권과 만화판과 다른 출판사 판이 함께 나오고, **첫 줄로 가면 제목은 같은데
다른 책**입니다. 링크가 깨지지도 않아서 틀린 줄 모릅니다. 그래서 2026-11-09 까지는 제목 규칙에 상세를
걸 수 없다고 적어 두었습니다.

그런데 그런 OPAC 의 상당수가 **결과 목록에는 ISBN 을 적어 둡니다**(2026-11-10: 제목 규칙 호스트 52개
가운데 32개, 도서관 319곳). 검색이 ISBN 을 색인하지 않을 뿐입니다. 서버가 그 글자를 닻으로 삼아 「그
ISBN 이 적힌 항목의 링크」를 고를 수 있습니다(`kr.wimb.opac.IsbnAnchor`). 이 스크립트는 그 고르기를
파이썬으로 똑같이 해 보고(`pick` 이 서버와 같은 차례입니다. **한쪽을 고치면 다른 쪽도 고치세요**),
만든 상세 주소를 실제로 받아 봅니다.

## 무엇으로 확인하는가

**도착한 상세 화면에 그 ISBN 이 적혀 있는지**로 봅니다. 제목으로 보면 안 됩니다. 이 규칙이 틀렸을 때
가는 곳이 바로 「제목이 같은 다른 판」이라, 제목 검사는 틀린 규칙을 전부 통과시킵니다.

  1. 양성   그 도서관이 소장한 책의 제목으로 검색하고, 그 책의 ISBN 을 닻으로 뽑은 상세에 **그 ISBN 이
            있습니다.**
  2. 음성   그 상세에 **결과 목록의 다른 책 ISBN 들이 함께 있지 않습니다.** 검색 결과 자기 자리로
            되돌아오는 함수(자료 종류 탭, 정렬)를 여기서 거릅니다. 그런 화면에는 ISBN 이 전부 있습니다.
  3. 재확인 다른 소장 책으로 한 번 더.

「앞」인지 「뒤」인지는 **둘 다 해 보고 통과한 쪽**을 냅니다. 항목 안에서 링크가 ISBN 보다 앞에 오는
목록(`@anchor:`)과 뒤에 오는 목록(`@anchor-after:`)이 있고, 글자만 보고는 알 수 없습니다. 거꾸로 적으면
윗줄이나 아랫줄 책의 상세가 열리는데 거기에는 그 ISBN 이 없으므로 1번에서 떨어집니다.

## 결과가 프레임에 있는 OPAC (`@frame:`)

검색 화면의 HTML 에 결과가 없고 **다른 주소의 프레임**에 있는 OPAC 이 있습니다(충북교육청 계열 14곳:
검색 화면은 `/srch/smart2.do?…`, 결과는 같은 호스트의 `:7443/dls_le/index.php?…&word=…`). 서버는
`@frame:[인코딩 ]프레임주소틀 => @anchor:…` 규칙으로 그 프레임을 직접 받습니다. 이 도구는 검색 화면에
ISBN 이 없으면 **그 화면의 `<iframe src>` 가운데 검색어가 들어 있는 것**을 프레임 주소로 삼아 같은
확인을 프레임에 대고 합니다. 프레임 주소를 스크립트가 만들어 HTML 에 없으면 사람이 브라우저의 개발자
도구에서 읽어 `--frame` 으로 알려 줍니다.

    ./scripts/opac-title-probe.py --only www.cbelib.go.kr
    ./scripts/opac-title-probe.py --only www.cbelib.go.kr \
        --frame 'www.cbelib.go.kr=https://www.cbelib.go.kr:7443/dls_le/index.php?mod=wdDataSearch&act=searchIList&word={title}&searchType=&manageCode=MA&siteId=SITE_000000000000003'

프레임은 **검색 화면과 다른 출처**(포트가 다릅니다)라 robots.txt 를 따로 받아 지킵니다. 프레임 주소에
기관마다 다른 값(`manageCode`, `siteId`)이 들어가므로 규칙은 호스트마다 한 줄이고, 한 호스트의
도서관들이 서로 다른 프레임 주소를 쓰면 규칙을 내지 않습니다.

서버와 같은 UA 로, 자바스크립트 없이 받습니다. robots.txt 가 검색 경로를 막는 호스트는 조사하지
않습니다(서버도 받지 않으므로 규칙을 두어도 늘 검색 결과로 내려갑니다). 정보나루는 부르지 않습니다
(`probe.json` 의 소장 책을 씁니다). 한 호스트에 한꺼번에 하나씩, 요청 사이 3초입니다.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import sys
import time
import urllib.parse
from collections import defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)


def _load(name: str, filename: str):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, filename))
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


od = _load("od", "opac-discover.py")
oc = _load("oc", "opac-onclick-probe.py")

ANCHOR_PREFIX = "@anchor:"
ANCHOR_AFTER_PREFIX = "@anchor-after:"
ISBN_TOKEN = re.compile(r"(?<!\d)97[89]\d{10}(?!\d)")


def pick(page: str, spans: list[tuple[int, int, object]], isbn: str, link_first: bool):
    """`spans` (문서 순서의 (시작, 끝, 내용)) 가운데 그 ISBN 이 적힌 항목의 것. (내용 또는 None, 사유).

    **서버의 `IsbnAnchor.pick` 과 같은 차례입니다.** ① 링크 글자 안에 그 ISBN 이 있으면 그것 ② 아니면
    ISBN 이 적힌 자리마다 바로 앞(`link_first`) 또는 바로 뒤의 링크 ③ 그 사이에 다른 ISBN 이 끼어
    있으면 남의 항목이므로 버리고 다음 자리로.
    """
    if not isbn or not ISBN_TOKEN.fullmatch(isbn):
        return None, f"ISBN-13 이 아닙니다: {isbn}"
    for start, end, payload in spans:
        if isbn in page[start:end]:
            return payload, "링크 안에 그 ISBN 이 있습니다"
    ours, others = [], []
    for m in ISBN_TOKEN.finditer(page):
        (ours if m.group(0) == isbn else others).append((m.start(), m.end()))
    if not ours:
        return None, "결과에 그 ISBN 이 적혀 있지 않습니다"

    def clean(a: int, b: int) -> bool:
        return not any(a <= start < b for start, _ in others)

    for start, end in ours:
        if link_first:
            before = [s for s in spans if s[1] <= start]
            if before and clean(before[-1][1], start):
                return before[-1][2], "그 ISBN 바로 앞의 링크입니다"
        else:
            after = [s for s in spans if s[0] >= end]
            if after and clean(end, after[0][0]):
                return after[0][2], "그 ISBN 바로 뒤의 링크입니다"
    return None, "그 ISBN 이 적힌 항목에서 링크를 찾지 못했습니다"


def calls(page: str, fn: str) -> list[tuple[int, int, list[str]]]:
    """그 함수의 모든 호출과 놓인 자리. 서버의 `OnclickFormReader.allCalls` 와 같습니다."""
    out = []
    for m in re.finditer(r"""(?:onclick|href)\s*=\s*["']\s*(?:javascript:)?\s*""" + re.escape(fn) + r"\s*\(([^)]*)\)", page):
        out.append((m.start(), m.end(), oc.split_args(oc.unescape(m.group(1)))))
    return out


IFRAME_SRC = re.compile(r"""<i?frame\b[^>]*?\bsrc\s*=\s*["']([^"']+)["']""", re.I)


def frame_template(page: str, base: str, title: str) -> tuple[str, str] | None:
    """검색 화면의 `<iframe src>` 가운데 **그 검색어가 들어 있는 것**을 (인코딩, 주소틀)로.

    검색어를 UTF-8 과 EUC-KR 로, 공백을 `%20` 과 `+` 로 적은 네 가지를 찾습니다. 찾은 자리를 `{title}`
    로 바꾼 것이 주소틀입니다. 검색어가 든 프레임이 없으면 None 입니다(스크립트가 주소를 만드는
    화면이거나, 결과가 프레임에 있는 화면이 아닙니다). **주소를 짐작해 만들지 않습니다.** 그 화면이
    스스로 적어 둔 주소만 씁니다.
    """
    for m in IFRAME_SRC.finditer(page):
        src = urllib.parse.urljoin(base, od.attr_unescape(m.group(1)).strip())
        if not src.startswith(("http://", "https://")):
            continue
        for encoding in ("UTF-8", "EUC-KR"):
            try:
                raw = title.strip().encode(encoding)
            except UnicodeEncodeError:
                continue
            quoted = urllib.parse.quote(raw, safe="*").replace("~", "%7E")
            for needle in (quoted, quoted.replace("%20", "+")):
                at = src.lower().find(needle.lower())
                if needle and at >= 0:
                    return encoding, src[:at] + "{title}" + src[at + len(needle):]
    return None


def frame_rule(encoding: str, template: str, inner: str) -> str:
    """서버의 `DetailRule.Frame.describe` 와 같은 한 줄."""
    prefix = "" if encoding.upper().replace("_", "-") == "UTF-8" else encoding + " "
    return f"{od.FRAME_PREFIX}{prefix}{template} {od.TEMPLATE_SEPARATOR} {inner}"


def split_rule(rule: str) -> tuple[str, bool] | None:
    """`@anchor:규칙` → (안쪽 규칙, 링크가 앞인가). 닻 규칙이 아니면 None."""
    low = rule.lower()
    if low.startswith(ANCHOR_AFTER_PREFIX):
        return rule[len(ANCHOR_AFTER_PREFIX):], False
    if low.startswith(ANCHOR_PREFIX):
        return rule[len(ANCHOR_PREFIX):], True
    return None


def resolve(page: str, base: str, rule: str, isbn: str, timeout: float, scripts: dict[str, str]) -> tuple[str | None, str]:
    """닻 규칙 한 줄을 서버처럼 실행합니다. (상세 주소 또는 None, 어떻게)."""
    parts = split_rule(rule)
    if parts is None:
        return None, "닻 규칙(@anchor:)이 아닙니다"
    inner, link_first = parts
    if inner.lower().startswith(od.ONCLICK_PREFIX):
        fn = inner[len(od.ONCLICK_PREFIX):].strip()
        args, how = pick(page, calls(page, fn), isbn, link_first)
        if args is None:
            return None, how
        found = oc.find_function(page, fn)
        if not found:
            for u in oc.script_urls(page, base):
                if u not in scripts:
                    try:
                        scripts[u] = od.decode(oc.get(u, timeout)[0], "")
                    except Exception:
                        scripts[u] = ""
                found = oc.find_function(scripts[u], fn)
                if found:
                    break
        if not found:
            return None, f"함수 {fn} 의 본문을 페이지와 스크립트에서 찾지 못했습니다"
        url, note = oc.build(page, base, args, found[0], found[1])
        return url, f"{note} ({how})"
    try:
        regex = re.compile(inner)
    except re.error as e:
        return None, f"정규식이 잘못되었습니다 ({e})"
    spans = [(m.start(), m.end(), m.group(1)) for m in regex.finditer(page)]
    href, how = pick(page, spans, isbn, link_first)
    if href is None:
        return None, how
    href = od.attr_unescape(href).strip()
    if not href or href.startswith(("#", "javascript:", "mailto:")):
        return None, f"링크가 주소가 아닙니다: {href}"
    return urllib.parse.urljoin(base, href.replace(" ", "%20")), how


def nearest_href(page: str, isbn: str, link_first: bool) -> str | None:
    """그 ISBN 의 항목에 있는 **주소다운 href**. 정규식 후보를 만들 재료입니다.

    **HTML 에 적힌 글자 그대로 돌려줍니다**(`&amp;` 를 `&` 로 되돌리지 않습니다). 이것으로 만든
    정규식은 서버가 받는 HTML 원문에 대고 돌기 때문입니다. 되돌린 글자로 만들면 앞머리에 `&` 가 든
    링크(`index.php?mod=…&amp;act=…`)에서 정규식이 원문과 한 번도 맞지 않습니다. 잡힌 값의 엔티티는
    서버(`DetailResolver.unescape`)와 `resolve` 가 되돌립니다.
    """
    spans = []
    for m in re.finditer(r"""<a\b[^>]*?\bhref\s*=\s*["']([^"']+)["']""", page):
        href = od.attr_unescape(m.group(1)).strip()
        if href and not href.startswith(("#", "javascript:", "mailto:")):
            spans.append((m.start(), m.end(), m.group(1).strip()))
    href, _ = pick(page, spans, isbn, link_first)
    return href


def candidates(pages: dict[str, str], link_first: bool) -> list[str]:
    """두 책의 결과 화면에서 닻 규칙 후보들. onclick 함수가 먼저이고 href 정규식이 뒤입니다."""
    prefix = ANCHOR_PREFIX if link_first else ANCHOR_AFTER_PREFIX
    out: list[str] = []
    first_isbn, first_page = next(iter(pages.items()))
    for fn in oc.candidate_functions(first_page, first_isbn.strip()):
        out.append(f"{prefix}{od.ONCLICK_PREFIX}{fn}")
    hrefs = [nearest_href(page, isbn.strip(), link_first) for isbn, page in pages.items()]
    if all(hrefs) and len(hrefs) >= 2:
        regex = od.pattern_from_hrefs(hrefs[:2])
        if regex:
            out.append(prefix + regex)
    return out


_ROBOTS: dict[str, list[str]] = {}


def disallows_for(url: str, pacer) -> list[str]:
    """그 주소의 출처(포트까지)의 robots.txt. 출처마다 한 번만 받습니다."""
    parsed = urllib.parse.urlparse(url)
    origin = f"{parsed.scheme}://{parsed.netloc}"
    if origin not in _ROBOTS:
        _ROBOTS[origin] = od.robots_disallows(origin, pacer)
    return _ROBOTS[origin]


def check_detail(url: str, isbn: str, listed: set[str], search_url: str, pacer, timeout: float) -> tuple[bool, str]:
    """도착한 상세에 그 ISBN 이 있고, 결과 목록의 다른 책 ISBN 들이 함께 있지 않은지."""
    if url == search_url:
        return False, "검색 결과 자기 주소입니다"
    # 상세 화면은 서버가 받지 않고(302 로 보낼 뿐입니다) 이 도구만 확인하려고 받습니다. 그래도 기계가
    # 받는 것이므로 그 출처의 robots.txt 를 지킵니다. 막혀 있으면 확인할 수 없는 규칙입니다.
    if od.blocked(url, disallows_for(url, pacer)):
        return False, "robots.txt 가 상세 경로를 막습니다 (기계로 확인할 수 없습니다)"
    status, page, _ = od.fetch(url, pacer, timeout=timeout)
    if status != 200:
        return False, f"상세를 받지 못했습니다 ({'HTTP ' + str(status) if status else page[:60]})"
    here = {m.group(0) for m in ISBN_TOKEN.finditer(page)}
    if isbn not in here:
        return False, "상세에 그 ISBN 이 없습니다 (다른 책이거나 자바스크립트로 그리는 화면)"
    others = (listed - {isbn}) & here
    if listed - {isbn} and len(others) * 2 >= len(listed - {isbn}) and len(others) >= 2:
        return False, f"상세에 결과 목록의 다른 ISBN 이 {len(others)}개 함께 있습니다 (검색 결과로 되돌아온 화면)"
    return True, "그 ISBN 의 상세입니다"


def title_hosts(csv_path: str, patterns_csv: str, libs: list[dict], probe: dict, only: set[str] | None):
    """제목 규칙만 있는 도서관을 호스트로 묶습니다. 이미 닻 규칙이 덮는 주소는 뺍니다."""
    by_code = {l["libCode"]: l for l in libs}
    kinds: dict[str, set[str]] = defaultdict(set)
    rows = list(od.template_rows(csv_path))
    for row in rows:
        kinds[row[0]].add(row[1])
    existing = od.existing_patterns(patterns_csv)

    def covered(url: str) -> str | None:
        """이 주소에 걸리는 규칙. 서버와 같은 차례입니다(경로가 긴 열쇠, 칸 조건이 붙은 열쇠)."""
        hit = od.pattern_for(existing, url)
        return hit[1] if hit else None

    hosts: dict[str, list[tuple[dict, str, str]]] = defaultdict(list)
    for row in rows:
        if row[1] != "TITLE_SEARCH" or kinds[row[0]] != {"TITLE_SEARCH"} or row[0] not in by_code:
            continue
        rule = covered(row[3])
        if rule and split_rule(rule):
            continue                                   # 이미 닻 규칙이 있습니다
        host = re.sub(r":\d+$", "", urllib.parse.urlparse(row[3]).netloc.lower())
        if only and host not in only:
            continue
        hosts[host].append((by_code[row[0]], row[3], row[2]))
    return hosts, existing


# 분관을 좁히는 칸으로 알려진 이름들. 값이 ALL 이거나 비어 있으면 좁힌 것이 아닙니다.
BRANCH_PARAMS = ("searchLibraryArr", "searchManageCodeArr", "manage_code", "manageCode", "libraryCodes")


def branch_param(templates: list[str]) -> str | None:
    """이 호스트의 검색 주소들이 분관을 좁히는 데 쓰는 칸 이름. 그런 칸을 쓰는 주소가 하나도 없으면 None."""
    for name in BRANCH_PARAMS:
        for template in templates:
            # 「값 하나로」의 판정은 서버와 같은 함수에 맡깁니다. 여러 번 나오거나 쉼표로 늘어놓은 것은
            # 분관을 좁힌 것이 아닙니다.
            if not od.pattern_covers("x?" + name, "https://x/?" + urllib.parse.urlparse(template).query):
                continue
            value = urllib.parse.parse_qs(urllib.parse.urlparse(template).query).get(name, [""])[0]
            if value.upper() != "ALL" and "{" not in value:
                return name
    return None


def fill(template: str, title: str, encoding: str) -> str:
    """서버(OpacTemplates.fill)와 같이 자리표의 인코딩으로 퍼센트 부호를 씁니다."""
    raw = title.encode("euc-kr" if encoding.upper() == "EUC-KR" else "utf-8")
    return template.replace("{title}", urllib.parse.quote(raw))


def ascii_url(url: str) -> str:
    """주소에 한글이 그대로 들어 있으면 퍼센트 부호로 바꿉니다. 이미 바뀐 것은 건드리지 않습니다."""
    return urllib.parse.quote(url, safe="%/:?&=#;,+@!$'()*~[]-._")


def probe_host(host: str, members: list[tuple[dict, str, str]], probe: dict, pacer, timeout: float,
               frame: tuple[str, str] | None = None) -> tuple[str | None, str]:
    """(통과한 닻 규칙 또는 None, 사유).

    `frame` 은 사람이 알려 준 (인코딩, 프레임주소틀)입니다. 없으면 검색 화면에 ISBN 이 없을 때 그
    화면의 `<iframe src>` 에서 찾습니다. 프레임으로 본 호스트의 규칙은 `@frame:… => @anchor:…` 로 냅니다.

    **두 책을 될 수 있으면 서로 다른 도서관의 검색 주소로 봅니다.** 한 호스트에 분관마다 검색 주소가
    따로 있는 시스템이 많은데(강남·세종·용인), 규칙은 호스트에 한 줄입니다. 한 분관의 화면 둘에서
    정규식을 만들면 `?manage_code=MP&…` 처럼 **그 분관의 부호까지 앞머리에 들어가** 다른 분관에서는
    하나도 잡히지 않습니다. 실제로 세종과 성동이 그렇게 나왔습니다(2026-11-10).
    """
    usable = []
    for lib, template, encoding in members:
        owned = [i for i in probe.get(lib["libCode"], []) if i in od.PROBE_BOOKS]
        if owned:
            usable.append((lib, template, encoding, owned))
    if not usable or sum(len(u[3]) for u in usable) < 2:
        return None, "검증용으로 쓸 소장 도서가 두 권이 안 됩니다"
    parsed = urllib.parse.urlparse(usable[0][1])
    disallows = od.robots_disallows(f"{parsed.scheme}://{parsed.netloc}", pacer)

    # 봐야 할 (도서관, 책)의 차례. 검색 주소가 다른 도서관을 먼저 세우고, 책도 겹치지 않게 고릅니다.
    plan: list[tuple[dict, str, str, str]] = []
    seen_templates: set[str] = set()
    for lib, template, encoding, owned in usable:
        if template in seen_templates:
            continue
        seen_templates.add(template)
        for isbn in owned:
            plan.append((lib, template, encoding, isbn))

    views: list[tuple[dict, str, str, str, str]] = []      # (도서관, ISBN, 검색 주소, 최종 주소, 본문)
    fetches = 0
    framed: tuple[str, str] | None = frame                 # (인코딩, 프레임주소틀). 정해지면 프레임을 받습니다
    for lib, template, encoding, isbn in plan:
        if len(views) == 2 or fetches >= 6:
            break
        if views and (views[0][0]["libCode"] == lib["libCode"] and len(seen_templates) > 1):
            continue                                        # 다른 도서관이 있으면 같은 도서관을 또 보지 않습니다
        if views and views[0][1] == isbn and any(p[3] != isbn for p in plan):
            continue                                        # 다른 책이 있으면 같은 책을 또 보지 않습니다
        url = fill(template, od.PROBE_BOOKS[isbn], encoding)
        title = od.PROBE_BOOKS[isbn]
        if framed is None:
            # 프레임 주소를 아직 모르면 검색 화면부터 받습니다. 거기에 결과가 있으면 프레임이 아닌 보통
            # 화면이고, 없으면 그 화면이 적어 둔 프레임 주소를 읽습니다. 프레임 주소를 알게 된 뒤로는
            # 검색 화면을 받지 않습니다. 서버도 받지 않습니다.
            if od.blocked(url, disallows):
                return None, "robots.txt 가 검색 결과 경로를 막습니다 (서버가 받을 수 없어 조사하지 않습니다)"
            status, body, final = od.fetch(url, pacer, timeout=timeout)
            fetches += 1
            if status != 200:
                return None, f"검색 결과를 서버 방식으로 받지 못했습니다 ({'HTTP ' + str(status) if status else body[:60]})"
            if isbn in {m.group(0) for m in ISBN_TOKEN.finditer(body)}:
                views.append((lib, isbn, url, final or url, body))
                continue
            found = frame_template(body, final or url, title)
            if found is None:
                continue                                    # 프레임도 없습니다. 아래에서 사유를 말합니다
            framed = found
        encoding_f, template_f = framed
        frame_url = od.fill_frame(template_f, encoding_f, title=title, isbn13=isbn, lib_code=lib["libCode"])
        fp = urllib.parse.urlparse(frame_url)
        if od.registrable_domain(fp.hostname or "") != od.registrable_domain(host):
            return None, f"프레임이 다른 기관의 주소입니다 ({fp.hostname})"
        # 포트가 다르면 다른 출처입니다. 검색 화면의 robots.txt 로 갈음하지 않습니다.
        if od.blocked(frame_url, disallows_for(frame_url, pacer)):
            return None, "robots.txt 가 프레임의 경로를 막습니다 (서버가 받을 수 없어 조사하지 않습니다)"
        # 포트만 다른 같은 기계일 수 있습니다. 검색 화면을 받은 직후에 잇달아 두드리지 않습니다.
        time.sleep(pacer.delay)
        status, body, final = od.fetch(frame_url, pacer, timeout=timeout)
        fetches += 1
        if status != 200:
            return None, f"프레임을 서버 방식으로 받지 못했습니다 ({'HTTP ' + str(status) if status else body[:60]})"
        if isbn in {m.group(0) for m in ISBN_TOKEN.finditer(body)}:
            views.append((lib, isbn, frame_url, final or frame_url, body))
    if len(views) < 2:
        where = "프레임의 결과 목록" if framed else "결과 목록"
        return None, (f"{where}에 ISBN 이 적혀 있지 않습니다 (닻으로 삼을 것이 없습니다. 결과가 프레임에 있는데 "
                      f"그 주소가 HTML 에 없으면 --frame 으로 알려 주세요)" if not views
                      else f"두 번째 책의 ISBN 이 {where}에 없습니다 (그 OPAC 에 없는 판)")
    if framed and any(v[2].split("?")[0] != views[0][2].split("?")[0] for v in views):
        return None, "프레임과 검색 화면이 섞였습니다 (같은 호스트에 두 가지 화면이 있습니다)"
    if framed and len({t for _, t, _ in members}) > 1:
        # 프레임 주소에는 기관마다 다른 값(manageCode, siteId)이 글자 그대로 들어갑니다. 한 호스트의
        # 도서관들이 검색 주소를 따로 가지면 프레임 주소도 따로일 것이고, 한 줄로는 남의 분관을 받습니다.
        return None, ("이 호스트의 도서관들이 검색 주소를 따로 씁니다. 프레임 규칙은 호스트에 한 줄이라 "
                      "낼 수 없습니다 (도서관마다 프레임 주소가 같은지 사람이 봐야 합니다)")

    scripts: dict[str, str] = {}
    reasons: list[str] = []
    for link_first in (True, False):
        for rule in candidates({v[1]: v[4] for v in views} if views[0][1] != views[1][1]
                               else {views[0][1]: views[0][4], views[1][1] + " ": views[1][4]}, link_first):
            ok = True
            for lib, isbn, url, final, body in views:
                detail, how = resolve(body, final, rule, isbn, timeout, scripts)
                if not detail:
                    reasons.append(f"{rule}: {how}")
                    ok = False
                    break
                listed = {m.group(0) for m in ISBN_TOKEN.finditer(body)}
                good, why = check_detail(ascii_url(detail), isbn, listed, url, pacer, timeout)
                if not good:
                    reasons.append(f"{rule}: {why}")
                    ok = False
                    break
            if ok:
                names = " · ".join(dict.fromkeys(v[0]["name"] for v in views))
                if framed:
                    return (frame_rule(framed[0], framed[1], rule),
                            f"{names} 에서 두 책 모두 프레임의 그 ISBN 줄에서 상세로 갔습니다")
                return rule, f"{names} 에서 두 책 모두 그 ISBN 의 상세로 갔습니다"
    return None, " / ".join(reasons[:4]) or "후보가 없습니다 (그 항목에 상세로 보이는 링크가 없습니다)"


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=".opac-work")
    ap.add_argument("--only", metavar="HOST[,HOST...]", help="이 호스트들만")
    ap.add_argument("--templates-csv", default=od.DEFAULT_CSV)
    ap.add_argument("--patterns-csv", default=od.DEFAULT_PATTERNS_CSV)
    ap.add_argument("--timeout", type=float, default=30.0)
    ap.add_argument("--host-delay", type=float, default=3.0)
    ap.add_argument("--frame", action="append", default=[], metavar="HOST=[인코딩 ]프레임주소틀",
                    help="결과가 프레임에 있는데 그 주소가 검색 화면의 HTML 에 없을 때, 브라우저에서 읽은 "
                         "프레임 주소를 {title} 자리표와 함께 알려 줍니다. 여러 번 줄 수 있습니다")
    args = ap.parse_args()

    frames: dict[str, tuple[str, str]] = {}
    for item in args.frame:
        frame_host, _, spec = item.partition("=")
        try:
            parsed = od.split_frame(od.FRAME_PREFIX + spec.strip() + " => x")
        except ValueError as e:
            print(f"--frame {item!r}: {e}", file=sys.stderr)
            return 1
        problem = od.pattern_problem(f'{od.FRAME_PREFIX}{spec.strip()} => @anchor:href="(x[^"]+)"')
        if parsed is None or problem:
            print(f"--frame {item!r}: {problem or '프레임 주소틀이 아닙니다'}", file=sys.stderr)
            return 1
        frames[frame_host.strip().lower()] = (parsed[0], parsed[1])

    libs = od.load_libraries(args.work)
    probe_path = os.path.join(args.work, "probe.json")
    if not os.path.exists(probe_path):
        print("probe.json 이 없습니다. 먼저 ./scripts/opac-discover.py --probe 를 돌리세요.", file=sys.stderr)
        return 1
    probe = json.load(open(probe_path))
    only = {h.strip().lower() for h in args.only.split(",") if h.strip()} if args.only else None
    hosts, existing = title_hosts(args.templates_csv, args.patterns_csv, libs, probe, only)

    pacer = od.Pacer(args.host_delay)
    print("# opac-title-probe.py 가 서버와 같은 방식으로 확인한 @anchor 규칙입니다.")
    print("# --import-patterns 로 넣습니다.")
    found = 0
    for host, members in sorted(hosts.items(), key=lambda kv: -len(kv[1])):
        try:
            rule, why = probe_host(host, members, probe, pacer, args.timeout, frames.get(host))
        except Exception as e:                         # 한 곳의 이상한 응답으로 전체가 죽지 않게
            rule, why = None, f"{type(e).__name__}: {e}"
        print(f"# {host} ({len(members)}곳): {rule or '—'}  {why}", file=sys.stderr, flush=True)
        if not rule:
            continue
        found += 1
        # **열쇠에 분관을 좁히는 칸 이름을 붙입니다.** 닻은 판을 가릴 뿐 분관을 가리지 못합니다. 통합
        # 검색 결과에는 같은 ISBN 의 줄이 분관마다 따로 있어서, 분관으로 좁힌 검색에만 규칙이 걸려야
        # 합니다. 파주·부평은 분관 사이트마다 주소가 다르면서 일부(또는 전부)가 searchLibrary=ALL 이라,
        # 호스트 열쇠 하나로 덮으면 남의 분관 소장본으로 갑니다.
        framed = od.split_frame(rule)
        if framed:
            # **프레임 규칙은 좁히는 값이 프레임 주소에 글자 그대로 들어 있습니다**(manageCode=MA). 열쇠의
            # ?칸이름 은 검색 주소의 칸을 보는 조건인데, 그 값은 검색 주소가 아니라 프레임 주소에 있으므로
            # 붙이지 않습니다. 대신 프레임 주소가 한 도서관으로 좁혀 주는지를 사람이 봐야 합니다.
            in_frame = branch_param([framed[1]])
            print(f"#   ⚠ {host}: 프레임 규칙입니다. 프레임의 목록에 **그 도서관의 줄만** 나오는지 결과의 소장처를 "
                  f"보세요" + (f" (프레임 주소의 {in_frame} 가 좁히는 값으로 보입니다)." if in_frame else
                             ". 프레임 주소에 분관을 좁히는 칸이 보이지 않습니다. 여러 도서관의 줄이 섞여 "
                             "나오면 이 줄을 넣지 마세요."), file=sys.stderr, flush=True)
            print(f"{od.group_key(members[0][0]['homepageUrl'])} | DETAIL_PATTERN | {host} | {rule}", flush=True)
            continue
        narrowed = branch_param([t for _, t, _ in members])
        suffix = "?" + narrowed if narrowed else ""
        if not narrowed:
            print(f"#   ⚠ {host}: 검색 주소에 분관 부호 칸이 없습니다. 사이트 경로가 분관으로 좁혀 주는지 "
                  f"결과의 소장처를 보고, 아니면 이 줄을 넣지 마세요.", file=sys.stderr, flush=True)
        # 그 호스트에 이미 다른 규칙(ISBN 검색용)이 있으면 열쇠가 겹칩니다. 제목 규칙의 경로까지 내려 적습니다.
        if host in existing:
            for key in sorted({host + "/" + urllib.parse.urlparse(t).path.strip("/").split("/")[0] for _, t, _ in members}):
                group = od.group_key(next(l for l, t, _ in members if urllib.parse.urlparse(t).path.strip("/").split("/")[0] == key.split("/", 1)[1])["homepageUrl"])
                print(f"{group} | DETAIL_PATTERN | {key}{suffix} | {rule}", flush=True)
        else:
            print(f"{od.group_key(members[0][0]['homepageUrl'])} | DETAIL_PATTERN | {host}{suffix} | {rule}", flush=True)
    print(f"# 호스트 {len(hosts)}개 가운데 {found}개에서 닻 규칙을 얻었습니다", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

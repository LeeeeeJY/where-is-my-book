#!/usr/bin/env python3
"""도서관 OPAC 주소 규칙을 찾아서 실제 응답으로 검증합니다.

**이것은 「크롤링으로 규칙을 자동 발견하지 마세요」의 예외가 아니라 그 규칙을 지키는
방법입니다.** 금지된 것은 주소를 짐작해서 넣는 일이고, 여기서 하는 일은 그 도서관이
자기 검색 폼으로 알려 준 주소를 그대로 써 보고 **응답 본문에 그 책이 실제로 나오는지
확인**하는 것입니다. 확인을 통과하지 못한 규칙은 버립니다.

틀린 규칙은 HTTP 200 을 주면서 결과만 0건이 되어 「소장한다더니 그 책이 없네」로 보이고,
깨진 링크와 달리 눈에 띄지도 않습니다. 그래서 검증을 세 겹으로 겹칩니다.

  1. 양성   그 도서관이 **실제로 소장한** 책의 ISBN 을 넣고, 응답에 그 책 제목이 나오는지
            봅니다. 소장 여부는 정보나루가 답한 것을 씁니다(--probe). 소장하지 않은 책으로
            0건을 받으면 규칙이 틀린 것인지 그 책이 없는 것인지 구별할 수 없습니다.
  2. 음성   **존재하지 않는 ISBN** 을 넣고, 그 제목이 나오지 않는지 봅니다. 검색어를 무시하고
            전체 목록을 뿌리는 OPAC 이 있는데, 이것이 없으면 그런 곳이 전부 「성공」이 됩니다.
  3. 재확인 그 도서관이 소장한 **다른** 책으로 한 번 더 봅니다. 첫 번째만 우연히 맞는 경우가
            있습니다.

셋을 다 통과한 것만 규칙이 됩니다.

## 외부 서버에 대한 예의

같은 호스트에는 **한 번에 하나씩**, 요청 사이에 간격을 둡니다(--host-delay, 기본 3초).
서로 다른 호스트끼리만 겹칩니다(--concurrency, 기본 4). robots.txt 를 받아 거부된 경로는
건드리지 않습니다. 한 묶음이 그 서버에 보내는 요청은 최대 열 번 안쪽입니다.
**이 값들을 올리기 전에 CLAUDE.md 의 「외부 서버에 대한 예의」를 읽으세요.**

## 지금 쓰는 방법은 사람이 주소를 주는 쪽입니다

    ./scripts/opac-discover.py --worklist 30 > 작업목록.md    # 조사할 묶음을 표로 뽑기
    ./scripts/opac-discover.py --import-file 결과.txt         # 받은 주소를 CSV 로

`--worklist` 는 묶음마다 **그 도서관이 실제로 소장한 책**을 함께 적어 줍니다. 그 책으로
물어봐야 0건이 나왔을 때 「규칙이 틀렸다」고 말할 수 있습니다.

`--import-file` 은 받은 줄을 그대로 믿지 않고, 실행해 보지 않고도 잡을 수 있는 것을
거릅니다. **자리표가 없는 줄과 다른 기관 도메인으로 보내는 줄**이 특히 그렇습니다.

## 자동 조사(`--probe` `--limit` `--all`)는 반쪽입니다

    ./scripts/opac-discover.py --probe            # 검증용 데이터 만들기 (우리 API 를 씁니다)
    ./scripts/opac-discover.py --limit 30         # 큰 묶음부터 30개 조사
    ./scripts/opac-discover.py --all              # 전부. 중단해도 이어서 됩니다
    ./scripts/opac-discover.py --emit             # 통과한 규칙을 templates.csv 형식으로 출력

**2026-09-09 에 상위 30묶음을 실제로 돌려 규칙을 하나도 찾지 못했습니다.** 판정 로직은
멀쩡한데(가짜 OPAC 일곱 종류를 통과합니다) 실제 도서관에서 걸린 것은 다음 셋입니다.

  - **검색어가 경로에 들어가는 OPAC 을 못 찾습니다.** 노원은 `/KeywordSearchResult/{isbn13}`,
    부천은 `/search/keyword/{isbn13}` 인데, 자바스크립트가 폼 제출을 가로채 이 주소를 만듭니다.
    **HTML 어디에도 그 형태가 적혀 있지 않아 폼을 읽는 방식으로는 원리적으로 못 찾습니다.**
    사람은 주소창을 보고 5초면 압니다.
  - 국내 공공도서관 상당수가 **해외 IP 를 막습니다.** GitHub Actions 러너에서 30묶음 가운데
    18묶음이 접속조차 되지 않았습니다(대부분 타임아웃).
  - 접속된 곳도 검색 폼이 자바스크립트로 그려지거나 POST 라 주소에 검색어가 남지 않습니다.

그래서 **자동 조사에 기대지 마세요.** 국내에서 돌리면 접속 문제는 풀리지만 경로형은 여전히
못 찾습니다. 지금은 사람이 주소를 주고 `--import-file` 로 반영하는 쪽이 확실합니다.
"""

from __future__ import annotations

import argparse
import csv
import html
import json
import os
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

UA = "wimb-opac-check/1.0 (+https://github.com/LeeeeeJY/where-is-my-book)"
API = os.environ.get("WIMB_API", "https://where-is-my-book.duckdns.org")

# 검증에 쓰는 책. 널리 소장된 것으로 골랐고, 제목이 흔한 낱말이 아니어야 합니다.
# 「코스모스」처럼 짧은 말은 메뉴나 배너에 우연히 있을 수 있어 음성 대조가 그것을 잡습니다.
#
# **여기 적은 ISBN 과 제목이 실제로 같은 책인지 반드시 확인하세요.** 예전에 데미안 자리에
# `9788937460777` 을 적어 두었는데 그것은 조지 오웰 『1984』였습니다. 그러면 재확인 단계가
# 「데미안」이라는 글자를 찾는데 그 ISBN 은 1984 를 데려오므로, **규칙이 맞아도 그 책이 걸린
# 묶음은 무조건 실패합니다.** 2026-09-09 조사가 0/30 이었던 원인의 하나입니다.
# 확인은 `curl -s "$WIMB_API/api/search?isbn=<ISBN>"` 한 번이면 됩니다.
PROBE_BOOKS = {
    "9788937473135": "82년생 김지영",
    "9788996991342": "미움받을 용기",
    "9788936433598": "채식주의자",
    "9788937460449": "데미안",
    "9788983711892": "코스모스",
    "9788983920683": "해리 포터와 마법사의 돌",
}
DEFAULT_CSV = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                           "backend/src/main/resources/opac/templates.csv")

# 있을 수 없는 ISBN. 체크디지트까지 맞지 않아 어느 도서관에도 없습니다.
ABSENT_ISBN = "9799999999999"
ABSENT_TITLE = "존재하지않는제목갈매기수프변주"

NO_RESULT_HINTS = ("검색결과가 없", "검색 결과가 없", "자료가 없", "조회된 자료가 없",
                   "결과가 없습니다", "no result")

SEARCH_NAME_HINTS = ("searchkeyword", "keyword", "query", "searchword", "search_word",
                     "sw", "q", "kwd", "text1", "searchtxt", "search_text", "schword",
                     "searchvalue", "strkeyword", "search_key")


# ── 느리게, 한 호스트에 하나씩 ────────────────────────────────────────────────

class Pacer:
    """호스트마다 하나씩, 간격을 두고 내보냅니다."""

    def __init__(self, delay: float):
        self.delay = delay
        self._locks: dict[str, threading.Lock] = {}
        self._last: dict[str, float] = {}
        self._guard = threading.Lock()

    def lock_for(self, host: str) -> threading.Lock:
        with self._guard:
            return self._locks.setdefault(host, threading.Lock())

    def wait(self, host: str) -> None:
        last = self._last.get(host)
        if last is not None:
            gap = self.delay - (time.time() - last)
            if gap > 0:
                time.sleep(gap)
        self._last[host] = time.time()


def normalize_home(url: str | None) -> str | None:
    """도서관 홈페이지 주소를 쓸 수 있는 모양으로. 못 쓰면 None 입니다.

    **정보나루는 홈페이지가 없는 곳에 `-` 를 줍니다.** 그리고 스킴을 빼고 주는 곳도 있어서
    (`lib.yongin.go.kr/dongcheon`), 그대로 넘기면 urllib 이 「모르는 주소 유형」으로
    죽습니다. 실제로 그것 하나가 조사 전체를 멈춰 세웠습니다. 스킴만 빠진 것은 살리고
    (진짜 도서관 주소입니다) 나머지는 홈페이지가 없는 것으로 봅니다.
    """
    if not url:
        return None
    url = url.strip()
    if not url or url in ("-", "_", "없음", "N/A"):
        return None
    if not url.startswith(("http://", "https://")):
        # `://` 로만 찾으면 `mailto:` 처럼 슬래시 없는 스킴을 놓쳐 `http://mailto:...` 라는
        # 이상한 주소를 만듭니다. 콜론 뒤가 숫자면 스킴이 아니라 포트로 봅니다.
        if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:(?![0-9])", url):
            return None
        url = "http://" + url
    p = urllib.parse.urlparse(url)
    return url if p.netloc and "." in p.netloc else None


def fetch(url: str, pacer: Pacer, timeout: float = 20.0) -> tuple[int, str, str]:
    """(상태코드, 본문, 최종주소). 실패하면 상태코드가 0 이고 본문이 오류 문구입니다.

    **여기서 예외가 새어 나가면 안 됩니다.** 조사는 묶음 수백 개를 도는 일이라, 한 곳의
    이상한 주소로 전체가 죽으면 그때까지 두드린 남의 서버 요청이 전부 헛것이 됩니다.
    """
    host = urllib.parse.urlparse(url).netloc
    with pacer.lock_for(host):
        pacer.wait(host)
        try:
            req = urllib.request.Request(url, headers={
                "User-Agent": UA,
                "Accept": "text/html,application/xhtml+xml",
                "Accept-Language": "ko",
            })
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                raw = resp.read(1_500_000)
                return resp.status, decode(raw, resp.headers.get("Content-Type", "")), resp.url
        except urllib.error.HTTPError as e:
            try:
                raw = e.read(200_000)
            except Exception:
                raw = b""
            headers = e.headers.get("Content-Type", "") if e.headers else ""
            return e.code, decode(raw, headers), url
        except Exception as e:                       # 타임아웃, TLS, DNS 등
            return 0, f"{type(e).__name__}: {e}", url


def decode(raw: bytes, content_type: str) -> str:
    """한글 OPAC 은 EUC-KR 이 아직 남아 있어서 선언을 보고 고릅니다."""
    m = re.search(r"charset=([\w-]+)", content_type, re.I)
    if not m:
        head = raw[:4096].decode("ascii", "replace")
        m = re.search(r'charset=["\']?([\w-]+)', head, re.I)
    for enc in ([m.group(1)] if m else []) + ["utf-8", "euc-kr", "cp949"]:
        try:
            return raw.decode(enc, "strict")
        except (LookupError, UnicodeDecodeError):
            continue
    return raw.decode("utf-8", "replace")


# ── robots.txt ───────────────────────────────────────────────────────────────

def robots_disallows(origin: str, pacer: Pacer) -> list[str]:
    """User-agent: * 에 걸린 Disallow 경로. 받지 못하면 제한이 없는 것으로 봅니다."""
    status, body, _ = fetch(urllib.parse.urljoin(origin, "/robots.txt"), pacer, timeout=10)
    if status != 200 or "<html" in body[:200].lower():
        return []
    out, applies = [], False
    for line in body.splitlines():
        line = line.split("#", 1)[0].strip()
        if not line:
            continue
        key, _, value = line.partition(":")
        key, value = key.strip().lower(), value.strip()
        if key == "user-agent":
            applies = value == "*"
        elif key == "disallow" and applies and value:
            out.append(value)
    return out


def blocked(url: str, disallows: list[str]) -> bool:
    path = urllib.parse.urlparse(url).path or "/"
    return any(path.startswith(rule) for rule in disallows)


# ── 검색 폼 찾기 ─────────────────────────────────────────────────────────────

def attr_of(attrs: str, key: str) -> str | None:
    m = re.search(rf'\b{key}\s*=\s*"([^"]*)"', attrs, re.I) or \
        re.search(rf"\b{key}\s*=\s*'([^']*)'", attrs, re.I) or \
        re.search(rf"\b{key}\s*=\s*([^\s>]+)", attrs, re.I)
    return m.group(1) if m else None


def search_forms(page: str, base: str) -> list[tuple[str, str, dict[str, str]]]:
    """(action, 검색어 필드 이름, 함께 보낼 hidden 값들).

    **폼을 읽는 것이 핵심입니다.** 벤더별 주소 패턴을 외워서 넣는 것은 짐작이지만, 그
    사이트가 자기 HTML 로 「이 주소에 이 이름으로 보내라」고 적어 둔 것을 그대로 쓰는 것은
    짐작이 아닙니다. method 가 POST 인 폼은 주소에 검색어가 남지 않아 규칙을 만들 수
    없으므로 버립니다.
    """
    out = []
    for m in re.finditer(r"<form\b([^>]*)>(.*?)</form>", page, re.I | re.S):
        attrs, inner = m.group(1), m.group(2)
        method = attr_of(attrs, "method") or "get"
        if method.lower() != "get":
            continue
        action = html.unescape(attr_of(attrs, "action") or "")
        url = urllib.parse.urljoin(base, action) if action else base
        if not url.startswith(("http://", "https://")):
            continue

        text_names, hidden = [], {}
        for tag in re.finditer(r"<input\b([^>]*)>", inner, re.I):
            a = tag.group(1)
            name = attr_of(a, "name")
            if not name:
                continue
            kind = (attr_of(a, "type") or "text").lower()
            if kind == "hidden":
                hidden[name] = html.unescape(attr_of(a, "value") or "")
            elif kind in ("text", "search"):
                text_names.append(name)
        if not text_names:
            continue
        # 검색어 칸으로 보이는 이름을 앞세우되, 확신이 없으면 있는 것을 몇 개 시도합니다.
        text_names.sort(key=lambda n: (0 if n.lower() in SEARCH_NAME_HINTS else
                                       1 if any(h in n.lower() for h in SEARCH_NAME_HINTS) else 2))
        for name in text_names[:3]:
            out.append((url, name, hidden))
    return out


SEARCH_LINK_HINTS = ("자료검색", "통합검색", "소장자료", "도서검색", "자료찾기", "장서검색",
                     "책검색", "소장검색", "opac", "search")


def search_page_links(page: str, base: str, limit: int = 3) -> list[str]:
    """홈페이지에서 자료검색 페이지로 가는 링크.

    **검색창이 홈페이지에 없는 도서관이 많습니다.** 「자료검색」 메뉴를 눌러야 폼이 나오는
    구조인데, 홈페이지만 보고 포기하면 그런 곳이 전부 「폼이 없다」로 떨어집니다. 한 단계만
    따라갑니다. 더 들어가면 그 서버에 보내는 요청이 빠르게 불어납니다.
    """
    seen, out = set(), []
    for m in re.finditer(r"<a\b([^>]*)>(.*?)</a>", page, re.I | re.S):
        href = attr_of(m.group(1), "href")
        if not href or href.startswith(("#", "javascript:", "mailto:")):
            continue
        label = squash(re.sub(r"<[^>]+>", " ", m.group(2)))
        haystack = (label + " " + href).lower()
        if not any(h in haystack for h in SEARCH_LINK_HINTS):
            continue
        url = urllib.parse.urljoin(base, html.unescape(href))
        if not url.startswith(("http://", "https://")) or url in seen:
            continue
        # 홈페이지와 다른 호스트로 나가는 링크는 그 도서관 것이 아닐 수 있습니다.
        if urllib.parse.urlparse(url).netloc != urllib.parse.urlparse(base).netloc:
            continue
        seen.add(url)
        out.append(url)
        if len(out) >= limit:
            break
    return out


def build_url(action: str, field: str, hidden: dict[str, str], value: str, encoding: str) -> str:
    """자리표를 넣기 전의 실제 주소. 폼의 hidden 값을 그대로 함께 보냅니다."""
    parsed = urllib.parse.urlparse(action)
    params = dict(urllib.parse.parse_qsl(parsed.query, keep_blank_values=True))
    params.update({k: v for k, v in hidden.items() if k != field})
    params[field] = value
    query = urllib.parse.urlencode(params, encoding=encoding, errors="replace")
    return urllib.parse.urlunparse(parsed._replace(query=query))


# ── 검증 ─────────────────────────────────────────────────────────────────────

def squash(text: str) -> str:
    return re.sub(r"[\s​·・,]+", "", text)


def page_has(page: str, title: str) -> bool:
    """본문에 그 책 제목이 있는지. 태그가 제목 사이에 끼어드는 경우가 있어 공백과
    구두점을 지우고 견줍니다."""
    return squash(title) in squash(re.sub(r"<[^>]+>", " ", page))


def says_no_result(page: str) -> bool:
    text = squash(re.sub(r"<[^>]+>", " ", page))
    return any(squash(h) in text for h in NO_RESULT_HINTS)


def verify(action: str, field: str, hidden: dict[str, str], kind: str,
           positives: list[tuple[str, str]], pacer: Pacer,
           encoding: str = "utf-8") -> dict | None:
    """세 겹 검증. 통과하면 증거를 담은 사전을, 아니면 None 을 돌려줍니다.

    positives 는 (넣을 값, 본문에서 찾을 제목) 쌍이고, ISBN 검색이면 값이 ISBN,
    제목 검색이면 값이 제목입니다.
    """
    if len(positives) < 2:
        return None
    evidence = {"kind": kind, "field": field, "encoding": encoding, "checks": []}

    # 1) 양성 — 그 도서관이 실제로 가진 책
    value, title = positives[0]
    url = build_url(action, field, hidden, value, encoding)
    status, page, _ = fetch(url, pacer)
    hit = status == 200 and page_has(page, title) and not says_no_result(page)
    evidence["checks"].append({"step": "양성", "url": url, "status": status,
                               "expect": title, "found": hit})
    if not hit:
        return None

    # 2) 음성 — 검색어를 무시하고 전체를 뿌리는 곳을 걸러 냅니다
    absent = ABSENT_ISBN if kind != "TITLE_SEARCH" else ABSENT_TITLE
    url = build_url(action, field, hidden, absent, encoding)
    status, page, _ = fetch(url, pacer)
    leaked = status == 200 and page_has(page, title)
    evidence["checks"].append({"step": "음성", "url": url, "status": status,
                               "must_not_find": title, "leaked": leaked})
    if leaked:
        return None

    # 3) 재확인 — 첫 번째만 우연히 맞는 경우가 있습니다
    value, title = positives[1]
    url = build_url(action, field, hidden, value, encoding)
    status, page, _ = fetch(url, pacer)
    hit = status == 200 and page_has(page, title) and not says_no_result(page)
    evidence["checks"].append({"step": "재확인", "url": url, "status": status,
                               "expect": title, "found": hit})
    if not hit:
        return None

    placeholder = "{title}" if kind == "TITLE_SEARCH" else "{isbn13}"
    template = build_url(action, field, hidden, placeholder, encoding)
    for quoted in (urllib.parse.quote(placeholder, safe=""),
                   urllib.parse.quote_plus(placeholder)):
        template = template.replace(quoted, placeholder)
    evidence["template"] = template
    return evidence if placeholder in template else None


# ── 묶음 ─────────────────────────────────────────────────────────────────────

def group_key(url: str) -> str:
    url = normalize_home(url) or url
    p = urllib.parse.urlparse(url)
    segs = [s for s in p.path.split("/") if s and "." not in s]
    return p.netloc.lower() + ("/" + segs[0] if segs else "")


# 한국 도메인은 2단계 접미사가 흔해서(`lib.gwe.go.kr`) 뒤의 두 조각만 보면 `go.kr` 이 되어
# 서로 다른 기관이 같은 도메인으로 읽힙니다. 이 목록에 걸리면 한 조각 더 봅니다.
SECOND_LEVEL_KR = {"go", "or", "co", "re", "ac", "ne", "pe", "hs", "ms", "es", "sc", "kg"}


def registrable_domain(host: str) -> str:
    """그 호스트가 어느 기관 것인지. `www.` 와 `search.` 같은 앞머리를 흡수합니다.

    OPAC 이 홈페이지와 다른 서브도메인에 있는 것은 정당합니다(`lib.x.kr` 의 검색이
    `search.x.kr` 에 있는 식). 그래서 호스트가 정확히 같은지가 아니라 **같은 기관인지**를
    봅니다. 그러면서도 아무 관계 없는 남의 도메인은 그대로 걸립니다.
    """
    parts = [p for p in host.lower().split(":")[0].split(".") if p]
    if len(parts) <= 2:
        return ".".join(parts)
    if parts[-1] == "kr" and parts[-2] in SECOND_LEVEL_KR:
        return ".".join(parts[-3:])
    return ".".join(parts[-2:])


def investigate(group: dict, probe: dict, pacer: Pacer) -> dict:
    """묶음 하나를 조사합니다. 예외가 새어 나가지 않게 감쌉니다.

    **수백 묶음을 도는 일이라 한 곳의 예외로 전체가 죽으면 안 됩니다.** 실제로 홈페이지
    주소에 스킴이 빠진 도서관 하나가 조사 전체를 멈춰 세웠고, 그때까지 두드린 남의 서버
    요청이 전부 헛것이 되었습니다.
    """
    try:
        return _investigate(group, probe, pacer)
    except Exception as e:                              # 어떤 것이든 그 묶음에서 멈춥니다
        rep = group["libraries"][0]
        return {"group": group["key"], "libCodes": [l["libCode"] for l in group["libraries"]],
                "name": rep["name"], "homepage": rep.get("homepageUrl"), "rules": [],
                "note": f"조사 중 오류가 났습니다 ({type(e).__name__}: {e})"[:200]}


def _investigate(group: dict, probe: dict, pacer: Pacer) -> dict:
    """대표 도서관의 홈페이지에서 폼을 읽고 검증합니다."""
    rep = pick_representative(group["libraries"], probe)
    result = {"group": group["key"], "libCodes": [l["libCode"] for l in group["libraries"]],
              "name": rep["name"], "homepage": rep["homepageUrl"], "rules": [], "note": ""}

    owned = [(i, PROBE_BOOKS[i]) for i in probe.get(rep["libCode"], []) if i in PROBE_BOOKS]
    if len(owned) < 2:
        result["note"] = "검증용으로 쓸 소장 도서가 두 권이 안 됩니다"
        return result

    home = normalize_home(rep["homepageUrl"])
    if not home:
        result["note"] = f"쓸 수 있는 홈페이지 주소가 아닙니다 ({rep['homepageUrl']!r})"
        return result
    parsed = urllib.parse.urlparse(home)
    disallows = robots_disallows(f"{parsed.scheme}://{parsed.netloc}", pacer)
    status, page, final = fetch(home, pacer)
    if status != 200:
        result["note"] = f"홈페이지를 받지 못했습니다 ({status}: {page[:60]})"
        return result

    titles = [(t, t) for _, t in owned]
    tried = 0

    def try_forms(forms: list[tuple[str, str, dict[str, str]]]) -> bool:
        nonlocal tried
        for action, field, hidden in forms:
            if blocked(action, disallows) or tried >= 5:
                continue
            tried += 1
            found = verify(action, field, hidden, "ISBN_SEARCH", owned, pacer)
            if found:
                result["rules"].append(found)
                return True
            # ISBN 이 안 되면 제목으로. 한글이라 인코딩을 둘 다 봅니다. 여기서 인코딩을
            # 잘못 고르면 그 도서관은 200 을 주면서 결과만 0건이 됩니다.
            for enc in ("utf-8", "euc-kr"):
                found = verify(action, field, hidden, "TITLE_SEARCH", titles, pacer, encoding=enc)
                if found:
                    result["rules"].append(found)
                    return True
        return False

    forms = search_forms(page, final)
    if try_forms(forms):
        return result

    # 홈페이지에 검색창이 없거나 그것으로 안 되면 자료검색 페이지로 한 단계 들어갑니다.
    visited = 0
    for link in search_page_links(page, final):
        if blocked(link, disallows) or tried >= 5:
            break
        status, sub, sub_final = fetch(link, pacer)
        if status != 200:
            continue
        visited += 1
        sub_forms = search_forms(sub, sub_final)
        forms += sub_forms
        if try_forms(sub_forms):
            return result

    if not forms:
        result["note"] = ("GET 으로 보내는 검색 폼이 없습니다 "
                          f"(POST 검색이거나 자바스크립트, 검색 페이지 {visited}곳까지 봤습니다)")
    else:
        result["note"] = (f"폼 {len(forms)}개를 시도했지만 응답에서 그 책을 확인하지 "
                          f"못했습니다 (검색 페이지 {visited}곳 포함)")
    return result


# ── 데이터 ───────────────────────────────────────────────────────────────────

def api_json(path: str, payload=None, timeout=300):
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(API + path, data=data,
                                 headers={"Content-Type": "application/json", "User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.load(r)


def load_libraries(work: str) -> list[dict]:
    path = os.path.join(work, "libraries.json")
    if not os.path.exists(path):
        json.dump(api_json("/api/libraries"), open(path, "w"), ensure_ascii=False)
    return json.load(open(path))


def build_probe(work: str, libs: list[dict]) -> dict:
    """도서관마다 「확실히 소장한 책」 목록. 정보나루가 답한 것이라 짐작이 아닙니다."""
    codes = [l["libCode"] for l in libs]
    owned: dict[str, list[str]] = defaultdict(list)
    for isbn, title in PROBE_BOOKS.items():
        r = api_json("/api/holdings", {"isbn13List": [isbn], "libs": codes})
        for code in r["libCodes"]:
            owned[code].append(isbn)
        print(f"  {title:24s} {len(r['libCodes']):5d}곳", file=sys.stderr)
    json.dump(owned, open(os.path.join(work, "probe.json"), "w"))
    return owned


def summary(results_path: str) -> int:
    """지금까지 조사한 것의 성적표. 실패 사유별로 세어 어디를 손봐야 하는지 보입니다.

    **성공률이 낮을 때 사유를 갈라 보는 것이 중요합니다.** 폼을 못 찾은 것과 폼은 찾았는데
    검증에서 떨어진 것은 손볼 곳이 다릅니다. 앞은 검색 페이지를 더 따라가야 하는 것이고,
    뒤는 그 OPAC 이 주소로 검색어를 받지 않는다는 뜻이라 애초에 규칙을 만들 수 없습니다.
    """
    if not os.path.exists(results_path):
        print("조사 결과가 없습니다.", file=sys.stderr)
        return 1
    rows = [json.loads(l) for l in open(results_path) if l.strip()]
    ok = [r for r in rows if r["rules"]]
    covered = sum(len(r["libCodes"]) for r in ok)
    total_libs = sum(len(r["libCodes"]) for r in rows)
    print(f"묶음 {len(ok)}/{len(rows)} 에서 규칙을 찾았습니다 "
          f"({len(ok) * 100 // max(len(rows), 1)}%)")
    print(f"도서관 {covered}/{total_libs} 곳이 책 페이지로 갑니다")
    kinds: dict[str, int] = defaultdict(int)
    for r in ok:
        kinds[r["rules"][0]["kind"]] += 1
    for k, n in sorted(kinds.items(), key=lambda kv: -kv[1]):
        print(f"  {k:14s} {n}묶음")
    print("\n못 찾은 사유")
    notes: dict[str, int] = defaultdict(int)
    for r in rows:
        if r["rules"]:
            continue
        note = r["note"]
        for pattern in ("GET 으로 보내는 검색 폼이 없습니다", "응답에서 그 책을 확인하지 못했습니다",
                        "홈페이지를 받지 못했습니다", "검증용으로 쓸 소장 도서가"):
            if pattern in note:
                note = pattern
                break
        notes[note] += 1
    for note, n in sorted(notes.items(), key=lambda kv: -kv[1])[:10]:
        print(f"  {n:4d}묶음  {note[:64]}")
    return 0


def gaps(csv_path: str, libs: list[dict]) -> int:
    """규칙이 있는 기관인데 규칙을 못 받은 도서관을 셉니다.

    **묶음키가 홈페이지의 첫 경로 조각까지 보기 때문에 한 시스템이 갈립니다.** 분관이
    저마다 `.../jungang`, `.../wolgye` 를 쓰면 서로 다른 묶음이 되고, 사람은 대표 한 곳만
    조사해 주소를 주므로 **나머지가 조용히 빠집니다.** 빠진 쪽이 하필 큰 도서관입니다.
    작은도서관은 대개 홈페이지가 시스템 최상위라 한 묶음에 뭉쳐 있고, 분관 페이지를 따로
    가진 곳이 사람이 실제로 가는 도서관이기 때문입니다.

    실제로 노원은 작은도서관 스물일곱 곳이 책 검색으로 가는 동안 노원중앙·상계·불암·화랑을
    비롯한 여덟 곳이 홈페이지로 갔습니다. **화면에는 아무 이상이 없어 보입니다.** 소장
    목록에 이름은 나오고 눌리기도 하니, 도착한 곳이 홈페이지라는 것은 눌러 본 사람만
    압니다.

    여기서 세는 것은 「넓히면 된다」가 아니라 **「확인할 곳이 여기 있다」**입니다. 분관마다
    장서가 따로인 시스템도 있어서(안산의 지금 규칙은 작은도서관 전용 검색입니다) 넓히려면
    검색 결과 화면에 그 분관 이름이 실제로 보이는지 봐야 합니다.
    """
    have: set[str] = set()
    for row in csv.reader(open(csv_path, encoding="utf-8")):
        if row and not row[0].lstrip().startswith("#") and len(row) >= 4:
            have.add(row[0].strip())

    by_org: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        home = normalize_home(l.get("homepageUrl"))
        if home:
            by_org[registrable_domain(urllib.parse.urlparse(home).netloc)].append(l)

    missing = 0
    for org, members in sorted(by_org.items(), key=lambda kv: -len(kv[1])):
        without = [l for l in members if l["libCode"] not in have]
        if not without or len(without) == len(members):
            continue                                    # 규칙이 아예 없는 기관은 조사 대상
        print(f"\n[{org}]  규칙 있음 {len(members) - len(without)}곳 · 빠짐 {len(without)}곳")
        for l in sorted(without, key=lambda x: x["name"]):
            print(f"   {l['libCode']}  {l['name']}  →  {l.get('homepageUrl')}")
        missing += len(without)
    print(f"\n같은 기관에 규칙이 있는데 빠진 도서관 {missing}곳", file=sys.stderr)
    return 0


def emit(results_path: str) -> int:
    if not os.path.exists(results_path):
        print("조사 결과가 없습니다. 먼저 --limit 이나 --all 로 도세요.", file=sys.stderr)
        return 1
    writer = csv.writer(sys.stdout, lineterminator="\n")
    covered = 0
    for line in open(results_path):
        r = json.loads(line)
        for rule in r["rules"]:
            for code in r["libCodes"]:
                writer.writerow([code, rule["kind"], rule["encoding"].upper(), rule["template"]])
                covered += 1
    print(f"# 도서관 {covered}곳", file=sys.stderr)
    return 0


WORKLIST_HEAD = """# OPAC 주소 규칙 조사 — 작업 목록 (묶음 {n}개, 도서관 {covered}곳)

아래 묶음을 조사해 주세요. **한 번에 열 개씩 나눠서** 하시고, 열 개가 끝날 때마다 결과를
내주세요.

## 묶음마다 할 일

1. 「홈페이지」를 엽니다.
2. 자료검색 칸에 **「소장 확인용」의 ISBN** 을 넣고 검색합니다. 그 도서관이 실제로 소장한
   책이라 규칙만 맞으면 반드시 나옵니다.
3. 결과가 나온 화면의 **주소를 통째로 복사**하고, ISBN 자리를 `{{isbn13}}` 으로 바꿉니다.
4. **바꾼 주소를 다시 엽니다.** 자리표에 「재확인용」 ISBN 을 넣고 그 책이 나오는지 봅니다.
5. 한 번 더, 자리표에 **`9799999999999`** 를 넣고 엽니다. 있을 수 없는 ISBN 이라
   **아무것도 안 나와야 정상**입니다. 여기서 책 목록이 나오면 그 OPAC 은 검색어를 무시하는
   것이므로 **그 규칙은 버립니다.**

세 번을 다 통과한 것만 적어 주세요.

## 지킬 것

- **열어 보지 않은 주소는 절대 적지 마세요.** 「아마 이런 형식일 것」은 쓸 수 없습니다.
  틀린 규칙은 링크가 깨지는 것이 아니라 **200 을 주면서 결과만 0건**이 되어, 쓰는 사람에게는
  「소장한다더니 그 책이 없네」로 보이고 눈에 띄지도 않습니다. **못 찾은 것은 못 찾았다고
  적는 편이 훨씬 낫습니다.**
- ISBN 으로 안 되면 제목(「소장 확인용」의 책 제목)으로 시도하고, 제목 자리를 `{{title}}` 로
  바꿉니다. 주소창의 한글이 `%C4%DA...` 로 보이면 인코딩은 `EUC-KR`, `%EC%BD%94...` 로
  보이면 `UTF-8` 입니다.
- 검색 결과에서 책을 눌러 들어간 주소에 ISBN 이 있으면 그것이 더 좋습니다(`ISBN_DETAIL`).
  내부 등록번호를 쓰면 책마다 달라 규칙이 못 되니 적지 마세요.
- 주소는 자르지 말고 파라미터를 그대로 두세요. 검색 조건일 때가 있습니다.
- **다른 기관의 주소를 적으면 걸러집니다.** 규칙은 그 도서관 도메인 안에 있어야 합니다.

## 결과 형식

묶음마다 한 줄씩 주세요. 못 찾은 것도 사유와 함께 남겨 주세요.

```
묶음키 | ISBN_SEARCH | UTF-8 | https://.../search?q={{isbn13}} | 재확인 통과, 빈검색 0건
묶음키 | 실패 | - | - | POST 검색이라 주소에 검색어가 안 남습니다
```

---
"""


def pick_representative(members: list[dict], probe: dict) -> dict:
    """묶음을 대표할 도서관. **작은도서관을 앞에 세우지 않습니다.**

    검증용 책을 많이 가진 곳으로만 고르면 대표가 죄다 작은도서관이 됩니다. 그런데 정보나루가
    「그 작은도서관이 소장한다」고 답해도 **시립 통합 OPAC 에는 그 자료가 올라가 있지 않은
    일이 있습니다.** 그러면 규칙이 맞아도 0건이 나와 실패로 읽힙니다. 실제로 2026-09-10 조사에서
    「검색은 동작하나 시험 도서 두 권 모두 0건」인 10묶음(60곳)이 전부 이 경우였습니다
    (`www.l4d.or.kr/small`, `mplib.mapo.go.kr/libsmall`, 유성·동작·관악…).

    그래서 이름에 「작은도서관」이나 「문고」가 없는 곳을 먼저 세우고, 그 안에서 검증용 책을
    많이 가진 곳을 고릅니다. 묶음이 작은도서관뿐이면 어쩔 수 없이 그중에서 고릅니다.
    """
    def rank(lib: dict) -> tuple:
        name = lib.get("name", "")
        small = 1 if ("작은도서관" in name or "문고" in name) else 0
        return (small, -len(probe.get(lib["libCode"], [])), name)

    return min(members, key=rank)


def worklist(libs: list[dict], probe: dict, count: int) -> int:
    """조사할 묶음 목록을 사람이 읽을 수 있게 뽑습니다.

    **검증용 책을 두 권 이상 가진 도서관을 대표로 세웁니다.** 그 도서관이 소장한 책으로
    물어봐야 0건이 나왔을 때 「규칙이 틀렸다」고 말할 수 있습니다. 아무 책이나 쓰면 규칙이
    맞는데도 실패로 읽습니다.
    """
    groups: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        if normalize_home(l.get("homepageUrl")):
            groups[group_key(l["homepageUrl"])].append(l)

    rows = []
    for key, members in sorted(groups.items(), key=lambda kv: -len(kv[1])):
        rep = pick_representative(members, probe)
        books = [i for i in probe.get(rep["libCode"], []) if i in PROBE_BOOKS][:3]
        if len(books) < 2:
            continue
        rows.append((key, members, rep, books))
        if len(rows) >= count:
            break

    print(WORKLIST_HEAD.format(n=len(rows), covered=sum(len(m) for _, m, _, _ in rows)))
    for i, (key, members, rep, books) in enumerate(rows, 1):
        if i % 10 == 1:
            print(f"## {i}~{min(i + 9, len(rows))}번\n")
        print(f"### {i}. `{key}` — {len(members)}곳\n")
        print(f"- 대표 도서관: **{rep['name']}** ({rep.get('sido', '')} {rep.get('sigungu', '')})")
        print(f"- 홈페이지: {rep['homepageUrl']}")
        print(f"- 소장 확인용: `{books[0]}` 「{PROBE_BOOKS[books[0]]}」")
        print(f"- 재확인용: `{books[1]}` 「{PROBE_BOOKS[books[1]]}」")
        print("- 빈 검색용: `9799999999999` (아무것도 안 나와야 합니다)\n")
    return 0


def import_findings(path: str, libs: list[dict]) -> int:
    """사람이나 다른 도구가 조사해 온 결과를 읽어 CSV 로 바꿉니다.

    한 줄이 묶음 하나입니다. `묶음키 | 종류 | 인코딩 | 주소 | 메모` 형식이고, 「실패」로
    적힌 줄과 `#` 로 시작하는 줄은 건너뜁니다.

    **묶음키 뒤에 `*` 를 붙이면 같은 기관의 도서관 전체로 넓힙니다.** 묶음키는 홈페이지의
    호스트와 첫 경로 조각이라, 분관마다 경로가 다른 곳은 한 시스템인데도 묶음이 갈립니다
    (`www.nowonlib.kr` 과 `nowonlib.kr/jungang`). 그런 곳은 대개 **분관들의 검색이 한
    화면에 있는데**, 갈린 채로 두면 정작 사람이 많이 가는 큰 도서관만 규칙을 못 받습니다.
    실제로 노원은 작은도서관 스물일곱 곳에 규칙이 들어가고 노원중앙·상계·화랑을 비롯한
    여덟 곳이 빠져 있었습니다.

    **그래도 기본은 좁은 쪽입니다.** 분관마다 장서가 따로인 시스템에서 넓히면 **어느 책을
    눌러도 남의 도서관 목록이 뜹니다.** 안산이 그런 경우인데, 지금 있는 규칙이
    `lib.ansan.go.kr/smalllib/...` 라 작은도서관 전용 검색입니다. 그래서 `*` 는 검색 결과
    화면에 **그 분관들의 이름이 실제로 보이는 것을 확인한 사람만** 붙입니다.

    **여기서 거르는 것이 마지막 방어선입니다.** 조사한 쪽이 열어 보지 않고 그럴듯한 주소를
    적어 왔을 때, 실행해 보지 않고도 잡을 수 있는 것이 몇 가지 있습니다. 그 가운데 가장
    잘 걸리는 것이 **호스트가 그 도서관 홈페이지와 다른 경우**입니다. 형식만 맞는 주소는
    서버가 뜰 때 통과해 버리고, 실제로 눌러 보기 전에는 아무도 모릅니다.
    """
    groups: dict[str, list[dict]] = defaultdict(list)
    by_org: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        home = normalize_home(l.get("homepageUrl"))
        if not home:
            continue
        groups[group_key(l["homepageUrl"])].append(l)
        by_org[registrable_domain(urllib.parse.urlparse(home).netloc)].append(l)
    # 묶음키를 옮겨 적을 때 `www.` 가 붙고 빠지는 것으로 전부 버려지면 안 됩니다.
    aliases = {k.removeprefix("www."): k for k in groups}

    kinds = {"ISBN_DETAIL", "ISBN_SEARCH", "TITLE_SEARCH"}
    writer = csv.writer(sys.stdout, lineterminator="\n")
    problems, taken, covered = [], 0, 0
    seen: dict[tuple[str, str], str] = {}
    for lineno, raw in enumerate(open(path, encoding="utf-8"), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = [p.strip() for p in line.split("|")]
        if len(parts) < 4:
            continue                                    # 표 머리글이나 설명 줄
        key, kind, encoding, url = parts[:4]
        if kind in ("실패", "-", "", "종류") or set(kind) <= {"-", ":", " "}:
            continue                                    # 못 찾은 줄, 표 머리글, 구분선
        where = f"{lineno}행 {key}"
        key = key.strip("`").lower()
        whole_org = key.endswith("*")
        key = key.removesuffix("*").strip()
        key = key if key in groups else aliases.get(key.removeprefix("www."), key)
        if key not in groups:
            problems.append(f"{where}: 모르는 묶음입니다")
            continue
        if kind not in kinds:
            problems.append(f"{where}: 모르는 종류 {kind}")
            continue
        try:
            "".encode(encoding)
        except LookupError:
            problems.append(f"{where}: 모르는 인코딩 {encoding}")
            continue
        needed = "{title}" if kind == "TITLE_SEARCH" else "{isbn13}"
        if needed not in url:
            problems.append(f"{where}: {needed} 자리표가 없습니다")
            continue
        if not url.startswith(("http://", "https://")):
            problems.append(f"{where}: 주소가 아닙니다 — {url[:40]}")
            continue

        # 남의 도메인으로 보내는 규칙은 받지 않습니다. 조사한 쪽이 열어 보지 않고 그럴듯한
        # 주소를 지어냈을 때 가장 잘 걸리는 것이 이것입니다. 서브도메인이 다른 것은
        # 정당하므로(검색이 별도 호스트에 있는 도서관이 있습니다) 기관 단위로 견줍니다.
        rule_host = urllib.parse.urlparse(url).netloc.lower()
        home_host = key.split("/")[0]
        if registrable_domain(rule_host) != registrable_domain(home_host):
            problems.append(f"{where}: 홈페이지({home_host})와 다른 기관의 주소입니다 "
                            f"({rule_host})")
            continue

        targets = by_org[registrable_domain(home_host)] if whole_org else groups[key]
        taken += 1
        for lib in targets:
            # 한 도서관이 두 줄에 걸리는 것은 `*` 를 붙이면 흔해집니다. 같은 주소면 조용히
            # 넘어가고, 다른 주소면 **어느 쪽이 맞는지 우리가 모르므로** 알립니다.
            # 한 도서관에 종류가 다른 줄을 여럿 두는 것은 정상입니다(상세가 안 열릴 때
            # 검색 결과로 내려갑니다). 막아야 하는 것은 **같은 자리를 두 주소가 다투는**
            # 경우라, 열쇠를 (도서관, 종류)로 잡습니다.
            slot = (lib["libCode"], kind)
            before = seen.get(slot)
            if before is not None:
                if before != url:
                    problems.append(f"{where}: {lib['name']}({lib['libCode']}) 의 {kind} 가 "
                                    f"이미 다른 주소로 잡혀 있습니다 — {before[:60]}")
                continue
            seen[slot] = url
            writer.writerow([lib["libCode"], kind, encoding.upper(), url])
            covered += 1

    print(f"# 묶음 {taken}개 → 도서관 {covered}곳", file=sys.stderr)
    for p in problems:
        print(f"  버림  {p}", file=sys.stderr)
    return 1 if problems and taken == 0 else 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=".opac-work")
    ap.add_argument("--import-file", help="조사해 온 결과를 읽어 CSV 로 바꿉니다")
    ap.add_argument("--worklist", type=int, metavar="N",
                    help="사람이나 다른 클로드가 조사할 묶음 N개를 표로 뽑습니다")
    ap.add_argument("--probe", action="store_true", help="검증용 소장 데이터를 새로 만듭니다")
    ap.add_argument("--limit", type=int, default=0, help="큰 묶음부터 이만큼만 조사합니다")
    ap.add_argument("--all", action="store_true", help="남은 묶음을 전부 조사합니다")
    ap.add_argument("--emit", action="store_true", help="통과한 규칙을 CSV 형식으로 출력합니다")
    ap.add_argument("--summary", action="store_true", help="지금까지의 성적표와 실패 사유")
    ap.add_argument("--gaps", metavar="CSV", nargs="?", const=DEFAULT_CSV,
                    help="규칙이 있는 기관인데 규칙을 못 받은 도서관을 셉니다")
    ap.add_argument("--concurrency", type=int, default=4, help="서로 다른 호스트를 몇 개씩 겹칠지")
    ap.add_argument("--host-delay", type=float, default=3.0, help="같은 호스트의 요청 간격(초)")
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    results_path = os.path.join(args.work, "results.jsonl")
    if args.emit:
        return emit(results_path)
    if args.summary:
        return summary(results_path)
    if args.gaps:
        return gaps(args.gaps, load_libraries(args.work))
    if args.import_file:
        return import_findings(args.import_file, load_libraries(args.work))

    libs = load_libraries(args.work)
    probe_path = os.path.join(args.work, "probe.json")
    if args.probe or not os.path.exists(probe_path):
        print("검증용 소장 데이터를 만듭니다 (우리 API 를 씁니다)", file=sys.stderr)
        probe = build_probe(args.work, libs)
    else:
        probe = json.load(open(probe_path))

    if args.worklist:
        return worklist(libs, probe, args.worklist)

    groups: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        if normalize_home(l.get("homepageUrl")):
            groups[group_key(l["homepageUrl"])].append(l)
    # 검증용 책을 많이 가진 도서관을 대표로 세웁니다. 못 고르면 조사 자체가 안 됩니다.
    ordered = sorted(
        ({"key": k, "libraries": sorted(v, key=lambda l: -len(probe.get(l["libCode"], [])))}
         for k, v in groups.items()),
        key=lambda g: -len(g["libraries"]))

    done = set()
    if os.path.exists(results_path):
        for line in open(results_path):
            try:
                done.add(json.loads(line)["group"])
            except Exception:
                pass
    todo = [g for g in ordered if g["key"] not in done]
    if args.limit:
        todo = todo[:args.limit]
    elif not args.all:
        print(f"묶음 {len(ordered)}개 가운데 {len(todo)}개가 남았습니다. "
              f"--limit 30 으로 먼저 재 보거나 --all 로 전부 도세요.", file=sys.stderr)
        return 0

    print(f"{len(todo)}개 묶음을 조사합니다 (호스트 간격 {args.host_delay}초, "
          f"동시 {args.concurrency}개)", file=sys.stderr)
    pacer = Pacer(args.host_delay)
    lock = threading.Lock()
    ok = 0
    with open(results_path, "a") as out, ThreadPoolExecutor(max_workers=args.concurrency) as ex:
        for i, r in enumerate(ex.map(lambda g: investigate(g, probe, pacer), todo), 1):
            with lock:
                out.write(json.dumps(r, ensure_ascii=False) + "\n")
                out.flush()
            if r["rules"]:
                ok += 1
                print(f"  [{i}/{len(todo)}] ✓ {r['name'][:20]:22s} {len(r['libCodes']):4d}곳  "
                      f"{r['rules'][0]['kind']}", file=sys.stderr)
            else:
                print(f"  [{i}/{len(todo)}] · {r['name'][:20]:22s} {r['note'][:44]}",
                      file=sys.stderr)
    print(f"\n{ok}/{len(todo)} 묶음에서 규칙을 찾았습니다. --emit 으로 CSV 를 뽑으세요.",
          file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

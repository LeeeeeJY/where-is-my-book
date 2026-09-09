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

## 사용법

    ./scripts/opac-discover.py --probe            # 검증용 데이터 만들기 (우리 API 를 씁니다)
    ./scripts/opac-discover.py --limit 30         # 큰 묶음부터 30개 조사 (먼저 이걸로 재 보세요)
    ./scripts/opac-discover.py --all              # 전부. 오래 걸리고 중단해도 이어서 됩니다
    ./scripts/opac-discover.py --emit             # 통과한 규칙을 templates.csv 형식으로 출력

작업 파일은 --work (기본 .opac-work/) 아래에 쌓이고, 이미 조사한 묶음은 건너뜁니다.
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
PROBE_BOOKS = {
    "9788937473135": "82년생 김지영",
    "9788996991342": "미움받을 용기",
    "9788936433598": "채식주의자",
    "9788937460777": "데미안",
    "9788983711892": "코스모스",
    "9788983920683": "해리 포터와 마법사의 돌",
}
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


def fetch(url: str, pacer: Pacer, timeout: float = 20.0) -> tuple[int, str, str]:
    """(상태코드, 본문, 최종주소). 실패하면 상태코드가 0 이고 본문이 오류 문구입니다."""
    host = urllib.parse.urlparse(url).netloc
    with pacer.lock_for(host):
        pacer.wait(host)
        req = urllib.request.Request(url, headers={
            "User-Agent": UA,
            "Accept": "text/html,application/xhtml+xml",
            "Accept-Language": "ko",
        })
        try:
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
    p = urllib.parse.urlparse(url)
    segs = [s for s in p.path.split("/") if s and "." not in s]
    return p.netloc.lower() + ("/" + segs[0] if segs else "")


def investigate(group: dict, probe: dict, pacer: Pacer) -> dict:
    """묶음 하나를 조사합니다. 대표 도서관의 홈페이지에서 폼을 읽고 검증합니다."""
    rep = group["libraries"][0]
    result = {"group": group["key"], "libCodes": [l["libCode"] for l in group["libraries"]],
              "name": rep["name"], "homepage": rep["homepageUrl"], "rules": [], "note": ""}

    owned = [(i, PROBE_BOOKS[i]) for i in probe.get(rep["libCode"], []) if i in PROBE_BOOKS]
    if len(owned) < 2:
        result["note"] = "검증용으로 쓸 소장 도서가 두 권이 안 됩니다"
        return result

    parsed = urllib.parse.urlparse(rep["homepageUrl"])
    disallows = robots_disallows(f"{parsed.scheme}://{parsed.netloc}", pacer)
    status, page, final = fetch(rep["homepageUrl"], pacer)
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


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=".opac-work")
    ap.add_argument("--probe", action="store_true", help="검증용 소장 데이터를 새로 만듭니다")
    ap.add_argument("--limit", type=int, default=0, help="큰 묶음부터 이만큼만 조사합니다")
    ap.add_argument("--all", action="store_true", help="남은 묶음을 전부 조사합니다")
    ap.add_argument("--emit", action="store_true", help="통과한 규칙을 CSV 형식으로 출력합니다")
    ap.add_argument("--concurrency", type=int, default=4, help="서로 다른 호스트를 몇 개씩 겹칠지")
    ap.add_argument("--host-delay", type=float, default=3.0, help="같은 호스트의 요청 간격(초)")
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    results_path = os.path.join(args.work, "results.jsonl")
    if args.emit:
        return emit(results_path)

    libs = load_libraries(args.work)
    probe_path = os.path.join(args.work, "probe.json")
    if args.probe or not os.path.exists(probe_path):
        print("검증용 소장 데이터를 만듭니다 (우리 API 를 씁니다)", file=sys.stderr)
        probe = build_probe(args.work, libs)
    else:
        probe = json.load(open(probe_path))

    groups: dict[str, list[dict]] = defaultdict(list)
    for l in libs:
        if l.get("homepageUrl"):
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

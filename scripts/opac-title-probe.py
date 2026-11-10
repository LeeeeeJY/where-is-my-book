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
    """그 ISBN 의 항목에 있는 **주소다운 href**. 정규식 후보를 만들 재료입니다."""
    spans = []
    for m in re.finditer(r"""<a\b[^>]*?\bhref\s*=\s*["']([^"']+)["']""", page):
        href = od.attr_unescape(m.group(1)).strip()
        if href and not href.startswith(("#", "javascript:", "mailto:")):
            spans.append((m.start(), m.end(), href))
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


def check_detail(url: str, isbn: str, listed: set[str], search_url: str, pacer, timeout: float) -> tuple[bool, str]:
    """도착한 상세에 그 ISBN 이 있고, 결과 목록의 다른 책 ISBN 들이 함께 있지 않은지."""
    if url == search_url:
        return False, "검색 결과 자기 주소입니다"
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
        """이 주소를 덮는 가장 긴 열쇠의 규칙."""
        rest = re.sub(r"^https?://", "", url)
        rest = re.split(r"[?#]", rest)[0]
        host, _, path = rest.partition("/")
        host_path = (re.sub(r":\d+$", "", host) + ("/" + path if path else "")).lower().rstrip("/")
        best = None
        for key in existing:
            if host_path == key or host_path.startswith(key + "/"):
                if best is None or len(key) > len(best):
                    best = key
        return existing[best] if best else None

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


def fill(template: str, title: str, encoding: str) -> str:
    """서버(OpacTemplates.fill)와 같이 자리표의 인코딩으로 퍼센트 부호를 씁니다."""
    raw = title.encode("euc-kr" if encoding.upper() == "EUC-KR" else "utf-8")
    return template.replace("{title}", urllib.parse.quote(raw))


def ascii_url(url: str) -> str:
    """주소에 한글이 그대로 들어 있으면 퍼센트 부호로 바꿉니다. 이미 바뀐 것은 건드리지 않습니다."""
    return urllib.parse.quote(url, safe="%/:?&=#;,+@!$'()*~[]-._")


def probe_host(host: str, members: list[tuple[dict, str, str]], probe: dict, pacer, timeout: float) -> tuple[str | None, str]:
    """(통과한 닻 규칙 또는 None, 사유).

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
    for lib, template, encoding, isbn in plan:
        if len(views) == 2 or fetches >= 6:
            break
        if views and (views[0][0]["libCode"] == lib["libCode"] and len(seen_templates) > 1):
            continue                                        # 다른 도서관이 있으면 같은 도서관을 또 보지 않습니다
        if views and views[0][1] == isbn and any(p[3] != isbn for p in plan):
            continue                                        # 다른 책이 있으면 같은 책을 또 보지 않습니다
        url = fill(template, od.PROBE_BOOKS[isbn], encoding)
        if od.blocked(url, disallows):
            return None, "robots.txt 가 검색 결과 경로를 막습니다 (서버가 받을 수 없어 조사하지 않습니다)"
        status, body, final = od.fetch(url, pacer, timeout=timeout)
        fetches += 1
        if status != 200:
            return None, f"검색 결과를 서버 방식으로 받지 못했습니다 ({'HTTP ' + str(status) if status else body[:60]})"
        if isbn in {m.group(0) for m in ISBN_TOKEN.finditer(body)}:
            views.append((lib, isbn, url, final or url, body))
    if len(views) < 2:
        return None, ("결과 목록에 ISBN 이 적혀 있지 않습니다 (닻으로 삼을 것이 없습니다)" if not views
                      else "두 번째 책의 ISBN 이 결과 목록에 없습니다 (그 OPAC 에 없는 판)")

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
    args = ap.parse_args()

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
            rule, why = probe_host(host, members, probe, pacer, args.timeout)
        except Exception as e:                         # 한 곳의 이상한 응답으로 전체가 죽지 않게
            rule, why = None, f"{type(e).__name__}: {e}"
        print(f"# {host} ({len(members)}곳): {rule or '—'}  {why}", file=sys.stderr, flush=True)
        if not rule:
            continue
        found += 1
        # 그 호스트에 이미 다른 규칙(ISBN 검색용)이 있으면 열쇠가 겹칩니다. 제목 규칙의 경로까지 내려 적습니다.
        if host in existing:
            for key in sorted({host + "/" + urllib.parse.urlparse(t).path.strip("/").split("/")[0] for _, t, _ in members}):
                group = od.group_key(next(l for l, t, _ in members if urllib.parse.urlparse(t).path.strip("/").split("/")[0] == key.split("/", 1)[1])["homepageUrl"])
                print(f"{group} | DETAIL_PATTERN | {key} | {rule}", flush=True)
        else:
            print(f"{od.group_key(members[0][0]['homepageUrl'])} | DETAIL_PATTERN | {host} | {rule}", flush=True)
    print(f"# 호스트 {len(hosts)}개 가운데 {found}개에서 닻 규칙을 얻었습니다", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

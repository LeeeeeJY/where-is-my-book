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

## 쓰는 법

    ./scripts/opac-discover.py --probe          # 검증용 소장 데이터 (우리 API 를 씁니다)
    ./scripts/opac-browse.py --limit 30         # 큰 묶음부터 30개
    ./scripts/opac-browse.py --gaps             # 규칙 있는 기관에서 빠진 도서관만 확인
    ./scripts/opac-browse.py --emit > findings.txt
    ./scripts/opac-discover.py --import-file findings.txt >> \\
        backend/src/main/resources/opac/templates.csv

**`--emit` 은 `--import-file` 이 읽는 형식으로 냅니다.** 거기서 자리표·인코딩·다른 기관
도메인을 한 번 더 거르므로 그 통로를 우회하지 마세요.

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
import sys
import urllib.parse
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
        return False, False, f"열지 못했습니다: {type(e).__name__}"
    finally:
        context.close()


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

    context = browser.new_context()
    try:
        page = context.new_page()
        pacer.wait(host)
        page.goto(home, timeout=timeout * 1000, wait_until="domcontentloaded")
        boxes = search_boxes(page)
        if not boxes:
            out["note"] = "검색창을 찾지 못했습니다"
            return out

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
            url = submit(page, box, books[0], timeout)
            template = templatize(url, books[0])
            if not template:
                continue                                # 주소에 검색어가 남지 않는 OPAC
            if od.blocked(template, disallows):
                out["note"] = "robots.txt 가 막은 경로입니다"
                return out

            # ① 양성 — 그 도서관이 실제로 소장한 책
            hit, _, _ = opens_fresh(browser, template, books[0], od.PROBE_BOOKS[books[0]],
                                    pacer, timeout)
            if not hit:
                out["note"] = "새 창에서 열면 그 책이 나오지 않습니다"
                continue

            # ② 음성 — 있을 수 없는 ISBN 으로 그 책이 나오면 검색어를 무시하는 OPAC 입니다
            ghost, _, _ = opens_fresh(browser, template, od.ABSENT_ISBN,
                                      od.PROBE_BOOKS[books[0]], pacer, timeout)
            if ghost:
                out["note"] = "검색어를 무시하고 목록을 뿌립니다"
                return out

            # ③ 재확인 — 다른 책으로 한 번 더. 첫 번째만 우연히 맞는 경우가 있습니다
            again, _, _ = opens_fresh(browser, template, books[1], od.PROBE_BOOKS[books[1]],
                                      pacer, timeout)
            if not again:
                out["note"] = "다른 책으로는 확인되지 않았습니다"
                return out

            out["rules"] = [{"kind": "ISBN_SEARCH", "encoding": "UTF-8", "url": template}]
            return out

        out["note"] = out["note"] or "주소에 검색어가 남지 않습니다"
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


def run(work: str, limit: int, host_delay: float, timeout: float) -> int:
    sync_playwright = playwright()

    libs = od.load_libraries(work)
    probe_path = os.path.join(work, "probe.json")
    if not os.path.exists(probe_path):
        print("probe.json 이 없습니다. 먼저 ./scripts/opac-discover.py --probe 를 돌리세요.",
              file=sys.stderr)
        return 1
    probe = json.load(open(probe_path))
    results_path = os.path.join(work, "browse.jsonl")
    done = {json.loads(l)["key"] for l in open(results_path)} if os.path.exists(results_path) else set()

    rows = [r for r in groups_of(libs, probe) if r[0] not in done]
    if limit:
        rows = rows[:limit]
    pacer = od.Pacer(host_delay)
    found = 0
    with sync_playwright() as p:
        browser = chromium(p)
        try:
            with open(results_path, "a") as sink:
                for i, (key, members, rep, books) in enumerate(rows, 1):
                    r = investigate(browser, key, members, rep, books, pacer, timeout)
                    sink.write(json.dumps(r, ensure_ascii=False) + "\n")
                    sink.flush()
                    mark = "찾음" if r["rules"] else "  — "
                    found += 1 if r["rules"] else 0
                    detail = r["rules"][0]["url"] if r["rules"] else r["note"][:44]
                    print(f"[{i}/{len(rows)}] {mark} {key:34s} {len(members):3d}곳  {detail}",
                          file=sys.stderr)
        finally:
            browser.close()
    print(f"\n묶음 {found}/{len(rows)} 에서 규칙을 찾았습니다", file=sys.stderr)
    return 0


def emit(work: str) -> int:
    """`--import-file` 이 읽는 형식으로. 거기서 한 번 더 걸러집니다."""
    path = os.path.join(work, "browse.jsonl")
    if not os.path.exists(path):
        print("조사 결과가 없습니다.", file=sys.stderr)
        return 1
    print("# opac-browse.py 가 브라우저로 확인한 주소입니다. 세 겹 검증을 통과한 것만 있습니다.")
    for line in open(path):
        r = json.loads(line)
        for rule in r["rules"]:
            print(f"{r['key']} | {rule['kind']} | {rule['encoding']} | {rule['url']}")
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
    ap.add_argument("--emit", action="store_true", help="찾은 규칙을 import 형식으로 출력")
    ap.add_argument("--gaps", metavar="CSV", nargs="?", const=od.DEFAULT_CSV,
                    help="규칙 있는 기관에서 빠진 도서관이 그 검색 화면에 나오는지 확인")
    ap.add_argument("--host-delay", type=float, default=3.0, help="같은 호스트의 요청 간격(초)")
    ap.add_argument("--timeout", type=float, default=20.0)
    args = ap.parse_args()

    os.makedirs(args.work, exist_ok=True)
    if args.emit:
        return emit(args.work)
    if args.gaps:
        return check_gaps(args.work, args.gaps, args.host_delay, args.timeout)
    return run(args.work, args.limit, args.host_delay, args.timeout)


if __name__ == "__main__":
    sys.exit(main())

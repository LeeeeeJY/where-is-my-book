#!/usr/bin/env python3
"""검색 결과에서 멈추는 OPAC 가운데 **자바스크립트 폼 전송으로 상세로 가는 시스템**을 찾아,
서버의 `@onclick:함수` 규칙으로 쓸 수 있는지 실제로 확인하고 import 형식으로 냅니다.

    ./scripts/opac-onclick-probe.py                 # 규칙은 있는데 상세 패턴이 없는 호스트 전부
    ./scripts/opac-onclick-probe.py --only lib.sdm.or.kr,www.eplib.or.kr
    ./scripts/opac-onclick-probe.py > 결과.txt && ./scripts/opac-discover.py --import-patterns 결과.txt

왜 따로 있는가. 2026-11-06 에 검색 결과까지만 가던 342곳을 들여다보니 제목 링크가 `href="#"` 이고
`onclick="fnDetail('bookKey','speciesKey','isbn','MO')"` 처럼 함수에 열쇠만 넘기는 시스템이 절반을
넘었습니다. 그 함수는 숨은 폼의 칸을 채우고 전송하는 것이라, **서버(DetailResolver 의
OnclickFormReader)가 같은 HTML 을 읽어 같은 주소를 만들 수 있습니다.** 이 스크립트는 그 읽기를
파이썬으로 똑같이 흉내 내어(모양이 갈리면 서버에서만 되는 규칙이 들어갑니다) 만든 주소를 실제로
열어 봅니다.

세 겹으로 봅니다. 그 도서관이 소장한 책의 ISBN 으로 검색해 만든 상세 주소에 **그 책 제목이 있고**
(양성), **검증용 다른 책 제목은 없고**(음성: 열쇠를 무시하고 목록을 뿌리는 페이지를 거릅니다),
**다른 소장 책으로 한 번 더** 같은 일이 되는 것(재확인)만 냅니다. 함수 이름은 페이지의 onclick
호출들에서 후보를 뽑아 하나씩 해 봅니다. 공유·담기·검색어·쪽수 함수는 뺍니다.

서버와 같은 UA 로, 자바스크립트 없이 받습니다. 정보나루는 부르지 않습니다(`probe.json` 의 소장
책을 씁니다). 한 호스트에 한꺼번에 하나씩, 요청 사이 3초입니다.
"""
from __future__ import annotations

import argparse
import html as H
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request
from urllib.parse import urljoin, urlsplit, urlencode

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import importlib.util
_spec = importlib.util.spec_from_file_location("od", os.path.join(HERE, "opac-discover.py"))
od = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(od)

UA = od.UA
TOKEN_FIELD = re.compile(r"csrf|token|nonce|viewstate|eventvalidation", re.I)
SKIP_FUNCTION = re.compile(r"share|bookmark|searchkeyword|^fnsearch$|page|sort|print|login|close|open$|basket|export|sns|kakao|twitter|facebook|copy|toggle|menu|lang", re.I)
MAX_SCRIPTS = 24
# 서버(OnclickFormReader.LIKELY_SCRIPT)와 같습니다. 이 말이 든 스크립트를 먼저 받습니다.
LIKELY_SCRIPT = re.compile(r"search|book|detail|view|opac|alpas", re.I)


def get(url: str, timeout: float) -> tuple[bytes, str]:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ko"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.geturl()


def unescape(s: str) -> str:
    return od.attr_unescape(s)


def split_args(raw: str) -> list[str]:
    out, cur, quote = [], [], ""
    for c in raw:
        if quote:
            if c == quote:
                quote = ""
            else:
                cur.append(c)
        elif c in "'\"":
            quote = c
        elif c == ",":
            out.append("".join(cur).strip())
            cur = []
        else:
            cur.append(c)
    if raw.strip():
        out.append("".join(cur).strip())
    return out


def first_call(page: str, fn: str) -> list[str] | None:
    m = re.search(r"""(?:onclick|href)\s*=\s*["']\s*(?:javascript:)?\s*""" + re.escape(fn) + r"\s*\(([^)]*)\)", page)
    return split_args(unescape(m.group(1))) if m else None


RECORD_KEY = re.compile(r"\d")


def candidate_functions(page: str, isbn: str) -> list[str]:
    """상세 함수일 수 있는 onclick 함수 이름들, 문서 순서로.

    **첫 호출의 인자에 레코드 열쇠로 보이는 값이 있어야 합니다**(다섯 글자 이상이고 숫자가 들어 있으며
    ISBN 자체는 아닌 것). 상세 링크는 그 책을 가리키는 열쇠(bookKey·regNo·reckey)를 넘기지 않을 수
    없고, 자료 종류 탭(`fnSearchPubFormCode('ALL')`)·보기 방식(`fnList('LIST')`)·정렬 같은 함수는
    짧은 글자만 넘깁니다. 2026-11-06 첫 회차에서 그런 함수들이 「그 책이 나오는 페이지」로 통과한
    뒤에 둔 거름망입니다. 그 함수들이 만드는 주소는 검색 결과 자기 자리라 ISBN 검색 결과에는 그
    책만 있어 제목 검사로는 걸러지지 않았습니다."""
    names: list[str] = []
    for m in re.finditer(r"""(?:onclick|href)\s*=\s*["']\s*(?:javascript:)?\s*([A-Za-z_$][\w$.]*)\s*\(([^)]*)\)""", page):
        n = m.group(1)
        if SKIP_FUNCTION.search(n) or n in names or n == "void":
            continue
        args = split_args(unescape(m.group(2)))
        if not any(len(a) >= 5 and RECORD_KEY.search(a) and a != isbn for a in args):
            continue
        names.append(n)
    return names


def find_function(text: str, fn: str) -> tuple[list[str], str] | None:
    q = re.escape(fn)
    m = re.search(r"function\s+" + q + r"\s*\(([^)]*)\)\s*\{", text) or \
        re.search(r"(?<![\w$.])" + q + r"\s*[=:]\s*function\s*\(([^)]*)\)\s*\{", text)
    if not m:
        return None
    depth, i = 1, m.end()
    while i < len(text) and depth > 0:
        depth += text[i] == "{"
        depth -= text[i] == "}"
        i += 1
    if depth:
        return None
    return [p.strip() for p in m.group(1).split(",") if p.strip()], text[m.end():i - 1]


def script_urls(page: str, base: str) -> list[str]:
    """같은 기관의 스크립트. 이름에 search·book·detail 이 든 것을 먼저, 나머지는 문서 순서로(서버와 같음)."""
    likely, rest = [], []
    for m in re.finditer(r"""<script\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""", page, re.I):
        src = unescape(m.group(1)).strip()
        if "\n" in src or "\r" in src:
            continue
        u = urljoin(base, src.replace(" ", "%20"))
        if od.registrable_domain(urlsplit(u).netloc) != od.registrable_domain(urlsplit(base).netloc):
            continue
        (likely if LIKELY_SCRIPT.search(urlsplit(u).path.rsplit("/", 1)[-1]) else rest).append(u)
        if len(likely) + len(rest) >= MAX_SCRIPTS:
            break
    return likely + rest


def attr(tag: str, name: str) -> str | None:
    m = re.search(r"(?i)\b" + name + r"""\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", tag)
    if not m:
        return None
    return next(g for g in m.groups() if g is not None)


def find_form(page: str, name_or_id: str):
    for m in re.finditer(r"<form\b[^>]*>", page, re.I):
        tag = m.group(0)
        if attr(tag, "name") != name_or_id and attr(tag, "id") != name_or_id:
            continue
        end = page.lower().find("</form>", m.end())
        inner = page[m.end():end if end >= 0 else len(page)]
        hidden: dict[str, str] = {}
        for im in re.finditer(r"<input\b[^>]*>", inner, re.I):
            t = im.group(0)
            if (attr(t, "type") or "").lower() != "hidden":
                continue
            n = attr(t, "name")
            if n and n not in hidden:
                v = attr(t, "value")
                hidden[n] = unescape(v) if v else ""
        return (attr(tag, "method") or "get").lower(), (unescape(attr(tag, "action")) if attr(tag, "action") else None), hidden
    return None


def name_of_input_with_id(page: str, id_: str) -> str | None:
    for im in re.finditer(r"<input\b[^>]*>", page, re.I):
        if attr(im.group(0), "id") == id_:
            return attr(im.group(0), "name")
    return None


def evaluate(expr: str, env: dict[str, str]) -> str | None:
    e = expr.strip()
    if e in env:
        return env[e]
    m = re.fullmatch(r"(?:encodeURIComponent|encodeURI|escape)\(\s*([\w$]+)\s*\)", e)
    if m and m.group(1) in env:
        return env[m.group(1)]
    if len(e) >= 2 and e[0] in "'\"" and e[-1] == e[0]:
        return e[1:-1]
    return None


def build(page: str, base: str, args: list[str], params: list[str], body: str) -> tuple[str | None, str]:
    """OnclickFormReader.build 와 같은 순서, 같은 규칙."""
    if len(params) < len(args):
        return None, f"호출 인자 {len(args)}개가 매개변수 {len(params)}개보다 많습니다"
    env = dict(zip(params, args))
    assigned: dict[str, str] = {}
    for m in re.finditer(r"([\w$]+)\s*\.\s*([\w$]+)\s*\.\s*value\s*=\s*([^;]+);", body):
        if m.group(1) == "document":
            continue
        v = evaluate(m.group(3), env)
        if v is not None:
            assigned[m.group(2)] = v
    for pat in (r"""find\(\s*["']input\[name=["']?([\w$]+)["']?]\s*["']\s*\)\s*\.val\(\s*([^)]+)\)""",
                r"""\$\(\s*["']input\[name=["']?([\w$]+)["']?]\s*["']\s*\)\s*\.val\(\s*([^)]+)\)"""):
        for m in re.finditer(pat, body):
            v = evaluate(m.group(2), env)
            if v is not None:
                assigned[m.group(1)] = v
    for m in re.finditer(r"""\$\(\s*["']#([\w$-]+)["']\s*\)\s*\.val\(\s*([^)]+)\)""", body):
        v = evaluate(m.group(2), env)
        if v is not None:
            assigned[name_of_input_with_id(page, m.group(1)) or m.group(1)] = v
    if not assigned:
        return None, "함수 본문에서 폼 칸에 인자를 넣는 자리를 찾지 못했습니다"
    action = None
    m = re.search(r"""\.action\s*=\s*["']([^"']+)["']""", body) or re.search(r"""\.attr\(\s*["']action["']\s*,\s*["']([^"']+)["']""", body)
    if m:
        action = m.group(1)
    form_name = None
    for pat in (r"""getElementById\(\s*["']([^"']+)["']\s*\)""", r"""document\.forms\[\s*["']([^"']+)["']\s*]""",
                r"""form\[name=["']?([\w$]+)["']?]""", r"document\.(?!getElementById|forms|querySelector)([\w$]+)",
                r"""\$\(\s*["']#([\w$-]+)["']\s*\)\s*\.(?:attr\(\s*["']action|submit)"""):
        m = re.search(pat, body)
        if m:
            form_name = m.group(1)
            break
    method, hidden = "get", {}
    form = find_form(page, form_name) if form_name else None
    if form:
        method, form_action, hidden = form
        action = action or form_action
    if not action:
        return None, f"폼의 action 을 찾지 못했습니다 (폼 {form_name or '?'})"
    query = {k: v for k, v in hidden.items() if not TOKEN_FIELD.search(k)}
    query.update(assigned)
    sep = "&" if "?" in action else "?"
    return urljoin(base, (action + sep + urlencode(query)).replace(" ", "%20")), f"폼 {form_name or '?'} ({method})"


def resolve(page: str, base: str, fn: str, timeout: float, script_cache: dict[str, str]) -> tuple[str | None, str]:
    args = first_call(page, fn)
    if args is None:
        return None, f"{fn}( 호출이 없습니다"
    found = find_function(page, fn)
    if not found:
        for u in script_urls(page, base):
            if u not in script_cache:
                try:
                    script_cache[u] = od.decode(get(u, timeout)[0], "")
                except Exception:
                    script_cache[u] = ""
            found = find_function(script_cache[u], fn)
            if found:
                break
    if not found:
        return None, f"함수 {fn} 의 본문을 페이지와 스크립트에서 찾지 못했습니다"
    params, body = found
    return build(page, base, args, params, body)


def check_detail(url: str, isbn: str, timeout: float, search_url: str = "") -> tuple[bool, str]:
    """양성(그 책 제목)과 음성(다른 검증용 책 제목 없음).

    검색 결과 자기 주소로 돌아오는 것은 상세가 아닙니다. 경로가 같다고 거르면 김제·전주처럼 같은
    경로에 `mode=view` 만 다른 상세까지 걸러지므로, 상세 아닌 함수를 가르는 일은 `candidate_functions`
    의 「레코드 열쇠가 있는 인자」가 맡습니다."""
    if search_url and url == search_url:
        return False, "검색 결과 자기 주소입니다"
    try:
        raw, _ = get(url, timeout)
    except Exception as e:
        return False, f"상세를 받지 못했습니다 ({str(e)[:60]})"
    page = od.decode(raw, "")
    if not od.page_has(page, od.PROBE_BOOKS[isbn]):
        return False, "상세에 그 책 제목이 없습니다"
    others = [t for k, t in od.PROBE_BOOKS.items() if k != isbn and od.page_has(page, t)]
    if others:
        return False, f"상세에 다른 책 제목이 함께 나옵니다 ({others[0]})"
    return True, "그 책만 나옵니다"


def hosts_without_patterns(csv_path: str, patterns_csv: str, libs: list[dict], probe: dict) -> dict[str, tuple[str, str, list[str]]]:
    """호스트 → (묶음키, 검색 주소 틀, 검증용 ISBN 들). 상세 패턴이 이미 있는 호스트는 뺍니다."""
    patterned = set(od.existing_patterns(patterns_csv))
    out: dict[str, tuple[str, str, list[str]]] = {}
    by_code = {l["libCode"]: l for l in libs}
    for row in od.template_rows(csv_path):
        if row[1] != "ISBN_SEARCH":
            continue
        host = urlsplit(row[3]).netloc.lower()
        host_path = host + urlsplit(row[3]).path
        if host in out or any(host_path == k or host_path.startswith(k + "/") or host == k for k in patterned):
            continue
        books = probe.get(row[0], [])
        if len(books) < 2 or row[0] not in by_code:
            continue
        out[host] = (od.group_key(by_code[row[0]]["homepageUrl"]), row[3], books)
    return out


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--work", default=".opac-work")
    ap.add_argument("--only", metavar="HOST[,HOST...]", help="이 호스트들만")
    ap.add_argument("--templates-csv", default=od.DEFAULT_CSV)
    ap.add_argument("--patterns-csv", default=od.DEFAULT_PATTERNS_CSV)
    ap.add_argument("--timeout", type=float, default=25.0)
    ap.add_argument("--host-delay", type=float, default=3.0)
    args = ap.parse_args()

    libs = od.load_libraries(args.work)
    probe = json.load(open(os.path.join(args.work, "probe.json")))
    targets = hosts_without_patterns(args.templates_csv, args.patterns_csv, libs, probe)
    if args.only:
        wanted = {h.strip().lower() for h in args.only.split(",")}
        targets = {h: v for h, v in targets.items() if h in wanted}
    print("# opac-onclick-probe.py 가 서버와 같은 방식으로 확인한 @onclick 규칙입니다.")
    print("# --import-patterns 로 넣습니다.")
    found = 0
    for host, (key, template, books) in sorted(targets.items()):
        isbn, isbn2 = books[0], books[1]
        try:
            raw, final = get(template.replace("{isbn13}", isbn), args.timeout)
        except Exception as e:
            print(f"# {host}: 검색 결과를 받지 못했습니다 ({str(e)[:70]})", file=sys.stderr)
            continue
        page = od.decode(raw, "")
        script_cache: dict[str, str] = {}
        verdict = "상세로 보이는 onclick 호출이 없습니다"
        tried: list[str] = []
        for fn in candidate_functions(page, isbn)[:40]:
            url, note = resolve(page, final, fn, args.timeout, script_cache)
            if not url:
                tried.append(f"{fn}: {note}")
                continue
            time.sleep(args.host_delay)
            ok, why = check_detail(url, isbn, args.timeout, final)
            if not ok:
                tried.append(f"{fn}: {why}")
                continue
            # 재확인: 다른 소장 책으로 같은 길이 열리는지
            time.sleep(args.host_delay)
            try:
                raw2, final2 = get(template.replace("{isbn13}", isbn2), args.timeout)
                url2, _ = resolve(od.decode(raw2, ""), final2, fn, args.timeout, script_cache)
                time.sleep(args.host_delay)
                ok2, why2 = check_detail(url2, isbn2, args.timeout, final2) if url2 else (False, "둘째 책의 주소를 못 만들었습니다")
            except Exception as e:
                ok2, why2 = False, str(e)[:60]
            if not ok2:
                tried.append(f"{fn}: 다른 책으로는 확인되지 않았습니다 ({why2})")
                continue
            print(f"{key} | DETAIL_PATTERN | {host} | @onclick:{fn}")
            print(f"# {host}: {fn} → {url[:120]}", file=sys.stderr)
            found += 1
            verdict = None
            break
        if verdict:
            # 후보마다 왜 떨어졌는지 전부 적습니다. 마지막 것만 적으면 상세 함수가 어디서 걸렸는지 모릅니다.
            print(f"# {host}: {verdict if not tried else ' / '.join(tried)}", file=sys.stderr)
        time.sleep(args.host_delay)
    print(f"# 호스트 {len(targets)}개 가운데 {found}개에서 @onclick 규칙을 얻었습니다", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())

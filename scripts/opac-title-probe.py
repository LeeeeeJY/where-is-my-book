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

## 목록에 ISBN 이 없는 OPAC (`@verify:`) 과 큰글자책 줄 (`@plain:`)

닻으로 삼을 ISBN 이 목록에 어떤 모양으로도 없는 OPAC 이 있습니다(유성). 서버는 `@verify:규칙` 으로 후보 줄의
**상세 화면을 받아** 그 ISBN 이 글자로 적힌 줄만 보냅니다(`kr.wimb.opac.IsbnVerify`). 그리고 도서관이 큰글자판을
보통 판의 ISBN 으로 등록해 둔 화면에서는 `@plain:규칙` 으로 **표기가 없는 줄**을 고릅니다
(`kr.wimb.opac.PlainRows`). 이 도구는 두 고르기를 서버와 같은 차례로 합니다(`mentions`·`verify_pick`,
`plain_rows`·`plain_choose`, 그리고 규칙 한 줄을 통째로 실행하는 `run_rule`). **한쪽을 고치면 다른 쪽도
고치세요.** 주소 하나에 규칙을 한 번 실행해 보려면 이렇게 합니다.

    ./scripts/opac-title-probe.py --run '<서버가 받는 주소>' --rule '@verify:@onclick:fnDetail' --isbn 9788937473135
    ./scripts/opac-title-probe.py --run '<ISBN 검색 주소>' --rule '@plain:href="(bookdetail[^"]*)"' --isbn 9788937473135

`@plain:` 은 줄마다의 글자와 「표기 있음/없음」을 함께 적습니다. **넣기 전에 그 글자가 한 줄씩 제대로 갈려
있는지 눈으로 보세요.** 줄의 글자는 규칙이 잡은 자리에서 다음 줄의 자리 앞까지라, 규칙이 줄의 맨 앞에서
잡히지 않으면 줄 머리의 표기가 윗줄의 것으로 읽힙니다.

둘을 함께 쓰는 꼴은 `@verify:@plain:규칙` 하나입니다. 큰글자판을 보통 판의 ISBN 으로 등록해 둔 도서관에서는
두 줄의 상세 화면에 같은 ISBN 이 적혀 있어서, 상세 화면만 봐서는 첫 줄(큰글자책)에서 멈춥니다. 이 꼴은 목록의
줄에 표기가 있는 후보에서 멈추지 않고 남은 후보를 마저 본 뒤, 그 ISBN 이 적힌 후보 가운데 표기 없는 줄을
먼저 고릅니다(유성도서관의 「82년생 김지영」, 2026-11-25).

    ./scripts/opac-title-probe.py --run '<서버가 받는 주소>' --isbn 9788937473135 \
        --rule '@verify:@plain:@template:<input type="checkbox" name="ctrlno" value="(\\d+)" => /상세.do?vCtrl={1}'

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


def pick_all(page: str, spans: list[tuple[int, int, object]], isbn: str, link_first: bool):
    """그 ISBN 의 줄 **전부**. (자리들, 사유). 서버의 `IsbnAnchor.pickAll` 과 같은 차례입니다.

    `pick` 은 이 가운데 첫 줄 하나만 돌려줍니다. 한 ISBN 에 줄이 여럿일 때 그 가운데서 다시 골라야 하는
    규칙(`@plain:`)이 씁니다. **첫 자리는 `pick` 이 고르는 것과 같습니다.** ① 링크 글자 안에 그 ISBN 이 든
    자리가 있으면 그런 자리 전부 ② 아니면 ISBN 이 적힌 자리마다 규칙이 말한 쪽의 가장 가까운 링크 ③ 「뒤」
    방향에서는 그 링크 뒤로 다른 ISBN 이 나오기 전까지의 링크도(남양주: 목록 앞에 검색한 ISBN 이 한 번
    적히고 줄에는 ISBN 이 없습니다).
    """
    if not isbn or not ISBN_TOKEN.fullmatch(isbn):
        return [], f"ISBN-13 이 아닙니다: {isbn}"
    carrying = [s for s in spans if isbn in page[s[0]:s[1]]]
    if carrying:
        return carrying, "링크 안에 그 ISBN 이 있습니다"
    ours, others = [], []
    for m in ISBN_TOKEN.finditer(page):
        (ours if m.group(0) == isbn else others).append((m.start(), m.end()))
    if not ours:
        return [], "결과에 그 ISBN 이 적혀 있지 않습니다"

    def clean(a: int, b: int) -> bool:
        return not any(a <= start < b for start, _ in others)

    found: list = []
    for start, end in ours:
        if link_first:
            before = [s for s in spans if s[1] <= start]
            if before and clean(before[-1][1], start) and before[-1] not in found:
                found.append(before[-1])
        else:
            for span in spans:
                if span[0] < end:
                    continue
                if not clean(end, span[0]):
                    break                              # 여기부터는 다른 책의 줄입니다
                if span not in found:
                    found.append(span)
    if not found:
        return [], "그 ISBN 이 적힌 항목에서 링크를 찾지 못했습니다"
    return found, "그 ISBN 바로 앞의 링크입니다" if link_first else "그 ISBN 바로 뒤의 링크입니다"


# ── 화면의 글자 (서버의 RowText) ─────────────────────────────────────────────
#
# 자바와 파이썬의 정규식은 \s 와 \d 와 대소문자 무시가 받는 글자가 다릅니다. 같은 글자를 내려고 re.A 를
# 붙이고 공백과 숫자는 글자를 늘어놓습니다. **서버의 RowText.visible 과 같은 글자를 내야 합니다.**

_HIDDEN = re.compile(r"<!--.*?-->|<script\b.*?</script\s*>|<style\b.*?</style\s*>", re.S | re.I | re.A)
_TAG = re.compile(r"<[^>]*>")
_NUMERIC = re.compile(r"&#(?:([0-9]{1,7})|[xX]([0-9A-Fa-f]{1,6}));")
_SPACES = re.compile(r"[ \t\r\n\f\v]+")


def visible_text(page: str) -> str:
    """태그와 스크립트를 뺀 글자. 숫자 문자 참조는 글자로 되돌리고 공백은 하나로 줄입니다.

    ① 주석·`<script>`·`<style>` 을 통째로 버리고 ② 남은 태그를 공백으로 바꾸고 ③ `&#44544;` 같은 숫자
    참조를 글자로 되돌리고(거창은 한글을 전부 이렇게 적습니다) ④ 공백을 하나로 줄입니다. 속성값은 글자가
    아닙니다. 검색해 들어간 화면은 숨은 칸에 검색어를 되돌려 적어서, 속성값까지 보면 어느 상세 화면에나
    「그 ISBN 이 있다」가 됩니다.
    """
    if not page:
        return ""
    text = _TAG.sub(" ", _HIDDEN.sub(" ", page))

    def numeric(m):
        code = int(m.group(1)) if m.group(1) is not None else int(m.group(2), 16)
        valid = 0 < code <= 0x10FFFF and not 0xD800 <= code <= 0xDFFF
        return chr(code) if valid else m.group(0)

    text = _SPACES.sub(" ", _NUMERIC.sub(numeric, text))
    start = 1 if text.startswith(" ") else 0
    end = len(text) - 1 if len(text) > start and text.endswith(" ") else len(text)
    return text[start:end]


# ── 상세 화면의 ISBN 으로 고르기 (서버의 IsbnVerify, `@verify:`) ─────────────

VERIFY_PREFIX = "@verify:"
MAX_VERIFY_DETAILS = 3          # 서버의 DetailResolver.MAX_VERIFY_DETAILS 와 같아야 합니다
_ISBN13 = re.compile(r"97[89][0-9]{10}")


def mentions(detail_page: str | None, isbn: str | None) -> bool:
    """그 화면의 **글자**에 그 ISBN 이 13자리 그대로 적혀 있는지. 서버의 `IsbnVerify.mentions` 와 같습니다.

    하이픈을 넣은 꼴과 10자리 꼴은 보지 않습니다. 속성값과 스크립트도 보지 않습니다.
    """
    if detail_page is None or not isbn or not _ISBN13.fullmatch(isbn):
        return False
    return re.search(r"(?<![0-9])" + isbn + r"(?![0-9])", visible_text(detail_page)) is not None


def distinct(spans: list) -> list:
    """규칙이 잡은 자리에서 줄마다 하나씩만. 문서 순서이고 내용이 같은 자리는 처음 것만 남습니다.

    한 줄에 같은 상세로 가는 링크가 둘인 목록(표지와 제목)이 흔합니다. 서버의 `IsbnVerify.distinct` 와 같습니다.
    """
    out, seen = [], []
    for span in spans:
        if span[2] in seen:
            continue
        seen.append(span[2])
        out.append(span)
    return out


def verify_pick(seen: list[tuple], isbns: list[str]):
    """받아 본 후보 `(후보, 상세 HTML 또는 None[, 표기 있는 줄인가])` 가운데 보낼 것. (후보 또는 None, 사유).

    서버의 `IsbnVerify.pick` 과 같습니다. ISBN 은 앞의 것이 먼저이고, 한 ISBN 을 두고는 앞선 후보가
    먼저입니다. **어느 후보에도 없으면 첫 후보로 내려가지 않습니다.** 받지 못한 후보(None)는 「없다」로
    치지 않습니다.

    그 ISBN 이 적힌 후보가 여럿이면 **큰글자책·점자책 표기가 없는 줄의 후보가 먼저**입니다(`@verify:@plain:`
    일 때만 셋째 값이 참일 수 있습니다). 큰글자판을 보통 판의 ISBN 으로 등록해 둔 도서관에서는 두 줄의 상세
    화면에 같은 ISBN 이 적혀 있습니다. 전부 표기 있는 줄이면 앞선 후보입니다.
    """
    if not isbns:
        return None, "확인할 ISBN 이 없습니다"
    for isbn in isbns:
        first, skipped = None, 0
        for i, item in enumerate(seen):
            candidate, page, is_marked = item[0], item[1], (item[2] if len(item) > 2 else False)
            if not mentions(page, isbn):
                continue
            if not is_marked:
                extra = f" (같은 ISBN 이 적힌 큰글자책·점자책 줄 {skipped}개를 빼고 골랐습니다)" if skipped else ""
                return candidate, f"후보 {i + 1}번째의 상세 화면에 그 ISBN 이 적혀 있습니다{extra}"
            if first is None:
                first = i
            skipped += 1
        if first is not None:
            return seen[first][0], f"후보 {first + 1}번째의 상세 화면에 그 ISBN 이 적혀 있습니다"
    read = sum(1 for item in seen if item[1] is not None)
    return None, f"받아 본 후보 {read}개의 상세 화면에 그 ISBN 이 적혀 있지 않습니다"


# ── 큰글자책·점자책 줄 건너뛰기 (서버의 PlainRows, `@plain:`) ────────────────

PLAIN_PREFIX = "@plain:"
# 줄의 글자에 이 가운데 하나가 있으면 큰글자책·점자책 줄로 봅니다. 서버의 PlainRows.MARKS 와 같아야 합니다.
# 화면에서 본 것만 더하세요(2026-11-25: 거창·순천·김제 「큰글자 도서」, 청주·보령 「큰글자책」, 보령 「큰글씨책」,
# 남양주·화명 「대활자본」, 단양 청구기호 「대활자813.7」).
MARKS = ("큰글자", "큰글씨", "대활자", "점자")
# 청구기호 앞의 별치기호. 청주 오송도서관은 큰글자책 줄의 표제에 아무 표시가 없고 청구기호가 「큰 813.7-…」으로
# 시작할 뿐입니다. 「큰」 한 글자는 제목의 글자와 겹치므로 「큰」이나 「큰글」 뒤에 공백 하나, 숫자 셋, 점이나
# 붙임표가 올 때만 읽습니다(「큰 813.7-」, 「큰글 808-」). 서버의 PlainRows.CALL_NUMBER_MARK 와 같아야 합니다.
_CALL_NUMBER_MARK = re.compile(r"(?<![가-힣])큰글? [0-9]{3}[.\-]")


def marked(text: str) -> bool:
    """줄의 글자에 큰글자책·점자책 표기가 있는지. 서버의 `PlainRows.marked` 와 같습니다."""
    return any(mark in text for mark in MARKS) or _CALL_NUMBER_MARK.search(text) is not None


def _after_open_tag(page: str, start: int, end: int) -> int:
    """규칙이 태그 한가운데에서 잡혔으면 그 태그가 끝난 자리. 남은 속성값을 글자로 읽지 않으려는 것입니다."""
    gt, lt = page.find(">", start), page.find("<", start)
    if 0 <= gt < end and (lt < 0 or gt < lt):
        return gt + 1
    return start


def _before_open_tag(page: str, start: int, end: int) -> int:
    """다음 줄의 자리가 태그 한가운데면 그 태그가 열린 자리. 열다 만 태그의 속성값을 이 줄의 글자로 읽지 않습니다."""
    lt = page.rfind("<", 0, end)
    if lt >= start and page.find(">", lt) >= end:
        return lt
    return end


def plain_rows(page: str, spans: list) -> list[tuple[tuple, str, bool]]:
    """화면의 줄들. `(자리, 그 줄의 글자, 판 표기가 있는가)`. 서버의 `PlainRows.rows` 와 같습니다.

    줄의 글자는 규칙이 잡은 자리에서 다음 줄의 자리 앞까지이고, 마지막 줄은 그 앞의 가장 짧은 줄만큼입니다
    (길게 보면 목록 뒤의 글자가 마지막 줄의 것이 됩니다).
    **그래서 규칙은 줄의 맨 앞에서 잡혀야 합니다.** 줄 머리의 표기가 규칙이 잡는 자리보다 앞에 있으면 윗줄의
    것으로 읽힙니다. 넣기 전에 이 함수가 내는 글자를 눈으로 보세요.
    """
    starts = distinct(spans)
    out, shortest = [], None
    for i, span in enumerate(starts):
        start = span[0]
        if i + 1 < len(starts):
            end = starts[i + 1][0]
            shortest = end - start if shortest is None else min(shortest, end - start)
        else:
            end = len(page) if shortest is None else min(len(page), start + shortest)
        text = visible_text(page[_after_open_tag(page, start, end):_before_open_tag(page, start, end)])
        out.append((span, text, marked(text)))
    return out


def plain_choose(page: str, spans: list, mine: list, how: str):
    """그 ISBN 의 줄들(`mine`) 가운데 보낼 줄. (내용, 사유). 서버의 `PlainRows.choose` 와 같습니다.

    표기가 있는 줄과 없는 줄이 **섞여 있을 때만** 표기 없는 첫 줄입니다. 줄이 하나뿐이거나 전부 그 표기면
    첫 줄 그대로입니다. 그 ISBN 의 줄 가운데 글자가 없는 줄이 있으면 줄의 경계를 가르지 못한 것으로 보고
    아무것도 바꾸지 않습니다.
    """
    candidates = distinct(mine)
    first = candidates[0][2]
    if len(candidates) < 2:
        return first, how
    rows = plain_rows(page, spans)
    ours = []
    for candidate in candidates:
        row = next((r for r in rows if r[0][2] == candidate[2]), None)
        if row is not None:
            ours.append(row)
    if len(ours) != len(candidates):
        return first, how
    marked = 0
    for _, text, is_marked in ours:
        if not text:
            return first, how + " (글자 없는 줄이 있어 판 표기를 보지 않았습니다)"
        marked += 1 if is_marked else 0
    if marked == 0 or marked == len(ours):
        return first, how
    for i, (span, _, is_marked) in enumerate(ours):
        if not is_marked:
            return span[2], (f"{how} (그 ISBN 의 줄 {len(ours)}개 가운데 큰글자책·점자책 표기가 있는 줄 "
                             f"{marked}개를 빼고 고른 {i + 1}번째 줄입니다)")
    return first, how


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


def picks_by_isbn(rule: str) -> bool:
    """그 ISBN 으로 줄을 골라 가는 규칙인지. 서버의 `DetailRule.anchored()` 와 같습니다.

    목록의 ISBN 으로 고르는 `@anchor:`, 상세 화면의 ISBN 으로 고르는 `@verify:`, 그리고 안쪽이 그런
    `@frame:` 과 `@plain:` 입니다. 제목 검색 규칙은 이것이 참일 때만 상세로 갑니다.
    """
    framed = od.split_frame(rule)
    if framed:
        return picks_by_isbn(framed[2])
    low = rule.lower()
    if low.startswith(PLAIN_PREFIX):
        return picks_by_isbn(rule[len(PLAIN_PREFIX):].strip())
    return low.startswith(VERIFY_PREFIX) or split_rule(rule) is not None


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


def fill_template(template: str, groups: tuple) -> str:
    """`@template:` 의 주소틀에 정규식의 그룹을 끼웁니다. 서버의 `DetailResolver.fillTemplate` 과 같은 글자를 냅니다."""
    def one(m):
        value = od.attr_unescape(groups[int(m.group(1)) - 1] or "").strip()
        # 자바의 URLEncoder 는 공백을 + 로, 「~」를 %7E 로 적습니다.
        return urllib.parse.quote_plus(value, safe="*").replace("~", "%7E")
    return re.sub(r"\{(\d+)}", one, template)


def _target(href: str, base: str, search_url: str | None) -> tuple[str | None, str]:
    """뽑은 링크를 보낼 수 있는 절대 주소로. 서버의 `DetailResolver.target` 과 같은 것을 거릅니다."""
    href = (href or "").strip()
    if not href or href.startswith("#") or href.lower().startswith(("javascript:", "mailto:")):
        return None, f"링크가 주소가 아닙니다: {href}"
    url = urllib.parse.urljoin(base, href.replace(" ", "%20"))
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme not in ("http", "https") or not parsed.hostname:
        return None, f"http 주소가 아닙니다: {url}"
    if od.registrable_domain(parsed.hostname) != od.registrable_domain(urllib.parse.urlparse(base).hostname or ""):
        return None, f"다른 기관의 주소입니다: {parsed.hostname}"
    if search_url and url == search_url:
        return None, "검색 결과 자기 주소입니다"
    return url, ""


def _base_rule(page: str, base: str, inner: str, timeout: float, scripts: dict[str, str]):
    """안쪽 규칙(정규식·`@onclick:`·`@template:`)이 잡는 자리들과, 그 내용을 상세 주소로 만드는 함수.

    (자리들, 주소로 만드는 함수, 못 잡았을 때의 사유). 자리의 내용은 정규식이면 잡힌 글자, `@template:` 이면
    채운 주소틀, `@onclick:` 이면 호출 인자입니다. 주소로 만드는 함수는 (주소 또는 None, 사유)를 돌려줍니다.
    """
    low = inner.lower()
    if low.startswith(od.ONCLICK_PREFIX):
        fn = inner[len(od.ONCLICK_PREFIX):].strip()
        found: list = []

        def body_of():
            if not found:
                hit = oc.find_function(page, fn)
                if not hit:
                    for u in oc.script_urls(page, base):
                        if u not in scripts:
                            try:
                                scripts[u] = od.decode(oc.get(u, timeout)[0], "")
                            except Exception:
                                scripts[u] = ""
                        hit = oc.find_function(scripts[u], fn)
                        if hit:
                            break
                found.append(hit)
            return found[0]

        def to_url(args):
            hit = body_of()
            if not hit:
                return None, f"함수 {fn} 의 본문을 페이지와 스크립트에서 찾지 못했습니다"
            return oc.build(page, base, args, hit[0], hit[1])

        return calls(page, fn), to_url, f"{fn}( 호출이 없습니다"
    if low.startswith(od.TEMPLATE_PREFIX):
        pattern, _, template = inner[len(od.TEMPLATE_PREFIX):].partition(od.TEMPLATE_SEPARATOR)
        regex = re.compile(pattern.strip())
        spans = [(m.start(), m.end(), fill_template(template.strip(), m.groups())) for m in regex.finditer(page)]
        return spans, (lambda filled: (filled, "속성값을 주소틀에 끼웠습니다")), "패턴에 맞는 자리가 없습니다"
    regex = re.compile(inner)
    spans = [(m.start(), m.end(), m.group(1)) for m in regex.finditer(page)]
    return spans, (lambda href: (od.attr_unescape(href).strip(), "찾았습니다")), "패턴에 맞는 링크가 없습니다"


def run_rule(page: str, base: str, rule: str, isbns: list[str], timeout: float = 30.0,
             scripts: dict[str, str] | None = None, fetch_detail=None,
             search_url: str | None = None) -> tuple[str | None, str]:
    """규칙 한 줄을 **서버처럼** 실행합니다. (상세 주소 또는 None, 어떻게).

    `page` 는 서버가 받는 화면(검색 화면, `@frame:` 이면 프레임)이고 `rule` 은 그 화면에 적용하는 규칙입니다
    (`@frame:` 의 안쪽). 받는 모양은 넷입니다. 감싼 것이 없는 규칙(첫 줄), `@anchor:`·`@anchor-after:`(그
    ISBN 이 적힌 줄), `@plain:`(그 ISBN 의 줄 가운데 큰글자책·점자책 표기가 없는 줄), `@verify:`(후보의 상세
    화면에 그 ISBN 이 적힌 줄)입니다.

    `isbns` 는 앞의 것이 그 도서관이 가진 판입니다. `@verify:` 는 `fetch_detail(주소)` 로 후보의 상세 화면을
    받습니다(HTML 을 돌려주고, 받지 못했으면 None). 서버처럼 앞의 셋까지만 받고, 하나를 받지 못하면 거기서
    그만둡니다.
    """
    scripts = {} if scripts is None else scripts
    low = rule.lower()
    try:
        if low.startswith(VERIFY_PREFIX):
            inner = rule[len(VERIFY_PREFIX):].strip()
            # @verify:@plain: 이면 목록의 줄에 큰글자책·점자책 표기가 있는지를 후보마다 함께 듭니다.
            plain = inner.lower().startswith(PLAIN_PREFIX)
            if plain:
                inner = inner[len(PLAIN_PREFIX):].strip()
            spans, to_url, empty = _base_rule(page, base, inner, timeout, scripts)
            if not isbns:
                return None, "@verify: 규칙은 어느 책인지(ISBN) 알아야 합니다"
            urls, problem, url_of = [], None, []
            for _, _, payload in distinct(spans):
                href, note = to_url(payload)
                url, why = _target(href, base, search_url) if href else (None, note)
                if url:
                    url_of.append((payload, url))
                    if url not in urls:
                        urls.append(url)
                elif problem is None:
                    problem = why
            if not urls:
                return None, problem or empty
            marked_urls: set[str] = set()
            if plain:
                # 글자 없는 줄이 하나라도 있으면 줄의 경계를 가르지 못한 화면입니다. 아무것도 표기 있는 줄로 보지 않습니다.
                for span, text, is_marked in plain_rows(page, spans):
                    url = next((u for p, u in url_of if p == span[2]), None)
                    if url is None:
                        continue
                    if not text:
                        marked_urls = set()
                        break
                    if is_marked:
                        marked_urls.add(url)
            if fetch_detail is None:
                return None, "@verify: 규칙은 후보의 상세 화면을 받아야 합니다 (fetch_detail 이 없습니다)"
            seen: list[tuple[str, str | None, bool]] = []
            for url in urls[:MAX_VERIFY_DETAILS]:
                detail = fetch_detail(url)
                if detail is None:
                    picked, how = verify_pick(seen, isbns)
                    return (picked, how) if picked else (None, f"후보의 상세 화면을 받지 못했습니다: {url}")
                seen.append((url, detail, url in marked_urls))
                # 큰글자책·점자책 줄이면 같은 ISBN 의 보통 판 줄이 뒤에 있을 수 있어 남은 후보를 마저 봅니다.
                if url not in marked_urls and mentions(detail, isbns[0]):
                    break
            picked, how = verify_pick(seen, isbns)
            return picked, f"{how} (검색 결과의 줄 {len(urls)}개 가운데 {len(seen)}개의 상세 화면을 봤습니다)"

        plain = low.startswith(PLAIN_PREFIX)
        shaped = rule[len(PLAIN_PREFIX):].strip() if plain else rule
        anchor = split_rule(shaped)
        spans, to_url, empty = _base_rule(page, base, anchor[0] if anchor else shaped, timeout, scripts)
        if anchor:
            if not isbns:
                return None, "@anchor: 규칙은 어느 책인지(ISBN) 알아야 합니다"
            payload, how = None, "닻으로 삼을 ISBN 이 없습니다"
            first_why = None
            for isbn in isbns:
                if plain:
                    mine, why = pick_all(page, spans, isbn, anchor[1])
                    if mine:
                        payload, how = plain_choose(page, spans, mine, why)
                        break
                else:
                    payload, why = pick(page, spans, isbn, anchor[1])
                    if payload is not None:
                        how = why
                        break
                first_why = first_why or why
            if payload is None:
                return None, first_why or how
        elif not spans:
            return None, empty
        elif plain:
            payload, how = plain_choose(page, spans, spans, "ISBN 으로 찾은 결과의 줄입니다")
        else:
            payload, how = spans[0][2], "찾았습니다"
        href, note = to_url(payload)
        if not href:
            return None, note
        url, why = _target(href, base, search_url)
        return (url, how if anchor or plain else note) if url else (None, why)
    except re.error as e:
        return None, f"정규식이 잘못되었습니다 ({e})"


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
        if rule and picks_by_isbn(rule):
            continue                                   # 이미 ISBN 으로 줄을 고르는 규칙이 있습니다
        host = re.sub(r":\d+$", "", urllib.parse.urlparse(row[3]).netloc.lower())
        if only and host not in only:
            continue
        hosts[host].append((by_code[row[0]], row[3], row[2]))
    return hosts, existing


# 분관을 좁히는 칸으로 알려진 이름들. 값이 ALL 이거나 비어 있으면 좁힌 것이 아닙니다.
# `sh_MGC` 는 울진입니다(2026-11-17). 이 목록에 없던 때에는 도구가 호스트만 적은 열쇠를 냈습니다.
BRANCH_PARAMS = ("searchLibraryArr", "searchManageCodeArr", "manage_code", "manageCode", "libraryCodes", "sh_MGC")


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


def run_once(url: str, rule: str, isbns: list[str], pacer, timeout: float) -> int:
    """`--run`: 주소 하나를 서버 방식으로 받아 규칙을 서버처럼 실행하고, 본 것을 적습니다.

    서버가 누를 때 하는 일을 이 회선에서 한 번 해 보는 것입니다. `@verify:` 는 후보의 상세 화면을 앞의
    셋까지 받고, `@plain:` 은 줄마다의 글자와 판 표기를 함께 적습니다. robots.txt 가 막는 경로는 받지 않습니다.
    """
    if od.split_frame(rule):
        print("--run 에는 @frame: 의 안쪽 규칙을 주고, 주소로 프레임 주소(자리표를 채운 것)를 주세요.", file=sys.stderr)
        return 1
    problem = od.pattern_problem(rule)
    if problem:
        print(f"규칙을 서버가 받아 주지 않습니다: {problem}", file=sys.stderr)
        return 1
    if od.blocked(url, disallows_for(url, pacer)):
        print("robots.txt 가 그 경로를 막습니다. 받지 않았습니다.")
        return 1
    started = time.time()
    status, page, final = od.fetch(url, pacer, timeout=timeout)
    print(f"# 받은 화면: HTTP {status}, {len(page):,}자, {time.time() - started:.1f}초")
    if status != 200:
        print(f"받지 못했습니다: {page[:80] if status == 0 else 'HTTP ' + str(status)}")
        return 1
    fetched: list[str] = []

    def fetch_detail(detail_url: str):
        if od.blocked(detail_url, disallows_for(detail_url, pacer)):
            print(f"#   후보 상세: robots.txt 가 막습니다  {detail_url}")
            return None
        began = time.time()
        code, body, _ = od.fetch(ascii_url(detail_url), pacer, timeout=timeout)
        fetched.append(detail_url)
        hit = [isbn for isbn in isbns if mentions(body, isbn)] if code == 200 else []
        print(f"#   후보 상세 {len(fetched)}: HTTP {code}, {time.time() - began:.1f}초, "
              f"글자에 적힌 ISBN {hit or '없음'}  {detail_url}")
        return body if code == 200 else None

    low = rule.lower()
    if low.startswith(PLAIN_PREFIX):
        shaped = rule[len(PLAIN_PREFIX):].strip()
        anchor = split_rule(shaped)
        spans, _, _ = _base_rule(page, final or url, anchor[0] if anchor else shaped, timeout, {})
        for i, (span, text, marked) in enumerate(plain_rows(page, spans), 1):
            print(f"#   줄 {i} [{'표기 있음' if marked else '표기 없음'}] {text[:150]}")
    detail, how = run_rule(page, final or url, rule, isbns, timeout, {}, fetch_detail, url)
    print(f"{detail or '—'}\n# {how}")
    return 0


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
    ap.add_argument("--run", metavar="URL",
                    help="이 주소(서버가 받는 화면. 자리표를 채운 것)를 서버 방식으로 받아 --rule 을 서버처럼 "
                         "한 번 실행합니다. @verify: 는 후보의 상세 화면까지 받습니다")
    ap.add_argument("--rule", help="--run 에 쓸 규칙 한 줄 (@frame: 이면 그 안쪽 규칙)")
    ap.add_argument("--isbn", action="append", default=[],
                    help="--run 에 쓸 ISBN. 여러 번 주면 앞의 것이 그 도서관이 가진 판입니다")
    args = ap.parse_args()

    if args.run:
        if not args.rule:
            print("--run 에는 --rule 이 필요합니다.", file=sys.stderr)
            return 1
        return run_once(args.run, args.rule, args.isbn, od.Pacer(args.host_delay), args.timeout)

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

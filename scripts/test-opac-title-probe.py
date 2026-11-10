#!/usr/bin/env python3
"""opac-title-probe.py 의 고르기가 **서버(IsbnAnchor)와 같은 답**을 내는지 봅니다.

    python3 scripts/test-opac-title-probe.py

여기서 쓰는 목록은 `IsbnAnchorTest` 와 `DetailResolverTest` 의 것과 같습니다. 조사 도구와 서버가
서로 다른 줄을 고르면, 도구가 확인한 규칙이 서버에서는 다른 책으로 갑니다. 네트워크는 쓰지 않습니다.
"""
import importlib.util
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("tp", os.path.join(HERE, "opac-title-probe.py"))
tp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tp)

LINK = re.compile(r'href="(/detail/\d+)"')


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

    print()
    if failures:
        print(f"실패 {len(failures)}건: {', '.join(failures)}")
        return 1
    print("전부 통과했습니다.")
    return 0


if __name__ == "__main__":
    sys.exit(main())

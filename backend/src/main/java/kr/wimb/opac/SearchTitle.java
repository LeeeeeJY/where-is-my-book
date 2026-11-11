package kr.wimb.opac;

import kr.wimb.bib.BibNormalizer;
import kr.wimb.bib.Volume;

/**
 * 도서관의 제목 검색에 넣을 표제를 만듭니다. <b>화면의 표제에서 꼬리의 권차를 뗍니다.</b>
 *
 * <p>화면의 표제는 권차를 붙여 보여 줍니다(「해리 포터와 마법사의 돌 1」, {@code SearchDocBuilder.displayTitle}).
 * 그 글자를 그대로 도서관의 제목 검색에 넣으면 OPAC 마다 결과가 갈립니다. 도서관은 그 책을
 * 「해리포터와 마법사의 돌 . 1(상)」처럼 제각각 적어 두기 때문입니다. 2026-11-11 에 그 책을 소장한다고
 * 정보나루가 답한 도서관으로 제목 규칙 호스트를 한 번씩 받아 보니, 결과가 보이는 31개 호스트 가운데
 * <b>3개(경산·강진·동대문)는 권차를 붙이면 0건</b>이었고 여럿이 줄었으며, 파주·마포·평택은 결과는 있는데
 * 그 판의 ISBN 이 목록에서 빠져 닻 규칙이 헛돌았습니다. 권차를 뗀 표제로는 31개 모두 결과가 있었습니다.
 * 사용자에게 0건은 「소장한다더니 그 책이 없네」로 보입니다.
 *
 * <p><b>떼는 것은 맨 끝에, 띄어 적힌 권차뿐입니다.</b> 「천하」의 「하」나 「1984」를 권차로 떼면 다른 책을
 * 찾게 됩니다. 판정은 표제를 만든 쪽과 같은 {@link BibNormalizer#parseTitle} 에 맡기고, 여기서는 그
 * 권차가 실제로 꼬리에 떨어져 적혀 있는지만 봅니다.
 *
 * <p>권차를 떼면 결과가 넓어집니다. 권수가 많은 전집은 찾는 권이 첫 쪽에 없을 수 있습니다. 그래서
 * 닻 규칙이 있는 도서관은 권차를 붙인 표제로 먼저 찾고, 그 판이 없을 때만 뗀 표제로 한 번 더 찾습니다
 * ({@code WimbController.detailOrSame}). 서버가 받아 보지 않는 도서관은 뗀 표제의 검색 결과로 보냅니다.
 * 넓은 목록이 빈 목록보다 낫습니다.
 */
public final class SearchTitle {

    private SearchTitle() {}

    /** 꼬리의 권차를 뗀 표제. 뗄 것이 없으면 받은 그대로입니다. */
    public static String withoutVolume(String title) {
        if (title == null) return null;
        String shown = title.strip();
        Volume volume;
        try {
            volume = BibNormalizer.parseTitle(shown).volume();
        } catch (RuntimeException e) {
            return title;   // 못 읽는 표제는 건드리지 않습니다.
        }
        if (volume == null) return title;
        String mark = volume.mark();
        if (mark == null || mark.isBlank() || !shown.endsWith(mark)) return title;
        String head = shown.substring(0, shown.length() - mark.length());
        if (head.isEmpty() || !isSeparator(head.charAt(head.length() - 1))) return title;
        int end = head.length();
        while (end > 0 && isSeparator(head.charAt(end - 1))) end--;
        String core = head.substring(0, end);
        return core.isBlank() ? title : core;
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c) || c == '.' || c == ',' || c == '-' || c == '·' || c == ':';
    }
}

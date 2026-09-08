package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 정보나루가 실제로 돌려준 문자열을 그대로 고정해 둔 테스트입니다.
 *
 * <p>인증키가 활성화되기 전에는 표준 KORMARC 표기(<code>" : "</code>, <code>" ; "</code>)를
 * 가정하고 만들었는데, 실제 응답을 받아 보니 <b>구분 기호 주변 공백이 제각각</b>이었습니다.
 * 같은 책의 다른 판이 서로 다른 형태로 옵니다.
 *
 * <pre>
 * "코스모스 :특별판 "              콜론 뒤에 공백이 없고 끝에 공백이 붙음
 * "칼 세이건 지음 ;홍승수 옮김"     세미콜론 뒤에 공백이 없음
 * "칼 세이건 지음;홍승수 옮김"      세미콜론 앞뒤로 공백이 없음
 * </pre>
 *
 * <p>여기 적힌 문자열은 <b>지어낸 것이 아니라 받은 그대로</b>입니다. 파서를 고칠 때 이
 * 테스트가 실제 데이터를 대신합니다.
 */
class RealResponseShapeTest {

    @Test
    @DisplayName("정보나루 표기와 표준 표기가 같은 결과를 준다")
    void realAndStandardNotationAgree() {
        var real = BibNormalizer.parseTitle("코스모스 :특별판 ");
        var standard = BibNormalizer.parseTitle("코스모스 : 특별판");

        assertEquals("코스모스", real.titleProper(), "제목에 콜론이 남으면 화면에 그대로 나옵니다");
        assertEquals("코스모스", standard.titleProper());
        assertEquals("특별판", real.subtitle());
        assertEquals("특별판", standard.subtitle());
        assertEquals(List.of("특별판"), real.editionTokens());
        assertEquals(List.of("특별판"), standard.editionTokens(),
                "판본 이름을 잃으면 화면에서 판을 구분해 보여 줄 수 없습니다");
        assertEquals(real.titleKeyCore(), standard.titleKeyCore());
    }

    @Test
    @DisplayName("세미콜론 주변 공백이 어떻든 저자와 역자가 갈린다")
    void authorSplitSurvivesWhitespace() {
        // 정규화 규칙 전체가 이 필드의 모양 위에 서 있습니다. 셋 다 실제로 받은 형태입니다.
        for (String raw : List.of(
                "칼 세이건 지음 ;홍승수 옮김",
                "칼 세이건 지음;홍승수 옮김",
                "칼 세이건 지음 ; 홍승수 옮김")) {
            List<Contributor> parsed = BibNormalizer.parseContributors(raw);

            assertEquals(2, parsed.size(), raw);
            assertEquals("칼 세이건", parsed.get(0).name(), raw);
            assertEquals(Contributor.Role.AUTHOR, parsed.get(0).role(), raw);
            assertEquals("홍승수", parsed.get(1).name(), raw);
            assertEquals(Contributor.Role.TRANSLATOR, parsed.get(1).role(), raw);
        }
    }

    @Test
    @DisplayName("각색 표기가 부제로 밀려나도 병합 거부 근거를 잃지 않는다")
    void adaptationInSubtitleIsStillFound() {
        // **구분 기호를 느슨하게 고치면서 같이 고쳐야 했던 부분입니다.**
        // 부제가 분리되기 시작하면 「청소년판」이 표제에서 사라지는데, 표제만 보고 각색 표기를
        // 찾으면 그 순간 「데미안」과 그냥 합쳐집니다. 각색본을 소장으로 세면 헛걸음입니다.
        for (String raw : List.of("데미안 : 청소년판", "데미안 :청소년판", "데미안(청소년판)")) {
            var parts = BibNormalizer.parseTitle(raw);
            assertTrue(parts.adaptationTokens().contains("청소년판"),
                    raw + " 에서 각색 표기를 놓쳤습니다: " + parts.adaptationTokens());
        }
    }

    @Test
    @DisplayName("공백 없는 콜론과 슬래시는 쪼개지 않는다")
    void doesNotSplitOnBareDelimiters() {
        // 한쪽 공백만 요구하는 것이지 아예 없어도 되는 것은 아닙니다.
        assertEquals("입/출력의 이해", BibNormalizer.parseTitle("입/출력의 이해").titleProper());
        assertNull(BibNormalizer.parseTitle("10:30에 만나요").subtitle());
    }
}

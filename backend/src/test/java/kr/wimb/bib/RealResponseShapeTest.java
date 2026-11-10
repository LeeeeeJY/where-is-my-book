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

    /**
     * <b>저자 필드는 한 가지 모양으로 오지 않습니다.</b> 2026-11-10 에 「이방인」 검색 한 번의
     * 결과 안에서 아래 모양이 전부 나왔습니다. 첫째 모양만 읽던 때에는 셋째의 주저자가
     * 저자와 무관하게 「)」 한 글자였고, 그것이 화면의 저자 칸과 저자 되찾기 질의로 그대로
     * 나갔습니다.
     */
    @Test
    @DisplayName("역할이 이름 뒤에 오든 앞에 오든 괄호에 들든 주저자는 사람 이름이다")
    void primaryAuthorSurvivesEveryNotation() {
        for (String raw : List.of(
                "알베르 카뮈 지음;이휘영 옮김",
                "알베르 카뮈 지음 ;김진하 옮김",
                "지은이: 알베르 카뮈 ;옮긴이: 김화영",
                "원작: 알베르 카뮈 ;옮긴이: 이재룡",
                "[원저자]: 알베르 카뮈;삽화: 손창복",
                "지은이: 알베르 카뮈 ;김준우",
                "알베르 카뮈 (지은이), 이정서 (옮긴이)",
                "알베르 카뮈, Matthew Ward (지은이)",
                // 배포한 뒤에도 저자 칸이 「알베르 카뮈 [」로 나가던 판입니다.
                "알베르 카뮈 [지음] ;유기환 옮김")) {
            var primary = BibNormalizer.primaryAuthor(BibNormalizer.parseContributors(raw));
            assertEquals("알베르 카뮈", primary == null ? null : primary.name(), raw);
        }
    }

    @Test
    @DisplayName("역할이 앞에 오는 표기에서 역자를 저자로 읽지 않는다")
    void roleFirstNotationKeepsTheTranslatorOut() {
        assertEquals(
                List.of(new Contributor("알베르 카뮈", Contributor.Role.AUTHOR),
                        new Contributor("김화영", Contributor.Role.TRANSLATOR)),
                BibNormalizer.parseContributors("지은이: 알베르 카뮈 ;옮긴이: 김화영"));
        assertEquals(
                List.of(new Contributor("알베르 카뮈", Contributor.Role.ORIGINAL_AUTHOR),
                        new Contributor("손창복", Contributor.Role.ILLUSTRATOR)),
                BibNormalizer.parseContributors("[원저자]: 알베르 카뮈;삽화: 손창복"));
    }

    /**
     * 대괄호는 「자료에 없어 사서가 보충했다」는 표시입니다. 첫 배포 뒤 「이방인」 저작 100개
     * 가운데 넷의 저자 칸이 여전히 깨져 있었고, 그중 셋이 이 모양이었습니다(2026-11-10).
     */
    @Test
    @DisplayName("대괄호는 떼고 읽는다. 역할이든 책임표시 전체든")
    void squareBracketsAreDropped() {
        assertEquals(
                List.of(new Contributor("알베르 까뮈", Contributor.Role.AUTHOR),
                        new Contributor("박용철", Contributor.Role.TRANSLATOR)),
                BibNormalizer.parseContributors("알베르 까뮈 [지음];박용철 옮김"));
        assertEquals(
                List.of(new Contributor("알베르 까뮈", Contributor.Role.AUTHOR),
                        new Contributor("김용길...등", Contributor.Role.ILLUSTRATOR)),
                BibNormalizer.parseContributors("[알베르 까뮈 지음];김용길...[등]그림"));
    }

    /**
     * 모르는 역할은 지어내지 않고 받은 글자를 그대로 둡니다. 정보나루의 도서 검색에 섞여 오는
     * 영상물이 이 모양입니다.
     */
    @Test
    @DisplayName("괄호 안이 모르는 말이면 원문을 그대로 둔다")
    void unknownRolesInParensAreLeftAsReceived() {
        String raw = "오손 웰즈 (감독), 오손 웰즈, 에드워드 G. 로빈슨, 로레타 영 (출연)";
        assertEquals(List.of(new Contributor(raw, Contributor.Role.AUTHOR)),
                BibNormalizer.parseContributors(raw));
    }

    @Test
    @DisplayName("괄호 표기에서는 쉼표가 사람을 나누고, 괄호 앞의 이름은 모두 그 역할이다")
    void roleInParensSplitsPeopleByComma() {
        assertEquals(
                List.of(new Contributor("잠뜰TV", Contributor.Role.ORIGINAL_AUTHOR),
                        new Contributor("uno", Contributor.Role.AUTHOR),
                        new Contributor("김기수", Contributor.Role.ILLUSTRATOR),
                        new Contributor("이현구", Contributor.Role.ILLUSTRATOR)),
                BibNormalizer.parseContributors("잠뜰TV (원작), uno (글), 김기수, 이현구 (그림)"));
    }

    @Test
    @DisplayName("역할어가 아닌 괄호는 이름의 일부로 두고, 괄호 없는 쉼표는 여전히 도치다")
    void otherParensAndCommasAreLeftAlone() {
        assertEquals(
                List.of(new Contributor("홍길동(洪吉童)", Contributor.Role.AUTHOR)),
                BibNormalizer.parseContributors("홍길동(洪吉童) 지음"));
        assertEquals(
                List.of(new Contributor("롤링, 조앤 캐슬린", Contributor.Role.AUTHOR)),
                BibNormalizer.parseContributors("롤링, 조앤 캐슬린"));
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

    /**
     * <b>큰글자책이 보통 판과 한 저작으로 합쳐져 있었습니다.</b> 김영사는 그 표기를 부제가 아니라
     * 대등표제 자리에 적습니다(2026-11-10, 정보나루 상세 페이지에서 확인한 등록 표제). 판본·각색
     * 표기를 본표제와 부제에서만 찾던 때에는 이 판이 각색 표기가 없는 책으로 읽혔습니다.
     */
    @Test
    @DisplayName("각색 표기가 대등표제 자리에 있어도 병합 거부 근거를 잃지 않는다")
    void adaptationInParallelTitleIsStillFound() {
        for (String raw : List.of(
                "숙론 =큰글자책 /Discourse ",
                "식물학자의 노트 =큰글자책 /Notes of a botanist ",
                "존 리, 새로운 10년의 시작 =큰글자책 /The beginning of next 10 years ")) {
            var parts = BibNormalizer.parseTitle(raw);
            assertTrue(parts.adaptationTokens().contains("큰글자책"),
                    raw + " 에서 각색 표기를 놓쳤습니다: " + parts.adaptationTokens());
        }
    }

    @Test
    @DisplayName("대등표제가 보통의 다른 말이면 각색 표기를 지어내지 않는다")
    void ordinaryParallelTitleAddsNothing() {
        // 같은 책의 보통 판은 그 자리에 부제를 적어 두었습니다. 이쪽까지 각색으로 읽으면
        // 반대로 멀쩡한 판이 갈라집니다.
        var plain = BibNormalizer.parseTitle(
                "식물학자의 노트 =식물이 내게 들려준 이야기 /Notes of a botanist ");
        assertEquals(List.of(), plain.adaptationTokens());
        assertEquals("식물학자의 노트", plain.titleProper());
        assertEquals(List.of(), BibNormalizer.parseTitle("코스모스 = Cosmos").adaptationTokens());
    }

    @Test
    @DisplayName("판본 표기가 대등표제 자리에 있으면 판본 표기로 읽는다")
    void editionInParallelTitleIsFound() {
        assertEquals(List.of("특별판"), BibNormalizer.parseTitle("코스모스 =특별판").editionTokens());
    }

    @Test
    @DisplayName("공백 없는 콜론과 슬래시는 쪼개지 않는다")
    void doesNotSplitOnBareDelimiters() {
        // 한쪽 공백만 요구하는 것이지 아예 없어도 되는 것은 아닙니다.
        assertEquals("입/출력의 이해", BibNormalizer.parseTitle("입/출력의 이해").titleProper());
        assertNull(BibNormalizer.parseTitle("10:30에 만나요").subtitle());
    }
}

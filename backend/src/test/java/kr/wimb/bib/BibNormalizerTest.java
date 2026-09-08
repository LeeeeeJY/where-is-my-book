package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BibNormalizerTest {

    @Nested
    @DisplayName("표제 분해")
    class TitleParsing {

        @Test
        @DisplayName("KORMARC 구분 기호가 섞인 표제를 분해한다")
        void splitsKormarcSubfields() {
            TitleParts p = BibNormalizer.parseTitle(
                    "해리 포터와 마법사의 돌 = Harry Potter and the sorcerer's stone : 1부"
                            + " / J.K. 롤링 지음 ; 강동혁 옮김");

            assertEquals("해리 포터와 마법사의 돌", p.titleProper());
            assertEquals("Harry Potter and the sorcerer's stone", p.parallelTitle());
            assertEquals("1부", p.subtitle());
            assertEquals("J.K. 롤링 지음 ; 강동혁 옮김", p.statementOfResponsibility());
            assertEquals("해리포터와마법사의돌", p.titleKeyCore());
        }

        @Test
        @DisplayName("부표제의 숫자를 권차로 오인하지 않는다")
        void doesNotTakeVolumeFromSubtitle() {
            // 부표제의 숫자는 시리즈 번호일 때가 많다. 권차로 삼으면 같은 책이 갈린다.
            TitleParts a = BibNormalizer.parseTitle("해리 포터와 마법사의 돌 : 해리 포터 시리즈 1");
            TitleParts b = BibNormalizer.parseTitle("해리포터와 마법사의돌");

            assertNull(a.volNo());
            assertEquals(b.titleKeyCore(), a.titleKeyCore());
        }

        @Test
        @DisplayName("판본 표기는 키에서 빼고 따로 보관한다")
        void extractsEditionTokens() {
            TitleParts p = BibNormalizer.parseTitle("코스모스 (특별판)");
            assertEquals(BibNormalizer.parseTitle("코스모스").titleKeyCore(), p.titleKeyCore());
            assertEquals(List.of("특별판"), p.editionTokens());
        }

        @Test
        @DisplayName("각색 표기는 키에서 빠지되 별도로 탐지된다")
        void extractsAdaptationTokens() {
            // 키가 같아져야 같은 후보 묶음에 들어가고, 그다음 각색 표기가 병합을 막는다.
            TitleParts plain = BibNormalizer.parseTitle("데미안");
            TitleParts youth = BibNormalizer.parseTitle("데미안 (청소년판)");

            assertEquals(plain.titleKeyCore(), youth.titleKeyCore());
            assertEquals(List.of("청소년판"), youth.adaptationTokens());
            assertTrue(plain.adaptationTokens().isEmpty());
        }

        @Test
        @DisplayName("한글 뒤 괄호 한자는 별칭 키로 살린다")
        void keepsHanjaAsAlias() {
            TitleParts p = BibNormalizer.parseTitle("난중일기(亂中日記)");
            assertEquals("난중일기", p.titleKeyCore());
            assertTrue(p.aliasKeys().contains("亂中日記"));
        }
    }

    @Nested
    @DisplayName("권차 추출")
    class VolumeExtraction {

        @Test
        @DisplayName("여러 표기 형태를 인식한다")
        void recognizesForms() {
            assertEquals(2, BibNormalizer.parseTitle("미움받을 용기 2").volNo());
            assertEquals(3, BibNormalizer.parseTitle("토지 3권").volNo());
            assertEquals(1, BibNormalizer.parseTitle("토지 제1권").volNo());
            assertEquals(1, BibNormalizer.parseTitle("레·미제라블 Ⅰ").volNo());
            assertEquals(3, BibNormalizer.parseTitle("토지 (하)").volNo());
            assertEquals(3, BibNormalizer.parseTitle("듄 3").volNo());
        }

        @Test
        @DisplayName("표제의 일부인 숫자를 권차로 오인하지 않는다")
        void avoidsFalsePositives() {
            assertNull(BibNormalizer.parseTitle("1984").volNo());
            assertEquals("1984", BibNormalizer.parseTitle("1984").titleKeyCore());
            assertNull(BibNormalizer.parseTitle("코스모스 2020").volNo());
            assertNull(BibNormalizer.parseTitle("82년생 김지영").volNo());
        }

        /**
         * <b>실제로 이렇게 잘리고 있었습니다.</b> 상·중·하를 붙여 쓴 것까지 잡는 바람에
         * 「천하」가 「천」의 3권이 되었습니다. 표제 키가 「천」으로 바뀌므로 「천하」를
         * 찾는 사람에게 그 책은 <b>제목이 안 맞는 것으로 밀려</b> 목록 아래로 내려가고,
         * 상·중·하로 나온 책이 아닌데 낱권으로 갈라져 <b>세트 취급을 받습니다.</b>
         */
        @Test
        @DisplayName("붙여 쓴 상·중·하는 권차가 아니다")
        void attachedSangHaIsNotAVolume() {
            for (String title : List.of("천하", "지하", "은하", "명상", "환상", "집중")) {
                assertNull(BibNormalizer.parseTitle(title).volNo(),
                        title + " 는 낱권이 아니라 그 자체가 표제입니다");
                assertEquals(title, BibNormalizer.parseTitle(title).titleProper(),
                        title + " 의 표제가 잘리면 안 됩니다");
            }
        }

        @Test
        @DisplayName("공백이나 괄호로 떨어진 상·중·하는 권차로 읽는다")
        void separatedSangHaIsAVolume() {
            assertEquals(1, BibNormalizer.parseTitle("토지 상").volNo());
            assertEquals(2, BibNormalizer.parseTitle("토지 중").volNo());
            assertEquals(3, BibNormalizer.parseTitle("토지 하").volNo());
            assertEquals(1, BibNormalizer.parseTitle("토지 상권").volNo());
            assertEquals(1, BibNormalizer.parseTitle("토지(상)").volNo());
            assertEquals("토지", BibNormalizer.parseTitle("토지 상").titleProper());
        }

        /**
         * 정보나루가 권차를 {@code vol} 로 따로 주는데 매뉴얼에 형식이 적혀 있지 않습니다.
         * 숫자가 아니라고 버리면 그 책들은 권차가 없는 것이 되어 <b>상·중·하가 한 저작으로
         * 합쳐지고</b>, 상권만 가진 도서관이 「있음」으로 나옵니다.
         */
        @Test
        @DisplayName("권차 필드가 숫자가 아니어도 읽을 수 있으면 읽는다")
        void readsAVolumeFieldThatIsNotDigits() {
            assertEquals(3, BibNormalizer.volumeOrdinal("3"));
            assertEquals(3, BibNormalizer.volumeOrdinal("제3권"));
            // 숫자를 이어 붙이면 두 권을 묶은 「1-2」 가 12권이 됩니다. 실제로 「레 미제라블
            // 12권」이 목록 6위에 나왔는데 민음사 판은 다섯 권뿐입니다.
            assertEquals(1, BibNormalizer.volumeOrdinal("1-2"));
            assertEquals(1, BibNormalizer.volumeOrdinal("1,2"));
            assertEquals(1, BibNormalizer.volumeOrdinal("1~3"));
            assertEquals(1, BibNormalizer.volumeOrdinal("상"));
            assertEquals(2, BibNormalizer.volumeOrdinal("중"));
            assertEquals(3, BibNormalizer.volumeOrdinal("하"));
            assertEquals(1, BibNormalizer.volumeOrdinal("상권"));
            assertEquals(2, BibNormalizer.volumeOrdinal("II"));
            assertNull(BibNormalizer.volumeOrdinal(null));
            assertNull(BibNormalizer.volumeOrdinal("  "));
            assertNull(BibNormalizer.volumeOrdinal("전집"));
        }
    }

    @Nested
    @DisplayName("책임표시 분해")
    class Contributors {

        @Test
        @DisplayName("세미콜론과 역할어로 사람을 나눈다")
        void splitsBySemicolonAndRole() {
            assertEquals(
                    List.of(new Contributor("조앤 K. 롤링", Contributor.Role.AUTHOR),
                            new Contributor("강동혁", Contributor.Role.TRANSLATOR)),
                    BibNormalizer.parseContributors("조앤 K. 롤링 지음 ; 강동혁 옮김"));

            assertEquals(
                    List.of(new Contributor("J.K. 롤링", Contributor.Role.AUTHOR),
                            new Contributor("강동혁", Contributor.Role.TRANSLATOR)),
                    BibNormalizer.parseContributors("J.K. 롤링 지음, 강동혁 옮김"));
        }

        @Test
        @DisplayName("쉼표는 목록이 아니라 도치 구분자다")
        void commaIsInversionNotList() {
            assertEquals(
                    List.of(new Contributor("롤링, 조앤 캐슬린", Contributor.Role.AUTHOR)),
                    BibNormalizer.parseContributors("롤링, 조앤 캐슬린"));
        }

        @Test
        @DisplayName("매칭 키에는 저자만 넣고 역자는 넣지 않는다")
        void primaryAuthorSkipsTranslator() {
            var contributors = BibNormalizer.parseContributors("조앤 K. 롤링 지음 ; 강동혁 옮김");
            assertEquals("조앤 K. 롤링", BibNormalizer.primaryAuthor(contributors).name());
        }
    }

    @Nested
    @DisplayName("공유 정규화 함수")
    class Normalization {

        @Test
        @DisplayName("적재와 질의가 같은 결과를 내야 한다")
        void ingestAndQueryAgree() {
            // 서로 다른 정규화를 적용하면 색인과 질의가 조용히 어긋난다.
            String indexed = BibNormalizer.parseTitle("총, 균, 쇠 (개정판)").titleKeyCore();
            String queried = BibNormalizer.normalizeKey("총 균 쇠");
            assertEquals(indexed, queried);
        }

        @Test
        @DisplayName("공백과 구두점을 제거한다")
        void stripsWhitespaceAndPunctuation() {
            assertEquals("총균쇠", BibNormalizer.normalizeKey("총·균·쇠"));
            assertEquals("총균쇠", BibNormalizer.normalizeKey("총, 균, 쇠"));
        }

        @Test
        @DisplayName("출판사의 법인격 표기를 제거한다")
        void stripsLegalForms() {
            assertEquals("문학수첩", BibNormalizer.normalizePublisher("(주)문학수첩"));
            assertEquals("문학수첩", BibNormalizer.normalizePublisher("문학수첩"));
            // 사로 끝나는 상호 자체는 건드리지 않는다.
            assertEquals("문학과지성사", BibNormalizer.normalizePublisher("문학과지성사"));
        }
    }
}

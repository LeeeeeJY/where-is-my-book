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

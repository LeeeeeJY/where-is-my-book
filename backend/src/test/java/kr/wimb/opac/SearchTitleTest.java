package kr.wimb.opac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 도서관의 제목 검색에 넣는 표제. 화면의 표제에서 꼬리의 권차만 뗍니다.
 *
 * <p>2026-11-11 에 「해리 포터와 마법사의 돌 1」로 찾으면 0건이고 「해리 포터와 마법사의 돌」로는 나오는
 * OPAC 을 셋 봤습니다. 반대로 떼면 안 되는 것을 떼면 다른 책을 찾게 됩니다.
 */
class SearchTitleTest {

    @Test
    @DisplayName("띄어 적힌 꼬리의 권차를 뗀다")
    void dropsATrailingVolume() {
        assertEquals("해리 포터와 마법사의 돌", SearchTitle.withoutVolume("해리 포터와 마법사의 돌 1"));
        assertEquals("레 미제라블", SearchTitle.withoutVolume("레 미제라블 1"));
        assertEquals("원피스", SearchTitle.withoutVolume("원피스 100"));
        assertEquals("토지", SearchTitle.withoutVolume("토지 상"));
        assertEquals("미움받을 용기", SearchTitle.withoutVolume("미움받을 용기 2"));
    }

    @Test
    @DisplayName("권차가 없는 표제는 그대로 둔다")
    void leavesPlainTitlesAlone() {
        assertEquals("82년생 김지영", SearchTitle.withoutVolume("82년생 김지영"));
        assertEquals("코스모스", SearchTitle.withoutVolume("코스모스"));
        assertEquals("마의 산", SearchTitle.withoutVolume("마의 산"));
    }

    @Test
    @DisplayName("붙여 적힌 글자와 연도는 권차로 떼지 않는다")
    void doesNotCutWordsOrYears() {
        assertEquals("천하", SearchTitle.withoutVolume("천하"));
        assertEquals("명상", SearchTitle.withoutVolume("명상"));
        assertEquals("1984", SearchTitle.withoutVolume("1984"));
        assertEquals("코스모스 2020", SearchTitle.withoutVolume("코스모스 2020"));
    }

    @Test
    @DisplayName("빈 값은 그대로 돌려준다")
    void passesNullThrough() {
        assertNull(SearchTitle.withoutVolume(null));
        assertEquals("", SearchTitle.withoutVolume(""));
    }
}

package kr.wimb.bib;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class IsbnTest {

    @Test
    @DisplayName("부가기호가 뒤에 붙어도 13자리를 잘라 낸다")
    void stripsSupplementaryCode() {
        assertEquals("9788983920775", Isbn.canonicalize("9788983920775 03840").orElseThrow());
        assertEquals("9788983920775", Isbn.canonicalize("9788983920775(03840)").orElseThrow());
        assertEquals("9788983920775", Isbn.canonicalize("978-89-8392-077-5").orElseThrow());
    }

    @Test
    @DisplayName("ISBN10을 ISBN13으로 변환한다")
    void convertsIsbn10() {
        assertEquals("9788983920775", Isbn.canonicalize("89-8392-077-7").orElseThrow());
        assertEquals("9788983920775", Isbn.canonicalize("8983920777").orElseThrow());
    }

    @Test
    @DisplayName("체크디지트가 맞지 않으면 거부한다")
    void rejectsBadCheckDigit() {
        assertTrue(Isbn.canonicalize("9788983920776").isEmpty());
    }

    @Test
    @DisplayName("자리 채우기용 더미를 거부한다")
    void rejectsDummyValues() {
        // 이 값은 ISBN10 경로를 타면 체크디지트까지 맞는 그럴듯한 ISBN13이 되어 버린다.
        assertTrue(Isbn.canonicalize("0000000000000").isEmpty());
        assertTrue(Isbn.canonicalize("1111111111").isEmpty());
        assertTrue(Isbn.canonicalize("").isEmpty());
        assertTrue(Isbn.canonicalize(null).isEmpty());
    }

    @Test
    @DisplayName("도서가 아닌 접두를 거부한다")
    void rejectsNonBookPrefix() {
        assertTrue(Isbn.canonicalize("1234567890128").isEmpty());
    }

    @Test
    @DisplayName("오타 난 ISBN13을 ISBN10으로 재해석하지 않는다")
    void doesNotReinterpretBrokenIsbn13() {
        // 앞 10자리가 우연히 유효한 ISBN10이더라도 전혀 다른 책의 번호가 만들어진다.
        assertTrue(Isbn.canonicalize("9788983920776").isEmpty());
    }
}

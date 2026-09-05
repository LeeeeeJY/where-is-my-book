package kr.wimb.bib;

import java.util.Optional;

/**
 * ISBN 정규화. 국내 데이터에는 ISBN 필드에 부가기호가 섞여 들어오는 경우가 흔하므로
 * ({@code "9788983920775 03840"}), 잘라 내고 체크디지트로 검증합니다.
 *
 * <p>체크디지트 검증을 반드시 거치는 이유는, 잘라 내기가 우연히 13자리를 만들어 낼 수 있기
 * 때문입니다. 검증 없이 받아들이면 존재하지 않는 ISBN으로 소장 조회를 하게 됩니다.
 */
public final class Isbn {

    private Isbn() {}

    /**
     * 어떤 형태로 들어오든 ISBN13으로 정규화합니다. 판별할 수 없으면 비어 있는 값을 돌려줍니다.
     * 예외를 던지지 않는 것이 의도적입니다. ISBN이 없는 자료는 정상 범위이므로
     * 건수만 세어 남기고 넘어가야 합니다.
     */
    public static Optional<String> canonicalize(String raw) {
        if (raw == null) return Optional.empty();

        String cleaned = raw.replaceAll("[^0-9Xx]", "").toUpperCase();

        // 자리 채우기용 더미는 입력 단계에서 걸러야 합니다. 변환을 거치고 나면
        // 「0000000000」이 체크디지트까지 맞는 그럴듯한 ISBN13으로 바뀌어 버립니다.
        if (cleaned.isEmpty() || isRepeatedDigit(cleaned)) return Optional.empty();

        // 13자리 우선. 부가기호가 뒤에 붙은 경우가 여기서 걸립니다.
        if (cleaned.length() >= 13) {
            String candidate = cleaned.substring(0, 13);
            if (isValidIsbn13(candidate) && hasUsablePrefix(candidate)) {
                return Optional.of(candidate);
            }
            // 978/979로 시작하는데 검증에 실패했다면 오타가 난 ISBN13입니다.
            // 앞 10자리를 ISBN10으로 재해석하면 전혀 다른 책의 번호가 만들어질 수 있으므로
            // 시도하지 않고 포기합니다.
            if (hasUsablePrefix(candidate)) return Optional.empty();
        }
        // 10자리를 13자리로 변환합니다.
        if (cleaned.length() >= 10) {
            String candidate = cleaned.substring(0, 10);
            if (isValidIsbn10(candidate)) return Optional.of(toIsbn13(candidate));
        }
        return Optional.empty();
    }

    public static boolean isValidIsbn13(String s) {
        if (s.length() != 13 || !s.chars().allMatch(Character::isDigit)) return false;
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (s.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        int check = (10 - sum % 10) % 10;
        return check == s.charAt(12) - '0';
    }

    public static boolean isValidIsbn10(String s) {
        if (s.length() != 10) return false;
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c)) return false;
            sum += (c - '0') * (10 - i);
        }
        char last = s.charAt(9);
        int check = last == 'X' ? 10 : (Character.isDigit(last) ? last - '0' : -1);
        if (check < 0) return false;
        return (sum + check) % 11 == 0;
    }

    /** ISBN10을 978 접두의 ISBN13으로 변환하고 체크디지트를 다시 계산합니다. */
    public static String toIsbn13(String isbn10) {
        String body = "978" + isbn10.substring(0, 9);
        int sum = 0;
        for (int i = 0; i < 12; i++) {
            sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
        }
        return body + ((10 - sum % 10) % 10);
    }

    /** 도서 ISBN의 접두는 978 또는 979입니다. */
    private static boolean hasUsablePrefix(String isbn13) {
        return isbn13.startsWith("978") || isbn13.startsWith("979");
    }

    /** 같은 숫자만 반복되는 값은 자리 채우기용 더미입니다. */
    private static boolean isRepeatedDigit(String s) {
        return s.chars().distinct().count() == 1;
    }
}

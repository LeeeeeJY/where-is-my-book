package kr.wimb.bib;

import java.util.List;
import java.util.Set;
import java.util.Comparator;

/**
 * 정규화에 쓰이는 어휘 목록입니다.
 *
 * <p><b>판본 표기와 각색 표기를 절대 섞지 마세요.</b> 이 두 목록을 혼동하는 것이
 * 오병합의 가장 큰 원인입니다. 판본은 같은 내용의 다른 인쇄이므로 묶어야 하고,
 * 각색은 내용이 다른 별개 저작이므로 묶으면 안 됩니다.
 */
public final class Lexicons {

    private Lexicons() {}

    /**
     * 판본 표기. 키에서 <b>제거하고 묶습니다</b>.
     * 「코스모스」와 「코스모스(특별판)」은 같은 저작이고, 화면 표시만 다르게 합니다.
     */
    public static final List<String> EDITION_TOKENS = lengthDesc(List.of(
            "개정증보판", "전면개정판", "개정판", "증보판", "개역판", "신판",
            "초판", "재판", "제2판", "2판",
            "양장본", "양장", "반양장", "무선",
            "보급판", "문고판", "특별판", "한정판", "리커버", "합본판", "완역본"
    ));

    /**
     * 각색 표기. 키에서는 제거하되(같은 후보 묶음에 들어가야 하므로)
     * <b>병합 거부의 근거</b>로 씁니다.
     * 「데미안」과 「데미안(청소년판)」은 정규화하면 키가 같아지므로,
     * 이 목록이 없으면 반드시 잘못 합쳐집니다.
     */
    public static final List<String> ADAPTATION_TOKENS = lengthDesc(List.of(
            "만화로보는", "만화", "청소년판", "어린이", "그림책",
            "요약", "축약본", "쉽게읽는", "큰글자책", "큰글씨책", "대활자본",
            "점자", "오디오북", "전자책",
            "영어판", "일본어판", "원서"
    ));

    /**
     * 저자 역할어. 두 글자 이상은 붙여 써도 떼어 내지만,
     * 한 글자짜리는 이름 끝 글자와 구분되지 않으므로 앞에 구분자가 있을 때만 뗍니다.
     */
    public static final List<String> ROLE_WORDS = lengthDesc(List.of(
            "지음", "저자", "엮은이", "엮음", "편저", "편역", "옮긴이", "옮김",
            "번역", "그린이", "그림", "사진", "감수", "해설", "각색", "원작",
            "만화", "기획", "공저",
            "저", "글", "씀", "편", "역", "著", "編", "譯"
    ));

    /** 저작 매칭 키에 넣는 역할. 역자는 판본마다 다르고, 역자가 다른 판본이야말로 묶고 싶은 대상입니다. */
    public static final Set<String> KEY_ROLES = Set.of("AUTHOR", "ORIGINAL_AUTHOR");

    /** 키에서 제거하는 구두점과 기호. */
    public static final String PUNCTUATION = "·‧・ᆞ,.-–—~!?'\"“”‘’()[]{}<>《》「」『』〈〉:;/\\&+*_";

    /** 긴 것부터 지워야 「개정증보판」에서 「증보판」만 떨어져 「개정」이 남는 일이 없습니다. */
    private static List<String> lengthDesc(List<String> tokens) {
        return tokens.stream()
                .sorted(Comparator.comparingInt(String::length).reversed())
                .toList();
    }
}

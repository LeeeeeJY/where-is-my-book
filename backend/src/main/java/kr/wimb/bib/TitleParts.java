package kr.wimb.bib;

import java.util.List;

/**
 * 표제 필드를 분해한 결과입니다.
 *
 * @param titleProper      표제 (표시용 원형)
 * @param subtitle         부표제, 없으면 null
 * @param parallelTitle    대등표제(원서명 등), 없으면 null
 * @param seriesTitle      시리즈명, 없으면 null
 * @param volNo            권차. <b>표제 자체의 꼬리에서만 뽑습니다.</b> 아래 설명 참조
 * @param editionTokens    판본 표기. 키에서 빠지지만 화면에는 표시합니다
 * @param adaptationTokens 각색 표기. 병합 거부의 근거가 됩니다
 * @param titleKeyCore     군집화용 키
 * @param titleKeyFull     표제+부표제 키. 점수 계산과 동점 처리에 씁니다
 * @param aliasKeys        후보 묶기용 별칭 키
 * @param statementOfResponsibility 표제 필드에 섞여 들어온 책임표시. 저자 필드가 비었을 때 씁니다
 *
 * <p><b>권차를 표제 꼬리에서만 뽑는 이유:</b> 부표제의 숫자는 시리즈 번호인 경우가 많은데
 * 그것은 저작의 정체성이 아닙니다. 「해리 포터와 마법사의 돌 : 해리 포터 시리즈 1」과
 * 「해리포터와 마법사의 돌」은 같은 책이므로, 부표제의 1을 권차로 삼으면 권차 불일치로
 * 잘못 갈라집니다. 반면 「미움받을 용기 2」의 2는 표제의 일부이고 실제로 다른 책입니다.
 */
public record TitleParts(
        String titleProper,
        String subtitle,
        String parallelTitle,
        String seriesTitle,
        Integer volNo,
        List<String> editionTokens,
        List<String> adaptationTokens,
        String titleKeyCore,
        String titleKeyFull,
        List<String> aliasKeys,
        String statementOfResponsibility
) {}

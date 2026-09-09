package kr.wimb.bib;

import java.util.List;

/**
 * 표제 필드를 분해한 결과입니다.
 *
 * @param titleProper      표제 (표시용 원형)
 * @param subtitle         부표제, 없으면 null
 * @param parallelTitle    대등표제(원서명 등), 없으면 null
 * @param seriesTitle      시리즈명, 없으면 null
 * @param volume           권차. <b>표제 자체의 꼬리에서만 뽑습니다.</b> 아래 설명 참조. 순서를
 *                         정하는 서수와 사람에게 보여 줄 표기({@code Volume.label})를 함께 듭니다
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
        Volume volume,
        List<String> editionTokens,
        List<String> adaptationTokens,
        String titleKeyCore,
        String titleKeyFull,
        List<String> aliasKeys,
        String statementOfResponsibility
) {

    /**
     * 바깥에서 알아낸 권차를 채워 넣은 사본.
     *
     * <p><b>정보나루는 권차를 {@code vol} 로 따로 줍니다.</b> 표제에는 안 들어 있는 경우가
     * 많아서, 표제만 보면 「레미제라블」 1권부터 5권까지가 전부 같은 표제로 옵니다. 그러면
     * 권차가 모두 {@code null} 이 되어 <b>다섯 권이 한 저작으로 합쳐집니다.</b>
     *
     * <p>합쳐지면 두 가지가 한꺼번에 무너집니다. 화면에는 하나만 나와서 <b>낱권을 고를 수
     * 없고</b>, 그 하나의 ISBN 목록에 다섯 권이 다 들어가므로 <b>1권만 있는 도서관이
     * 「레미제라블 있음」으로 나옵니다.</b> 찾는 권이 없는데 있다고 답하는 것입니다.
     *
     * <p>표제 꼬리에서 이미 권차를 뽑았으면 그대로 둡니다. {@code vol} 이 없을 때 표제에서
     * 뽑은 값을 쓰는 것이 아니라, 그 반대로 <b>표제에서 못 뽑았을 때만</b> 이 값을 씁니다.
     * 표제에 적힌 「미움받을 용기 2」의 2가 더 믿을 만한 자리이기 때문입니다.
     */
    public TitleParts withVolume(Volume fromApi) {
        if (volume != null || fromApi == null) return this;
        return new TitleParts(titleProper, subtitle, parallelTitle, seriesTitle, fromApi,
                editionTokens, adaptationTokens, titleKeyCore, titleKeyFull, aliasKeys,
                statementOfResponsibility);
    }

    /** 숫자만 아는 권차를 채우는 지름길. 표기는 서수 그대로입니다. */
    public TitleParts withVolNo(Integer fromApi) {
        return withVolume(fromApi == null ? null : Volume.of(fromApi));
    }

    /**
     * 권차의 서수. 정렬과 「같은 권인지」 판정은 이것으로 합니다. 상·중·하는 1·2·3 이라
     * 상·하만 있는 책도 상이 하보다 앞에 섭니다. <b>화면에 적을 때는 이것을 쓰지 마세요.</b>
     * 상·하 두 권이 「1」「3」이 됩니다. 표기는 {@link Volume#mark()} 가 압니다.
     */
    public Integer volNo() {
        return volume == null ? null : volume.ordinal();
    }
}

package kr.wimb.index;

import java.time.LocalDate;
import java.util.List;

/**
 * 색인 문서 한 건. {@code search_doc_vN} 테이블의 한 행에 그대로 대응합니다.
 *
 * <p>소장 정보는 여기에 없습니다. 조회 시점에 {@code isbn13List} 전체로 정보나루에
 * 물어보고 캐시에서 답합니다.
 *
 * @param isbn13List 이 문서에서 가장 중요한 값입니다. 저작에 묶인 <b>모든 판본</b>의 ISBN 이
 *                   들어 있고 소장 조회는 이 목록 전체를 대상으로 합니다. 판본 하나만
 *                   조회하면 도서관이 다른 판을 가지고 있어도 미소장으로 나옵니다.
 * @param searchText 정규화된 검색 대상. 한 저작에 묶인 모든 판본의 표기를 모았기 때문에
 *                   어느 판본의 표기로 검색해도 걸립니다.
 */
public record SearchDoc(
        int workId,
        String titleDisplay,
        String authorDisplay,
        String publisherDisplay,
        LocalDate pubDate,
        Integer price,
        List<String> isbn13List,
        List<String> editionLabels,
        String searchText,
        String searchBigrams
) {}

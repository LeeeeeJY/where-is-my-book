import type { Library } from '../domain/types';

/**
 * **개발용 샘플입니다. 실제 데이터가 아닙니다.**
 *
 * 진짜 목록은 공공데이터포털의 도서관 정보 제공 서비스에서 받아 옵니다.
 * 전국 1,604개관이 들어오고, 도서관부호도 거기서만 나옵니다.
 *
 * 위경도가 비어 있는 항목은 전국도서관표준데이터와의 대조에 실패한 경우를 재현한 것입니다.
 * 그런 도서관은 "내 주변" 에는 나오지 않지만 이름 검색과 지역 계층에서는 찾을 수 있어야 합니다.
 */
export const SAMPLE_LIBRARIES: Library[] = [
  { libCode: '011001', shortId: 1, sido: '서울특별시', sigungu: '서초구', name: '국립중앙도서관', latitude: 37.4979, longitude: 127.0276, homepageUrl: 'https://www.nl.go.kr' },
  { libCode: '111001', shortId: 2, sido: '서울특별시', sigungu: '강남구', name: '강남도서관', latitude: 37.515, longitude: 127.047, homepageUrl: null },
  { libCode: '111002', shortId: 3, sido: '서울특별시', sigungu: '강남구', name: '논현문화마루도서관', latitude: 37.511, longitude: 127.029, homepageUrl: null },
  { libCode: '111003', shortId: 4, sido: '서울특별시', sigungu: '마포구', name: '마포중앙도서관', latitude: 37.556, longitude: 126.908, homepageUrl: null },
  { libCode: '111004', shortId: 5, sido: '서울특별시', sigungu: '마포구', name: '서강도서관', latitude: 37.551, longitude: 126.935, homepageUrl: null },
  { libCode: '111005', shortId: 6, sido: '서울특별시', sigungu: '서초구', name: '반포도서관', latitude: 37.503, longitude: 127.01, homepageUrl: null },
  { libCode: '111006', shortId: 7, sido: '서울특별시', sigungu: '송파구', name: '송파위례도서관', latitude: 37.479, longitude: 127.142, homepageUrl: null },
  { libCode: '111007', shortId: 8, sido: '서울특별시', sigungu: '은평구', name: '은평구립도서관', latitude: 37.618, longitude: 126.927, homepageUrl: null },
  { libCode: '111008', shortId: 9, sido: '서울특별시', sigungu: '종로구', name: '종로도서관', latitude: 37.579, longitude: 126.966, homepageUrl: null },
  { libCode: '111009', shortId: 10, sido: '서울특별시', sigungu: '노원구', name: '노원중앙도서관', latitude: 37.654, longitude: 127.056, homepageUrl: null },
  { libCode: '141053', shortId: 11, sido: '경기도', sigungu: '성남시', name: '성남시중원도서관', latitude: 37.43, longitude: 127.15, homepageUrl: null },
  { libCode: '141054', shortId: 12, sido: '경기도', sigungu: '성남시', name: '분당도서관', latitude: 37.382, longitude: 127.119, homepageUrl: null },
  { libCode: '141055', shortId: 13, sido: '경기도', sigungu: '성남시', name: '판교도서관', latitude: 37.395, longitude: 127.111, homepageUrl: null },
  { libCode: '141056', shortId: 14, sido: '경기도', sigungu: '수원시', name: '수원선경도서관', latitude: 37.283, longitude: 127.014, homepageUrl: null },
  { libCode: '141057', shortId: 15, sido: '경기도', sigungu: '수원시', name: '수원중앙도서관', latitude: 37.265, longitude: 127.0, homepageUrl: null },
  { libCode: '141058', shortId: 16, sido: '경기도', sigungu: '고양시', name: '고양시립화정도서관', latitude: 37.635, longitude: 126.834, homepageUrl: null },
  { libCode: '141059', shortId: 17, sido: '경기도', sigungu: '용인시', name: '용인시립중앙도서관', latitude: 37.241, longitude: 127.178, homepageUrl: null },
  { libCode: '141060', shortId: 18, sido: '경기도', sigungu: '안양시', name: '안양시립평촌도서관', latitude: 37.39, longitude: 126.95, homepageUrl: null },
  { libCode: '211001', shortId: 19, sido: '부산광역시', sigungu: '해운대구', name: '해운대도서관', latitude: 35.163, longitude: 129.164, homepageUrl: null },
  { libCode: '211002', shortId: 20, sido: '부산광역시', sigungu: '부산진구', name: '부산광역시립시민도서관', latitude: 35.155, longitude: 129.059, homepageUrl: null },
  { libCode: '221001', shortId: 21, sido: '대구광역시', sigungu: '중구', name: '대구중앙도서관', latitude: 35.869, longitude: 128.592, homepageUrl: null },
  { libCode: '231001', shortId: 22, sido: '인천광역시', sigungu: '연수구', name: '인천광역시립송도도서관', latitude: 37.386, longitude: 126.639, homepageUrl: null },
  { libCode: '241001', shortId: 23, sido: '광주광역시', sigungu: '북구', name: '광주광역시립무등도서관', latitude: null, longitude: null, homepageUrl: null },
  { libCode: '251001', shortId: 24, sido: '대전광역시', sigungu: '유성구', name: '대전한밭도서관', latitude: null, longitude: null, homepageUrl: null },
];

import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Analytics, type BeforeSendEvent } from '@vercel/analytics/react';
import App from './App';
import './styles.css';

/**
 * 방문자 수를 세기 전에 **주소에서 물음표 뒤를 떼어 냅니다.**
 *
 * 고른 도서관이 `?libs=` 로 주소에 실리는 것이 이 도구의 공유 기능이고
 * (`domain/selectionUrl.ts`), 선택이 바뀔 때마다 `App` 이 `replaceState` 로 주소를 고쳐
 * 씁니다. 그래서 주소를 그대로 넘기면 **어느 도서관을 골랐는지가 체크 한 번에 한 줄씩
 * Vercel 로 넘어갑니다.** 개인정보처리방침이 「고른 도서관은 이 기기에만 남는다」고 적어
 * 둔 것과 정면으로 어긋납니다.
 *
 * 떼어도 잃는 것이 없습니다. 화면이 하나뿐이라 어차피 셀 것은 경로뿐이고, 오히려 선택을
 * 바꿀 때마다 주소가 달라져 같은 화면이 여러 갈래로 흩어지는 것을 막아 줍니다.
 *
 * **이 함수를 떼지 마세요.** 떼면 오류도 경고도 없이 조용히 다시 새어 나가고, 그 자리에서
 * `public/privacy.html` 이 거짓말이 됩니다.
 */
function withoutQuery(event: BeforeSendEvent): BeforeSendEvent {
  const url = new URL(event.url, window.location.origin);
  url.search = '';
  return { ...event, url: url.toString() };
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
    {/*
      Vercel 웹 분석. 기기에 표시를 남기지 않고(쿠키도 브라우저 저장소도 쓰지 않습니다),
      스크립트는 남의 도메인이 아니라 같은 주소의 `/_vercel/insights/script.js` 에서
      옵니다. 개발 중에는 아무것도 보내지 않습니다.
    */}
    <Analytics beforeSend={withoutQuery} />
  </StrictMode>,
);

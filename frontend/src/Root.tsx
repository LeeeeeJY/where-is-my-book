import App from './App';
import { parseRoute, usePathname } from './domain/route';
import { Worldcup } from './worldcup/Worldcup';

/**
 * 주소에 따라 화면을 가릅니다.
 *
 * <p><b>월드컵은 `App` 밖에 있습니다.</b> `App` 은 도서관 목록을 받은 뒤에야 탭과 내용을
 * 그리는데, 월드컵은 광고에서 곧바로 들어오는 화면이라 그 목록을 기다릴 이유가 없고
 * API 서버가 내려가 있는 날에도 열려야 합니다. 후보 책은 화면이 들고 있는 고정 목록입니다.
 */
export default function Root() {
  const route = parseRoute(usePathname());
  if (route.kind === 'app') return <App />;
  return <Worldcup route={route} />;
}

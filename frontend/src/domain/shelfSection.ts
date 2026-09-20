/**
 * 서가에 선 갈래의 이름을 <b>화면에 쓸 만큼만 줄입니다.</b>
 *
 * <p>정보나루가 주는 분류명은 「문학 &gt; 한국문학 &gt; 소설」처럼 맨 앞에 대주제가
 * 붙어 옵니다. 그런데 **그 대주제는 지금 보고 있는 서가 그 자체**라, 목록의 모든 줄에
 * 같은 말이 되풀이됩니다. 스물여덟 줄이 전부 「문학 &gt; 」으로 시작하면 정작 갈리는
 * 부분이 오른쪽으로 밀려 읽히지 않습니다.
 *
 * <p>**이름을 우리가 지어내지는 않습니다.** 떼는 것은 맨 앞 한 칸뿐이고 나머지는 받은
 * 글자 그대로입니다. 분류번호로 갈래 이름을 만들면 그 표가 틀리는 날 서가가 엉뚱한
 * 이름을 답합니다.
 */
export function sectionLabel(name: string): string {
  const parts = name
    .split('>')
    .map((one) => one.trim())
    .filter((one) => one.length > 0);

  if (parts.length === 0) return '';
  // 한 칸뿐이면 뗄 것이 없습니다. 떼면 이름이 통째로 사라집니다.
  if (parts.length === 1) return parts[0];
  return parts.slice(1).join(' · ');
}

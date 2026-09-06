package kr.wimb.data4library;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.XMLConstants;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 정보나루 XML 응답을 항목 단위로 꺼냅니다.
 *
 * <p>매뉴얼(v20260210)은 항목 이름과 중첩 구조({@code libs > lib}, {@code docs > doc})는
 * 밝히지만 <b>최상위 요소 이름은 적어 두지 않았습니다.</b> 그래서 루트를 가정하지 않고
 * 문서 어디에 있든 해당 이름의 요소를 찾는 방식으로 읽습니다. 루트가 무엇이든 동작합니다.
 *
 * <p>XML 을 쓰는 이유는 매뉴얼이 기본 응답 형식으로 명시하고 구조까지 적어 두었기
 * 때문입니다. {@code format=json} 도 지원하지만 JSON 쪽 감싸는 구조는 문서에 없습니다.
 */
public final class Data4LibraryResponse {

    private Data4LibraryResponse() {}

    /**
     * 정보나루가 본문에 담아 보내는 오류.
     *
     * @param code {@code authErr}(인증정보 불일치), {@code vitalizationErr}(키 미활성) 등
     */
    public record ApiError(String code, String message) {}

    // 오류 응답에서만 나타나는 항목들입니다. XML 과 JSON 두 가지 형태를 모두 봅니다.
    // 실제로 libSrch 는 XML, srchBooks 는 JSON 으로 오류를 돌려주는 것을 확인했습니다.
    private static final Pattern XML_CODE = Pattern.compile("<errCode>\\s*([^<]*?)\\s*</errCode>");
    private static final Pattern XML_MESSAGE = Pattern.compile("<error>\\s*([^<]*?)\\s*</error>");
    private static final Pattern JSON_CODE = Pattern.compile("\"errCode\"\\s*:\\s*\"([^\"]*)\"");
    private static final Pattern JSON_MESSAGE = Pattern.compile("\"error\"\\s*:\\s*\"([^\"]*)\"");

    /**
     * 응답이 오류인지 봅니다.
     *
     * <p><b>정보나루는 오류도 HTTP 200 으로 돌려줍니다.</b> 상태 코드만 보고 넘기면 오류
     * 본문에는 {@code lib} 도 {@code doc} 도 없으므로 빈 목록이 되고, 화면은 그것을
     * <b>"미소장"으로 그립니다.</b> 멀쩡히 있는 책을 없다고 답하게 되는 자리라 반드시
     * 여기서 걸러야 합니다.
     *
     * <p>본문을 정규식으로 봅니다. XML 파서를 쓰면 HTML 오류 페이지 같은 응답에서
     * 예외가 나서 원인이 가려집니다.
     */
    public static Optional<ApiError> errorOf(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        String code = firstMatch(XML_CODE, body);
        if (code == null) code = firstMatch(JSON_CODE, body);
        if (code == null) return Optional.empty();

        String message = firstMatch(XML_MESSAGE, body);
        if (message == null) message = firstMatch(JSON_MESSAGE, body);
        return Optional.of(new ApiError(code, message));
    }

    private static String firstMatch(Pattern pattern, String body) {
        Matcher matcher = pattern.matcher(body);
        if (!matcher.find()) return null;
        String value = matcher.group(1);
        return value == null || value.isBlank() ? null : value;
    }

    /**
     * @param itemTag 반복되는 항목의 이름. 도서관이면 {@code lib}, 도서면 {@code doc} 입니다.
     * @return 항목마다 자식 요소의 이름과 값을 담은 맵. 값이 없는 자식은 넣지 않습니다.
     */
    public static List<Map<String, String>> items(String xml, String itemTag) {
        Document document = parse(xml);
        NodeList nodes = document.getElementsByTagName(itemTag);

        List<Map<String, String>> out = new ArrayList<>(nodes.getLength());
        for (int i = 0; i < nodes.getLength(); i++) {
            if (nodes.item(i) instanceof Element element) out.add(fieldsOf(element));
        }
        return out;
    }

    /** 목록 바깥의 단일 값을 읽습니다. {@code numFound} 같은 것입니다. */
    public static String scalar(String xml, String tag) {
        NodeList nodes = parse(xml).getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : text(nodes.item(0));
    }

    private static Map<String, String> fieldsOf(Element item) {
        Map<String, String> fields = new LinkedHashMap<>();
        NodeList children = item.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element child) {
                // 자식이 또 요소를 가지면 그 값은 여기서 다루지 않습니다.
                // callNumbers 처럼 한 겹 더 들어가는 항목은 부르는 쪽에서 따로 읽습니다.
                String value = text(child);
                if (value != null && !value.isBlank()) fields.put(child.getTagName(), value);
            }
        }
        return fields;
    }

    /** CDATA 로 감싸여 오는 값이 많아 {@code getTextContent} 로 읽습니다. */
    private static String text(Node node) {
        String raw = node.getTextContent();
        return raw == null ? null : raw.trim();
    }

    private static Document parse(String xml) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // 외부 엔티티를 막습니다. 남의 서버가 주는 문서를 파싱하는 자리이므로
            // 기본값에 맡기지 않고 명시적으로 끕니다.
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);

            return factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalArgumentException("정보나루 응답을 읽지 못했습니다: " + summarize(xml), e);
        }
    }

    /** 오류 메시지에 응답 전체를 쏟지 않되, 무엇이 왔는지는 알 수 있게 합니다. */
    private static String summarize(String xml) {
        if (xml == null) return "(없음)";
        String flat = xml.replaceAll("\\s+", " ").trim();
        return flat.length() <= 200 ? flat : flat.substring(0, 200) + "...";
    }
}

package kr.n.nframe.newfeature.odtcommon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ODT 임베디드 수식 객체(ObjectNN/) 누적 + manifest 미디어타입 관리.
 *
 * <p>PictureRegistry 와 동일한 모델: 키 = 객체 디렉터리명(예: "Object1"),
 * 값 = 그 객체의 content.xml(MathML 트리) 바이트.
 * 각 객체는 OpenDocument Formula 서브문서로 패키지에 기록된다.
 */
public final class MathRegistry {

    /** name(예: "Object1") → content.xml 바이트 */
    private final Map<String, byte[]> data = new LinkedHashMap<>();
    private int counter = 0;

    public static final String FORMULA_MEDIA_TYPE =
            "application/vnd.oasis.opendocument.formula";

    /**
     * 수식 객체 content.xml 을 등록하고 객체 디렉터리명(예: "Object1")을 반환.
     * 본문에서는 ./Object1 형태(xlink:href)로 참조한다.
     * @param contentXml 객체의 content.xml 전체 문자열(MathML 포함)
     */
    public String add(String contentXml) {
        String name = "Object" + (++counter);
        data.put(name, contentXml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return name;
    }

    public boolean isEmpty() { return data.isEmpty(); }

    /** name(객체 디렉터리명) → content.xml 바이트 */
    public Map<String, byte[]> entries() { return data; }
}

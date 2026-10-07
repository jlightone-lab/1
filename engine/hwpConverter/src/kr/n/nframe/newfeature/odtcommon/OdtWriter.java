package kr.n.nframe.newfeature.odtcommon;

import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OdtBuildContext 누적 상태 → content.xml / styles.xml / meta.xml 문자열 직렬화.
 *
 * <p>네임스페이스는 서연 리서치 정정 반영: fo = xsl-fo-compatible:1.0, svg = svg-compatible:1.0.
 * 출력은 OpenDocument 1.2.
 */
public final class OdtWriter {

    private static final String NS =
            " xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\""
          + " xmlns:style=\"urn:oasis:names:tc:opendocument:xmlns:style:1.0\""
          + " xmlns:text=\"urn:oasis:names:tc:opendocument:xmlns:text:1.0\""
          + " xmlns:table=\"urn:oasis:names:tc:opendocument:xmlns:table:1.0\""
          + " xmlns:draw=\"urn:oasis:names:tc:opendocument:xmlns:drawing:1.0\""
          + " xmlns:fo=\"urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0\""
          + " xmlns:xlink=\"http://www.w3.org/1999/xlink\""
          + " xmlns:svg=\"urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0\""
          + " xmlns:meta=\"urn:oasis:names:tc:opendocument:xmlns:meta:1.0\""
          + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"";

    private static final String XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private final OdtBuildContext ctx;

    public OdtWriter(OdtBuildContext ctx) { this.ctx = ctx; }

    public String contentXml() {
        StringBuilder sb = new StringBuilder(8192);
        sb.append(XML_DECL);
        sb.append("<office:document-content").append(NS)
          .append(" office:version=\"1.2\">");
        sb.append(fontFaceDecls());
        sb.append("<office:automatic-styles>")
          .append(ctx.styles.emit())
          .append("</office:automatic-styles>");
        sb.append("<office:body><office:text>");
        sb.append(ctx.body());
        sb.append("</office:text></office:body>");
        sb.append("</office:document-content>");
        return sb.toString();
    }

    public String stylesXml() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append(XML_DECL);
        sb.append("<office:document-styles").append(NS)
          .append(" office:version=\"1.2\">");
        sb.append(fontFaceDecls());

        // 기본 스타일
        sb.append("<office:styles>");
        sb.append("<style:default-style style:family=\"paragraph\">")
          .append("<style:text-properties style:font-name=\"함초롬바탕\""
                + " style:font-name-asian=\"함초롬바탕\" fo:font-size=\"10pt\""
                + " style:font-size-asian=\"10pt\"/>")
          .append("</style:default-style>");
        sb.append("<style:style style:name=\"Standard\" style:family=\"paragraph\""
                + " style:class=\"text\"/>");
        // 하이퍼링크 표준 스타일: 일반클릭 follow(이름규약으로 LibreOffice 인식) + 표준색
        sb.append("<style:style style:name=\"Internet_20_Link\""
                + " style:display-name=\"Internet Link\" style:family=\"text\">")
          .append("<style:text-properties fo:color=\"#0000FF\""
                + " style:text-underline-style=\"solid\""
                + " style:text-underline-width=\"auto\""
                + " style:text-underline-color=\"font-color\"/>")
          .append("</style:style>");
        sb.append("<style:style style:name=\"Visited_20_Internet_20_Link\""
                + " style:display-name=\"Visited Internet Link\" style:family=\"text\">")
          .append("<style:text-properties fo:color=\"#800080\""
                + " style:text-underline-style=\"solid\""
                + " style:text-underline-width=\"auto\""
                + " style:text-underline-color=\"font-color\"/>")
          .append("</style:style>");
        sb.append("</office:styles>");

        // 페이지 레이아웃 (A4 세로 기본)
        sb.append("<office:automatic-styles>");
        sb.append("<style:page-layout style:name=\"pm1\">")
          .append("<style:page-layout-properties"
                + " fo:page-width=\"21cm\" fo:page-height=\"29.7cm\""
                + " style:print-orientation=\"portrait\""
                + " fo:margin-top=\"2cm\" fo:margin-bottom=\"2cm\""
                + " fo:margin-left=\"2cm\" fo:margin-right=\"2cm\"/>")
          .append("</style:page-layout>");
        // BUG1/2 fix: 머리글·바닥글(master-page)이 참조하는 자동 스타일(P2/P3/T1/T2 등)을
        // styles.xml 의 automatic-styles 에도 함께 넣어야 LibreOffice 가 해석 가능.
        // (ODF 상 content.xml 과 styles.xml 의 automatic-styles 는 별개 스코프 → 동명 허용)
        sb.append(ctx.styles.emit());
        sb.append("</office:automatic-styles>");

        // master-page (머리글/바닥글 포함)
        sb.append("<office:master-styles>");
        sb.append("<style:master-page style:name=\"Standard\" style:page-layout-name=\"pm1\">");
        if (ctx.hasHeader()) {
            sb.append("<style:header>").append(ctx.headerContent()).append("</style:header>");
        }
        if (ctx.hasFooter()) {
            sb.append("<style:footer>").append(ctx.footerContent()).append("</style:footer>");
        }
        sb.append("</style:master-page>");
        sb.append("</office:master-styles>");

        sb.append("</office:document-styles>");
        return sb.toString();
    }

    /** style:font-name / style:font-name-asian 속성에서 폰트명 추출용. */
    private static final Pattern FONT_NAME_PAT =
            Pattern.compile("style:font-name(?:-asian)?=\"([^\"]*)\"");

    /**
     * BUG3 fix: content.xml/styles.xml 양쪽에 넣을 &lt;office:font-face-decls&gt; 생성.
     * ctx.styles.emit() 문자열과 styles.xml 기본값("함초롬바탕")에서 쓰이는 모든
     * 폰트명을 스캔·중복 제거(LinkedHashSet, 등장 순서 유지, 빈값 제외)한다.
     */
    private String fontFaceDecls() {
        Set<String> names = new LinkedHashSet<>();
        // styles.xml 기본 스타일에서 사용하는 폰트
        names.add("함초롬바탕");
        // 모든 자동 스타일에 박혀 있는 폰트명 수집
        Matcher m = FONT_NAME_PAT.matcher(ctx.styles.emit());
        while (m.find()) {
            String name = m.group(1);
            if (name != null && !name.isEmpty()) names.add(name);
        }
        StringBuilder sb = new StringBuilder(256);
        sb.append("<office:font-face-decls>");
        for (String name : names) {
            // v16t33 task1/2 안전망: 미설치 폰트가 큰 대체폰트로 떨어지지 않도록
            // generic-family·pitch·panose·대체 체인을 함께 발화(서연 리서치-0605).
            sb.append("<style:font-face style:name=\"").append(esc(name))
              .append("\" svg:font-family=\"").append(esc(FontPolicy.fontFamilyChain(name)))
              .append("\" style:font-family-generic=\"").append(esc(FontPolicy.genericFamily(name)))
              .append("\" style:font-pitch=\"").append(esc(FontPolicy.pitch(name)))
              .append("\" svg:panose-1=\"").append(esc(FontPolicy.panose(name)))
              .append("\"/>");
        }
        sb.append("</office:font-face-decls>");
        return sb.toString();
    }

    public String metaXml() {
        StringBuilder sb = new StringBuilder(512);
        sb.append(XML_DECL);
        sb.append("<office:document-meta").append(NS)
          .append(" office:version=\"1.2\">");
        sb.append("<office:meta>");
        sb.append("<meta:generator>hwpConverter-hwp2odt</meta:generator>");
        if (ctx.title() != null && !ctx.title().isEmpty()) {
            sb.append("<dc:title>").append(esc(ctx.title())).append("</dc:title>");
        }
        sb.append("</office:meta>");
        sb.append("</office:document-meta>");
        return sb.toString();
    }
}

package kr.n.nframe.newfeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ODT 문서의 중간 메모리 모델.
 *
 * <p>{@link OdtParser} 가 채우고 {@link OdtToHwpDocumentBuilder} 가 소비한다.
 *   ODT 의 XML 구조 그대로가 아니라, HWP / HWPX 변환에 필요한 정보만 정규화된
 *   형태로 담는다.
 *
 * <h2>설계 메모</h2>
 * <ul>
 *   <li>스타일 상속은 파싱 시점에 미리 펼치지 않고 {@code parentName} 만 보관 →
 *       빌더에서 {@link OdtStyle#resolveText(Map)} 등으로 필요 시 해결.</li>
 *   <li>이미지 바이트는 ZIP 엔트리 이름(예 {@code "pad_tech_0.jpg"}) 키로 보관 →
 *       본문의 {@link ImageBlock#href} 와 매칭.</li>
 *   <li>본 클래스는 단순 POJO. 로직은 별도 클래스(파서/빌더)에서 처리.</li>
 * </ul>
 */
public final class OdtDocumentModel {

    // ------------------------------------------------------------------
    //  최상위 구조
    // ------------------------------------------------------------------

    /** 본문 블록 (paragraph / heading / table / image / list) 의 순차 리스트. */
    public final List<Block> blocks = new ArrayList<>();

    /**
     * 모든 스타일 정의 (styles.xml 의 명시 스타일 + content.xml 의 자동 스타일).
     * key = {@code style:name} (또는 display-name 보조 인덱스). 삽입 순서 유지.
     */
    public final Map<String, OdtStyle> styles = new LinkedHashMap<>();

    /** ZIP 엔트리 이름 → 이미지 바이트. */
    public final Map<String, byte[]> images = new LinkedHashMap<>();

    /**
     * v16t45 FIX A-2: 수식 임베드 객체 — ZIP 의 {@code ObjectN/content.xml}(MathML).
     * key = 객체 디렉터리 이름 (예: "Object1"). 본문 {@code draw:object xlink:href="./Object1"}
     * 가 이를 참조한다 (LibreOffice·자체 hwp(x)→odt 정방향 출력 공통 형식).
     */
    public final Map<String, String> mathObjects = new LinkedHashMap<>();

    /** 머리글 텍스트 (master-page/style:header). null 가능. */
    public String headerText;
    /** v16t51 S5: 머리글 첫 paragraph 의 text:p style-name (paraStyle border-bottom 매핑용). null=무지정. */
    public String headerParaStyleName;
    /** 바닥글 텍스트 (master-page/style:footer). null 가능. */
    public String footerText;
    /** v16t51 S6: 바닥글 첫 paragraph 의 text:p style-name. null=무지정. */
    public String footerParaStyleName;

    // v16.00-test15: task2 — text:list-style 의 ordered 여부 (number-format 존재 → ordered=true).
    //   key = style:name, value = true(번호) / false(글머리표).
    public final Map<String, Boolean> listStyleOrdered = new LinkedHashMap<>();

    // ------------------------------------------------------------------
    //  Block 계층 (sealed 미사용 — Java 8 호환)
    // ------------------------------------------------------------------

    /** 본문 블록의 공통 인터페이스. */
    public interface Block { }

    /** Heading 단락. ODT {@code <text:h outline-level="N">} → level 1~6. */
    public static final class HeadingBlock implements Block {
        public final int level;                 // 1..6
        public final String paragraphStyleName; // ex) "H2"
        public final List<Run> runs = new ArrayList<>();
        public HeadingBlock(int level, String paragraphStyleName) {
            this.level = Math.max(1, Math.min(6, level));
            this.paragraphStyleName = paragraphStyleName;
        }
    }

    /** 일반 단락. ODT {@code <text:p style-name="Body">} 등. */
    public static final class ParagraphBlock implements Block {
        public final String paragraphStyleName; // ex) "Body", "BodyCenter", "Code"
        public final List<Run> runs = new ArrayList<>();
        public ParagraphBlock(String paragraphStyleName) {
            this.paragraphStyleName = paragraphStyleName;
        }
    }

    /** 표. {@code rows[i][j]} = (i)번째 row 의 (j)번째 cell. */
    public static final class TableBlock implements Block {
        public final String tableStyleName;
        public final int columnCount;
        public final List<Row> rows = new ArrayList<>();
        /** ODT table style 의 style:width (예 "6.5in"). null 가능. */
        public String widthSpec;
        /** ODT table-column 별 style:column-width 리스트 (예 "1.6in"). number-columns-repeated 만큼 펼침. */
        public final List<String> columnWidthSpecs = new ArrayList<>();
        public TableBlock(String tableStyleName, int columnCount) {
            this.tableStyleName = tableStyleName;
            this.columnCount = columnCount;
        }
        public static final class Row {
            public final boolean header;
            public final List<Cell> cells = new ArrayList<>();
            /** v16t45 F-2: 행 스타일의 row-height/min-row-height (예 "0.135cm"). null = 미지정(기본 높이). */
            public String heightSpec;
            public Row(boolean header) { this.header = header; }
        }
        public static final class Cell {
            public final String cellStyleName; // ex) "HeaderCell", "AltCell", "BodyCell"
            public final int colSpan;
            public final int rowSpan;
            public final List<Block> content = new ArrayList<>(); // 셀 안의 paragraph/list/그 외
            /** v16t45 FIX C: {@code table:formula} 원문 (예 "ooow:&lt;D4&gt;+&lt;D5&gt;"). null = 수식 셀 아님. */
            public String formulaSpec;
            /** v16t45 FIX C: {@code office:value} 원문. null 가능. */
            public String officeValue;
            public Cell(String cellStyleName, int colSpan, int rowSpan) {
                this.cellStyleName = cellStyleName;
                this.colSpan = Math.max(1, colSpan);
                this.rowSpan = Math.max(1, rowSpan);
            }
        }
    }

    /** draw:frame > draw:image 한 개를 본문 흐름에 인라인 배치. */
    public static final class ImageBlock implements Block {
        public final String href;     // ZIP 엔트리 이름 (예: "pad_tech_0.jpg") — images 맵 키
        public final double widthMm;  // svg:width 를 mm 로 환산. ≤0 이면 기본값.
        public final double heightMm;
        /** v16t45 FIX A: frame 을 품었던 모(母)문단의 style-name — 정렬(textAlign) 복원용. null 가능. */
        public String paragraphStyleName;
        public ImageBlock(String href, double widthMm, double heightMm) {
            this.href = href;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
        }
    }

    /**
     * draw:frame > draw:object > math:math 한 개를 본문 흐름에 인라인 배치.
     *
     * <p>v16.00-test15c — task3: MathML → 한컴 수식편집기 스크립트로 변환된
     *   문자열({@link #script})을 보관. 빌더가 이를 CtrlPicture(shapeType="equation")
     *   의 eqScript 필드에 그대로 직렬화. ODT 의 svg:width/svg:height 도 함께 보관해
     *   수식 박스 크기를 시각적으로 비슷하게 유지한다.
     *
     * <p>v1 설계: 인라인 위치는 별도 ParagraphBlock 으로 분리 emit (caption + 수식이
     *   서로 다른 paragraph). 완전한 line-inline 재현이 필요하면 후속 작업에서 Run
     *   타입으로 승격할 수 있다.
     */
    public static final class EquationBlock implements Block {
        public final String script;     // 한컴 수식 스크립트 (예: "a^{2}+b^{2}=c^{2}")
        public final double widthMm;    // svg:width 를 mm 로 환산. ≤0 이면 기본값.
        public final double heightMm;
        /** v16t45 FIX A: frame 을 품었던 모(母)문단의 style-name — 정렬(textAlign) 복원용. null 가능. */
        public String paragraphStyleName;
        public EquationBlock(String script, double widthMm, double heightMm) {
            this.script = script == null ? "" : script;
            this.widthMm = widthMm;
            this.heightMm = heightMm;
        }
    }

    /** {@code <text:list>} 의 평탄화된 표현. 중첩은 items 자체가 자식 ListBlock 일 수 있음. */
    public static final class ListBlock implements Block {
        public final boolean ordered;
        public final List<ListItem> items = new ArrayList<>();
        public ListBlock(boolean ordered) { this.ordered = ordered; }
        public static final class ListItem {
            /** 평문/스타일 적용된 run 들. */
            public final List<Run> runs = new ArrayList<>();
            /** 중첩 리스트가 있다면 children 으로. */
            public final List<Block> nested = new ArrayList<>();
        }
    }

    // ------------------------------------------------------------------
    //  인라인 Run — paragraph 안의 한 텍스트 조각 + 인라인 스타일
    // ------------------------------------------------------------------

    /**
     * paragraph / heading / cell 안의 한 텍스트 조각.
     *
     * <p>{@code spanStyleName} 가 있으면 그 스타일을 우선 적용하고,
     *   비어 있는 필드는 paragraph 의 부모 스타일을 상속한다 (빌더 단계에서 처리).
     */
    public static final class Run {
        public final String text;
        public final String spanStyleName; // null 가능
        /** v16t45 FIX B: 이 run 을 감싸던 {@code text:a} 의 xlink:href. null = 링크 아님. */
        public String linkHref;
        /** v16t45 FIX B: non-null 이면 이 run 은 {@code text:bookmark} 마커 (text 는 ""). */
        public String bookmarkName;
        /** v16t45 FIX C: non-null 이면 이 run(수식 셀 표시 텍스트)을 FIELD_FORMULA 필드쌍으로 감싼다. */
        public String formulaCommand;
        /** v16t45 F-4: non-null 이면 이 run 은 as-char 인라인 이미지 (text 는 "") — 텍스트 줄 안 위치 보존. */
        public ImageBlock inlineImage;
        public Run(String text, String spanStyleName) {
            this.text = text == null ? "" : text;
            this.spanStyleName = spanStyleName;
        }
        public static Run plain(String text) { return new Run(text, null); }
    }

    // ------------------------------------------------------------------
    //  OdtStyle — paragraph / text / table-cell 속성 통합 컨테이너
    // ------------------------------------------------------------------

    /**
     * ODT 스타일 한 건. paragraph-properties / text-properties / table-cell-properties
     *   세 영역을 모두 담는다 (해당 영역이 없으면 null 유지).
     */
    public static final class OdtStyle {
        public final String name;                       // style:name
        public String displayName;                      // style:display-name (널 가능)
        public String family;                           // paragraph / text / table-cell / ...
        public String parentName;                       // style:parent-style-name (널 가능)

        // text-properties --------------------------------------------------
        /** ex) "#FFFFFF". null = 미지정. */
        public String textColor;
        /** ex) "#1F4E79" (글자 하이라이트). */
        public String textBackgroundColor;
        public Boolean bold;
        public Boolean italic;
        public Boolean underline;
        public Boolean strike;
        /** 폰트 패밀리 (영문 ex "Liberation Sans"). 한글 미지정 시 빌더가 기본값 부여. */
        public String fontFamily;
        public String fontFamilyAsian;
        /** ex) "16pt". 빌더에서 hp 단위(1/100 pt)로 환산. */
        public String fontSize;
        /** v16t45 FIX E: fo:letter-spacing 절대길이 (예 "-0.5pt"). null = 기본 자간. */
        public String letterSpacing;

        // paragraph-properties --------------------------------------------
        /** ex) "center", "right", "left", "justify". */
        public String textAlign;
        /** ex) "1pt solid #2C3E50". H1/H2 직선의 핵심 단서. */
        public String borderBottom;
        public String borderTop;
        public String borderLeft;
        public String borderRight;
        public String marginTop;
        public String marginBottom;
        /** v16t52 P2 T6-b: paragraph-properties 의 fo:break-before ("page"). null/none = false. */
        public Boolean breakBeforePage;
        /** paragraph-properties 의 fo:background-color (예 "#F2F2F2"). null 가능. */
        public String paragraphBackgroundColor;
        /** paragraph-properties 의 fo:margin-left (예 "0.5in"). null 가능. */
        public String marginLeft;
        /** paragraph-properties 의 fo:margin-right. null 가능. */
        public String marginRight;
        /** v16t50 R2: paragraph-properties 의 fo:text-indent (예 "0.4cm"). null 가능. */
        public String textIndent;

        // table-cell-properties -------------------------------------------
        public String cellBackgroundColor;
        public String cellPadding;
        public String cellBorder;
        /** v16t50 R3/R5/R8: style:vertical-align (top/middle/bottom). null=무지정. */
        public String cellVerticalAlign;
        /** v16t51 S3: table-cell-properties > style:background-image xlink:href (예 "Pictures/image3.png"). null=무지정. */
        public String cellBackgroundImageHref;
        /** v16t51 S3: style:background-image style:repeat (stretch/repeat/no-repeat). null=무지정. */
        public String cellBackgroundImageRepeat;

        // table-properties / table-column-properties ----------------------
        /** {@code <style:table-properties style:width>} . 예 "6.5in". */
        public String tableWidth;
        /** {@code <style:table-column-properties style:column-width>} . 예 "1.6in". */
        public String columnWidth;
        /** v16t45 F-2: {@code <style:table-row-properties style:row-height>} (없으면 min-row-height). 예 "0.135cm". */
        public String rowHeight;

        /**
         * v16t46 FIX D: {@code <style:tab-stops>/<style:tab-stop>} 목록.
         * null = 미지정(부모 상속), 빈 리스트 = 명시적 탭 제거.
         */
        public java.util.List<TabStop> tabStops;

        /** v16t46 FIX D: tab-stop 1건. */
        public static final class TabStop {
            /** style:position 예 "16.99cm". */
            public String positionSpec;
            /** style:type — left/right/center/char. null = 무지정(left). */
            public String type;
            /** style:leader-style 예 "dotted". null = 리더 없음. */
            public String leaderStyle;
            /** style:leader-text 예 "·". */
            public String leaderText;
        }

        public OdtStyle(String name) { this.name = name; }

        /**
         * 부모 체인을 따라 올라가며 비어 있는 text-properties 를 채워 반환.
         * 원본은 보존, 결과는 새 인스턴스. style map 이 null/비어 있으면 자기 자신 반환.
         */
        public OdtStyle resolveText(Map<String, OdtStyle> styleMap) {
            if (styleMap == null || styleMap.isEmpty() || parentName == null) return this;
            OdtStyle resolved = copy(this);
            OdtStyle cur = styleMap.get(parentName);
            int safety = 0;
            while (cur != null && safety++ < 16) {
                if (resolved.textColor == null) resolved.textColor = cur.textColor;
                if (resolved.textBackgroundColor == null) resolved.textBackgroundColor = cur.textBackgroundColor;
                if (resolved.bold == null) resolved.bold = cur.bold;
                if (resolved.italic == null) resolved.italic = cur.italic;
                if (resolved.underline == null) resolved.underline = cur.underline;
                if (resolved.strike == null) resolved.strike = cur.strike;
                if (resolved.fontFamily == null) resolved.fontFamily = cur.fontFamily;
                if (resolved.fontFamilyAsian == null) resolved.fontFamilyAsian = cur.fontFamilyAsian;
                if (resolved.fontSize == null) resolved.fontSize = cur.fontSize;
                if (resolved.letterSpacing == null) resolved.letterSpacing = cur.letterSpacing;
                if (resolved.borderBottom == null) resolved.borderBottom = cur.borderBottom;
                if (resolved.borderTop == null) resolved.borderTop = cur.borderTop;
                if (resolved.borderLeft == null) resolved.borderLeft = cur.borderLeft;
                if (resolved.borderRight == null) resolved.borderRight = cur.borderRight;
                if (resolved.textAlign == null) resolved.textAlign = cur.textAlign;
                if (resolved.paragraphBackgroundColor == null) resolved.paragraphBackgroundColor = cur.paragraphBackgroundColor;
                if (resolved.breakBeforePage == null) resolved.breakBeforePage = cur.breakBeforePage;
                if (resolved.marginLeft == null) resolved.marginLeft = cur.marginLeft;
                if (resolved.marginRight == null) resolved.marginRight = cur.marginRight;
                if (resolved.textIndent == null) resolved.textIndent = cur.textIndent;
                if (resolved.tableWidth == null) resolved.tableWidth = cur.tableWidth;
                if (resolved.columnWidth == null) resolved.columnWidth = cur.columnWidth;
                if (resolved.tabStops == null) resolved.tabStops = cur.tabStops;
                if (cur.parentName == null) break;
                cur = styleMap.get(cur.parentName);
            }
            return resolved;
        }

        private static OdtStyle copy(OdtStyle s) {
            OdtStyle c = new OdtStyle(s.name);
            c.displayName = s.displayName;
            c.family = s.family;
            c.parentName = s.parentName;
            c.textColor = s.textColor;
            c.textBackgroundColor = s.textBackgroundColor;
            c.bold = s.bold;
            c.italic = s.italic;
            c.underline = s.underline;
            c.strike = s.strike;
            c.fontFamily = s.fontFamily;
            c.fontFamilyAsian = s.fontFamilyAsian;
            c.fontSize = s.fontSize;
            c.letterSpacing = s.letterSpacing;
            c.textAlign = s.textAlign;
            c.borderBottom = s.borderBottom;
            c.borderTop = s.borderTop;
            c.borderLeft = s.borderLeft;
            c.borderRight = s.borderRight;
            c.marginTop = s.marginTop;
            c.marginBottom = s.marginBottom;
            c.paragraphBackgroundColor = s.paragraphBackgroundColor;
            c.breakBeforePage = s.breakBeforePage;
            c.marginLeft = s.marginLeft;
            c.marginRight = s.marginRight;
            c.textIndent = s.textIndent;
            c.cellBackgroundColor = s.cellBackgroundColor;
            c.cellPadding = s.cellPadding;
            c.cellBorder = s.cellBorder;
            c.cellVerticalAlign = s.cellVerticalAlign;
            c.cellBackgroundImageHref = s.cellBackgroundImageHref;
            c.cellBackgroundImageRepeat = s.cellBackgroundImageRepeat;
            c.tableWidth = s.tableWidth;
            c.columnWidth = s.columnWidth;
            c.tabStops = s.tabStops;
            return c;
        }
    }
}

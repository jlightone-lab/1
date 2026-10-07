package kr.n.nframe.newfeature.hwp2odt;

import java.util.List;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.docinfo.ParaShape;
import kr.dogfoot.hwplib.object.docinfo.parashape.Alignment;
import kr.dogfoot.hwplib.object.docinfo.BorderFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.EachBorder;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillInfo;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PatternFill;
import kr.dogfoot.hwplib.object.docinfo.TabDef;
import kr.dogfoot.hwplib.object.docinfo.tabdef.TabInfo;
import kr.dogfoot.hwplib.object.etc.Color4Byte;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.ParaCharShape;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharControlInline;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlEquation;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.EquationMathConverter;
import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

/**
 * HWP 본문 DFS 순회 → ODT XML 발화. 문단/글자런/표/필드/이미지/머리·바닥글 디스패치.
 *
 * <p>문자 스트림은 누적 wchar 위치로 글자모양(charShape) 전환점을 추적한다.
 * 확장 컨트롤(ControlExtend) 등장 시 paragraph.getControlList() 큐에서 순서대로 pop.
 */
public final class HwpDocumentTraverser {

    private final HWPFile hwp;
    private final DocInfo di;
    private final OdtBuildContext ctx;
    private final FontMapper fonts;
    private final TableMapper tables;
    private final ImageMapper images;
    private final FieldMapper fields;
    private final HeaderFooterMapper headerFooter;
    final Result result;

    /**
     * G/H(T13-clip): 표 셀 안 문단 발화 중인지 여부. 셀 안에서는 음수 text-indent 가
     * 셀 좌측 패딩(0.05cm)을 넘어 첫 글자를 셀 경계 밖으로 당겨 클립시키므로,
     * 셀 컨텍스트에서만 음수 들여쓰기를 0 으로 클램프한다. 셀 밖 일반 문단의
     * 행잉인덴트는 그대로 보존(무회귀). TableMapper.emitCell 가 토글한다.
     */
    boolean inCell = false;

    /**
     * task5/6: 현재 발화 중인 문단(표의 호스트가 될 수 있는)의 table:align 값(left/center/right).
     * 글자처럼(inline) 표는 호스트 문단 정렬을 따라 배치된다. 문단마다 갱신하므로 중첩 셀에서도
     * 가장 가까운(직속) 호스트 문단 정렬이 표에 적용된다. TableMapper.emit 가 읽는다.
     */
    String tableHostAlign = "left";

    public HwpDocumentTraverser(HWPFile hwp, OdtBuildContext ctx, Result result) {
        this.hwp = hwp;
        this.di = hwp.getDocInfo();
        this.ctx = ctx;
        this.result = result;
        this.fonts = new FontMapper(di, ctx.styles);
        this.tables = new TableMapper(this, ctx, di);
        this.images = new ImageMapper(hwp, ctx);
        this.fields = new FieldMapper(ctx);
        this.headerFooter = new HeaderFooterMapper(this, ctx);
    }

    /** 셀 배경 이미지필(ImageFill) 추출·등록 위임 → "Pictures/imageN.ext" 또는 null. */
    String registerCellImage(int binId) {
        return images.registerByBinId(binId);
    }

    public void run() {
        StringBuilder body = new StringBuilder(4096);
        for (Section sec : hwp.getBodyText().getSectionList()) {
            for (Paragraph p : sec) {
                emitParagraph(p, body);
            }
        }
        ctx.append(body.toString());
    }

    /** 문단 리스트(표 셀/머리글 등 하위)를 sink 에 발화. */
    public void emitParagraphs(List<Paragraph> paras, StringBuilder sink) {
        if (paras == null) return;
        for (Paragraph p : paras) emitParagraph(p, sink);
    }

    /** 단일 문단 발화. 블록 컨트롤(표/이미지)은 문단을 닫고 별도 블록으로. */
    public void emitParagraph(Paragraph p, StringBuilder out) {
        if (p == null) return;
        result.paragraphs++;

        int breakCount = countLineBreaks(p);
        // task5/6: 이 문단이 표의 호스트가 될 경우 표 정렬에 쓰일 가로정렬을 미리 산정.
        this.tableHostAlign = tableAlignOf(p);
        ParaStyleSet pss = paraStyleSet(p, breakCount);
        ParaState st = pss.splitJustify
                ? new ParaState(out, pss.base, pss.first, pss.mid, pss.last, true, breakCount)
                : new ParaState(out, pss.base);

        ParaText t = p.getText();
        List<HWPChar> chars = (t == null) ? null : t.getCharList();
        List<Control> controls = p.getControlList();
        int[] ctrlIdx = {0};

        if (chars == null || chars.isEmpty()) {
            st.finishEmpty();
            return;
        }

        int[] shapePositions = shapePositions(p);
        int[] shapeIds = shapeIds(p);
        int wpos = 0;
        int curShape = currentShape(shapePositions, shapeIds, 0);
        StringBuilder buf = new StringBuilder();
        int pendingHigh = -1; // 보류 중인 high surrogate(짝 결합용). 메서드 재진입 안전을 위해 지역변수.

        for (HWPChar c : chars) {
            HWPCharType ty = c.getType();
            int code = c.getCode() & 0xFFFF;
            int sz = c.getCharSize();

            int shapeAt = currentShape(shapePositions, shapeIds, wpos);
            if (shapeAt != curShape) {
                flushText(st, buf, curShape);
                curShape = shapeAt;
            }

            if (ty == HWPCharType.Normal) {
                int ncode = c.getCode() & 0xFFFF;
                if (ncode >= 0xD800 && ncode <= 0xDBFF) {        // high surrogate → 보류
                    pendingHigh = ncode;
                } else if (ncode >= 0xDC00 && ncode <= 0xDFFF) { // low surrogate
                    if (pendingHigh >= 0) {
                        int cp = 0x10000 + ((pendingHigh - 0xD800) << 10) + (ncode - 0xDC00);
                        buf.appendCodePoint(cp);                 // 보충문자 정상 발화(깨짐 방지)
                        pendingHigh = -1;
                    } else {
                        buf.append('·');                    // 짝 없는 low → 가운뎃점 대체
                    }
                } else {
                    if (pendingHigh >= 0) { buf.append('·'); pendingHigh = -1; } // 짝 없는 high
                    buf.append(normalChar((HWPCharNormal) c));
                }
            } else if (ty == HWPCharType.ControlInline) {
                if (pendingHigh >= 0) { buf.append('·'); pendingHigh = -1; } // 짝 없는 high 보류분 처리
                if (code == 0x0009) { // TAB
                    flushText(st, buf, curShape);
                    st.openP(); out.append("<text:tab/>");
                } else if (code == 0x0004) { // FIELD END
                    flushText(st, buf, curShape);
                    fields.endField(st);
                }
                // 그 외 인라인 컨트롤은 무시(자리만 차지)
            } else if (ty == HWPCharType.ControlChar) {
                if (pendingHigh >= 0) { buf.append('·'); pendingHigh = -1; } // 짝 없는 high 보류분 처리
                if (code == 0x000A) { // 강제 줄바꿈
                    flushText(st, buf, curShape);
                    st.lineBreak(); // v16t35: justify 분리모드면 피스 경계, 아니면 <text:line-break/>
                }
                // 0x000D 문단끝 등은 무시
            } else if (ty == HWPCharType.ControlExtend) {
                if (pendingHigh >= 0) { buf.append('·'); pendingHigh = -1; } // 짝 없는 high 보류분 처리
                flushText(st, buf, curShape);
                Control ctrl = nextControl(controls, ctrlIdx);
                dispatchExtend(ctrl, st);
            }
            wpos += sz;
        }
        flushText(st, buf, curShape);
        st.finish();
    }

    private void dispatchExtend(Control ctrl, ParaState st) {
        if (ctrl == null) return;
        try {
            if (ctrl instanceof ControlTable) {
                st.closeP();
                tables.emit((ControlTable) ctrl, st.out);
                result.tables++;
                return;
            }
            ControlType type = ctrl.getType();
            if (type == ControlType.Header || type == ControlType.Footer) {
                headerFooter.emit(ctrl, type);
                if (type == ControlType.Header) result.header = true; else result.footer = true;
                return;
            }
            if (type == ControlType.AutoNumber) {
                st.openP();
                if (isTotalPage(ctrl)) st.out.append("<text:page-count>1</text:page-count>");
                else st.out.append("<text:page-number text:select-page=\"current\">1</text:page-number>");
                return;
            }
            if (type == ControlType.Equation) {
                st.openP();
                String script;
                try {
                    script = ((ControlEquation) ctrl).getEQEdit().getScript().toUTF16LEString();
                } catch (Throwable t) {
                    return;
                }
                if (script != null && !script.trim().isEmpty()) {
                    // task1: 평문 텍스트(translateEquation) 대신 진짜 수식 객체(ODF Math)로 발화.
                    // 스크립트 → Presentation MathML content.xml → ObjectNN 등록 →
                    // 본문에는 draw:frame/draw:object 로 임베드 참조.
                    String contentXml = EquationMathConverter.toFormulaContentXml(script.trim());
                    if (contentXml != null) {
                        String obj = ctx.maths.add(contentXml);   // 예: "Object1"
                        st.out.append("<draw:frame text:anchor-type=\"as-char\"")
                              .append(" svg:width=\"3cm\" svg:height=\"0.7cm\">")
                              .append("<draw:object xlink:href=\"./").append(obj)
                              .append("\" xlink:type=\"simple\" xlink:show=\"embed\"")
                              .append(" xlink:actuate=\"onLoad\"/>")
                              .append("</draw:frame>");
                    }
                }
                return;
            }
            if (ctrl.isField()) {
                if (type == ControlType.FIELD_HYPERLINK) {
                    st.openP();
                    fields.beginHyperlink(ctrl, st);
                    result.hyperlinks++;
                } else if (type == ControlType.FIELD_BOOKMARK) {
                    st.openP();
                    fields.bookmark(ctrl, st);
                    result.bookmarks++;
                }
                return;
            }
            // 이미지/그리기 객체
            if (images.isPicture(ctrl)) {
                boolean inline = images.isInline(ctrl);
                if (inline) {
                    st.openP();
                    images.emit(ctrl, st.out, true);
                } else {
                    // 단독 그림: 정렬(좌/중/우) 반영한 별도 문단. as-char 프레임이라
                    // 문단 fo:text-align 이 그대로 가로 정렬로 표시된다.
                    st.closeP();
                    String align = images.alignKeyword(ctrl);
                    String pstyle = (align != null) ? ctx.styles.paragraphAlign(align) : null;
                    st.out.append("<text:p text:style-name=\"")
                          .append(pstyle != null ? pstyle : "Standard").append("\">");
                    images.emit(ctrl, st.out, true);
                    st.out.append("</text:p>");
                }
                result.images++;
            }
        } catch (Throwable t) {
            // best-effort: 한 컨트롤 실패가 전체 변환을 막지 않게
        }
    }

    /**
     * [DEAD CODE — task1 이후 미사용] 더 이상 호출되지 않는다. task1 에서 수식은
     * 평문이 아닌 ODF Math 객체(EquationMathConverter)로 발화하므로 이 메서드는
     * 참조되지 않으나, 삭제 금지 제약에 따라 보존한다.
     *
     * task5/task7: HWP 수식 스크립트 → 가독 텍스트.
     * HWP 수식 마크업 키워드(SUM/times/divide 등)를 유니코드 기호로 치환해
     * 한컴 표시(예: Σ)와 시각적으로 일치시킨다. 단어 경계 기준 치환이라
     * 숫자/식별자 일부를 깨뜨리지 않으며, 알 수 없는 토큰은 원문 그대로 보존한다.
     */
    static String translateEquation(String s) {
        if (s == null || s.isEmpty()) return "";
        // 키워드(대소문자 무시, 단어 경계) → 기호
        String[][] map = {
            {"sum",   "Σ"},  // Σ
            {"prod",  "Π"},  // Π
            {"int",   "∫"},  // ∫
            {"times", "×"},  // ×
            {"cdot",  "·"},  // ·
            {"divide","÷"},  // ÷
            {"div",   "÷"},  // ÷
            {"sqrt",  "√"},  // √
            {"pm",    "±"},  // ±
            {"leq",   "≤"},  // ≤
            {"geq",   "≥"},  // ≥
            {"neq",   "≠"},  // ≠
            {"infty", "∞"},  // ∞
            {"alpha", "α"}, {"beta", "β"}, {"gamma", "γ"},
            {"delta", "δ"}, {"theta", "θ"}, {"pi", "π"},
            {"sigma", "σ"}, {"mu", "μ"}, {"lambda", "λ"}
        };
        for (String[] kv : map) {
            s = s.replaceAll("(?i)(?<![\\w가-힣])" + kv[0] + "(?![\\w가-힣])",
                    java.util.regex.Matcher.quoteReplacement(kv[1]));
        }
        return s;
    }

    private void flushText(ParaState st, StringBuilder buf, int shapeId) {
        if (buf.length() == 0) return;
        st.openP();
        String styleName = fonts.styleFor(shapeId);
        if (styleName != null) {
            st.out.append("<text:span text:style-name=\"").append(styleName).append("\">")
                  .append(esc(buf.toString())).append("</text:span>");
        } else {
            st.out.append(esc(buf.toString()));
        }
        buf.setLength(0);
    }

    private static String normalChar(HWPCharNormal c) {
        try {
            String s = c.getCh();
            return s == null ? "" : s;
        } catch (Exception e) {
            return "";
        }
    }

    private static Control nextControl(List<Control> controls, int[] idx) {
        if (controls == null) return null;
        while (idx[0] < controls.size()) {
            Control c = controls.get(idx[0]++);
            if (c != null) return c;
        }
        return null;
    }

    // ---- charShape 위치 테이블 ----

    private static int[] shapePositions(Paragraph p) {
        ParaCharShape cs = p.getCharShape();
        if (cs == null || cs.getPositonShapeIdPairList() == null) return new int[]{0};
        List<CharPositionShapeIdPair> l = cs.getPositonShapeIdPairList();
        if (l.isEmpty()) return new int[]{0};
        int[] a = new int[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = (int) l.get(i).getPosition();
        return a;
    }

    private int[] shapeIds(Paragraph p) {
        ParaCharShape cs = p.getCharShape();
        if (cs == null || cs.getPositonShapeIdPairList() == null
                || cs.getPositonShapeIdPairList().isEmpty()) {
            return new int[]{ firstCharShapeId(p) };
        }
        List<CharPositionShapeIdPair> l = cs.getPositonShapeIdPairList();
        int[] a = new int[l.size()];
        for (int i = 0; i < l.size(); i++) a[i] = (int) l.get(i).getShapeId();
        return a;
    }

    private int firstCharShapeId(Paragraph p) {
        return 0;
    }

    private static int currentShape(int[] positions, int[] ids, int wpos) {
        int chosen = ids.length > 0 ? ids[0] : 0;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i] <= wpos) chosen = ids[i]; else break;
        }
        return chosen;
    }

    // ---- 문단 스타일(정렬) ----

    /** v16t35: 문단 스타일 묶음 — base(무분리) + justify 분리용 피스 스타일 3종. */
    private static final class ParaStyleSet {
        final String base, first, mid, last;
        final boolean splitJustify;
        ParaStyleSet(String base, String first, String mid, String last, boolean splitJustify) {
            this.base = base; this.first = first; this.mid = mid; this.last = last;
            this.splitJustify = splitJustify;
        }
        static final ParaStyleSet NONE = new ParaStyleSet(null, null, null, null, false);
    }

    /** v16t35: 문단의 수동 줄바꿈(ControlChar 0x000A) 개수. justify 분리 피스 수 산정용. */
    private static int countLineBreaks(Paragraph p) {
        ParaText t = p.getText();
        if (t == null || t.getCharList() == null) return 0;
        int n = 0;
        for (HWPChar c : t.getCharList()) {
            if (c.getType() == HWPCharType.ControlChar && (c.getCode() & 0xFFFF) == 0x000A) n++;
        }
        return n;
    }

    /**
     * 문단 스타일 산정. justify 문단이면서 내부에 수동 줄바꿈이 있으면(breakCount>0)
     * 피스 분리용 변형 스타일 3종을 함께 만든다(그 외 문단·align 은 base 만, 무회귀).
     *   first : 행잉 들여쓰기·윗여백 유지, 아래여백 0  (블록 맨 위)
     *   mid   : margin-left 만, 들여쓰기·위/아래여백 0  (피스 사이 — 빈 줄 방지)
     *   last  : margin-left+아래여백+하단테두리, 들여쓰기·윗여백 0  (블록 맨 아래)
     * 들여쓰기(행잉 음수 text-indent)는 '첫 줄'만 적용되므로 2번째+피스에선 제거해야
     * 위치가 어긋나지 않는다. 파일별 하드코딩 없이 소스 ParaShape 값으로만 산출.
     */
    private ParaStyleSet paraStyleSet(Paragraph p, int breakCount) {
        try {
            int psId = p.getHeader().getParaShapeId();
            java.util.List<ParaShape> list = di.getParaShapeList();
            if (list == null || psId < 0 || psId >= list.size()) return ParaStyleSet.NONE;
            ParaShape ps = list.get(psId);
            if (ps == null) return ParaStyleSet.NONE;
            String align = (ps.getProperty1() != null) ? mapAlign(ps.getProperty1().getAlignment()) : null;
            // task9: 양쪽정렬(justify) 문단에 끊기지 않는 URL(http://...)이 끼면 그 URL 토큰이
            // 조기 줄바꿈을 유발하고, LibreOffice 가 wrap 된 비최종줄을 행폭까지 늘려 단어간격이
            // 과대해진다(한컴은 늘리지 않음). v34/v35 정렬보정은 '수동 줄바꿈' 줄만 대상이라 자연
            // wrap 은 우회 → 여기서 'justify+URL' 문단만 좌측정렬로 정규화(URL 미포함 justify 무영향).
            if ("justify".equals(align) && paraHasUrl(p)) align = null;
            // task1/2(v16t38): 한컴은 ODF fo:text-align="justify" 를 honor 하여 표 셀의 짧은 한 줄을
            // 셀폭까지 양끝 신장(자간 과대) → 셀 안 문단만 justify→start 로 내림. 본문(셀 밖) justify 는
            // 줄바꿈 외양 보존을 위해 그대로 둔다. (text-align-last 는 한컴이 무시 → v16t37 폐기)
            if (inCell && "justify".equals(align)) align = "start";
            int leftRaw = ps.getLeftMargin();
            // G/H: 셀 안에서 음수 들여쓰기는 첫 글자 클립을 유발하므로 0 으로 클램프(셀 밖은 보존).
            int indentRaw = ps.getIndent();
            if (inCell && indentRaw < 0) {
                indentRaw = 0;
            } else if (!inCell && indentRaw < 0) {
                // task1/2: 행잉(음수 들여쓰기) 깊이를 '마커 텍스트시작' 기준으로 정규화.
                // 소스 indent(예 5.038cm, hwp 는 2×=10cm)를 그대로 쓰면 wrap 둘째줄+가 본문에서
                // 멀리 밀려 들쑥날쑥(원본 31.png 은 마커 뒤 텍스트 시작에 정렬). 마커폭(≈2em)을
                // 상한으로 캡해 행잉을 마커 직후로 정규화. cap 이하 행잉은 소스값 보존(무회귀).
                int hang = -indentRaw;
                int cap = 2 * paraFontEmHU(p);
                if (hang > cap) hang = cap;
                indentRaw = -hang;
                // 첫 줄(마커)이 좌측 여백 밖으로 나가지 않게 margin-left >= 행잉 보장(하린 사양).
                if (leftRaw < hang) leftRaw = hang;
            }
            String marginLeft   = cmOrNull(leftRaw);
            String textIndent   = signedCmOrNull(indentRaw);
            // v16t34 문제3b: hwplib ParaShape 의 문단 위/아래 간격은 실 HWPUNIT 의 2배로 들어온다
            // (동일 문서를 hwpx 로 열면 margin.prev/next 가 정확히 1/2 값·단위 HWPUNIT 명시).
            // 한컴 렌더 기준값 복원 위해 hwp 경로에서만 /2 보정(hwpx 는 이미 정상이라 무수정).
            String marginTop    = cmOrNull(ps.getTopParaSpace() / 2);
            String marginBottom = cmOrNull(ps.getBottomParaSpace() / 2);
            String borderBottom = paraBorderBottom(ps);
            String tabStops     = tabStopsXml(ps, p);
            String bg           = paraBackground(ps);
            // v16t52 P2 T6-b: paragraph header DivideSort.isDividePage() → ODT fo:break-before="page".
            //   골든 case1 hp:p pageBreak="1" 40건 1:1 (orig hwp 측정 isDividePage=40건 확정).
            //   ParaShape.splitPageBeforePara 가 아니라 paragraph header 의 DivideSort bit 2 가 본질.
            boolean breakBefore = (p.getHeader() != null && p.getHeader().getDivideSort() != null)
                    && p.getHeader().getDivideSort().isDividePage();
            String base = ctx.styles.paragraphStyle(align, marginLeft, textIndent,
                    marginTop, marginBottom, borderBottom, tabStops, bg, breakBefore);
            boolean split = "justify".equals(align) && breakCount > 0;
            if (!split) return new ParaStyleSet(base, null, null, null, false);
            String first = ctx.styles.paragraphStyle(align, marginLeft, textIndent,
                    marginTop, null, null, tabStops, bg, breakBefore);
            String mid   = ctx.styles.paragraphStyle(align, marginLeft, null,
                    null, null, null, tabStops, bg, false);
            String last  = ctx.styles.paragraphStyle(align, marginLeft, null,
                    null, marginBottom, borderBottom, tabStops, bg, false);
            return new ParaStyleSet(base, first, mid, last, true);
        } catch (Throwable ignore) {}
        return ParaStyleSet.NONE;
    }

    /**
     * task4/task8: 문단 음영/면색 — ParaShape→BorderFill 의 PatternFill 배경색.
     * HWP 는 문단 배경을 글자 음영과 별개로 BorderFill 의 채우기에 저장한다.
     * 흰색(255,255,255)은 "배경 없음" 센티넬이므로 null(미발화).
     */
    private String paraBackground(ParaShape ps) {
        try {
            int bfId = ps.getBorderFillId();
            if (bfId <= 0) return null;
            java.util.List<BorderFill> bfs = di.getBorderFillList();
            if (bfs == null || bfId - 1 < 0 || bfId - 1 >= bfs.size()) return null;
            BorderFill bf = bfs.get(bfId - 1);
            if (bf == null) return null;
            FillInfo fi = bf.getFillInfo();
            if (fi == null) return null;
            FillType ft = fi.getType();
            if (ft == null || !ft.hasPatternFill()) return null;
            PatternFill pf = fi.getPatternFill();
            if (pf == null) return null;
            Color4Byte c = pf.getBackColor();
            if (c == null) return null;
            int r = c.getR() & 0xFF, g = c.getG() & 0xFF, b = c.getB() & 0xFF;
            if (r == 255 && g == 255 && b == 255) return null; // 흰색=배경없음
            return String.format("#%02X%02X%02X", r, g, b);
        } catch (Throwable t) { return null; }
    }

    private static String cmOrNull(int hwpunit) {
        return hwpunit == 0 ? null : kr.n.nframe.newfeature.odtcommon.Units.hwpToCm(hwpunit);
    }
    private static String signedCmOrNull(int hwpunit) {
        return hwpunit == 0 ? null : kr.n.nframe.newfeature.odtcommon.Units.hwpToCmSigned(hwpunit);
    }

    /** task7: 머리글 밑줄 — ParaShape 의 BorderFill 하단 테두리. */
    private String paraBorderBottom(ParaShape ps) {
        try {
            int bfId = ps.getBorderFillId();
            if (bfId <= 0) return null;
            java.util.List<BorderFill> bfs = di.getBorderFillList();
            if (bfs == null || bfId - 1 < 0 || bfId - 1 >= bfs.size()) return null;
            BorderFill bf = bfs.get(bfId - 1);
            if (bf == null) return null;
            EachBorder b = bf.getBottomBorder();
            if (b == null || b.getType() == null || b.getType() == BorderType.None) return null;
            Color4Byte c = b.getColor();
            String color = (c == null) ? "#000000"
                : String.format("#%02X%02X%02X", c.getR() & 0xFF, c.getG() & 0xFF, c.getB() & 0xFF);
            return "0.5pt solid " + color;
        } catch (Throwable t) { return null; }
    }

    /** task3: 목차 점선 리더 — TabDef → ODT tab-stops. */
    private String tabStopsXml(ParaShape ps, Paragraph p) {
        try {
            // v16t53 T7: 인라인 TAB 컨트롤(0x0009)이 자체 fill leader(DOT/DASH)를 보유하면 그것이
            //   한컴·hwp2hwpx 가 hp:tab leader 로 쓰는 권위 신호다. ParaShape.tabDefId 가 점선 없는
            //   기본 탭정의를 가리켜도(목차 최상위 'Ⅰ.' 항목 등) 인라인 탭 leader 로 점선을 채워야 한다.
            //   기존 TabDef 경로가 점선 right 탭을 못 만드는 단락만 보강 — 인라인 탭에 fill leader 가
            //   있으면 정상 항목(P6 등)과 동일한 단일 right+leader 탭정지로 emit(작동 항목은 동일 산출).
            int inlineLeader = paraInlineFillLeader(p);
            if (inlineLeader == 3 || inlineLeader == 2) {
                String lead = (inlineLeader == 3)
                        ? " style:leader-style=\"dotted\" style:leader-text=\"·\""
                        : " style:leader-style=\"dashed\" style:leader-text=\"-\"";
                // 16.99cm = 페이지 우측 본문 경계 클램프(아래 right 탭 클램프와 동일 상수).
                return "<style:tab-stops><style:tab-stop style:position=\"16.99cm\""
                        + " style:type=\"right\"" + lead + "/></style:tab-stops>";
            }
            int tabId = ps.getTabDefId();
            java.util.List<TabDef> defs = di.getTabDefList();
            if (defs == null || tabId < 0 || tabId >= defs.size()) return null;
            TabDef td = defs.get(tabId);
            if (td == null) return null;
            java.util.List<TabInfo> tis = td.getTabInfoList();
            if (tis == null || tis.isEmpty()) return null;
            StringBuilder sb = new StringBuilder("<style:tab-stops>");
            boolean any = false;
            for (TabInfo ti : tis) {
                if (ti == null) continue;
                String type = mapTabSort(ti.getTabSort());           // null=left(기본)
                double cm = (ti.getPosition() / 7200.0) * 2.54;
                if ("right".equals(type) || cm > 16.99) cm = 16.99;   // 페이지 우측 본문 경계로 클램프
                String pos = trimCm(cm);
                // task7: 목차 점선 리더 — Dot 뿐 아니라 Dash 계열 등 채움이 있는 모든 탭을 점선으로.
                // 레거시 style:leader-char 는 LibreOffice 미렌더 → ODF1.2 표준 leader-style/leader-text 사용.
                BorderType fs = ti.getFillSort();
                // v16t52 T7-ⓐ: 한컴 TabInfo.fillSort 별 ODT leader-style 분기.
                //   Dot/CircleDot → dotted (가운뎃점 ·)·Dash/DashDot/DashDotDot/LongDash → dashed (하이픈 -)
                //   외부 hwp2hwpx 의 DASH→DOT 매핑 손실(잠복 #5) 회피의 첫 단계 — odt 시그널 보존.
                //   None/Solid 등은 leader 없음. 텍스트는 종전 가운뎃점 호환 위해 dotted 만 '·' 사용.
                String leader = "";
                if (fs != null) {
                    switch (fs) {
                        case Dot: case CircleDot:
                            leader = " style:leader-style=\"dotted\" style:leader-text=\"·\""; break;
                        case Dash: case DashDot: case DashDotDot: case LongDash:
                            leader = " style:leader-style=\"dashed\" style:leader-text=\"-\""; break;
                        case None: case Solid: default:
                            leader = ""; break;
                    }
                }
                sb.append("<style:tab-stop style:position=\"").append(pos).append("cm\"");
                if (type != null) sb.append(" style:type=\"").append(type).append("\"");
                sb.append(leader).append("/>");
                any = true;
            }
            sb.append("</style:tab-stops>");
            return any ? sb.toString() : null;
        } catch (Throwable t) { return null; }
    }
    /**
     * v16t53 T7: 단락의 인라인 TAB 컨트롤(0x0009) addition byte 에 기록된 fill leader 코드 반환.
     * ad[4] = leader (0 NONE·1 SOLID·2 DASH·3 DOT…). fill leader(DOT/DASH)를 가진 첫 탭의 값을,
     * 없으면 0 을 반환. HwpConverter.collectTabsFromParagraph 의 addition 해석과 동일.
     */
    private static int paraInlineFillLeader(Paragraph p) {
        try {
            if (p == null) return 0;
            ParaText t = p.getText();
            if (t == null || t.getCharList() == null) return 0;
            for (HWPChar c : t.getCharList()) {
                if (!(c instanceof HWPCharControlInline)) continue;
                if ((c.getCode() & 0xFFFF) != 0x0009) continue; // TAB 인라인 컨트롤만
                byte[] ad = ((HWPCharControlInline) c).getAddition();
                if (ad == null || ad.length < 6) continue;
                int leader = ad[4] & 0xFF;
                if (leader == 3 || leader == 2) return leader; // DOT / DASH
            }
        } catch (Throwable ignore) {}
        return 0;
    }
    private static String mapTabSort(Object tabSort) {
        if (tabSort == null) return null;
        String n = tabSort.toString();           // TabSort enum: Left/Right/Center/DecimalPoint
        if ("Right".equals(n)) return "right";
        if ("Center".equals(n)) return "center";
        if ("DecimalPoint".equals(n)) return "char";
        return null;                              // Left → ODT 기본
    }
    private static String trimCm(double v) {
        if (v < 0) v = 0;
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }

    /**
     * v16t51 S6: 바닥글 autoNum 번호 종류 판정 — hwplib NumberSort enum 에 TotalPage 가 없어
     * 종전 reflection toString() 검사는 항상 false 반환(orig TA-02 footer 의 TOTAL_PAGE 가
     * page-number 로 잘못 수출됨). HWP 5.0 spec 의 AutoNum Header property bit 0-3 == 6
     * (TOTAL_PAGE/LAST_PAGE) 을 직접 검사 — _diag/FooterAutoNumDump 실측 미러
     * (orig TA-02 footer 2건: property=0x0 PAGE, property=0x6 TOTAL_PAGE).
     */
    private static boolean isTotalPage(Control ctrl) {
        try {
            Object h = ctrl.getClass().getMethod("getHeader").invoke(ctrl);
            Object prop = h.getClass().getMethod("getProperty").invoke(h);
            long val = (long) prop.getClass().getMethod("getValue").invoke(prop);
            return (val & 0xF) == 6L;
        } catch (Throwable t) { return false; }
    }

    /**
     * task5/6: 문단 정렬 → 표 가로정렬(table:align). center/right 만 의미가 있고 나머지는 left.
     * (justify/divide 등 양끝맞춤은 표 정렬에선 left 로 취급.)
     */
    private String tableAlignOf(Paragraph p) {
        try {
            int psId = p.getHeader().getParaShapeId();
            java.util.List<ParaShape> list = di.getParaShapeList();
            if (list == null || psId < 0 || psId >= list.size()) return "left";
            ParaShape ps = list.get(psId);
            if (ps == null || ps.getProperty1() == null) return "left";
            Alignment a = ps.getProperty1().getAlignment();
            if (a == Alignment.Center) return "center";
            if (a == Alignment.Right)  return "right";
            return "left";
        } catch (Throwable t) { return "left"; }
    }

    /**
     * task1/2: 문단 첫 글자모양의 글자크기(em)를 HWPUNIT 로 반환. CharShape.baseSize 단위=1/100pt,
     * 1pt=100HWPUNIT 이므로 em(HWPUNIT) == baseSize 값. 미상이면 기본 1000(=10pt).
     */
    private int paraFontEmHU(Paragraph p) {
        try {
            int[] ids = shapeIds(p);
            int id = (ids != null && ids.length > 0) ? ids[0] : 0;
            java.util.List<kr.dogfoot.hwplib.object.docinfo.CharShape> list = di.getCharShapeList();
            if (list != null && id >= 0 && id < list.size() && list.get(id) != null) {
                int bs = list.get(id).getBaseSize();
                if (bs > 0) return bs;
            }
        } catch (Throwable ignore) {}
        return 1000;
    }

    /** task3/4(v16t38): 문단 첫 글자모양의 글자크기(pt 문자열). 띠 셀 빈 문단 높이 억제용. */
    public String paraFontSizePt(Paragraph p) {
        return kr.n.nframe.newfeature.odtcommon.Units.baseSizeToPt(paraFontEmHU(p));
    }

    /** task9: 문단 본문에 끊기지 않는 URL(http://·https://) 토큰이 있는지(정규 텍스트 스캔). */
    private static boolean paraHasUrl(Paragraph p) {
        try {
            ParaText t = p.getText();
            if (t == null || t.getCharList() == null) return false;
            StringBuilder sb = new StringBuilder();
            for (HWPChar c : t.getCharList()) {
                if (c.getType() == HWPCharType.Normal) {
                    int code = c.getCode() & 0xFFFF;
                    if (code >= 0x20) sb.appendCodePoint(code);
                }
            }
            return containsUrl(sb.toString());
        } catch (Throwable t) { return false; }
    }

    /** task9: URL 토큰 일반 탐지(파일별 하드코딩 없음). http/https 스킴 또는 www. 호스트. */
    static boolean containsUrl(String s) {
        if (s == null) return false;
        String low = s.toLowerCase(java.util.Locale.ROOT);
        return low.contains("http://") || low.contains("https://") || low.contains("www.");
    }

    private static String mapAlign(Alignment a) {
        if (a == null) return null;
        switch (a) {
            case Center:   return "center";
            case Right:    return "end";
            case Justify:
            case Distribute:
            case Divide:   return "justify";
            // v16t52 T1: Alignment.Left 명시 'left' 출력 (이전 null → ODT 미명시 → 빌더 미명시→JUSTIFY 매핑
            //   → orig LEFT 340건 모두 JUSTIFY 흡수 회귀). 빌더의 'left'→alignType=1 매핑 자동 동작.
            //   미명시 paragraph (Alignment=null) 는 종전 null 유지 (default JUSTIFY 호환).
            case Left:     return "left";
            default:       return null;
        }
    }

    /** 문단 발화 상태(지연 open/close). */
    static final class ParaState {
        final StringBuilder out;
        final String style;
        // v16t35: justify 문단을 수동 줄바꿈(line-break) 기준으로 별도 text:p 로 분리.
        // splitting=true 면 line-break 가 피스 경계가 되고 각 피스는 단독 문단이 된다
        // (LibreOffice 가 justify 문단의 마지막 줄은 늘리지 않으므로 늘어남 해소).
        private final String styleFirst, styleMid, styleLast;
        private final boolean splitting;
        private final int totalBreaks;
        private int pieceIndex = 0;
        private boolean open = false;
        private boolean produced = false;

        ParaState(StringBuilder out, String style) {
            this(out, style, null, null, null, false, 0);
        }
        ParaState(StringBuilder out, String style,
                  String styleFirst, String styleMid, String styleLast,
                  boolean splitting, int totalBreaks) {
            this.out = out; this.style = style;
            this.styleFirst = styleFirst; this.styleMid = styleMid; this.styleLast = styleLast;
            this.splitting = splitting; this.totalBreaks = totalBreaks;
        }

        // 현재 피스에 쓸 스타일: 첫 피스(행잉 들여쓰기+윗여백 유지), 중간(여백0), 마지막(아래여백 유지).
        private String pieceStyle() {
            if (!splitting) return style;
            if (pieceIndex == 0) return styleFirst;
            if (pieceIndex >= totalBreaks) return styleLast;
            return styleMid;
        }

        void openP() {
            if (!open) {
                String s = pieceStyle();
                out.append("<text:p");
                out.append(" text:style-name=\"").append(s == null ? "Standard" : s).append("\"");
                out.append(">");
                open = true;
                produced = true;
            }
        }
        void closeP() {
            if (open) { out.append("</text:p>"); open = false; }
        }
        // v16t35: 분리 모드면 피스 경계(현 피스 닫고 다음 피스로), 아니면 인라인 line-break.
        // 빈 피스는 openP 가 지연 호출이라 자동으로 text:p 를 만들지 않는다(빈 줄 방지).
        void lineBreak() {
            if (splitting) {
                closeP();
                pieceIndex++;
            } else {
                openP();
                out.append("<text:line-break/>");
            }
        }
        void finish() {
            closeP();
            if (!produced) out.append("<text:p text:style-name=\"")
                              .append(style == null ? "Standard" : style).append("\"/>");
        }
        void finishEmpty() {
            out.append("<text:p text:style-name=\"")
               .append(style == null ? "Standard" : style).append("\"/>");
        }
    }
}

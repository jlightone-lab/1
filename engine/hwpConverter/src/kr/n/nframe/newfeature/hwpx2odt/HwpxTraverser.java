package kr.n.nframe.newfeature.hwpx2odt;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.RefList;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.HorizontalAlign2;
import kr.dogfoot.hwpxlib.object.content.section_xml.ParaListCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.TItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.CtrlItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NormalText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.Tab;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.LineBreak;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.NBSpace;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.t.FWSpace;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldBegin;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldEnd;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.Bookmark;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.Header;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.Footer;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.AutoNum;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.inner.HeaderFooterCore;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.NumType;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.TabPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.parapr.ParaMargin;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.tabpr.TabItem;
import kr.dogfoot.hwpxlib.object.common.HWPXObject;
import kr.dogfoot.hwpxlib.object.common.compatibility.Switch;
import kr.dogfoot.hwpxlib.object.common.baseobject.ValueAndUnit;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.TabItemType;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ValueUnit2;
import kr.n.nframe.newfeature.odtcommon.Units;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FieldType;

import kr.n.nframe.newfeature.hwp2odt.Result;
import kr.n.nframe.newfeature.odtcommon.EquationMathConverter;
import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import static kr.n.nframe.newfeature.odtcommon.OdtEmitter.esc;

/**
 * HWPX 본문 순회 → ODT XML 발화. Section→Para→Run→RunItem 디스패치.
 *
 * <p>HWP 와 달리 글자모양(charPr)은 Run 단위로 고정이므로 위치 추적 불필요 — Run 하나가 span 하나.
 * T 가 자체 charPrIDRef 를 가지면 그 값으로 덮어쓴다(부분 서식).
 */
public final class HwpxTraverser {

    private final HWPXFile hwpx;
    private final RefList refList;
    private final OdtBuildContext ctx;
    private final HwpxFontMapper fonts;
    private final HwpxTableMapper tables;
    private final HwpxImageMapper images;
    private final HwpxFieldMapper fields;
    final Result result;

    /**
     * G/H(T14-clip): 표 셀 안 문단 발화 중인지 여부. marginOf() 가 switch 안 margin 을 살리면서
     * 셀 안에도 음수 text-indent(셀 좌측 패딩을 넘는 outdent)가 들어가 첫 글자가 클립된다.
     * 셀 컨텍스트에서만 음수 들여쓰기를 0 으로 클램프하고, 셀 밖 행잉인덴트는 보존(무회귀).
     * HwpxTableMapper.emitCell 가 토글한다. HWP 측 동작과 동일.
     */
    boolean inCell = false;

    /**
     * task5/6: 현재 발화 중인 문단(표 호스트가 될 수 있는)의 table:align 값(left/center/right).
     * 글자처럼(inline) 표는 호스트 문단 정렬을 따라 배치된다. 문단마다 갱신 → 중첩 셀에서도
     * 직속 호스트 문단 정렬이 표에 적용된다. HwpxTableMapper.emit 가 읽는다. HWP 측과 동일.
     */
    String tableHostAlign = "left";

    public HwpxTraverser(HWPXFile hwpx, OdtBuildContext ctx, Result result) {
        this.hwpx = hwpx;
        this.refList = (hwpx.headerXMLFile() == null) ? null : hwpx.headerXMLFile().refList();
        this.ctx = ctx;
        this.result = result;
        this.fonts = new HwpxFontMapper(refList, ctx.styles);
        this.tables = new HwpxTableMapper(this, ctx, refList);
        this.images = new HwpxImageMapper(hwpx, ctx);
        this.fields = new HwpxFieldMapper(ctx);
    }

    /** 셀 배경 이미지필(imgBrush) 추출·등록 위임 → "Pictures/imageN.ext" 또는 null. */
    String registerCellImage(String binId) {
        return images.registerByBinId(binId);
    }

    public void run() {
        StringBuilder body = new StringBuilder(4096);
        for (SectionXMLFile sec : hwpx.sectionXMLFileList().items()) {
            for (Para p : sec.paras()) {
                emitParagraph(p, body);
            }
        }
        ctx.append(body.toString());
    }

    /** 문단 리스트(셀/머리글 등 하위 ParaListCore)를 sink 에 발화. */
    public void emitParagraphs(ParaListCore list, StringBuilder sink) {
        if (list == null) return;
        for (Para p : list.paras()) emitParagraph(p, sink);
    }

    /** task3/4(v16t38): charPrIDRef 의 원본 글자크기(pt 문자열). 띠 셀 빈 문단 높이 억제용. */
    public String fontSizePtFor(String charPrIDRef) {
        return Units.baseSizeToPt(fonts.emHwpUnit(charPrIDRef));
    }

    private void emitParagraph(Para p, StringBuilder out) {
        if (p == null) return;
        result.paragraphs++;

        int breakCount = countLineBreaks(p);
        // task5/6: 이 문단이 표 호스트가 될 경우 표 정렬에 쓰일 가로정렬을 미리 산정.
        this.tableHostAlign = tableAlignOf(p);
        ParaStyleSet pss = paraStyleSet(p, breakCount);
        ParaState st = pss.splitJustify
                ? new ParaState(out, pss.base, pss.first, pss.mid, pss.last, true, breakCount)
                : new ParaState(out, pss.base);

        if (p.countOfRun() == 0) {
            st.finishEmpty();
            return;
        }
        for (Run r : p.runs()) {
            emitRun(r, st);
        }
        st.finish();
    }

    private void emitRun(Run r, ParaState st) {
        if (r == null) return;
        String runStyle = fonts.styleFor(r.charPrIDRef());
        for (RunItem it : r.runItems()) {
            if (it instanceof T) {
                emitText((T) it, st, runStyle);
            } else if (it instanceof Table) {
                st.closeP();
                tables.emit((Table) it, st.out);
                result.tables++;
            } else if (it instanceof Picture) {
                emitPicture((Picture) it, st);
            } else if (it instanceof Equation) {
                emitEquation((Equation) it, st);
            } else if (it instanceof Ctrl) {
                emitCtrl((Ctrl) it, st);
            }
            // 그 외 run item(도형 등)은 현재 무시
        }
    }

    /**
     * task3hx(Fix A): HWPX 수식(hp:equation) → 진짜 ODF Math 객체로 발화(HWP 측과 동일).
     * 스크립트(hp:script)를 Presentation MathML content.xml 로 변환 → ObjectNN 등록 →
     * 본문에는 inline draw:frame/draw:object 임베드 참조.
     * (HwpDocumentTraverser 의 ControlType.Equation 분기와 동일한 출력.)
     */
    private void emitEquation(Equation eq, ParaState st) {
        try {
            String script = (eq.script() == null) ? null : eq.script().text();
            if (script == null || script.trim().isEmpty()) return;
            String contentXml = EquationMathConverter.toFormulaContentXml(script.trim());
            if (contentXml == null) return;
            st.openP();
            String obj = ctx.maths.add(contentXml);   // 예: "Object1"
            st.out.append("<draw:frame text:anchor-type=\"as-char\"")
                  .append(" svg:width=\"3cm\" svg:height=\"0.7cm\">")
                  .append("<draw:object xlink:href=\"./").append(obj)
                  .append("\" xlink:type=\"simple\" xlink:show=\"embed\"")
                  .append(" xlink:actuate=\"onLoad\"/>")
                  .append("</draw:frame>");
        } catch (Throwable ignore) {}
    }

    private void emitText(T t, ParaState st, String runStyle) {
        String style = (t.charPrIDRef() != null && !t.charPrIDRef().isEmpty())
                ? fonts.styleFor(t.charPrIDRef()) : runStyle;

        if (t.isOnlyText()) {
            String s = t.onlyText();
            if (s != null && !s.isEmpty()) { st.openP(); emitSpan(st.out, style, s); }
            return;
        }
        if (t.items() == null) return;
        StringBuilder buf = new StringBuilder();
        for (TItem item : t.items()) {
            if (item instanceof NormalText) {
                String s = ((NormalText) item).text();
                if (s != null) buf.append(s);
            } else if (item instanceof Tab) {
                if (buf.length() > 0) { st.openP(); emitSpan(st.out, style, buf.toString()); buf.setLength(0); }
                st.openP(); st.out.append("<text:tab/>");
            } else if (item instanceof LineBreak) {
                if (buf.length() > 0) { st.openP(); emitSpan(st.out, style, buf.toString()); buf.setLength(0); }
                st.lineBreak(); // v16t35: justify 분리모드면 피스 경계, 아니면 <text:line-break/>
            } else if (item instanceof NBSpace || item instanceof FWSpace) {
                buf.append(' ');
            }
        }
        if (buf.length() > 0) { st.openP(); emitSpan(st.out, style, buf.toString()); }
    }

    private static void emitSpan(StringBuilder out, String styleName, String text) {
        if (styleName != null) {
            out.append("<text:span text:style-name=\"").append(styleName).append("\">")
               .append(esc(text)).append("</text:span>");
        } else {
            out.append(esc(text));
        }
    }

    private void emitPicture(Picture pic, ParaState st) {
        try {
            boolean inline = images.isInline(pic);
            if (inline) {
                st.openP();
                images.emit(pic, st.out, true);
            } else {
                st.closeP();
                String align = images.alignKeyword(pic);
                String pstyle = (align != null) ? ctx.styles.paragraphAlign(align) : null;
                st.out.append("<text:p text:style-name=\"")
                      .append(pstyle != null ? pstyle : "Standard").append("\">");
                images.emit(pic, st.out, true);
                st.out.append("</text:p>");
            }
            result.images++;
        } catch (Throwable ignore) {}
    }

    private void emitCtrl(Ctrl ctrl, ParaState st) {
        for (CtrlItem item : ctrl.ctrlItems()) {
            try {
                if (item instanceof FieldBegin) {
                    FieldBegin fb = (FieldBegin) item;
                    FieldType ty = fb.type();
                    if (ty == FieldType.HYPERLINK || ty == FieldType.CLICK_HERE) {
                        st.openP();
                        fields.beginHyperlink(fb, st);
                        result.hyperlinks++;
                    } else if (ty == FieldType.BOOKMARK) {
                        // task4: 책갈피 도착 지점 — 내부 하이퍼링크(#name)의 목적지 앵커 발화.
                        st.openP();
                        fields.beginBookmarkField(fb, st);
                        result.bookmarks++;
                    }
                } else if (item instanceof FieldEnd) {
                    fields.endField(st);
                } else if (item instanceof Bookmark) {
                    st.openP();
                    fields.bookmark((Bookmark) item, st);
                    result.bookmarks++;
                } else if (item instanceof AutoNum) {
                    // task1hx: 바닥글/머리글 자동 번호 — HWP 측 AutoNumber 분기와 동일 출력.
                    // numType=TOTAL_PAGE → page-count, 그 외(PAGE 등) → page-number.
                    NumType nt = ((AutoNum) item).numType();
                    st.openP();
                    if (nt == NumType.TOTAL_PAGE) {
                        st.out.append("<text:page-count>1</text:page-count>");
                    } else {
                        st.out.append("<text:page-number text:select-page=\"current\">1</text:page-number>");
                    }
                } else if (item instanceof Header) {
                    emitHeaderFooter((Header) item, true);
                    result.header = true;
                } else if (item instanceof Footer) {
                    emitHeaderFooter((Footer) item, false);
                    result.footer = true;
                }
            } catch (Throwable ignore) {}
        }
    }

    private void emitHeaderFooter(HeaderFooterCore hf, boolean header) {
        if (hf.subList() == null) return;
        StringBuilder sb = new StringBuilder();
        emitParagraphs(hf.subList(), sb);
        if (sb.length() == 0) return;
        if (header) ctx.appendHeader(sb.toString()); else ctx.appendFooter(sb.toString());
    }

    // ---- 문단 정렬 ----

    /** v16t35: 문단 스타일 묶음 — base(무분리) + justify 분리용 피스 스타일 3종(HWP 측과 동일). */
    private static final class ParaStyleSet {
        final String base, first, mid, last;
        final boolean splitJustify;
        ParaStyleSet(String base, String first, String mid, String last, boolean splitJustify) {
            this.base = base; this.first = first; this.mid = mid; this.last = last;
            this.splitJustify = splitJustify;
        }
        static final ParaStyleSet NONE = new ParaStyleSet(null, null, null, null, false);
    }

    /** v16t35: 문단 내 수동 줄바꿈(LineBreak run item) 개수. justify 분리 피스 수 산정용. */
    private static int countLineBreaks(Para p) {
        if (p == null || p.countOfRun() == 0) return 0;
        int n = 0;
        for (Run r : p.runs()) {
            if (r == null) continue;
            for (RunItem it : r.runItems()) {
                if (!(it instanceof T)) continue;
                T t = (T) it;
                if (t.items() == null) continue;
                for (TItem ti : t.items()) {
                    if (ti instanceof LineBreak) n++;
                }
            }
        }
        return n;
    }

    /**
     * 문단 스타일 산정. justify 문단이면서 내부에 수동 줄바꿈이 있으면(breakCount>0)
     * 피스 분리용 변형 스타일 3종을 함께 만든다(HWP 측 paraStyleSet 과 동일 규칙, 무회귀).
     */
    private ParaStyleSet paraStyleSet(Para p, int breakCount) {
        try {
            ParaPr pp = paraPr(p.paraPrIDRef());
            if (pp == null) return ParaStyleSet.NONE;
            // task1 대칭 복원: hwpx hp:p@pageBreak="1" → ODT fo:break-before="page".
            //   hwp2odt(HwpDocumentTraverser) 는 DivideSort.isDividePage() 로 동일 처리하나
            //   hwpx2odt 에는 통째로 누락돼 hwpx 소스 ODT 가 쪽나눔을 잃었다(골든 case1 40건→0건).
            //   신호는 Para.pageBreak()(Boolean, nullable). false/null 이면 미발화 → 종전 byte 동일.
            boolean breakBefore = Boolean.TRUE.equals(p.pageBreak());
            String align = (pp.align() != null) ? mapAlign(pp.align().horizontal()) : null;
            // task9: justify 문단에 끊기지 않는 URL(http://...)이 끼면 LO 가 wrap 된 비최종줄을
            // 행폭까지 늘려 단어간격 과대(한컴은 안 늘림). 'justify+URL' 문단만 좌측정렬 정규화.
            // (HWP 측 paraStyleSet 과 동일 규칙 — v34/v35 정렬보정은 수동줄바꿈 줄만 대상.)
            if ("justify".equals(align) && paraHasUrl(p)) align = null;
            // task1/2(v16t38): 한컴은 ODF fo:text-align="justify" 를 honor 하여 표 셀의 짧은 한 줄을
            // 셀폭까지 양끝 신장(자간 과대) → 셀 안 문단만 justify→start 로 내림. 본문(셀 밖) justify 는
            // 줄바꿈 외양 보존을 위해 그대로 둔다. (text-align-last 는 한컴이 무시 → v16t37 폐기)
            if (inCell && "justify".equals(align)) align = "start";
            // §0 문단속성 파리티: HWP 측 HwpDocumentTraverser.paragraphStyle 와 동일하게
            // margins / 하단 테두리(task2 머리글 파란선) / 탭 점선(task8 목차) 을 모두 발화한다.
            ParaMargin mg = marginOf(pp);
            Integer leftRaw   = (mg == null) ? null : rawHwpUnit(mg.left());
            Integer intentRaw = (mg == null) ? null : rawHwpUnit(mg.intent());
            // task11: hanging indent(음수 text-indent)에서 margin-left 가 |text-indent| 보다 작으면
            // 첫 줄/불릿(○ 등)이 좌측 여백 밖으로 나간다(시작점 = left + intent < 0). 하린 사양
            // (margin-left 10mm / text-indent -10mm 쌍)대로 margin-left >= |text-indent| 보장.
            // 셀 안은 아래에서 음수 text-indent 자체를 클램프하므로 제외(G/H 회귀 방지).
            // task1/2: 행잉(음수 text-indent) 깊이를 마커 텍스트시작 기준으로 정규화.
            // 소스 intent 를 그대로 쓰면 wrap 둘째줄+가 본문에서 멀리 밀려 들쑥날쑥(원본 31.png 은
            // 마커 뒤 텍스트 시작에 정렬). 마커폭(≈2em)을 상한으로 캡해 행잉을 마커 직후로 정규화.
            // cap 이하 행잉은 소스값 보존(무회귀). HWP 측 paraStyleSet 과 동일 규칙.
            if (!inCell && intentRaw != null && intentRaw < 0) {
                int hang = -intentRaw;
                int cap = 2 * paraFontEmHU(p);
                if (hang > cap) hang = cap;
                intentRaw = -hang;
                if (leftRaw == null || leftRaw < hang) leftRaw = hang;
            }
            String marginLeft   = cmOrNull(leftRaw, false);
            // G/H: 셀 안 음수 들여쓰기는 첫 글자 클립을 유발하므로 0(=null)으로 클램프(셀 밖은 보존).
            String textIndent   = cmOrNull(intentRaw, true);
            if (inCell && textIndent != null && textIndent.charAt(0) == '-') textIndent = null;
            String marginTop    = (mg == null) ? null : marginCmOrNull(mg.prev(), false);
            String marginBottom = (mg == null) ? null : marginCmOrNull(mg.next(), false);
            String borderBottom = paraBorderBottom(pp);
            String tabStops     = tabStopsXml(pp);
            // task3hx: 문단 배경색(면색) — ParaPr→border(borderFillIDRef)→BorderFill→FillBrush.WinBrush.faceColor.
            String bg = paraBackground(pp);
            if (align == null && bg == null && marginLeft == null && textIndent == null
                    && marginTop == null && marginBottom == null
                    && borderBottom == null && tabStops == null && !breakBefore) return ParaStyleSet.NONE;
            String base = ctx.styles.paragraphStyle(align, marginLeft, textIndent,
                    marginTop, marginBottom, borderBottom, tabStops, bg, breakBefore);
            boolean split = "justify".equals(align) && breakCount > 0;
            if (!split) return new ParaStyleSet(base, null, null, null, false);
            // first: 행잉 들여쓰기·윗여백 유지/아래여백0, mid: margin-left만(여백·들여쓰기0),
            // last: margin-left+아래여백+하단테두리/윗여백·들여쓰기0. 들여쓰기는 첫 줄만 적용되므로 2번째+피스 제거.
            String first = ctx.styles.paragraphStyle(align, marginLeft, textIndent,
                    marginTop, null, null, tabStops, bg, breakBefore);
            String mid   = ctx.styles.paragraphStyle(align, marginLeft, null,
                    null, null, null, tabStops, bg);
            String last  = ctx.styles.paragraphStyle(align, marginLeft, null,
                    null, marginBottom, borderBottom, tabStops, bg);
            return new ParaStyleSet(base, first, mid, last, true);
        } catch (Throwable ignore) {}
        return ParaStyleSet.NONE;
    }

    /**
     * task3(A): 문단 여백 추출. ParaPr.margin() 직계 자식이 null 인 경우,
     * &lt;hp:switch&gt; 로 감싼 &lt;hh:margin&gt; 을 수집한다(제목 paraPr 은 margin 이 switch 안에 있어
     * 직계 margin() 이 null → 1차에서 윗간격이 통째로 누락됐다). collectTabItems 와 동일 패턴.
     * 한컴 2016+ 가 렌더하는 HwpUnitChar case 분기를 우선하고, 없으면 default 분기를 사용한다.
     */
    private static ParaMargin marginOf(ParaPr pp) {
        if (pp.margin() != null) return pp.margin();
        if (pp.switchList() == null) return null;
        ParaMargin fromDefault = null;
        for (Switch sw : pp.switchList()) {
            if (sw == null) continue;
            if (sw.countOfCaseObject() > 0 && sw.getCaseObject(0) != null) {
                for (HWPXObject ch : sw.getCaseObject(0).children())
                    if (ch instanceof ParaMargin) return (ParaMargin) ch;
            }
            if (fromDefault == null && sw.defaultObject() != null) {
                for (HWPXObject ch : sw.defaultObject().children())
                    if (ch instanceof ParaMargin) fromDefault = (ParaMargin) ch;
            }
        }
        return fromDefault;
    }

    /** ValueAndUnit → HWPUNIT 원값. 없음/CHAR단위면 null(CHAR 단위는 글꼴 의존 → 생략). */
    private static Integer rawHwpUnit(ValueAndUnit vu) {
        if (vu == null || vu.value() == null) return null;
        if (vu.unit() != null && vu.unit() != ValueUnit2.HWPUNIT) return null;
        return vu.value();
    }

    /** HWPUNIT 원값 → cm 문자열. 0/없음이면 null. signed=true 면 음수 허용. */
    private static String cmOrNull(Integer v, boolean signed) {
        if (v == null || v == 0) return null;
        return signed ? Units.hwpToCmSigned(v) : Units.hwpToCm(v);
    }

    /** ValueAndUnit(HWPUNIT) → cm 문자열. 0/CHAR단위/없음이면 null. signed=true 면 음수 허용. */
    private static String marginCmOrNull(ValueAndUnit vu, boolean signed) {
        return cmOrNull(rawHwpUnit(vu), signed);
    }

    /** task2hx: 머리글 밑줄 등 — ParaPr 의 border(borderFillIDRef)→BorderFill 하단 테두리. */
    private String paraBorderBottom(ParaPr pp) {
        try {
            if (pp.border() == null) return null;
            String idRef = pp.border().borderFillIDRef();
            if (idRef == null || idRef.isEmpty() || refList == null
                    || refList.borderFills() == null) return null;
            for (BorderFill bf : refList.borderFills().items()) {
                if (!idRef.equals(bf.id())) continue;
                Border b = bf.bottomBorder();
                if (b == null || b.type() == null || b.type() == LineType2.NONE) return null;
                String color = b.color();
                if (color == null || color.isEmpty()) color = "#000000";
                else if (color.charAt(0) != '#') color = "#" + color;
                return "0.5pt solid " + color;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    /** task8hx: 목차 점선 리더 — ParaPr.tabPrIDRef → TabPr.tabItems → ODT tab-stops. */
    private String tabStopsXml(ParaPr pp) {
        try {
            String idRef = pp.tabPrIDRef();
            if (idRef == null || idRef.isEmpty() || refList == null
                    || refList.tabProperties() == null) return null;
            TabPr tp = null;
            for (TabPr cand : refList.tabProperties().items()) {
                if (idRef.equals(cand.id())) { tp = cand; break; }
            }
            if (tp == null) return null;
            StringBuilder sb = new StringBuilder("<style:tab-stops>");
            boolean any = false;
            for (TabItem ti : collectTabItems(tp)) {
                if (ti == null || ti.pos() == null) continue;
                String type = mapTabType(ti.type());                 // null=left(기본)
                double cm = (ti.pos() / 7200.0) * 2.54;
                if ("right".equals(type) || cm > 16.99) cm = 16.99;   // 페이지 우측 본문 경계로 클램프
                String leader = leaderChar(ti.leader());
                sb.append("<style:tab-stop style:position=\"").append(trimCm(cm)).append("cm\"");
                if (type != null) sb.append(" style:type=\"").append(type).append("\"");
                sb.append(leader).append("/>");
                any = true;
            }
            sb.append("</style:tab-stops>");
            return any ? sb.toString() : null;
        } catch (Throwable ignore) {}
        return null;
    }

    /**
     * TabPr 의 탭 항목 수집. 직계 tabItems() 외에, &lt;hp:switch&gt; 로 감싼 항목도 포함한다.
     * 목차 점선(RIGHT/DASH)은 보통 switch 의 default(HWPUNIT) 분기에 들어있어 직계로는 비어있다.
     * default 분기를 우선하고(표준 HWPUNIT 좌표), 없으면 첫 case 분기를 사용한다.
     */
    private static java.util.List<TabItem> collectTabItems(TabPr tp) {
        java.util.List<TabItem> out = new java.util.ArrayList<>();
        for (TabItem ti : tp.tabItems()) if (ti != null) out.add(ti);
        if (tp.switchList() != null) {
            for (Switch sw : tp.switchList()) {
                if (sw == null) continue;
                Iterable<HWPXObject> kids = null;
                if (sw.defaultObject() != null) {
                    kids = sw.defaultObject().children();
                } else if (sw.countOfCaseObject() > 0 && sw.getCaseObject(0) != null) {
                    kids = sw.getCaseObject(0).children();
                }
                if (kids == null) continue;
                for (HWPXObject ch : kids) if (ch instanceof TabItem) out.add((TabItem) ch);
            }
        }
        return out;
    }

    private static String mapTabType(TabItemType t) {
        if (t == TabItemType.RIGHT) return "right";
        if (t == TabItemType.CENTER) return "center";
        if (t == TabItemType.DECIMAL) return "char";
        return null; // LEFT → ODT 기본
    }

    /**
     * 점/선 리더 → ODF1.2 표준 leader-style + leader-text. NONE 이면 빈 문자열(리더 없음).
     * 레거시 style:leader-char 는 LibreOffice 가 렌더하지 않으므로 사용 금지(T7/T8).
     */
    private static String leaderChar(LineType2 lt) {
        if (lt == null || lt == LineType2.NONE) return "";
        // 한컴 원본은 가운뎃점(·, U+00B7) 리더 → leader-text 로 동일 글자 지정(시각 일치).
        return " style:leader-style=\"dotted\" style:leader-text=\"·\"";
    }

    private static String trimCm(double v) {
        if (v < 0) v = 0;
        String s = String.format(java.util.Locale.ROOT, "%.2f", v);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s.isEmpty() ? "0" : s;
    }

    /**
     * task3hx(round-3 port): 문단 음영/면색 — ParaPr 의 테두리/배경 borderFill 의 채우기 색.
     * 흰색(#FFFFFF)은 "배경 없음" 센티넬이므로 null(미발화). HWP 측과 동작 일치.
     */
    private String paraBackground(ParaPr pp) {
        try {
            if (pp.border() == null) return null;
            String idRef = pp.border().borderFillIDRef();
            if (idRef == null || idRef.isEmpty() || refList == null
                    || refList.borderFills() == null) return null;
            for (kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill bf
                    : refList.borderFills().items()) {
                if (!idRef.equals(bf.id())) continue;
                if (bf.fillBrush() == null || bf.fillBrush().winBrush() == null) return null;
                String hex = bf.fillBrush().winBrush().faceColor();
                if (hex == null || hex.isEmpty()) return null;
                if ("none".equalsIgnoreCase(hex)) return null; // HWPX '채우기 없음' → 배경 미발화
                if (hex.charAt(0) != '#') hex = "#" + hex;
                if ("#FFFFFF".equalsIgnoreCase(hex)) return null; // 흰색=배경없음
                return hex;
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private ParaPr paraPr(String idRef) {
        if (refList == null || idRef == null || refList.paraProperties() == null) return null;
        try {
            int id = Integer.parseInt(idRef.trim());
            if (id >= 0 && id < refList.paraProperties().count()) return refList.paraProperties().get(id);
        } catch (NumberFormatException ignore) {}
        // 인덱스 파싱 실패 시 id 속성 매칭으로 폴백
        for (ParaPr pp : refList.paraProperties().items()) {
            if (idRef.equals(pp.id())) return pp;
        }
        return null;
    }

    private static String mapAlign(HorizontalAlign2 a) {
        if (a == null) return null;
        if (a == HorizontalAlign2.CENTER) return "center";
        if (a == HorizontalAlign2.RIGHT) return "end";
        if (a == HorizontalAlign2.JUSTIFY || a == HorizontalAlign2.DISTRIBUTE
                || a == HorizontalAlign2.DISTRIBUTE_SPACE) return "justify";
        return null; // LEFT/기타 → 기본 좌측
    }

    /**
     * task5/6: 문단 정렬 → 표 가로정렬(table:align). center/right 만 의미, 나머지는 left.
     * HWP 측 tableAlignOf 와 동일 규칙.
     */
    private String tableAlignOf(Para p) {
        try {
            ParaPr pp = paraPr(p.paraPrIDRef());
            if (pp == null || pp.align() == null) return "left";
            HorizontalAlign2 a = pp.align().horizontal();
            if (a == HorizontalAlign2.CENTER) return "center";
            if (a == HorizontalAlign2.RIGHT)  return "right";
            return "left";
        } catch (Throwable t) { return "left"; }
    }

    /**
     * task1/2: 문단 첫 run 글자크기(em) HWPUNIT. CharPr.height 단위=1/100pt == em(HWPUNIT).
     * 미상이면 기본 1000(=10pt). HWP 측 paraFontEmHU 와 동일.
     */
    private int paraFontEmHU(Para p) {
        try {
            for (Run r : p.runs()) {
                if (r != null) return fonts.emHwpUnit(r.charPrIDRef());
            }
        } catch (Throwable ignore) {}
        return 1000;
    }

    /**
     * task9: 문단 본문에 URL 토큰(http/https/www)이 있는지(run 내 T 텍스트 스캔).
     * HWP 측 HwpDocumentTraverser.containsUrl 과 동일 일반 규칙(파일별 하드코딩 없음).
     */
    private static boolean paraHasUrl(Para p) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Run r : p.runs()) {
                if (r == null) continue;
                for (RunItem it : r.runItems()) {
                    if (it instanceof T) {
                        String s = ((T) it).onlyText();
                        if (s != null) sb.append(s);
                    }
                }
            }
            String low = sb.toString().toLowerCase(java.util.Locale.ROOT);
            return low.contains("http://") || low.contains("https://") || low.contains("www.");
        } catch (Throwable t) { return false; }
    }

    /** 문단 발화 상태(지연 open/close). hwp2odt 와 동일 정책이나 패키지 분리 위해 자체 보유. */
    static final class ParaState {
        final StringBuilder out;
        final String style;
        // v16t35: justify 문단을 수동 줄바꿈(line-break) 기준으로 별도 text:p 로 분리(HWP 측과 동일 알고리즘).
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

        private String pieceStyle() {
            if (!splitting) return style;
            if (pieceIndex == 0) return styleFirst;
            if (pieceIndex >= totalBreaks) return styleLast;
            return styleMid;
        }

        void openP() {
            if (!open) {
                String s = pieceStyle();
                out.append("<text:p text:style-name=\"")
                   .append(s == null ? "Standard" : s).append("\">");
                open = true;
                produced = true;
            }
        }
        void closeP() {
            if (open) { out.append("</text:p>"); open = false; }
        }
        // v16t35: 분리 모드면 피스 경계, 아니면 인라인 line-break. 빈 피스는 지연 openP 로 자동 생략.
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

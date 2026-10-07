package kr.n.nframe.newfeature.hwpx2odt;

import java.util.ArrayList;
import java.util.List;

import kr.dogfoot.hwpxlib.object.content.header_xml.RefList;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.FillBrush;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.ImgBrush;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageBrushMode;
import kr.dogfoot.hwpxlib.object.common.parameter.Param;
import kr.dogfoot.hwpxlib.object.common.parameter.StringParam;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.T;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.CtrlItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.FieldBegin;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.inner.Parameters;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.FieldType;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.TableWidths;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * HWPX 표 → ODT table:table. 열폭·셀병합(covered-table-cell)·테두리·세로정렬·셀 내부 문단 재귀.
 *
 * <p>병합: cellSpan(colSpan/rowSpan) 점유 칸을 covered-table-cell 로 채워 행마다 셀 수=열 수 유지.
 * 표 외곽선은 표 레벨 BorderFill 에서 가져와 경계 셀에 폴백(ODT 표 레벨 테두리 미표시 회피).
 */
public final class HwpxTableMapper {

    private final HwpxTraverser traverser;
    private final OdtBuildContext ctx;
    private final RefList refList;

    /** task3hx(Fix B): 표마다 고유한 table:name 부여용 시퀀스(LibreOffice 표 계산은 표 이름을 요구). */
    private int tableSeq = 0;

    public HwpxTableMapper(HwpxTraverser traverser, OdtBuildContext ctx, RefList refList) {
        this.traverser = traverser;
        this.ctx = ctx;
        this.refList = refList;
    }

    public void emit(Table tbl, StringBuilder out) {
        if (tbl == null) return;
        int colCount = Math.max(1, shortVal(tbl.colCnt(), 1));
        int rowCount = Math.max(1, shortVal(tbl.rowCnt(), 1));

        List<Tc> cells = new ArrayList<>();
        for (Tr tr : tbl.trs()) {
            for (Tc tc : tr.tcs()) cells.add(tc);
        }
        if (cells.isEmpty()) return;

        int gridRows = rowCount;
        for (Tc tc : cells) {
            int r = addr(tc, true);
            int rs = Math.max(1, span(tc, false));
            gridRows = Math.max(gridRows, r + rs);
        }

        Tc[][] origin = new Tc[gridRows][colCount];
        boolean[][] occupied = new boolean[gridRows][colCount];
        for (Tc tc : cells) {
            int r = addr(tc, true);
            int c = addr(tc, false);
            if (r >= 0 && r < gridRows && c >= 0 && c < colCount) origin[r][c] = tc;
        }

        // 열폭: 병합전용(폭 미상) 열도 병합셀 폭 분배로 채워 전 열에 폭 부여(v16t34 문제2).
        // 소스 폭(HWPUNIT)에서만 산출 — 하드코딩 없음. HWP 측 TableMapper 와 동일 알고리즘(TableWidths).
        java.util.List<long[]> widthCells = new ArrayList<>();
        for (Tc tc : cells) {
            int c = addr(tc, false);
            int sp = Math.max(1, span(tc, true));
            Long w = cellWidth(tc);
            widthCells.add(new long[]{ c, sp, (w == null ? 0L : w) });
        }
        long[] colHU = TableWidths.distribute(colCount, widthCells);
        // task7/8: 총폭이 인쇄 본문폭(17cm)을 넘으면 열폭 비례축소·캡(17cm 이하 표는 무영향).
        colHU = TableWidths.capToPrintWidth(colHU);
        String[] colWidths = TableWidths.toCm(colHU);
        long tableHU = TableWidths.sum(colHU);

        BorderFill tblBf = borderFill(tbl.borderFillIDRef());
        String[] tblOuter = {
            side(tblBf == null ? null : tblBf.leftBorder()),
            side(tblBf == null ? null : tblBf.rightBorder()),
            side(tblBf == null ? null : tblBf.topBorder()),
            side(tblBf == null ? null : tblBf.bottomBorder())
        };
        int effRows = 0;
        for (Tc tc : cells) {
            int r = addr(tc, true);
            int rs = Math.max(1, span(tc, false));
            effRows = Math.max(effRows, r + rs);
        }

        // task5/6: 글자처럼(inline) 표 가로정렬 = 호스트 문단 정렬(중첩표 가운데, 원본 32.png). HWP 측과 동일.
        String tableStyle = ctx.styles.tableStyleAligned(
                tableHU > 0 ? Units.hwpToCm(tableHU) : null, traverser.tableHostAlign);
        // task3hx(Fix B): HWP 측 TableMapper 와 동일하게 표에 고유 이름 부여 + xmlns:ooow 선언.
        // table:formula 의 "ooow:" 접두사는 이 네임스페이스 선언을 전제로 하며(미선언 시 "잘못된 계산"),
        // 루트 선언은 OdtWriter 소관(현재 누락)이라 표 요소 자체에 선언한다(HWP 측과 동일).
        String tableName = "Table" + (++tableSeq);
        out.append("<table:table xmlns:ooow=\"http://openoffice.org/2004/writer\"")
           .append(" table:name=\"").append(tableName).append("\"")
           .append(" table:style-name=\"").append(tableStyle).append("\">");
        for (int c = 0; c < colCount; c++) {
            String colStyle = ctx.styles.columnStyle(colWidths[c]);
            out.append("<table:table-column table:style-name=\"").append(colStyle).append("\"/>");
        }

        for (int r = 0; r < gridRows; r++) {
            boolean any = false;
            for (int c = 0; c < colCount; c++) if (origin[r][c] != null || occupied[r][c]) { any = true; break; }
            if (!any) continue;

            // task3/4(v16t38): '장식 띠' 행(채움+빈문단 셀로만 구성)은 행높이 미출력 시 한컴이 빈
            // 문단으로 행을 부풀린다 → 원본 cellSz 높이를 min-row-height 로 고정(나머지 표 무변경).
            long rowBandHU = 0; boolean allBand = true; boolean anyOrigin = false;
            for (int cc = 0; cc < colCount; cc++) {
                Tc oc = origin[r][cc];
                if (oc == null) continue;
                anyOrigin = true;
                if (isBandCell(oc)) {
                    Long h = (oc.cellSz() == null) ? null : oc.cellSz().height();
                    if (h != null && h > rowBandHU) rowBandHU = h;
                } else { allBand = false; }
            }
            String rowStyle = (anyOrigin && allBand && rowBandHU > 0)
                    ? ctx.styles.tableRowFixedHeight(Units.hwpToCm(rowBandHU)) : null;
            if (rowStyle != null)
                out.append("<table:table-row table:style-name=\"").append(rowStyle).append("\">");
            else
                out.append("<table:table-row>");
            int c = 0;
            while (c < colCount) {
                Tc cell = origin[r][c];
                if (cell != null) {
                    int colSpan = Math.max(1, span(cell, true));
                    int rowSpan = Math.max(1, span(cell, false));
                    boolean onLeft   = (c == 0);
                    boolean onRight  = (c + colSpan >= colCount);
                    boolean onTop    = (r == 0);
                    boolean onBottom = (r + rowSpan >= effRows);
                    long cellWidthHU = 0;
                    for (int k = c; k < c + colSpan && k < colCount; k++)
                        if (colHU[k] > 0) cellWidthHU += colHU[k];
                    emitCell(out, cell, colSpan, rowSpan, tblOuter, onLeft, onRight, onTop, onBottom,
                             origin, r, c, colCount, cellWidthHU);
                    for (int rr = r; rr < r + rowSpan && rr < gridRows; rr++)
                        for (int cc = c; cc < c + colSpan && cc < colCount; cc++)
                            if (!(rr == r && cc == c)) occupied[rr][cc] = true;
                    for (int k = 1; k < colSpan && c + k < colCount; k++)
                        out.append("<table:covered-table-cell/>");
                    c += colSpan;
                } else if (occupied[r][c]) {
                    out.append("<table:covered-table-cell/>");
                    c++;
                } else {
                    out.append("<table:table-cell/>");
                    c++;
                }
            }
            out.append("</table:table-row>");
        }
        out.append("</table:table>");
    }

    private void emitCell(StringBuilder out, Tc cell, int colSpan, int rowSpan,
                          String[] tblOuter, boolean onLeft, boolean onRight,
                          boolean onTop, boolean onBottom,
                          Tc[][] origin, int r, int c, int colCount, long cellWidthHU) {
        String style = cellStyle(cell, tblOuter, onLeft, onRight, onTop, onBottom, cellWidthHU);
        out.append("<table:table-cell table:style-name=\"").append(style).append("\"");
        if (colSpan > 1) out.append(" table:number-columns-spanned=\"").append(colSpan).append("\"");
        if (rowSpan > 1) out.append(" table:number-rows-spanned=\"").append(rowSpan).append("\"");

        // task3hx(Fix B): 자동계산(FORMULA 필드) 셀 → HWP 측 TableMapper 와 동일하게 진짜 수식으로 변환.
        //   - SUM(LEFT/RIGHT/ABOVE/BELOW) 를 LibreOffice 표 수식(ooow:<A1>+<B1>)으로 산출하되
        //     '숫자 셀만' +로 명시 결합(범위에 텍스트 헤더가 끼면 "잘못된 계산" 오류 → 시각 오라클 위반).
        //   - 표시값(office:value)도 부여 + 참조되는 일반 숫자 셀도 float 로 표기(피연산자 가독성).
        FormulaInfo fx = formulaInfo(cell, origin, r, c, colCount);
        if (fx != null) {
            if (fx.formula != null) {
                out.append(" table:formula=\"").append(fx.formula).append("\"");
            }
            out.append(" office:value-type=\"float\" office:value=\"").append(fx.value).append("\"");
        } else {
            String num = numericValue(cell);
            if (num != null) {
                out.append(" office:value-type=\"float\" office:value=\"").append(num).append("\"");
            }
        }
        out.append(">");

        if (isBandCell(cell)) {
            // task3/4(v16t38): 빈 장식 띠 셀 — 빈 문단을 원본 글자크기(보통 1pt)로 발화해 한컴이
            // 행을 부풀리지 않게 한다. 채움색(예 #0080C0)은 cellStyle 에서 그대로 유지(색 무회귀).
            String fpt = traverser.fontSizePtFor(firstCharPrIDRef(cell));
            String pst = ctx.styles.emptyBandParaStyle(fpt);
            int n = cell.subList().countOfPara();
            for (int i = 0; i < n; i++) {
                if (pst != null) out.append("<text:p text:style-name=\"").append(pst).append("\"/>");
                else out.append("<text:p/>");
            }
        } else if (cell.subList() == null || cell.subList().countOfPara() == 0) {
            out.append("<text:p/>");
        } else {
            boolean prevInCell = traverser.inCell;
            traverser.inCell = true;              // G/H: 셀 안 음수 들여쓰기 클램프 활성
            try {
                traverser.emitParagraphs(cell.subList(), out);
            } finally {
                traverser.inCell = prevInCell;    // 중첩 표 대비 복원
            }
        }
        out.append("</table:table-cell>");
    }

    /** task3hx(Fix B): 자동계산 셀 결과(table:formula + 표시 숫자값). */
    private static final class FormulaInfo {
        final String formula;   // table:formula(ooow:...) — 산출 불가 시 null
        final String value;     // office:value(콤마 제거 숫자)
        FormulaInfo(String formula, String value) { this.formula = formula; this.value = value; }
    }

    /**
     * 셀 안 FORMULA 필드(fieldBegin type="FORMULA")를 찾아 ODF 수식과 표시 숫자값 산출.
     * HWPX 는 parameters 의 "Command" StringParam 에 HWP 와 동일한 형식을 담는다:
     *   "=SUM(LEFT)??%g,;;25,700,000" (수식 ?? 결과포맷 ;; 표시값).
     */
    private FormulaInfo formulaInfo(Tc cell, Tc[][] origin, int r, int c, int colCount) {
        try {
            String raw = formulaCommand(cell);
            if (raw == null) return null;

            // 표시값: 마지막 ";;" 뒤
            String shown = raw;
            int semi = raw.lastIndexOf(";;");
            if (semi >= 0) shown = raw.substring(semi + 2);
            String numeric = shown.replaceAll("[,\\s]", "");
            if (!numeric.matches("[-+]?\\d+(\\.\\d+)?")) return null;

            // HWP 수식 본문: "??" 앞부분(앞쪽 '=' 제거)
            String expr = raw;
            int q = expr.indexOf("??");
            if (q >= 0) expr = expr.substring(0, q);
            if (expr.startsWith("=")) expr = expr.substring(1);
            String upper = expr.trim().toUpperCase();

            String formula = buildDirectionalSum(upper, origin, r, c, colCount);
            // v16t45 FIX C-2: 방향형 SUM 이 아니면 ?N 행참조형(예 "?3+?4+?5") 시도.
            //   행참조형이 office:value 만으로 발화되면 왕복 시 계산식이 정적 숫자로
            //   퇴화한다(정적숫자 금지 불변식 위반)— 진짜 수식으로 내보낸다.
            if (formula == null) formula = buildRowRefExpr(expr.trim(), c, origin);
            return new FormulaInfo(formula, numeric);
        } catch (Throwable t) { return null; }
    }

    /**
     * v16t45 FIX C-2: ?N 행참조형 HWP 계산식 → ooow 수식. ?N = 현재 열의 N행(1-base).
     *   ?N 참조와 사칙연산/괄호/공백만으로 이뤄진 식만 변환하며, 그 외 형태·범위 밖
     *   행 번호·텍스트 참조 셀은 종전대로 null(수식 미부여, office:value 만 유지 —
     *   텍스트 셀 참조 수식은 LibreOffice 재계산 시 "잘못된 계산" 오류).
     *   빈 셀 참조는 허용(HWP·LO 모두 0 으로 계산 — case1 "=?3+?4+?5" 의 ?5 가 빈 셀).
     */
    private String buildRowRefExpr(String expr, int c, Tc[][] origin) {
        if (expr.isEmpty() || expr.indexOf('?') < 0 || !expr.matches("[?0-9+\\-*/(). ]+")) return null;
        StringBuilder f = new StringBuilder("ooow:");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\?(\\d+)").matcher(expr);
        int last = 0;
        while (m.find()) {
            f.append(expr, last, m.start());
            int row1 = Integer.parseInt(m.group(1));
            if (row1 < 1 || row1 > origin.length || !isRowRefTargetCell(origin, row1 - 1, c)) return null;
            f.append("&lt;").append(a1(row1 - 1, c)).append("&gt;");
            last = m.end();
        }
        f.append(expr, last, expr.length());
        return f.toString();
    }

    /** v16t45 FIX C-2: ?N 참조 가능 셀 — 숫자 셀이거나 빈 셀(0 으로 계산). 텍스트 셀은 false. */
    private boolean isRowRefTargetCell(Tc[][] origin, int r, int c) {
        if (r < 0 || r >= origin.length || c < 0 || c >= origin[r].length) return false;
        Tc cell = origin[r][c];
        if (cell == null) return false;
        String txt = cellText(cell);
        if (txt == null) return false;
        String num = txt.replaceAll("[,\\s]", "");
        return num.isEmpty() || num.matches("[-+]?\\d+(\\.\\d+)?");
    }

    /** 셀의 첫 FORMULA 필드 Command 문자열(없으면 null). */
    private String formulaCommand(Tc cell) {
        SubList sl = cell.subList();
        if (sl == null) return null;
        for (Para p : sl.paras()) {
            if (p == null) continue;
            for (Run run : p.runs()) {
                if (run == null) continue;
                for (RunItem it : run.runItems()) {
                    if (!(it instanceof Ctrl)) continue;
                    for (CtrlItem ci : ((Ctrl) it).ctrlItems()) {
                        if (!(ci instanceof FieldBegin)) continue;
                        FieldBegin fb = (FieldBegin) ci;
                        if (fb.type() != FieldType.FORMULA) continue;
                        Parameters ps = fb.parameters();
                        if (ps == null) continue;
                        for (Param prm : ps.params()) {
                            if (prm instanceof StringParam && "Command".equals(prm.name())) {
                                String v = ((StringParam) prm).value();
                                if (v != null && !v.isEmpty()) return v;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * SUM(LEFT|RIGHT|ABOVE|BELOW) → 해당 방향의 숫자 셀만 +로 결합한 ooow: 수식.
     * 방향형이 아니거나 더할 숫자 셀이 없으면 null(수식 미부여, 표시 숫자만 유지). HWP 측과 동일.
     */
    private String buildDirectionalSum(String upper, Tc[][] origin, int r, int c, int colCount) {
        String dir = null;
        if (upper.contains("LEFT"))       dir = "LEFT";
        else if (upper.contains("RIGHT")) dir = "RIGHT";
        else if (upper.contains("ABOVE")) dir = "ABOVE";
        else if (upper.contains("BELOW")) dir = "BELOW";
        if (dir == null || !upper.startsWith("SUM")) return null;

        int nRows = origin.length;
        List<String> refs = new ArrayList<>();
        switch (dir) {
            case "LEFT":
                for (int cc = c - 1; cc >= 0; cc--) if (isNumericCell(origin, r, cc)) refs.add(a1(r, cc));
                break;
            case "RIGHT":
                for (int cc = c + 1; cc < colCount; cc++) if (isNumericCell(origin, r, cc)) refs.add(a1(r, cc));
                break;
            case "ABOVE":
                for (int rr = r - 1; rr >= 0; rr--) if (isNumericCell(origin, rr, c)) refs.add(a1(rr, c));
                break;
            case "BELOW":
                for (int rr = r + 1; rr < nRows; rr++) if (isNumericCell(origin, rr, c)) refs.add(a1(rr, c));
                break;
            default: return null;
        }
        if (refs.isEmpty()) return null;
        StringBuilder f = new StringBuilder("ooow:");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) f.append('+');
            f.append("&lt;").append(refs.get(i)).append("&gt;");
        }
        return f.toString();
    }

    /** 셀 표시 텍스트가 숫자면 콤마/공백 제거 숫자열, 아니면 null. */
    private String numericValue(Tc cell) {
        String txt = cellText(cell);
        if (txt == null) return null;
        String num = txt.replaceAll("[,\\s]", "");
        return num.matches("[-+]?\\d+(\\.\\d+)?") ? num : null;
    }

    /** 그리드 좌표(r,c) → A1 참조(열 A,B,C…, 행 1-base). HWP 측과 동일. */
    private static String a1(int r, int c) {
        StringBuilder col = new StringBuilder();
        int n = c;
        do { col.insert(0, (char) ('A' + (n % 26))); n = n / 26 - 1; } while (n >= 0);
        return col.toString() + (r + 1);
    }

    /** 이웃 셀이 '숫자 셀'인가(표시 텍스트가 숫자로 파싱되면 true). 헤더/라벨 텍스트는 false → 합산 제외. */
    private boolean isNumericCell(Tc[][] origin, int r, int c) {
        if (r < 0 || r >= origin.length || c < 0 || c >= origin[r].length) return false;
        Tc cell = origin[r][c];
        if (cell == null) return false;
        String txt = cellText(cell);
        if (txt == null) return false;
        String num = txt.replaceAll("[,\\s]", "");
        return num.matches("[-+]?\\d+(\\.\\d+)?");
    }

    /**
     * task3/4(v16t38): '장식 띠' 셀 여부 — 채움(배경/그라데이션)이 있고 표시 텍스트가 전혀 없는
     * (빈 문단만) 셀. 표지 상/하단 파란 띠가 이에 해당. 행높이를 빈 문단 폰트가 아닌 원본 cellSz
     * 높이로 고정하고 빈 문단 폰트를 원본 크기로 축소하는 대상 판정에 쓴다(case1 하드코딩 회피).
     */
    private boolean isBandCell(Tc cell) {
        if (cell == null || cell.subList() == null || cell.subList().countOfPara() == 0) return false;
        if (fillHex(borderFill(cell.borderFillIDRef())) == null) return false;
        // v16t45 FIX F-ⓑ: 채움색+표시텍스트 없음이라도 셀 문단에 확장개체(중첩표/그림/수식)가
        //   있으면 콘텐츠 셀 — 띠셀로 판정하면 해당 개체가 통째로 폐기된다(TA-02/04 소실 원인).
        for (Para p : cell.subList().paras()) {
            if (p == null) continue;
            for (Run run : p.runs()) {
                if (run == null) continue;
                for (RunItem it : run.runItems()) {
                    if (it instanceof Table
                            || it instanceof kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture
                            || it instanceof kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation) {
                        return false;
                    }
                }
            }
        }
        return cellText(cell).trim().isEmpty();
    }

    /** 셀 첫 Run 의 charPrIDRef(빈 문단 원본 글자크기 복원용) — 없으면 null. */
    private String firstCharPrIDRef(Tc cell) {
        SubList sl = cell.subList();
        if (sl == null) return null;
        for (Para p : sl.paras()) {
            if (p == null) continue;
            for (Run run : p.runs()) {
                if (run != null && run.charPrIDRef() != null && !run.charPrIDRef().isEmpty())
                    return run.charPrIDRef();
            }
        }
        return null;
    }

    /** 셀의 표시 텍스트(셀 내 모든 T 의 텍스트 연결). 수식 셀은 한컴이 채운 표시 숫자가 그대로 들어온다. */
    private String cellText(Tc cell) {
        SubList sl = cell.subList();
        if (sl == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Para p : sl.paras()) {
            if (p == null) continue;
            for (Run run : p.runs()) {
                if (run == null) continue;
                for (RunItem it : run.runItems()) {
                    if (it instanceof T) {
                        String s = ((T) it).onlyText();
                        if (s != null) sb.append(s);
                    }
                }
            }
        }
        return sb.toString();
    }

    /** task3/4: 연결셀 등 열폭이 좁은 셀(임계 이하)은 좌우 패딩 축소 → 글리프 가용폭 확보. HWP 측과 동일. */
    private static final long NARROW_CELL_HU = Math.round(0.8 / 2.54 * 7200.0); // 0.8cm

    private String cellStyle(Tc cell, String[] tblOuter, boolean onLeft, boolean onRight,
                             boolean onTop, boolean onBottom, long cellWidthHU) {
        boolean narrow = cellWidthHU > 0 && cellWidthHU <= NARROW_CELL_HU;
        BorderFill bf = borderFill(cell.borderFillIDRef());
        String left   = side(bf == null ? null : bf.leftBorder());
        String right  = side(bf == null ? null : bf.rightBorder());
        String top    = side(bf == null ? null : bf.topBorder());
        String bottom = side(bf == null ? null : bf.bottomBorder());
        // task5/6: 셀이 자체 borderFill 을 가지면 그 값이 한컴 렌더의 정답이다.
        // 명시적 NONE 은 '선 없음' 의도이므로 표 외곽선으로 덮어쓰지 않는다(제목표 색상 배너 등).
        // borderFill 미해결(bf==null) 셀만 표 외곽선으로 폴백 — 기존 동작 보존(회귀 방지).
        if (bf == null) {
            if (onLeft   && left   == null) left   = tblOuter[0];
            if (onRight  && right  == null) right  = tblOuter[1];
            if (onTop    && top    == null) top    = tblOuter[2];
            if (onBottom && bottom == null) bottom = tblOuter[3];
        }
        // v16t33 task4: 셀 배경 이미지필(imgBrush) 우선 — 보조사업자 로고 등이 셀 면색이 아닌
        // borderFill 의 imgBrush 로 들어가 그동안 누락됐다. 이미지가 있으면 단색 대신 발화.
        String[] img = cellImage(bf);
        if (img != null) {
            return ctx.styles.cellStyle(left, right, top, bottom, null, "middle",
                    img[0], img[1], img[2], narrow);
        }
        // task3hx: 셀 면색 — HWP 측 TableMapper.fillHex 와 동일하게 BorderFill 채우기색을 발화.
        // task2(v16t39): 표지 하단 띠는 원본이 RADIAL 그라데이션이나 LibreOffice 가 셀 그라데이션을
        // 미렌더(흰색)하여 띠가 사라지므로, 사용자 확정대로 단색 #0080C0(첫 정지색)으로 발화한다.
        // 띠 두께는 행높이 고정(tableRowFixedHeight)으로 별도 보정 — 색만 단색, 두께는 정확.
        String bg = fillHex(bf);
        return ctx.styles.cellStyle(left, right, top, bottom, bg, "middle",
                null, null, null, narrow);
    }

    /**
     * 셀 BorderFill 의 이미지필 → {href, repeat, position} 또는 null.
     * 추출은 본문 그림과 동일 인프라(매니페스트 binaryItemIDRef → Pictures/)를 재사용한다.
     */
    private String[] cellImage(BorderFill bf) {
        if (bf == null) return null;
        try {
            FillBrush fb = bf.fillBrush();
            if (fb == null) return null;
            ImgBrush ib = fb.imgBrush();
            if (ib == null || ib.img() == null) return null;
            String binId = ib.img().binaryItemIDRef();
            if (binId == null || binId.isEmpty()) return null;
            String href = traverser.registerCellImage(binId);
            if (href == null) return null;
            String[] rp = repeatPos(ib.mode());
            return new String[]{ href, rp[0], rp[1] };
        } catch (Throwable t) { return null; }
    }

    /** HWPX ImageBrushMode → ODF {style:repeat, style:position}. 불명 시 no-repeat/center. */
    private static String[] repeatPos(ImageBrushMode m) {
        if (m == null) return new String[]{ "no-repeat", "center" };
        switch (m) {
            case TILE:
            case TILE_HORZ_TOP:
            case TILE_HORZ_BOTTOM:
            case TILE_VERT_LEFT:
            case TILE_VERT_RIGHT:
                return new String[]{ "repeat", null };
            case TOTAL:
            case ZOOM:
                return new String[]{ "stretch", null };
            case CENTER:        return new String[]{ "no-repeat", "center" };
            case CENTER_TOP:    return new String[]{ "no-repeat", "center top" };
            case CENTER_BOTTOM: return new String[]{ "no-repeat", "center bottom" };
            case LEFT_CENTER:   return new String[]{ "no-repeat", "left" };
            case LEFT_TOP:      return new String[]{ "no-repeat", "left top" };
            case LEFT_BOTTOM:   return new String[]{ "no-repeat", "left bottom" };
            case RIGHT_CENTER:  return new String[]{ "no-repeat", "right" };
            case RIGHT_TOP:     return new String[]{ "no-repeat", "right top" };
            case RIGHT_BOTTOM:  return new String[]{ "no-repeat", "right bottom" };
            default:            return new String[]{ "no-repeat", "center" };
        }
    }

    /** 셀/표 BorderFill 의 채우기 면색(#RRGGBB) — 없으면 null. HWP 측 fillHex 와 동작 일치. */
    private static String fillHex(BorderFill bf) {
        if (bf == null) return null;
        try {
            if (bf.fillBrush() == null) return null;
            // 1) 단색(winBrush) 우선 — 기존 동작 무수정(솔리드·'none' 셀 출력 비트동일).
            if (bf.fillBrush().winBrush() != null) {
                String hex = bf.fillBrush().winBrush().faceColor();
                if (hex == null || hex.isEmpty()) return null;
                // HWPX 는 '채우기 없음' 을 faceColor="none" 으로 표기(HWP 의 !hasPatternFill 과 동일).
                // 이 경우 배경 미지정(null) — 그러지 않으면 "#none" 이 출력돼 셀이 잘못 채워진다.
                if ("none".equalsIgnoreCase(hex)) return null;
                if (hex.charAt(0) != '#') hex = "#" + hex;
                return hex;
            }
            // 2) 그라데이션(gradation) — 단색이 없을 때만 진입(무회귀). 첫 정지색으로 단색 근사.
            //    ODT 셀 배경의 정식 draw:gradient 표현은 까다로워 1차 근사로 첫 색을 채운다.
            if (bf.fillBrush().gradation() != null
                    && bf.fillBrush().gradation().countOfColor() > 0) {
                kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Color c0
                        = bf.fillBrush().gradation().getColor(0);
                if (c0 != null) {
                    String hex = c0.value();
                    if (hex != null && !hex.isEmpty() && !"none".equalsIgnoreCase(hex)) {
                        if (hex.charAt(0) != '#') hex = "#" + hex;
                        return hex;
                    }
                }
            }
            return null;
        } catch (Throwable t) { return null; }
    }

    private BorderFill borderFill(String idRef) {
        if (refList == null || idRef == null || refList.borderFills() == null) return null;
        try {
            int id = Integer.parseInt(idRef.trim());
            // HWPX borderFill id 는 1-base 가 흔하나, id 속성 매칭이 안전
        } catch (NumberFormatException ignore) {}
        for (BorderFill bf : refList.borderFills().items()) {
            if (idRef.equals(bf.id())) return bf;
        }
        return null;
    }

    private static String side(Border b) {
        if (b == null) return null;
        LineType2 t = b.type();
        if (t == null || t == LineType2.NONE) return null;
        String color = b.color();
        if (color == null || color.isEmpty()) color = "#000000";
        if (color.charAt(0) != '#') color = "#" + color;
        return "0.5pt solid " + color;
    }

    private static int addr(Tc tc, boolean row) {
        if (tc.cellAddr() == null) return -1;
        Short v = row ? tc.cellAddr().rowAddr() : tc.cellAddr().colAddr();
        return v == null ? -1 : v;
    }

    private static int span(Tc tc, boolean col) {
        if (tc.cellSpan() == null) return 1;
        Short v = col ? tc.cellSpan().colSpan() : tc.cellSpan().rowSpan();
        return v == null ? 1 : v;
    }

    private static Long cellWidth(Tc tc) {
        return (tc.cellSz() == null) ? null : tc.cellSz().width();
    }

    private static int shortVal(Short s, int dflt) {
        return s == null ? dflt : s;
    }
}

package kr.n.nframe.newfeature.hwp2odt;

import java.util.ArrayList;
import java.util.List;

import kr.dogfoot.hwplib.object.docinfo.BorderFill;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.object.docinfo.borderfill.BorderType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.EachBorder;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillInfo;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PatternFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.ImageFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.ImageFillType;
import kr.dogfoot.hwplib.object.etc.Color4Byte;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Table;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;

import kr.n.nframe.newfeature.odtcommon.OdtBuildContext;
import kr.n.nframe.newfeature.odtcommon.TableWidths;
import kr.n.nframe.newfeature.odtcommon.Units;

/**
 * HWP 표 → ODT table:table. 열폭·셀병합(covered-table-cell)·테두리·세로정렬·셀 내부 문단 재귀.
 *
 * <p>병합: 각 행의 셀 수 = 열 수가 되도록 colSpan 다음에 covered-table-cell,
 * rowSpan 점유 위치에도 covered-table-cell 를 채운다(서연 리서치 §8).
 */
public final class TableMapper {

    private final HwpDocumentTraverser traverser;
    private final OdtBuildContext ctx;
    private final DocInfo di;

    /** task2fx: 표마다 고유한 table:name 부여용 시퀀스(수식 셀 참조 좌표가 표 이름을 요구). */
    private int tableSeq = 0;

    public TableMapper(HwpDocumentTraverser traverser, OdtBuildContext ctx, DocInfo di) {
        this.traverser = traverser;
        this.ctx = ctx;
        this.di = di;
    }

    public void emit(ControlTable ct, StringBuilder out) {
        Table tbl = ct.getTable();
        if (tbl == null) return;
        int colCount = Math.max(1, tbl.getColumnCount());
        int rowCount = tbl.getRowCount();
        List<Row> rows = ct.getRowList();
        if (rows == null || rows.isEmpty()) return;

        // 그리드 인덱싱
        Cell[][] origin = new Cell[Math.max(rowCount, rows.size())][colCount];
        boolean[][] occupied = new boolean[origin.length][colCount];
        for (Row row : rows) {
            for (Cell cell : row.getCellList()) {
                int r = cell.getListHeader().getRowIndex();
                int c = cell.getListHeader().getColIndex();
                if (r >= 0 && r < origin.length && c >= 0 && c < colCount) origin[r][c] = cell;
            }
        }

        // 열폭: 병합전용(폭 미상) 열도 병합셀 폭 분배로 채워 전 열에 폭 부여(v16t34 문제2).
        // 소스 폭(HWPUNIT)에서만 산출 — 하드코딩 없음. hwp·hwpx 공통 알고리즘(TableWidths).
        java.util.List<long[]> widthCells = new ArrayList<>();
        for (Row row : rows) {
            for (Cell cell : row.getCellList()) {
                int c = cell.getListHeader().getColIndex();
                int span = Math.max(1, cell.getListHeader().getColSpan());
                widthCells.add(new long[]{ c, span, cell.getListHeader().getWidth() });
            }
        }
        long[] colHU = TableWidths.distribute(colCount, widthCells);
        // task7/8: 총폭이 인쇄 본문폭(17cm)을 넘으면 열폭 비례축소·캡(17cm 이하 표는 무영향).
        colHU = TableWidths.capToPrintWidth(colHU);
        String[] colWidths = TableWidths.toCm(colHU);
        long tableHU = TableWidths.sum(colHU);

        // 표 외곽 테두리: HWP 는 표 외곽선을 셀이 아닌 표 레벨 BorderFill 에 둔다.
        // ODT 는 표 레벨 테두리가 잘 표시되지 않으므로 경계 셀에 폴백으로 그린다.
        BorderFill tblBf = borderFill(tbl.getBorderFillId());
        String[] tblOuter = {
            side(tblBf == null ? null : tblBf.getLeftBorder()),
            side(tblBf == null ? null : tblBf.getRightBorder()),
            side(tblBf == null ? null : tblBf.getTopBorder()),
            side(tblBf == null ? null : tblBf.getBottomBorder())
        };
        // 실제 콘텐츠가 차지하는 마지막 행(외곽 하단 판정용)
        int effRows = 0;
        for (Row row : rows) {
            for (Cell cell : row.getCellList()) {
                int r = cell.getListHeader().getRowIndex();
                int rs = Math.max(1, cell.getListHeader().getRowSpan());
                effRows = Math.max(effRows, r + rs);
            }
        }

        // task5/6: 글자처럼(inline) 표의 가로정렬 = 호스트 문단 정렬. 중첩표는 셀 문단 정렬을
        // 따라 가운데/우측으로 배치된다(원본 32.png). traverser 가 현재 호스트 문단 정렬을 보유.
        String tableStyle = ctx.styles.tableStyleAligned(
                tableHU > 0 ? Units.hwpToCm(tableHU) : null, traverser.tableHostAlign);
        // task2fx: 수식(table:formula)의 셀 참조([.D2] 등)는 표 이름을 전제로 하지 않지만,
        // LibreOffice 표 계산이 동작하려면 표에 고유 이름이 있어야 한다. 표마다 일련번호로 생성.
        String tableName = "Table" + (++tableSeq);
        // table:formula 의 "ooow:" 접두사는 xmlns:ooow 네임스페이스 선언을 전제로 한다.
        // 루트(office:document-content) 선언은 OdtWriter 소관이며 현재 누락 → LibreOffice 가
        // 수식을 못 풀어 "잘못된 계산" 오류 발생. XML 은 임의 요소에 네임스페이스 선언이 허용되므로
        // 표 요소 자체에 xmlns:ooow 를 선언해 TableMapper 범위 안에서 수식이 동작하게 한다(검증 완료).
        out.append("<table:table xmlns:ooow=\"http://openoffice.org/2004/writer\"")
           .append(" table:name=\"").append(tableName).append("\"")
           .append(" table:style-name=\"").append(tableStyle).append("\">");
        for (int c = 0; c < colCount; c++) {
            String colStyle = ctx.styles.columnStyle(colWidths[c]);
            out.append("<table:table-column table:style-name=\"").append(colStyle).append("\"/>");
        }

        int nRows = origin.length;
        for (int r = 0; r < nRows; r++) {
            // 빈 행 스킵(그리드가 실제 행 수보다 클 때)
            boolean any = false;
            for (int c = 0; c < colCount; c++) if (origin[r][c] != null || occupied[r][c]) { any = true; break; }
            if (!any) continue;

            // task3/4(v16t38): '장식 띠' 행(채움+빈문단 셀로만 구성)은 행높이 미출력 시 한컴이 빈
            // 문단으로 행을 부풀린다 → 원본 cellSz 높이를 min-row-height 로 고정(나머지 표 무변경).
            // 업무3(v16.67): 회색 제목 띠(case1) 처럼 채움셀(밴드)과 '빈 셀(채움없음)'이 한 행에 섞이면
            //   종전 allBand 조건이 false 라 행높이가 미출력 → 정변환이 2400 폴백으로 부풀렸다.
            //   빈 셀은 콘텐츠가 없어 행을 늘릴 필요가 없으므로 '밴드 or 빈 셀'만으로 구성된 행도
            //   장식 띠로 간주해 원본 cellSz 높이를 출력한다(콘텐츠 셀이 하나라도 있으면 종전대로 미출력).
            long rowBandHU = 0; boolean allDecorative = true; boolean anyOrigin = false; boolean anyBand = false;
            for (int cc = 0; cc < colCount; cc++) {
                Cell oc = origin[r][cc];
                if (oc == null) continue;
                anyOrigin = true;
                boolean band = isBandCell(oc);
                if (band) anyBand = true;
                if (band || isEmptyCell(oc)) {
                    long h = oc.getListHeader().getHeight();
                    if (h > rowBandHU) rowBandHU = h;
                } else { allDecorative = false; }
            }
            boolean decorativeRow = anyOrigin && anyBand && allDecorative && rowBandHU > 0;
            String rowStyle = decorativeRow
                    ? ctx.styles.tableRowFixedHeight(Units.hwpToCm(rowBandHU)) : null;
            if (rowStyle != null)
                out.append("<table:table-row table:style-name=\"").append(rowStyle).append("\">");
            else
                out.append("<table:table-row>");
            int c = 0;
            while (c < colCount) {
                Cell cell = origin[r][c];
                if (cell != null) {
                    int colSpan = Math.max(1, cell.getListHeader().getColSpan());
                    int rowSpan = Math.max(1, cell.getListHeader().getRowSpan());
                    boolean onLeft   = (c == 0);
                    boolean onRight  = (c + colSpan >= colCount);
                    boolean onTop    = (r == 0);
                    boolean onBottom = (r + rowSpan >= effRows);
                    long cellWidthHU = 0;
                    for (int k = c; k < c + colSpan && k < colCount; k++)
                        if (colHU[k] > 0) cellWidthHU += colHU[k];
                    emitCell(out, cell, colSpan, rowSpan,
                             tblOuter, onLeft, onRight, onTop, onBottom,
                             origin, r, c, colCount, cellWidthHU, decorativeRow);
                    // 점유 표시
                    for (int rr = r; rr < r + rowSpan && rr < nRows; rr++)
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

    private void emitCell(StringBuilder out, Cell cell, int colSpan, int rowSpan,
                          String[] tblOuter, boolean onLeft, boolean onRight,
                          boolean onTop, boolean onBottom,
                          Cell[][] origin, int r, int c, int colCount, long cellWidthHU,
                          boolean decorativeRow) {
        String style = cellStyle(cell, tblOuter, onLeft, onRight, onTop, onBottom, cellWidthHU);
        out.append("<table:table-cell table:style-name=\"").append(style).append("\"");
        if (colSpan > 1) out.append(" table:number-columns-spanned=\"").append(colSpan).append("\"");
        if (rowSpan > 1) out.append(" table:number-rows-spanned=\"").append(rowSpan).append("\"");

        // task2fx: 자동계산(계산식) 셀 → 한컴 원본과 동일하게 '진짜 수식'(table:formula)으로 변환.
        //   - HWP SUM(LEFT)/SUM(ABOVE) 등을 LibreOffice Writer 표 수식(ooow:<A1>+<B1>)으로 산출하되,
        //     범위(SUM(<A2:C2>))가 아니라 '숫자 셀만' +로 명시 결합한다.
        //     (범위에 텍스트 헤더 셀이 끼면 LibreOffice 가 "잘못된 계산" 오류 → 시각 오라클 위반.)
        //   - 표시값(office:value)도 함께 부여해 재계산 전에도 화면 숫자가 원본과 일치.
        //   - 수식이 참조하는 일반 숫자 셀도 office:value(float) 로 표기해야 Writer 가 합산 가능
        //     (텍스트로 남으면 피연산자를 못 읽어 "잘못된 계산" 발생).
        FormulaInfo fx = formulaInfo(cell, origin, r, c, colCount);
        if (fx != null) {
            if (fx.formula != null) {
                out.append(" table:formula=\"").append(fx.formula).append("\"");
            }
            out.append(" office:value-type=\"float\" office:value=\"").append(fx.value).append("\"");
        } else {
            // 수식 셀이 아니지만 표시 텍스트가 숫자면 float 셀로 표기(수식 피연산자 대비).
            String num = numericValue(cell);
            if (num != null) {
                out.append(" office:value-type=\"float\" office:value=\"").append(num).append("\"");
            }
        }
        out.append(">");

        List<Paragraph> paras = cellParagraphs(cell);
        // 업무3(v16.67): 장식 띠 행(decorativeRow) 의 '빈 셀(채움없음)' 도 밴드셀과 동일하게 빈 문단을
        //   원본 글자크기로 발화한다. 종전엔 채움 없는 빈 셀이 콘텐츠 문단 스타일(큰 폰트)로 나가
        //   행높이를 고정해도 한컴이 큰 빈 문단으로 행을 다시 부풀렸다(case1 회색 제목띠 원인).
        boolean emptyDecorative = decorativeRow && !isBandCell(cell) && isEmptyCell(cell);
        if (isBandCell(cell) || emptyDecorative) {
            // task3/4(v16t38): 빈 장식 띠 셀 — 빈 문단을 원본 글자크기(보통 1pt)로 발화해 한컴이
            // 행을 부풀리지 않게 한다. 채움색(예 #0080C0)은 cellStyle 에서 그대로 유지(색 무회귀).
            String fpt = paras.isEmpty() ? null : traverser.paraFontSizePt(paras.get(0));
            String pst = ctx.styles.emptyBandParaStyle(fpt);
            int n = Math.max(1, paras.size());
            for (int i = 0; i < n; i++) {
                if (pst != null) out.append("<text:p text:style-name=\"").append(pst).append("\"/>");
                else out.append("<text:p/>");
            }
        } else if (paras.isEmpty()) {
            out.append("<text:p/>");
        } else {
            boolean prevInCell = traverser.inCell;
            traverser.inCell = true;              // G/H: 셀 안 음수 들여쓰기 클램프 활성
            try {
                traverser.emitParagraphs(paras, out);
            } finally {
                traverser.inCell = prevInCell;    // 중첩 표 대비 복원
            }
        }
        out.append("</table:table-cell>");
    }

    /** task2fx: 자동계산 셀 결과(of:= 수식 + 표시 숫자값). */
    private static final class FormulaInfo {
        final String formula;     // table:formula (of:=...) — 산출 불가 시 null
        final String value;       // office:value (콤마 제거 숫자)
        FormulaInfo(String formula, String value) { this.formula = formula; this.value = value; }
    }

    /**
     * 셀 안의 FIELD_FORMULA(계산식) 컨트롤을 찾아 ODF 수식(table:formula)과 표시 숫자값을 산출.
     * HWP 필드명 형식: "&lt;HWP수식&gt;??%g,;;&lt;표시값&gt;"  예) "=SUM(LEFT)??%g,;;25,700,000".
     *
     * <p>방향형 합계(SUM(LEFT)/SUM(RIGHT)/SUM(ABOVE)/SUM(BELOW))는 해당 방향의
     * '숫자 셀만' 골라 of:=[.B2]+[.C2] 처럼 명시 결합한다. (범위 SUM 은 텍스트 헤더 셀이
     * 끼면 LibreOffice 가 "잘못된 계산" 오류를 내므로 사용하지 않는다.)
     * 방향형 합계가 아니거나 숫자 셀이 없으면 수식은 비우고(office:value 만) 표시 숫자를 유지한다.
     * 표시값이 숫자가 아니면 null(기존 동작 유지 → 일반 텍스트 셀).
     */
    private FormulaInfo formulaInfo(Cell cell, Cell[][] origin, int r, int c, int colCount) {
        try {
            String raw = null;
            for (Paragraph p : cellParagraphs(cell)) {
                if (p.getControlList() == null) continue;
                for (kr.dogfoot.hwplib.object.bodytext.control.Control cc : p.getControlList()) {
                    if (cc == null) continue;
                    if (cc.getType() == kr.dogfoot.hwplib.object.bodytext.control.ControlType.FIELD_FORMULA) {
                        raw = ((kr.dogfoot.hwplib.object.bodytext.control.ControlField) cc).getName();
                        break;
                    }
                }
                if (raw != null) break;
            }
            if (raw == null) return null;

            // 표시값: 마지막 ";;" 뒤
            String shown = raw;
            int semi = raw.lastIndexOf(";;");
            if (semi >= 0) shown = raw.substring(semi + 2);
            String numeric = shown.replaceAll("[,\\s]", "");
            // 숫자가 아니면(잘못된 식 등) office:value 부여 불가 → null
            if (!numeric.matches("[-+]?\\d+(\\.\\d+)?")) return null;

            // HWP 수식 본문: "??%g" 앞부분 (앞쪽 '=' 제거)
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
    private String buildRowRefExpr(String expr, int c, Cell[][] origin) {
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
    private boolean isRowRefTargetCell(Cell[][] origin, int r, int c) {
        if (r < 0 || r >= origin.length || c < 0 || c >= origin[r].length) return false;
        Cell cell = origin[r][c];
        if (cell == null) return false;
        String txt = cellText(cell);
        if (txt == null) return false;
        String num = txt.replaceAll("[,\\s]", "");
        return num.isEmpty() || num.matches("[-+]?\\d+(\\.\\d+)?");
    }

    /**
     * SUM(LEFT|RIGHT|ABOVE|BELOW) → 해당 방향의 숫자 셀만 +로 결합한 of:= 수식 산출.
     * 방향형이 아니거나 더할 숫자 셀이 없으면 null(수식 미부여, 표시 숫자만 유지).
     */
    private String buildDirectionalSum(String upper, Cell[][] origin, int r, int c, int colCount) {
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
        // LibreOffice Writer 표 수식 형식: "ooow:<A1>+<B1>" (앞에 '=' 없음, 참조는 &lt;A1&gt;).
        // 검증: python-uno 로 LO 가 직접 작성한 수식 셀이 이 형식이며 재계산 시 정상 합산됨.
        StringBuilder f = new StringBuilder("ooow:");
        for (int i = 0; i < refs.size(); i++) {
            if (i > 0) f.append('+');
            f.append("&lt;").append(refs.get(i)).append("&gt;");
        }
        return f.toString();
    }

    /** 셀의 표시 텍스트가 숫자면 콤마/공백 제거 숫자열, 아니면 null. */
    private String numericValue(Cell cell) {
        String txt = cellText(cell);
        if (txt == null) return null;
        String num = txt.replaceAll("[,\\s]", "");
        return num.matches("[-+]?\\d+(\\.\\d+)?") ? num : null;
    }

    /** 그리드 좌표(r,c) → A1 참조(열 A,B,C…, 행 1-base). 0-base 격자 인덱스가 그대로 LO 표 좌표. */
    private static String a1(int r, int c) {
        StringBuilder col = new StringBuilder();
        int n = c;
        do { col.insert(0, (char) ('A' + (n % 26))); n = n / 26 - 1; } while (n >= 0);
        return col.toString() + (r + 1);
    }

    /**
     * 이웃 셀이 '숫자 셀'인가: 표시 텍스트가 숫자로 파싱되거나(콤마/공백 제거 후),
     * FIELD_FORMULA 의 표시값이 숫자이면 true. 헤더/라벨(한글 텍스트 등)은 false → 합산 제외.
     */
    private boolean isNumericCell(Cell[][] origin, int r, int c) {
        if (r < 0 || r >= origin.length || c < 0 || c >= origin[r].length) return false;
        Cell cell = origin[r][c];
        if (cell == null) return false;
        String txt = cellText(cell);
        if (txt == null) return false;
        String num = txt.replaceAll("[,\\s]", "");
        return num.matches("[-+]?\\d+(\\.\\d+)?");
    }

    /**
     * task3/4(v16t38): '장식 띠' 셀 여부 — 채움(면색/그라데이션)이 있고 표시 텍스트가 전혀 없는
     * (빈 문단만) 셀. 표지 상/하단 파란 띠가 이에 해당. 행높이를 빈 문단 폰트가 아닌 원본 cellSz
     * 높이로 고정하고 빈 문단 폰트를 원본 크기로 축소하는 대상 판정에 쓴다(case1 하드코딩 회피).
     */
    private boolean isBandCell(Cell cell) {
        if (cell == null) return false;
        List<Paragraph> paras = cellParagraphs(cell);
        if (paras.isEmpty()) return false;
        if (fillHex(borderFill(cell.getListHeader().getBorderFillId())) == null) return false;
        // v16t45 FIX F-ⓑ: 채움색+표시텍스트 없음이라도 셀 문단에 확장컨트롤(중첩표/그림·도형/수식)
        //   이 있으면 콘텐츠 셀 — 띠셀로 판정하면 해당 컨트롤이 통째로 폐기된다(TA-02/04 소실 원인).
        for (Paragraph p : paras) {
            List<kr.dogfoot.hwplib.object.bodytext.control.Control> cl = p.getControlList();
            if (cl == null) continue;
            for (kr.dogfoot.hwplib.object.bodytext.control.Control c : cl) {
                kr.dogfoot.hwplib.object.bodytext.control.ControlType t = c.getType();
                if (t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Table
                        || t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Gso
                        || t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Equation) {
                    return false;
                }
            }
        }
        String txt = cellText(cell);
        return txt != null && txt.trim().isEmpty();
    }

    /**
     * 업무3(v16.67): '빈 셀' 여부 — 표시 텍스트가 없고 확장 컨트롤(중첩표/그림·도형/수식)도 없는 셀.
     * 채움 유무는 따지지 않는다(밴드셀과 달리 면색 없는 빈 칸도 포함). 장식 띠 행에 채움셀과 섞여도
     * 행을 늘릴 콘텐츠가 없으므로 행높이 고정을 허용하는 판정에 쓴다(case1 회색 제목행의 빈 칸 대응).
     */
    private boolean isEmptyCell(Cell cell) {
        if (cell == null) return true;
        for (Paragraph p : cellParagraphs(cell)) {
            List<kr.dogfoot.hwplib.object.bodytext.control.Control> cl = p.getControlList();
            if (cl == null) continue;
            for (kr.dogfoot.hwplib.object.bodytext.control.Control c : cl) {
                kr.dogfoot.hwplib.object.bodytext.control.ControlType t = c.getType();
                if (t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Table
                        || t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Gso
                        || t == kr.dogfoot.hwplib.object.bodytext.control.ControlType.Equation) {
                    return false;
                }
            }
        }
        String txt = cellText(cell);
        return txt == null || txt.trim().isEmpty();
    }

    /** 셀의 표시 텍스트(문단 normal 문자 연결). 수식 셀은 한글이 채운 표시 숫자가 그대로 들어온다. */
    private String cellText(Cell cell) {
        StringBuilder sb = new StringBuilder();
        for (Paragraph p : cellParagraphs(cell)) {
            kr.dogfoot.hwplib.object.bodytext.paragraph.text.ParaText pt = p.getText();
            if (pt == null) continue;
            List<kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar> chars = pt.getCharList();
            if (chars == null) continue;
            for (kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar ch : chars) {
                if (ch.getType() == kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharType.Normal) {
                    int code = ch.getCode() & 0xFFFF;
                    if (code >= 0x20) sb.appendCodePoint(code);
                }
            }
        }
        return sb.toString();
    }

    private List<Paragraph> cellParagraphs(Cell cell) {
        List<Paragraph> list = new ArrayList<>();
        try {
            Object pl = cell.getParagraphList();
            if (pl == null) return list;
            int cnt = (int) pl.getClass().getMethod("getParagraphCount").invoke(pl);
            java.lang.reflect.Method getM = pl.getClass().getMethod("getParagraph", int.class);
            for (int i = 0; i < cnt; i++) list.add((Paragraph) getM.invoke(pl, i));
        } catch (Exception ignore) {}
        return list;
    }

    /** task3/4: 연결셀 등 열폭이 좁은 셀(임계 이하)은 좌우 패딩을 축소해 글리프 가용폭 확보. */
    private static final long NARROW_CELL_HU = Math.round(0.8 / 2.54 * 7200.0); // 0.8cm

    private String cellStyle(Cell cell, String[] tblOuter, boolean onLeft, boolean onRight,
                             boolean onTop, boolean onBottom, long cellWidthHU) {
        boolean narrow = cellWidthHU > 0 && cellWidthHU <= NARROW_CELL_HU;
        BorderFill bf = borderFill(cell.getListHeader().getBorderFillId());
        String left   = side(bf == null ? null : bf.getLeftBorder());
        String right  = side(bf == null ? null : bf.getRightBorder());
        String top    = side(bf == null ? null : bf.getTopBorder());
        String bottom = side(bf == null ? null : bf.getBottomBorder());
        // task5: 셀이 자체 borderFill 을 가지면 그 값이 한컴 렌더의 정답 — 명시적 NONE 은 '선 없음'
        // 의도이므로 표 외곽선으로 덮어쓰지 않는다(제목표 색상 배너 등). borderFill 미해결(bf==null)
        // 경계 셀만 표 외곽선으로 폴백 — 기존 동작 보존(회귀 방지).
        if (bf == null) {
            if (onLeft   && left   == null) left   = tblOuter[0];
            if (onRight  && right  == null) right  = tblOuter[1];
            if (onTop    && top    == null) top    = tblOuter[2];
            if (onBottom && bottom == null) bottom = tblOuter[3];
        }
        // v16t33 task3: 셀 배경 이미지필(ImageFill) 우선 — 로고 등이 셀 면색이 아닌
        // borderFill 의 ImageFill 로 들어가 그동안 누락됐다. 이미지가 있으면 단색 대신 발화.
        String[] img = cellImage(bf);
        if (img != null) {
            return ctx.styles.cellStyle(left, right, top, bottom, null, "middle",
                    img[0], img[1], img[2], narrow);
        }
        // task2(v16t39): 표지 하단 띠는 원본이 RADIAL 그라데이션이나 LibreOffice 가 셀 그라데이션을
        // 미렌더(흰색)해 띠가 사라지므로, 사용자 확정대로 단색 #0080C0(fillHex 첫 정지색)으로 발화.
        // 띠 두께는 행높이 고정(tableRowFixedHeight)으로 별도 보정 — hwpx 측과 동일(파리티).
        String bg = fillHex(bf);
        return ctx.styles.cellStyle(left, right, top, bottom, bg, "middle",
                null, null, null, narrow);
    }

    /**
     * 셀 BorderFill 의 이미지필 → {href, repeat, position} 또는 null.
     * 추출은 본문 그림과 동일 인프라(BinData → Pictures/)를 재사용한다.
     */
    private String[] cellImage(BorderFill bf) {
        if (bf == null) return null;
        try {
            FillInfo fi = bf.getFillInfo();
            if (fi == null) return null;
            FillType ft = fi.getType();
            if (ft == null || !ft.hasImageFill()) return null;
            ImageFill imf = fi.getImageFill();
            if (imf == null || imf.getPictureInfo() == null) return null;
            int binId = imf.getPictureInfo().getBinItemID();
            String href = traverser.registerCellImage(binId);
            if (href == null) return null;
            String[] rp = repeatPos(imf.getImageFillType());
            return new String[]{ href, rp[0], rp[1] };
        } catch (Throwable t) { return null; }
    }

    /** HWP ImageFillType → ODF {style:repeat, style:position}. 불명 시 no-repeat/center. */
    private static String[] repeatPos(ImageFillType t) {
        if (t == null) return new String[]{ "no-repeat", "center" };
        switch (t) {
            case TileAll:
            case TileHorizonalTop:
            case TileHorizonalBottom:
            case TileVerticalLeft:
            case TileVerticalRight:
                return new String[]{ "repeat", null };
            case FitSize:
            case Zoom:
                return new String[]{ "stretch", null };
            case Center:       return new String[]{ "no-repeat", "center" };
            case CenterTop:    return new String[]{ "no-repeat", "center top" };
            case CenterBottom: return new String[]{ "no-repeat", "center bottom" };
            case LeftCenter:   return new String[]{ "no-repeat", "left" };
            case LeftTop:      return new String[]{ "no-repeat", "left top" };
            case LeftBottom:   return new String[]{ "no-repeat", "left bottom" };
            case RightCenter:  return new String[]{ "no-repeat", "right" };
            case RightTop:     return new String[]{ "no-repeat", "right top" };
            case RightBottom:  return new String[]{ "no-repeat", "right bottom" };
            default:           return new String[]{ "no-repeat", "center" };
        }
    }

    private static String fillHex(BorderFill bf) {
        if (bf == null) return null;
        try {
            FillInfo fi = bf.getFillInfo();
            if (fi == null) return null;
            FillType ft = fi.getType();
            if (ft == null) return null;
            // 1) 단색(PatternFill) 우선 — 기존 동작 무수정.
            if (ft.hasPatternFill()) {
                PatternFill pf = fi.getPatternFill();
                if (pf == null) return null;
                Color4Byte c = pf.getBackColor();
                if (c == null) return null;
                // v16t45 FIX F-ⓐ: raw=0xFFFFFFFF 은 한컴 '채움 없음' 센티널(알파 FF) —
                //   #FFFFFF 흰색 채움으로 오판하면 isBandCell 이 셀 내용을 폐기한다.
                //   (hwpx 측은 faceColor="none"→null 로 이미 통과하므로 hwp 측만 보정.)
                if ((c.getValue() & 0xFFFFFFFFL) == 0xFFFFFFFFL) return null;
                return String.format("#%02X%02X%02X", c.getR() & 0xFF, c.getG() & 0xFF, c.getB() & 0xFF);
            }
            // 2) 그라데이션(GradientFill) — 단색이 없을 때만 진입(무회귀). 첫 정지색으로 단색 근사
            //    (HWPX gradation 측과 동일 정책). ODT 셀 배경의 정식 그라데이션 표현은 까다로워 1차 근사.
            if (ft.hasGradientFill()) {
                kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.GradientFill gf = fi.getGradientFill();
                if (gf != null && gf.getColorList() != null && !gf.getColorList().isEmpty()) {
                    Color4Byte c = gf.getColorList().get(0);
                    if (c != null) {
                        return String.format("#%02X%02X%02X", c.getR() & 0xFF, c.getG() & 0xFF, c.getB() & 0xFF);
                    }
                }
            }
            return null; // 면색 없음 → 배경 미지정
        } catch (Throwable t) { return null; }
    }

    private BorderFill borderFill(int id) {
        try {
            if (di == null) return null;
            List<BorderFill> list = di.getBorderFillList();
            // borderFillId 는 1-base
            int idx = id - 1;
            if (list != null && idx >= 0 && idx < list.size()) return list.get(idx);
        } catch (Throwable ignore) {}
        return null;
    }

    private static String side(EachBorder b) {
        if (b == null) return null;
        BorderType t = b.getType();
        if (t == null || t == BorderType.None) return null;
        String color = colorHex(b.getColor());
        return "0.5pt solid " + (color == null ? "#000000" : color);
    }

    private static String colorHex(Color4Byte c) {
        if (c == null) return "#000000";
        return String.format("#%02X%02X%02X", c.getR() & 0xFF, c.getG() & 0xFF, c.getB() & 0xFF);
    }
}

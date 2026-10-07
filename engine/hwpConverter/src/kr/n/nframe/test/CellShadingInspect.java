package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPChar;
import kr.dogfoot.hwplib.object.bodytext.paragraph.text.HWPCharNormal;
import kr.dogfoot.hwplib.object.docinfo.BorderFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillInfo;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.FillType;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.PatternFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.GradientFill;
import kr.dogfoot.hwplib.object.docinfo.borderfill.fillinfo.ImageFill;
import kr.dogfoot.hwplib.object.etc.Color4Byte;
import kr.dogfoot.hwplib.reader.HWPReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Cell shading inspection: dump every outer table's cells with borderFillId
 * and the bf's fillInfo (pattern back color RGB / gradient / image).
 *
 * Args: <hwpPath> [tableIndex]
 * If tableIndex is omitted, dumps all outer tables.
 * Also collects nested tables with their parent location info.
 */
public class CellShadingInspect {
    public static void main(String[] a) throws Exception {
        if (a.length == 0) {
            System.err.println("usage: CellShadingInspect <hwpPath> [tableIndex]");
            return;
        }
        HWPFile h = HWPReader.fromFile(a[0]);
        Integer onlyTable = (a.length > 1) ? Integer.parseInt(a[1]) : null;

        ArrayList<BorderFill> bfList = h.getDocInfo().getBorderFillList();
        System.out.println("=== File: " + a[0] + " ===");
        System.out.println("BorderFill count: " + bfList.size());
        // Optional brief BF dump (only those that look like fills)
        for (int i = 0; i < bfList.size(); i++) {
            BorderFill bf = bfList.get(i);
            String s = describeFill(bf);
            if (!"none".equals(s)) {
                System.out.printf("  bf[%d (1-based id=%d)]: %s%n", i, i + 1, s);
            }
        }

        Section sec = h.getBodyText().getSectionList().get(0);
        int tableIndex = 0;
        for (int pi = 0; pi < sec.getParagraphCount(); pi++) {
            Paragraph p = sec.getParagraph(pi);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (!(ctrl instanceof ControlTable)) continue;
                ControlTable t = (ControlTable) ctrl;
                if (onlyTable == null || tableIndex == onlyTable) {
                    dumpTable(t, tableIndex, pi, bfList, "");
                }
                tableIndex++;
            }
        }
        System.out.println("Total outer tables: " + tableIndex);
    }

    static void dumpTable(ControlTable t, int tableIndex, int paraIdx,
                          ArrayList<BorderFill> bfList, String prefix) {
        int rows = t.getRowList().size();
        int cols = (rows > 0) ? maxCols(t) : 0;
        System.out.println(prefix + "=== Table[" + tableIndex + "] @ para#" + paraIdx
                + " rows=" + rows + " maxCols=" + cols + " ===");
        int rIdx = 0;
        for (Row row : t.getRowList()) {
            int cIdx = 0;
            for (Cell cell : row.getCellList()) {
                ListHeaderForCell lh = cell.getListHeader();
                int rowNo = lh.getRowIndex();
                int colNo = lh.getColIndex();
                int cs = lh.getColSpan();
                int rs = lh.getRowSpan();
                long w = lh.getWidth();
                long ht = lh.getHeight();
                int bfId = lh.getBorderFillId();

                String bfDesc = "(invalid)";
                // hwplib stores 1-based ids in some places; bfList is 0-based.
                // Try (bfId-1) first, fallback bfId.
                BorderFill bf = null;
                int actualIdx = -1;
                if (bfId >= 1 && (bfId - 1) < bfList.size()) {
                    bf = bfList.get(bfId - 1);
                    actualIdx = bfId - 1;
                } else if (bfId >= 0 && bfId < bfList.size()) {
                    bf = bfList.get(bfId);
                    actualIdx = bfId;
                }
                if (bf != null) bfDesc = describeFill(bf);

                String txt = firstText(cell, 16);
                System.out.printf(prefix + "  cell[%d,%d] cs=%d rs=%d w=%d h=%d bfId=%d(idx=%d) %s | %s%n",
                        rowNo, colNo, cs, rs, w, ht, bfId, actualIdx, bfDesc, txt);
                cIdx++;
            }
            rIdx++;
        }
    }

    static int maxCols(ControlTable t) {
        int max = 0;
        for (Row r : t.getRowList()) {
            int c = 0;
            for (Cell cell : r.getCellList()) {
                int colNo = cell.getListHeader().getColIndex();
                int cs = cell.getListHeader().getColSpan();
                if (colNo + cs > c) c = colNo + cs;
            }
            if (c > max) max = c;
        }
        return max;
    }

    static String describeFill(BorderFill bf) {
        FillInfo fi = bf.getFillInfo();
        if (fi == null) return "none";
        FillType ft = fi.getType();
        if (ft == null) return "none";
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        if (ft.hasPatternFill()) {
            PatternFill pf = fi.getPatternFill();
            if (pf != null) {
                Color4Byte bc = pf.getBackColor();
                Color4Byte pc = pf.getPatternColor();
                sb.append(String.format("pattern bgRGB=%02X%02X%02X",
                        bc.getR() & 0xff, bc.getG() & 0xff, bc.getB() & 0xff));
                if (pc != null) {
                    sb.append(String.format(" patRGB=%02X%02X%02X",
                            pc.getR() & 0xff, pc.getG() & 0xff, pc.getB() & 0xff));
                }
                any = true;
            }
        }
        if (ft.hasGradientFill()) {
            if (any) sb.append(" + ");
            sb.append("gradient");
            any = true;
        }
        if (ft.hasImageFill()) {
            if (any) sb.append(" + ");
            ImageFill imf = fi.getImageFill();
            sb.append("image");
            if (imf != null && imf.getPictureInfo() != null) {
                sb.append("(binItem=").append(imf.getPictureInfo().getBinItemID()).append(")");
            }
            any = true;
        }
        if (!any) return "none";
        return sb.toString();
    }

    static String firstText(Cell cell, int max) {
        StringBuilder sb = new StringBuilder();
        if (cell.getParagraphList() == null) return "";
        for (Paragraph cp : cell.getParagraphList()) {
            if (cp.getText() == null) continue;
            for (HWPChar c : cp.getText().getCharList()) {
                if (c instanceof HWPCharNormal) {
                    sb.appendCodePoint(((HWPCharNormal) c).getCode());
                    if (sb.length() >= max) break;
                }
            }
            if (sb.length() >= max) break;
        }
        String s = sb.toString().replace("\n", " ").replace("\r", " ");
        if (s.length() > max) s = s.substring(0, max) + "...";
        return s;
    }
}

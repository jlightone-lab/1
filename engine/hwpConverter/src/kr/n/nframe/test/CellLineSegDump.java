package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.ControlType;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.object.bodytext.control.table.ListHeaderForCell;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.lineseg.LineSegItem;
import kr.dogfoot.hwplib.reader.HWPReader;

/**
 * Dumps per-cell paragraph LineSeg + ListHeader sizing for table cells in a
 * given HWP. Used to diagnose v14.40 글자 겹침 (text-overlap) issue against
 * the original case12.hwp.
 *
 *   java kr.n.nframe.test.CellLineSegDump <hwp> [tableLimit] [cellLimit]
 */
public class CellLineSegDump {

    public static void main(String[] args) throws Exception {
        String path = args[0];
        int tableLimit = args.length > 1 ? Integer.parseInt(args[1]) : 3;
        int cellLimit  = args.length > 2 ? Integer.parseInt(args[2]) : 6;

        HWPFile hwp = HWPReader.fromFile(path);
        Section sec = hwp.getBodyText().getLastSection();
        System.out.println("=== " + path + " ===");
        System.out.println("paragraphs=" + sec.getParagraphCount());

        int tableSeen = 0;
        for (int p = 0; p < sec.getParagraphCount(); p++) {
            Paragraph para = sec.getParagraph(p);
            if (para.getControlList() == null) continue;
            for (int ci = 0; ci < para.getControlList().size(); ci++) {
                Control ctrl = para.getControlList().get(ci);
                if (!(ctrl instanceof ControlTable)) continue;
                ControlTable ct = (ControlTable) ctrl;
                if (tableSeen >= tableLimit) {
                    System.out.println("(skipping further tables)");
                    return;
                }
                tableSeen++;
                int sumCellHeights = 0;
                int[] rowHs = new int[ct.getTable().getRowCount()];
                for (int ri = 0; ri < ct.getRowList().size(); ri++) {
                    Row rrow = ct.getRowList().get(ri);
                    for (int ck = 0; ck < rrow.getCellList().size(); ck++) {
                        Cell cc = rrow.getCellList().get(ck);
                        if (cc.getListHeader().getRowSpan() == 1) {
                            int idx = (int) cc.getListHeader().getRowIndex();
                            rowHs[idx] = Math.max(rowHs[idx],
                                (int) cc.getListHeader().getHeight());
                        }
                    }
                }
                for (int rh : rowHs) sumCellHeights += rh;
                StringBuilder rhStr = new StringBuilder();
                for (int rh : rowHs) { if (rhStr.length()>0) rhStr.append(","); rhStr.append(rh); }
                System.out.println("\n--- table#" + tableSeen + " (host para=" + p
                        + ", w=" + ct.getHeader().getWidth()
                        + ", h=" + ct.getHeader().getHeight()
                        + ", sumRowH=" + sumCellHeights + " [" + rhStr + "]"
                        + ", rows=" + ct.getTable().getRowCount()
                        + ", cols=" + ct.getTable().getColumnCount() + ") ---");
                int dumped = 0;
                for (int ri = 0; ri < ct.getRowList().size(); ri++) {
                    Row row = ct.getRowList().get(ri);
                    for (int ck = 0; ck < row.getCellList().size(); ck++) {
                        Cell cell = row.getCellList().get(ck);
                        ListHeaderForCell lh = cell.getListHeader();
                        if (dumped >= cellLimit) break;
                        dumped++;
                        StringBuilder sb = new StringBuilder();
                        sb.append("  cell[r").append(ri).append("c").append(ck).append("] ")
                          .append("col=").append(lh.getColIndex())
                          .append(" row=").append(lh.getRowIndex())
                          .append(" cs=").append(lh.getColSpan())
                          .append(" rs=").append(lh.getRowSpan())
                          .append(" w=").append(lh.getWidth())
                          .append(" h=").append(lh.getHeight())
                          .append(" tw=").append(lh.getTextWidth())
                          .append(" margins=L").append(lh.getLeftMargin())
                          .append("/R").append(lh.getRightMargin())
                          .append("/T").append(lh.getTopMargin())
                          .append("/B").append(lh.getBottomMargin())
                          .append(" applyInner=").append(lh.getProperty().isApplyInnerMagin())
                          .append(" tva=").append(lh.getProperty().getTextVerticalAlignment())
                          .append(" paraCount=").append(cell.getParagraphList().getParagraphCount());
                        System.out.println(sb);
                        for (int pi = 0; pi < cell.getParagraphList().getParagraphCount(); pi++) {
                            Paragraph cp = cell.getParagraphList().getParagraph(pi);
                            String text = cp.getNormalString();
                            String show = text == null ? "" : text;
                            if (show.length() > 60) show = show.substring(0, 60) + "...";
                            show = show.replace("\n", "\\n").replace("\r", "\\r");
                            System.out.println("    para[" + pi + "] chars="
                                    + cp.getHeader().getCharacterCount()
                                    + " csCount=" + cp.getHeader().getCharShapeCount()
                                    + " laCount=" + cp.getHeader().getLineAlignCount()
                                    + " paraShape=" + cp.getHeader().getParaShapeId()
                                    + " text=\"" + show + "\"");
                            if (cp.getLineSeg() != null) {
                                int n = cp.getLineSeg().getLineSegItemList().size();
                                System.out.println("      lineSegItems=" + n);
                                for (int li = 0; li < n; li++) {
                                    LineSegItem s = cp.getLineSeg().getLineSegItemList().get(li);
                                    System.out.println("        seg[" + li + "] startPos="
                                            + s.getTextStartPosition()
                                            + " linePos=" + s.getLineVerticalPosition()
                                            + " lineH=" + s.getLineHeight()
                                            + " textPartH=" + s.getTextPartHeight()
                                            + " baseDist=" + s.getDistanceBaseLineToLineVerticalPosition()
                                            + " lineSpace=" + s.getLineSpace()
                                            + " segW=" + s.getSegmentWidth()
                                            + " startCol=" + s.getStartPositionFromColumn()
                                            + " tag=0x" + Long.toHexString(s.getTag().getValue() & 0xFFFFFFFFL));
                                }
                            }
                            if (cp.getCharShape() != null) {
                                StringBuilder cs = new StringBuilder("      charShapeMap=");
                                for (int k = 0; k < cp.getCharShape().getPositonShapeIdPairList().size(); k++) {
                                    if (k > 0) cs.append(",");
                                    cs.append("[").append(cp.getCharShape().getPositonShapeIdPairList().get(k).getPosition())
                                      .append("→").append(cp.getCharShape().getPositonShapeIdPairList().get(k).getShapeId())
                                      .append("]");
                                }
                                System.out.println(cs);
                            }
                        }
                    }
                    if (dumped >= cellLimit) break;
                }
            }
        }
    }
}

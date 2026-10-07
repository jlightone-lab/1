package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectNested {
    static int totalTables = 0;
    static int nestedTables = 0;
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        for (int i = 0; i < s.getParagraphCount(); i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (ctrl instanceof ControlTable) {
                    int tn = totalTables++;
                    inspectTable((ControlTable)ctrl, "T"+tn, 0);
                }
            }
        }
        System.out.println("\nTotal outer tables: " + totalTables);
        System.out.println("Nested tables: " + nestedTables);
    }
    static void inspectTable(ControlTable ct, String prefix, int depth) {
        int rows = ct.getRowList().size();
        int cols = (rows > 0) ? ct.getRowList().get(0).getCellList().size() : 0;
        StringBuilder _sbIdt = new StringBuilder(); for (int _i = 0; _i < depth; _i++) _sbIdt.append("  ");
        String indent = _sbIdt.toString();
        kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso h = ct.getHeader();
        kr.dogfoot.hwplib.object.bodytext.control.table.Table tb = ct.getTable();
        System.out.println(String.format("%s%s: rows=%d cols=%d w=%d h=%d outerLR=%d/%d outerTB=%d/%d innerLR=%d/%d innerTB=%d/%d cellSp=%d bf=%d",
                indent, prefix, rows, cols,
                h.getWidth(), h.getHeight(),
                h.getOutterMarginLeft(), h.getOutterMarginRight(),
                h.getOutterMarginTop(), h.getOutterMarginBottom(),
                tb.getLeftInnerMargin(), tb.getRightInnerMargin(),
                tb.getTopInnerMargin(), tb.getBottomInnerMargin(),
                tb.getCellSpacing(), tb.getBorderFillId()));
        // Show first cell's margin/width/height
        if (rows > 0 && cols > 0) {
          Cell first = ct.getRowList().get(0).getCellList().get(0);
          System.out.println(String.format("%s   firstCell: w=%d h=%d cellLR=%d/%d cellTB=%d/%d",
                  indent,
                  first.getListHeader().getWidth(),
                  first.getListHeader().getHeight(),
                  first.getListHeader().getLeftMargin(),
                  first.getListHeader().getRightMargin(),
                  first.getListHeader().getTopMargin(),
                  first.getListHeader().getBottomMargin()));
        }
        int rIdx = 0;
        for (Row r : ct.getRowList()) {
            int cIdx = 0;
            for (Cell c : r.getCellList()) {
                if (c.getParagraphList() != null) {
                    for (int pi = 0; pi < c.getParagraphList().getParagraphCount(); pi++) {
                        Paragraph cp = c.getParagraphList().getParagraph(pi);
                        if (cp.getControlList() != null) {
                            for (Control ctrl : cp.getControlList()) {
                                if (ctrl instanceof ControlTable) {
                                    nestedTables++;
                                    String np = prefix + "/r"+rIdx+"c"+cIdx+"-NESTED";
                                    inspectTable((ControlTable)ctrl, np, depth+1);
                                }
                            }
                        }
                    }
                }
                cIdx++;
            }
            rIdx++;
        }
    }
}

package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectTableLayout {
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        int tn = 0;
        for (int i = 0; i < s.getParagraphCount(); i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (ctrl instanceof ControlTable) {
                    ControlTable ct = (ControlTable) ctrl;
                    int rowCount = ct.getRowList().size();
                    int colCount = (rowCount > 0) ? ct.getRowList().get(0).getCellList().size() : 0;
                    System.out.print("T"+tn+" rows="+rowCount+" cols="+colCount+" colWidths=[");
                    if (rowCount > 0) {
                        Row r0 = ct.getRowList().get(0);
                        for (int c = 0; c < r0.getCellList().size(); c++) {
                            Cell cell = r0.getCellList().get(c);
                            if (c > 0) System.out.print(",");
                            System.out.print(cell.getListHeader().getWidth());
                        }
                    }
                    System.out.println("]");
                    tn++;
                }
            }
        }
    }
}

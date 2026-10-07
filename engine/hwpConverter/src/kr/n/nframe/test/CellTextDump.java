package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.reader.HWPReader;
public class CellTextDump {
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
                    System.out.println("=== Table #" + tn + " (paraIdx=" + i + ") rows=" + ct.getRowList().size() + " ===");
                    int ri = 0;
                    for (Row r : ct.getRowList()) {
                        int ci = 0;
                        for (Cell c : r.getCellList()) {
                            StringBuilder cellText = new StringBuilder();
                            if (c.getParagraphList() != null) {
                                for (int pi = 0; pi < c.getParagraphList().getParagraphCount(); pi++) {
                                    Paragraph cp = c.getParagraphList().getParagraph(pi);
                                    String t = cp.getNormalString();
                                    if (t != null && !t.isEmpty()) {
                                        if (cellText.length() > 0) cellText.append("\n");
                                        cellText.append(t);
                                    }
                                }
                            }
                            if (cellText.length() > 0)
                                System.out.println("  T" + tn + "/r" + ri + "c" + ci + ": " + cellText.toString());
                            ci++;
                        }
                        ri++;
                    }
                    tn++;
                }
            }
        }
    }
}

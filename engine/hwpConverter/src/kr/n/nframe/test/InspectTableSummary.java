package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectTableSummary {
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
                    String firstText = "";
                    int totalLines = 0;
                    for (Row r : ct.getRowList()) {
                        for (Cell c : r.getCellList()) {
                            if (c.getParagraphList() != null) {
                                int pc = c.getParagraphList().getParagraphCount();
                                totalLines += pc;
                                if (firstText.isEmpty()) {
                                    for (int pi = 0; pi < pc; pi++) {
                                        String t = c.getParagraphList().getParagraph(pi).getNormalString();
                                        if (t != null && !t.isEmpty() && !t.equals("​")) {
                                            firstText = t.replace('\n', ' ');
                                            if (firstText.length() > 50) firstText = firstText.substring(0, 50) + "...";
                                            break;
                                        }
                                    }
                                }
                            }
                        }
                    }
                    System.out.println(String.format("T%-2d paraIdx=%-4d rows=%-2d cols=%-2d totalLines=%-3d firstText=\"%s\"",
                        tn, i, rowCount, colCount, totalLines, firstText));
                    tn++;
                }
            }
        }
    }
}

package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ControlTable;
import kr.dogfoot.hwplib.object.bodytext.control.table.Row;
import kr.dogfoot.hwplib.object.bodytext.control.table.Cell;
import kr.dogfoot.hwplib.reader.HWPReader;
public class VerifyLikeWord {
    static int outerCount = 0, nestedCount = 0;
    static int outerLikeWordTrue = 0, outerLikeWordFalse = 0;
    static int nestedLikeWordTrue = 0, nestedLikeWordFalse = 0;
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        for (int i = 0; i < s.getParagraphCount(); i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (ctrl instanceof ControlTable) {
                    inspectTable((ControlTable)ctrl, false);
                }
            }
        }
        System.out.println("File: " + args[0]);
        System.out.println("Outer tables:  " + outerCount + " (likeWord true=" + outerLikeWordTrue + ", false=" + outerLikeWordFalse + ")");
        System.out.println("Nested tables: " + nestedCount + " (likeWord true=" + nestedLikeWordTrue + ", false=" + nestedLikeWordFalse + ")");
    }
    static void inspectTable(ControlTable ct, boolean isNested) {
        boolean lw = ct.getHeader().getProperty().isLikeWord();
        if (isNested) {
            nestedCount++;
            if (lw) nestedLikeWordTrue++; else nestedLikeWordFalse++;
        } else {
            outerCount++;
            if (lw) outerLikeWordTrue++; else outerLikeWordFalse++;
        }
        for (Row r : ct.getRowList()) {
            for (Cell c : r.getCellList()) {
                if (c.getParagraphList() != null) {
                    for (int pi = 0; pi < c.getParagraphList().getParagraphCount(); pi++) {
                        Paragraph cp = c.getParagraphList().getParagraph(pi);
                        if (cp.getControlList() != null) {
                            for (Control ctrl : cp.getControlList()) {
                                if (ctrl instanceof ControlTable) {
                                    inspectTable((ControlTable)ctrl, true);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

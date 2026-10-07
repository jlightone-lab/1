package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.paragraph.charshape.CharPositionShapeIdPair;
import kr.dogfoot.hwplib.reader.HWPReader;

/** Quick rich-mode validator: scan paragraphs and report multi-charShape runs. */
public class DumpRichHwp {
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        int csCount = h.getDocInfo().getCharShapeList().size();
        System.out.println("docinfo charShapes: " + csCount);
        int multi = 0, headingsSeen = 0, boldRefs = 0;
        for (int i = 0; i < s.getParagraphCount(); i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getCharShape() == null) continue;
            int n = p.getCharShape().getPositonShapeIdPairList().size();
            String txt = p.getNormalString();
            if (n > 1) {
                multi++;
                if (multi <= 5) {
                    System.out.print("[para " + i + "] runs=" + n + " text=" +
                            (txt == null ? "" : txt.replace("\n", " ").substring(0,
                                    Math.min(80, txt.length()))));
                    System.out.print("  pairs=");
                    for (CharPositionShapeIdPair pr : p.getCharShape()
                            .getPositonShapeIdPairList()) {
                        System.out.print("(" + pr.getPosition() + "->" + pr.getShapeId() + ") ");
                    }
                    System.out.println();
                }
            }
            for (CharPositionShapeIdPair pr : p.getCharShape().getPositonShapeIdPairList()) {
                long sid = pr.getShapeId();
                if (sid == 7 || sid == 5 || sid == 12 || sid == 13 || sid == 20 || sid == 30)
                    headingsSeen++;
                if (sid == 55) boldRefs++;
            }
        }
        System.out.println("multi-CS paragraphs: " + multi);
        System.out.println("heading-CS refs (CS 7/5/12/13/20/30): " + headingsSeen);
        System.out.println("bold-CS refs (CS 55): " + boldRefs);

        System.out.println("--- first 25 paragraphs ---");
        for (int i = 0; i < Math.min(25, s.getParagraphCount()); i++) {
            Paragraph p = s.getParagraph(i);
            String txt = p.getNormalString();
            int firstSid = -1;
            int baseSize = -1;
            if (p.getCharShape() != null
                    && !p.getCharShape().getPositonShapeIdPairList().isEmpty()) {
                firstSid = (int) p.getCharShape().getPositonShapeIdPairList().get(0).getShapeId();
                if (firstSid >= 0 && firstSid < csCount) {
                    baseSize = h.getDocInfo().getCharShapeList().get(firstSid).getBaseSize();
                }
            }
            System.out.println(String.format("[%3d] cs=%2d size=%4d %s", i, firstSid, baseSize,
                    txt == null ? "" : txt.replace("\n", " ").substring(0,
                            Math.min(80, txt.length()))));
        }
    }
}

package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;

public class InspectFirstPara {
    public static void main(String[] args) throws Exception {
        HWPFile hwp = HWPReader.fromFile(args[0]);
        Section sec = hwp.getBodyText().getLastSection();
        System.out.println("paragraph count: " + sec.getParagraphCount());
        for (int i = 0; i < Math.min(3, sec.getParagraphCount()); i++) {
            Paragraph p = sec.getParagraph(i);
            System.out.println("para[" + i + "] chars=" + p.getHeader().getCharacterCount()
                    + " styleId=" + p.getHeader().getStyleId()
                    + " paraShapeId=" + p.getHeader().getParaShapeId()
                    + " ctrlMask=0x" + Long.toHexString(p.getHeader().getControlMask().getValue()));
            String text = p.getNormalString();
            System.out.println("  text='" + (text.length() > 60 ? text.substring(0,60)+"..." : text) + "'");
            if (p.getControlList() != null) {
                System.out.println("  controls: " + p.getControlList().size());
                for (int ci = 0; ci < p.getControlList().size(); ci++) {
                    System.out.println("    ctrl[" + ci + "] " + p.getControlList().get(ci).getClass().getSimpleName());
                }
            }
        }
    }
}

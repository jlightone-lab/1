package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;

public class TextDump {
    public static void main(String[] args) throws Exception {
        String hwp = args[0];
        int from = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int to = args.length > 2 ? Integer.parseInt(args[2]) : 50;
        HWPFile h = HWPReader.fromFile(hwp);
        Section s = h.getBodyText().getLastSection();
        System.out.println("paragraphs: " + s.getParagraphCount());
        for (int i = from; i < Math.min(to, s.getParagraphCount()); i++) {
            Paragraph p = s.getParagraph(i);
            String t = p.getNormalString();
            System.out.println(String.format("[%4d] %s", i, t));
        }
    }
}

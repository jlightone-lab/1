package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectPictureCtrl {
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        int picCount = 0;
        for (int i = 0; i < s.getParagraphCount(); i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (ctrl instanceof ControlPicture) {
                    ControlPicture pic = (ControlPicture) ctrl;
                    int binId = pic.getShapeComponentPicture().getPictureInfo().getBinItemID();
                    System.out.println("Para#" + i + " ControlPicture binItemID = " + binId);
                    picCount++;
                }
            }
        }
        System.out.println("Total picture controls: " + picCount);
    }
}

package kr.n.nframe.test;
import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.object.bodytext.control.gso.ControlPicture;
import kr.dogfoot.hwplib.object.bodytext.control.Control;
import kr.dogfoot.hwplib.object.bodytext.control.ctrlheader.CtrlHeaderGso;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponent.ShapeComponent;
import kr.dogfoot.hwplib.object.bodytext.control.gso.shapecomponenteach.ShapeComponentPicture;
import kr.dogfoot.hwplib.reader.HWPReader;
public class InspectPicCtrlDetail {
    public static void main(String[] args) throws Exception {
        HWPFile h = HWPReader.fromFile(args[0]);
        Section s = h.getBodyText().getLastSection();
        int n = 0;
        for (int i = 0; i < s.getParagraphCount() && n < 2; i++) {
            Paragraph p = s.getParagraph(i);
            if (p.getControlList() == null) continue;
            for (Control ctrl : p.getControlList()) {
                if (ctrl instanceof ControlPicture) {
                    ControlPicture pic = (ControlPicture) ctrl;
                    CtrlHeaderGso h2 = pic.getHeader();
                    ShapeComponent sc = pic.getShapeComponent();
                    ShapeComponentPicture scp = pic.getShapeComponentPicture();
                    System.out.println("\n=== Picture #" + n + " (paraIdx=" + i + ") ===");
                    System.out.println("Header: w=" + h2.getWidth() + " h=" + h2.getHeight() + " z=" + h2.getzOrder()
                        + " likeWord=" + h2.getProperty().isLikeWord()
                        + " textFlow=" + h2.getProperty().getTextFlowMethod()
                        + " textHorz=" + h2.getProperty().getTextHorzArrange()
                        + " widthCrit=" + h2.getProperty().getWidthCriterion()
                        + " heightCrit=" + h2.getProperty().getHeightCriterion()
                        + " vertRel=" + h2.getProperty().getVertRelTo()
                        + " horzRel=" + h2.getProperty().getHorzRelTo()
                        + " marginL=" + h2.getOutterMarginLeft()
                        + " marginR=" + h2.getOutterMarginRight());
                    System.out.println("Shape: offsetX=" + sc.getOffsetX() + " offsetY=" + sc.getOffsetY()
                        + " widthAtCreate=" + sc.getWidthAtCreate() + " heightAtCreate=" + sc.getHeightAtCreate()
                        + " widthAtCurrent=" + sc.getWidthAtCurrent() + " heightAtCurrent=" + sc.getHeightAtCurrent()
                        + " rotateAngle=" + sc.getRotateAngle());
                    System.out.println("ShapePic: binItemID=" + scp.getPictureInfo().getBinItemID()
                        + " effect=" + scp.getPictureInfo().getEffect()
                        + " borderThick=" + scp.getBorderThickness()
                        + " imageW=" + scp.getImageWidth() + " imageH=" + scp.getImageHeight()
                        + " brightness=" + scp.getPictureInfo().getBrightness()
                        + " contrast=" + scp.getPictureInfo().getContrast()
                        + " leftAfter=" + scp.getLeftAfterCutting()
                        + " topAfter=" + scp.getTopAfterCutting()
                        + " rightAfter=" + scp.getRightAfterCutting()
                        + " bottomAfter=" + scp.getBottomAfterCutting());
                    n++;
                    if (n >= 2) break;
                }
            }
        }
    }
}

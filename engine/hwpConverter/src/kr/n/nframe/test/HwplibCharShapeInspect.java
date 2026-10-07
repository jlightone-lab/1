package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;

/**
 * BlankFileMaker 가 제공하는 기본 CharShape / ParaShape 의 개수를 확인.
 */
public class HwplibCharShapeInspect {
    public static void main(String[] args) {
        HWPFile hwp = BlankFileMaker.make();
        DocInfo di = hwp.getDocInfo();
        System.out.println("charShape count: " + di.getCharShapeList().size());
        System.out.println("paraShape count: " + di.getParaShapeList().size());
        System.out.println("style count: " + di.getStyleList().size());
        System.out.println("faceName count(hangul): " + di.getHangulFaceNameList().size());
        System.out.println("borderFill count: " + di.getBorderFillList().size());
        for (int i = 0; i < di.getCharShapeList().size(); i++) {
            kr.dogfoot.hwplib.object.docinfo.CharShape cs = di.getCharShapeList().get(i);
            System.out.println("CharShape[" + i + "] baseSize=" + cs.getBaseSize());
        }
    }
}

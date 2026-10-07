package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

import kr.n.nframe.hwplib.constants.CtrlId;

public class CtrlSectionDef extends Control {
    public long property = 0;
    public int columnGap = 0;
    public int vertGrid = 0;
    public int horizGrid = 0;
    public int defaultTabStop = 8000;
    public int numberingParaShapeId = 0;
    public int pageNumber = 0;
    public int figNumber = 0;
    public int tableNumber = 0;
    public int equationNumber = 0;
    public int defaultLang = 0;
    public PageDef pageDef = new PageDef();
    public FootnoteShape footnoteShape = new FootnoteShape();
    public FootnoteShape endnoteShape = new FootnoteShape();
    public List<PageBorderFill> pageBorderFills = new ArrayList<>();

    public CtrlSectionDef() {
        super(CtrlId.SECTION_DEF);
    }
}

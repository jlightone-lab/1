package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class CtrlHeaderFooter extends Control {
    public long property = 0;
    public int textWidth = 0;
    public int textHeight = 0;
    public int textRefFlag = 0;
    public int numRefFlag = 0;
    public List<Paragraph> paragraphs = new ArrayList<>();

    public CtrlHeaderFooter(int ctrlId) {
        super(ctrlId);
    }
}

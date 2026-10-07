package kr.n.nframe.hwplib.model;

import kr.n.nframe.hwplib.constants.CtrlId;

public class CtrlColumnDef extends Control {
    public int propertyLow = 0;
    public int columnGap = 0;
    public int[] columnWidths;
    public int propertyHigh = 0;
    public int dividerType = 0;
    public int dividerWidth = 0;
    public long dividerColor = 0;

    public CtrlColumnDef() {
        super(CtrlId.COLUMN_DEF);
    }
}

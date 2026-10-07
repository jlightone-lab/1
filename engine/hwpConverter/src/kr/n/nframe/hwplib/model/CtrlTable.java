package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

import kr.n.nframe.hwplib.constants.CtrlId;

public class CtrlTable extends Control {
    // 표 전용 속성
    public long property = 0;
    public int rowCount = 0;
    public int colCount = 0;
    public int cellSpacing = 0;
    public int padLeft = 0;
    public int padRight = 0;
    public int padTop = 0;
    public int padBottom = 0;
    public int[] rowHeights;
    public int borderFillId = 0;
    public int zoneCount = 0;
    public List<TableCell> cells = new ArrayList<>();

    // 공통 개체 속성
    public long objProperty;
    public int vertOffset;
    public int horzOffset;
    public int width;
    public int height;
    public int zOrder;
    public int[] margins = new int[4];
    public long instanceId;
    public int pageBreakPrev = 0;
    public String description = "";

    public CtrlTable() {
        super(CtrlId.TABLE);
    }
}

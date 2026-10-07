package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class TableCell {
    public int colAddr;
    public int rowAddr;
    public int colSpan = 1;
    public int rowSpan = 1;
    public int width;
    public int height;
    public int[] margins = new int[4];
    public int borderFillId;
    public int listHeaderParaCount = 0;
    public long listHeaderProperty = 0;
    /** hp:subList의 텍스트 너비 (HWPUNIT). 확장 LIST_HEADER에서 사용. */
    public int textWidth = 0;
    /** hp:subList의 텍스트 높이 (HWPUNIT). 확장 LIST_HEADER에서 사용. */
    public int textHeight = 0;
    public List<Paragraph> paragraphs = new ArrayList<>();
}

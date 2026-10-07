package kr.n.nframe.hwplib.model;

public class Style {
    public String localName = "";
    public String englishName = "";
    public int type = 0;
    public int nextStyleId = 0;
    public int langId = 0;
    public int paraShapeId = 0;
    public int charShapeId = 0;
    public int lockForm = 0; // UINT16 lockForm 필드 (HWP 5.1+)
}

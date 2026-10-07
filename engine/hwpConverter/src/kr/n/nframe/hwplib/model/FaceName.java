package kr.n.nframe.hwplib.model;

public class FaceName {
    public String name;
    public int fontType; // 0=알 수 없음, 1=TTF, 2=HFT
    public boolean hasAltFont;
    public String altFontName;
    public int altFontType;
    public boolean hasTypeInfo;
    public byte[] typeInfo; // 10 byte PANOSE
    public boolean hasDefaultFont;
    public String defaultFontName;
}

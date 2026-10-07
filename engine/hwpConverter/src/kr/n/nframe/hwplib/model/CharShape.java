package kr.n.nframe.hwplib.model;

import java.util.Arrays;

public class CharShape {
    public int[] fontId = new int[7];
    public int[] ratio = new int[7];
    public int[] spacing = new int[7];
    public int[] relSize = new int[7];
    public int[] charOffset = new int[7];
    public int baseSize = 1000;
    public long property = 0;
    public int shadowOffsetX = 0;
    public int shadowOffsetY = 0;
    public long textColor = 0;
    public long underlineColor = 0;
    public long shadeColor = 0xFFFFFFFFL;
    public long shadowColor = 0;
    public int borderFillId = 0;
    public long strikeColor = 0;

    public CharShape() {
        Arrays.fill(ratio, 100);
        Arrays.fill(relSize, 100);
    }
}

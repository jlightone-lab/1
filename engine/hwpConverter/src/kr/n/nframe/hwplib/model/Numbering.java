package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class Numbering {
    public List<ParaHead> paraHeads = new ArrayList<>(); // 최대 7개 레벨 (0-6)
    public List<ParaHead> extParaHeads = new ArrayList<>(); // 확장 레벨 7-9 (HWP 5.1+)
    public int startNumber = 1;
    public int[] levelStartNumbers = new int[7];
    public int[] extLevelStartNumbers = new int[3]; // 확장 레벨 시작 번호 (레벨 8-10)

    public static class ParaHead {
        public long property = 0;
        public int widthAdjust = 0;
        public int textOffset = 0;
        public long charShapeId = 0;
        public String formatString = "";
    }
}

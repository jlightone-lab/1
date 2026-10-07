package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class TabDef {
    public long property = 0;
    public List<TabItem> items = new ArrayList<>();

    public static class TabItem {
        public int position;
        public int type;
        public int fillType;
        public int padding; // 정렬용 패딩
    }
}

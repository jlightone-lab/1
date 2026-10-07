package kr.n.nframe.hwplib.model;

import java.util.ArrayList;
import java.util.List;

public class Paragraph {
    public long nChars = 0;
    public long controlMask = 0;
    public int paraShapeId = 0;
    public int styleId = 0;
    public int columnBreakType = 0;
    public long instanceId = 0;
    public List<CharShapeRef> charShapeRefs = new ArrayList<>();
    public List<LineSeg> lineSegs = new ArrayList<>();
    public List<ParaRangeTag> rangeTags = new ArrayList<>();
    public ParaText paraText = null;
    public List<Control> controls = new ArrayList<>();
}

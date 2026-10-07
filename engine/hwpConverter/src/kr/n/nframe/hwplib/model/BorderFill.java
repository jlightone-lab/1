package kr.n.nframe.hwplib.model;

public class BorderFill {
    public int property = 0;
    public int[] borderTypes = new int[4];
    public int[] borderWidths = new int[4];
    public long[] borderColors = new long[4];
    public int diagType = 0;
    public int diagWidth = 0;
    public long diagColor = 0;
    public int fillType = 0;
    public long fillBgColor = 0xFFFFFFFFL;
    public long fillPatColor = 0;
    public int fillPatType = 0;
    public int gradType = 0;
    public int gradAngle = 0;
    public int gradCenterX = 0;
    public int gradCenterY = 0;
    public int gradStep = 50;       // HWPX "step" 속성 (예: 255)
    public int gradStepCenter = 50; // HWPX "stepCenter" 속성 (예: 50)
    public int gradColorNum = 2;
    public long[] gradColors;
    public int imgType = 0;
    public int imgBright = 0;
    public int imgContrast = 0;
    public int imgEffect = 0;
    public int imgBinItemId = 0;
}

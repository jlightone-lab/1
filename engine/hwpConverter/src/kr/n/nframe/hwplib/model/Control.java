package kr.n.nframe.hwplib.model;

public class Control {
    public int ctrlId;
    /** 원시 속성 데이터 바이트 (pgnp, atno, pghd 등 일반 컨트롤용). */
    public byte[] rawData;

    public Control(int ctrlId) {
        this.ctrlId = ctrlId;
    }
}

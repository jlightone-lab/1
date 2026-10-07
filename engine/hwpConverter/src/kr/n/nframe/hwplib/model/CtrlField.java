package kr.n.nframe.hwplib.model;

public class CtrlField extends Control {
    public long fieldProperty = 0;
    public int extraProperty = 0;
    public String command = "";
    public long fieldId = 0;
    /**
     * 책갈피 / 이름 있는 필드의 이름. hp:fieldBegin @name에서 채워진다.
     * BOOKMARK 필드의 경우 이 값이 책갈피 이름을 담는 HWPTAG_CTRL_DATA 레코드를
     * 동반 생성시킨다(한글 프로그램에서 "?section_A" 같은 내부 하이퍼링크가 정확한
     * 앵커로 해석되기 위해 필요).
     */
    public String name = "";

    public CtrlField(int ctrlId) {
        super(ctrlId);
    }
}

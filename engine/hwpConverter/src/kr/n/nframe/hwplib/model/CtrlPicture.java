package kr.n.nframe.hwplib.model;

import kr.n.nframe.hwplib.constants.CtrlId;

import java.util.ArrayList;
import java.util.List;

public class CtrlPicture extends Control {
    // shape 종류: "pic"=그림, "rect"=사각형, "line", "ellipse" 등
    public String shapeType = "pic";
    // 공통 개체 속성
    public long objProperty;
    public int vertOffset;
    public int horzOffset;
    public int width;
    public int height;
    public int zOrder;
    public int[] margins = new int[4];
    public long instanceId;
    public int pageBreakPrev;
    public String description = "";

    // 그림 전용 속성
    public int borderColor = 0;
    public int borderThickness = 0;
    public long borderProperty = 0;
    public int[] imageRectX = new int[4];
    public int[] imageRectY = new int[4];
    public int cropLeft;
    public int cropTop;
    public int cropRight;
    public int cropBottom;
    public int imgPadLeft;
    public int imgPadRight;
    public int imgPadTop;
    public int imgPadBottom;
    public int brightness;
    public int contrast;
    public int effect;
    public int binItemId;
    public int transparency;
    public long pictureInstanceId;

    // 원본 개체 크기 (hp:orgSz 출처) — SHAPE_COMPONENT initW/H 에 사용
    public int originalWidth;
    public int originalHeight;

    // 원본 이미지 픽셀 크기 (hp:imgDim 출처) — SHAPE_COMPONENT_PICTURE originalW/H 에 사용
    public int imgDimWidth;
    public int imgDimHeight;

    // hp:renderingInfo의 렌더링 행렬 (각 6개 double: e1..e6)
    public double[] transMatrix;  // 평행이동: [e1, e2, e3(tx), e4, e5, e6(ty)]
    public double[] scaMatrix;    // 확대/축소: [e1(sx), e2, e3(tx), e4, e5(sy), e6(ty)]
    public double[] rotMatrix;    // 회전: [e1, e2, e3, e4, e5, e6]

    // 수식 (shapeType=="equation") — HWPTAG_EQEDIT 페이로드.
    // Spec: HWP 5.0 §4.3.10.3 수식 (formula_extracted.txt 참조)
    public String eqScript = "";      // 수식 스크립트 텍스트
    public String eqVersion = "";     // 예: "Equation Version 60"
    public String eqFont = "";        // 예: "HancomEQN"
    public int    eqBaseUnit = 1000;  // HWPUNIT
    public int    eqBaseLine = 86;    // 백분율(%)
    public long   eqProperty = 0;

    // 그리기 개체용 텍스트박스(drawText) 속성
    public List<Paragraph> textboxParagraphs = new ArrayList<>();
    public int textboxMarginLeft;
    public int textboxMarginRight;
    public int textboxMarginTop;
    public int textboxMarginBottom;
    public int textboxLastWidth;
    public long textboxListProperty;  // bit 0-2=textDir, bit 3-4=lineWrap, bit 5-6=vertAlign (v15.8 수정: 셀과 동일 layout)

    public CtrlPicture() {
        super(CtrlId.GSO);
    }
}

package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.bodytext.Section;
import kr.dogfoot.hwplib.object.bodytext.paragraph.Paragraph;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.tool.blankfilemaker.EmptyParagraphAdder;
import kr.dogfoot.hwplib.tool.paragraphadder.ParaTextSetter;
import kr.dogfoot.hwplib.writer.HWPWriter;

/**
 * v14.6 PoC: hwplib BlankFileMaker + ParaTextSetter + HWPWriter 로
 * Hangul-호환 가능한 .hwp 직접 생성 검증.
 */
public class HwplibBlankPoc {
    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0]
                : "build/v146/poc/blank_test.hwp";

        // 1) blank HWPFile (hwplib 표준 defaults)
        HWPFile hwp = BlankFileMaker.make();
        Section sec = hwp.getBodyText().getLastSection();

        // 2) 첫 번째 paragraph (SectionDefine extend char 보유) 는 그대로 두고
        //    새 paragraph 들을 그 뒤에 추가한다.
        Paragraph first = sec.getParagraph(0);
        System.out.println("first para chars: " + first.getHeader().getCharacterCount());

        String[] contents = {
                "v14.6 PoC: hwplib 직접 생성 테스트입니다.",
                "한글 두번째 문단",
                "세번째 문단의 내용",
                "마지막 문단입니다."
        };
        for (String s : contents) {
            EmptyParagraphAdder.add(sec);
            Paragraph p = sec.getLastParagraph();
            ParaTextSetter.changeText(p, 0, 0, s);
        }

        // 4) 저장
        HWPWriter.toFile(hwp, out);
        System.out.println("PoC saved: " + out);

        // 5) 다시 읽어보기 (hwplib round-trip)
        HWPFile re = HWPReader.fromFile(out);
        System.out.println("re-read sections: " + re.getBodyText().getSectionList().size());
        System.out.println("re-read paragraphs: "
                + re.getBodyText().getLastSection().getParagraphCount());

        // 6) hwp2hwpx 검증 - 실패해도 무시 (hwp2hwpx 라이브러리 한계로
        //    blank 첫 paragraph 변환을 못함. Hangul 호환과는 별개임)
        try {
            kr.dogfoot.hwpxlib.object.HWPXFile probe = kr.dogfoot.hwp2hwpx.Hwp2Hwpx.toHWPX(re);
            System.out.println("hwp2hwpx probe: " + (probe == null ? "null" : "OK"));
        } catch (Throwable t) {
            System.out.println("hwp2hwpx 한계 (무시): " + t.getClass().getSimpleName()
                    + " - " + t.getMessage());
        }

        // 7) 모든 paragraph 의 text 가 정상 출력되는지 확인
        for (int i = 0; i < re.getBodyText().getLastSection().getParagraphCount(); i++) {
            Paragraph pp = re.getBodyText().getLastSection().getParagraph(i);
            String s;
            try { s = pp.getNormalString(); } catch (Exception e) { s = "<err " + e.getMessage() + ">"; }
            System.out.println("para[" + i + "] chars=" + pp.getHeader().getCharacterCount()
                    + " text='" + (s.length() > 60 ? s.substring(0, 60) + "..." : s) + "'");
        }
    }
}

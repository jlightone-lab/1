import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;

/** 자동 점검용 샘플 HWPX 생성: MakeSample <output.hwpx> */
public class MakeSample {
    public static void main(String[] args) throws Exception {
        HWPXFile file = BlankFileMaker.make();
        SectionXMLFile section = file.sectionXMLFileList().get(0);
        String[] lines = {
                "변환 시험 문서",
                "이 문서는 HWPX → HWP 변환기의 자동 점검용 샘플입니다.",
                "한글 텍스트, English text, 숫자 12,345 와 기호 ※ ① ★ 를 포함합니다.",
                "마지막 문단입니다.",
        };
        int id = 1000;
        for (String line : lines) {
            Para para = section.addNewPara();
            para.idAnd(String.valueOf(id++)).paraPrIDRefAnd("0").styleIDRefAnd("0");
            para.addNewRun().charPrIDRefAnd("0").addNewT().addText(line);
        }
        HWPXWriter.toFilepath(file, args[0]);
    }
}

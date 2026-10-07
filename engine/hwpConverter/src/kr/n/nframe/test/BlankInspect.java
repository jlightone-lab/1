package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwplib.tool.blankfilemaker.EmptyParagraphAdder;
import kr.dogfoot.hwplib.tool.paragraphadder.ParaTextSetter;
import kr.dogfoot.hwplib.writer.HWPWriter;

public class BlankInspect {
    public static void main(String[] args) throws Exception {
        // 1. blank only (no modifications)
        HWPFile h1 = BlankFileMaker.make();
        HWPWriter.toFile(h1, "build/v146/poc/blank_only.hwp");

        // 2. blank + 1 empty paragraph
        HWPFile h2 = BlankFileMaker.make();
        EmptyParagraphAdder.add(h2.getBodyText().getLastSection());
        HWPWriter.toFile(h2, "build/v146/poc/blank_plus1.hwp");

        // 3. blank + 1 paragraph with text
        HWPFile h3 = BlankFileMaker.make();
        EmptyParagraphAdder.add(h3.getBodyText().getLastSection());
        ParaTextSetter.changeText(h3.getBodyText().getLastSection().getLastParagraph(), 0, 0, "안녕");
        HWPWriter.toFile(h3, "build/v146/poc/blank_plus1text.hwp");

        System.out.println("done.");
    }
}

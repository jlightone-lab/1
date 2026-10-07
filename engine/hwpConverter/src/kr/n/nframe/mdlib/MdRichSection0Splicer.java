package kr.n.nframe.mdlib;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.writer.HWPWriter;

import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import kr.n.nframe.mdlib.MdRichParser.ImageEmbed;

import static kr.n.nframe.mdlib.MdRichTemplateMutator.loadMutatedTemplateBytes;
import static kr.n.nframe.mdlib.MdRichTemplateMutator.readEntryBytes;

/**
 * MdToHwpRich에서 분리된 Section0 splice + 최종 HWP 파일 출력 (v14.95).
 *
 * <h3>책임</h3>
 * <ol>
 *   <li>hwplib {@link HWPWriter#toFile}로 임시 .hwp 작성</li>
 *   <li>POI로 임시 파일에서 {@code BodyText/Section0} stream 추출</li>
 *   <li>{@link MdRichTemplateMutator#loadMutatedTemplateBytes}로 template.hwp base 획득</li>
 *   <li>template POIFS에서 기존 Section0 삭제 후 우리 Section0 stream 으로 재생성</li>
 *   <li>잔여 Section1, Section2... 삭제 (생성 컨텐츠가 단일 섹션이므로)</li>
 *   <li>최종 OLE2 출력 파일 작성</li>
 * </ol>
 *
 * <p>v14.16: 한/글 호환 우선. template.hwp 의 byte 100% 보존하고 Section0 만 교체하는
 * 것이 한/글 검증에 통과하는 유일한 안정 경로 (hwplib 의 round-trip 한계 우회).
 *
 * <p>stateless (static). v14.x 마커는 원본 그대로 보존.
 */
public final class MdRichSection0Splicer {
    private MdRichSection0Splicer() {}

    /**
     * hwplib {@link HWPFile} 의 Section0 를 mutated template.hwp 에 splice 하고
     * 최종 .hwp 를 작성한다. 임시 파일은 finally 에서 삭제.
     *
     * @param hwp          BlankFileMaker + 우리 paragraph emission 으로 채워진 HWPFile
     * @param imageEmbeds  base64 이미지 임베드 (없으면 빈 리스트 가능,
     *                     {@code loadMutatedTemplateBytes} 가 case1.hwp vs template.hwp 분기)
     * @param filePathHwp  출력 경로
     */
    public static void spliceAndSave(HWPFile hwp, List<ImageEmbed> imageEmbeds,
                                     String filePathHwp) throws Exception {
        // 2) hwplib HWPWriter 로 임시 .hwp 작성 → Section0 바이트 추출
        Path tmpHwp = Files.createTempFile("hwp-rich-section-", ".hwp");
        try {
            HWPWriter.toFile(hwp, tmpHwp.toString());
            byte[] section0Bytes;
            try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(tmpHwp.toFile()))) {
                DirectoryEntry bodyText = (DirectoryEntry) poi.getRoot().getEntry("BodyText");
                section0Bytes = readEntryBytes(bodyText, "Section0");
            }
            System.out.println("[MdToHwpRich] Section0 압축 바이트: " + section0Bytes.length);

            // 3) v14.16: 한/글 호환 우선. case1_8p.hwp (template.hwp, 검증된 v14.9 템플릿) 사용.
            //    template.hwp 에 본디 bold-property 가 set 된 charShape 가 없으므로,
            //    HWPReader 로 한 번 열어 CS_BOLD (=16) 의 isBold 비트만 켜고 다시
            //    HWPWriter 로 직렬화하여 splice 의 base 로 사용한다. (이렇게 해도
            //    Hangul 호환은 유지된다 — 라이브러리가 정상적으로 round-trip 가능한
            //    파일이고, v14.9 검증된 템플릿이므로 의미 있는 binary diff 는 없다.)
            byte[] templateBytes = loadMutatedTemplateBytes(imageEmbeds);
            System.out.println("[MdToHwpRich] mutated template.hwp 로드 (CS_BOLD bold-bit set): "
                    + templateBytes.length + " bytes");

            Path outPath = Paths.get(filePathHwp).toAbsolutePath();
            Files.createDirectories(outPath.getParent());
            try (POIFSFileSystem poi = new POIFSFileSystem(new ByteArrayInputStream(templateBytes))) {
                DirectoryEntry bodyText = (DirectoryEntry) poi.getRoot().getEntry("BodyText");
                // Section0 삭제 후 재생성
                for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                    if (e.getName().equals("Section0")) { e.delete(); break; }
                }
                bodyText.createDocument("Section0", new ByteArrayInputStream(section0Bytes));
                // Section1, Section2... 등 잔여 섹션 삭제 (생성 컨텐츠가 단일 섹션이므로)
                List<String> toDelete = new ArrayList<>();
                for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                    if (e.getName().startsWith("Section") && !e.getName().equals("Section0")) {
                        toDelete.add(e.getName());
                    }
                }
                for (String n : toDelete) {
                    for (org.apache.poi.poifs.filesystem.Entry e : bodyText) {
                        if (e.getName().equals(n)) { e.delete(); break; }
                    }
                }
                try (FileOutputStream fos = new FileOutputStream(outPath.toFile())) {
                    poi.writeFilesystem(fos);
                }
            }
            long finalSize = new java.io.File(outPath.toString()).length();
            System.out.println("[MdToHwpRich] 최종 .hwp 저장 완료: " + outPath + " (" + finalSize + " bytes)");
        } finally {
            try { Files.deleteIfExists(tmpHwp); } catch (Exception ignored) {}
        }
    }
}

package kr.n.nframe.test;

import org.apache.poi.poifs.filesystem.*;
import java.io.*;
import java.nio.file.*;

/**
 * 한/글 .hwp 의 OLE2 스트림 중 한/글이 요구하는 메타 스트림을 파일로 추출.
 * 추출 대상: HwpSummaryInformation, PrvText, _LinkDoc, Scripts/*
 * (PrvImage 는 크기가 커서 별도 옵션으로 추출)
 */
public class StreamExtract {
    public static void main(String[] args) throws Exception {
        String src = args[0];
        String outDir = args[1];
        boolean withPrvImage = args.length > 2 && "--with-prvimg".equals(args[2]);
        Files.createDirectories(Paths.get(outDir));
        try (POIFSFileSystem poi = new POIFSFileSystem(new FileInputStream(src))) {
            DirectoryEntry root = poi.getRoot();
            extract(root, "HwpSummaryInformation", outDir + "/HwpSummaryInformation.bin");
            // OLE2 PropertySet 스트림은 0x05 prefix 가 붙음
            extract(root, "HwpSummaryInformation", outDir + "/HwpSummaryInformation_05.bin");
            // 모든 엔트리 raw name 출력 (디버깅)
            for (Entry e : root) {
                String n = e.getName();
                StringBuilder hex = new StringBuilder();
                for (int i = 0; i < n.length(); i++) hex.append(String.format("%02X ", (int)n.charAt(i) & 0xFF));
                System.out.println("[entry] '" + n + "' hex=[" + hex.toString().trim() + "]");
            }
            extract(root, "PrvText", outDir + "/PrvText.bin");
            if (withPrvImage) extract(root, "PrvImage", outDir + "/PrvImage.bin");
            // _LinkDoc 는 DocOptions 하위
            try {
                DirectoryEntry docOpt = (DirectoryEntry) root.getEntry("DocOptions");
                extractFrom(docOpt, "_LinkDoc", outDir + "/DocOptions__LinkDoc.bin");
            } catch (Exception e) { System.out.println("[skip] DocOptions/_LinkDoc: " + e.getMessage()); }
            // Scripts
            try {
                DirectoryEntry sc = (DirectoryEntry) root.getEntry("Scripts");
                extractFrom(sc, "JScriptVersion", outDir + "/Scripts__JScriptVersion.bin");
                extractFrom(sc, "DefaultJScript", outDir + "/Scripts__DefaultJScript.bin");
            } catch (Exception e) { System.out.println("[skip] Scripts: " + e.getMessage()); }
        }
        System.out.println("[ok] stream extract done -> " + outDir);
    }

    static void extract(DirectoryEntry dir, String name, String out) {
        try { extractFrom(dir, name, out); }
        catch (Exception e) { System.out.println("[skip] " + name + ": " + e.getMessage()); }
    }
    static void extractFrom(DirectoryEntry dir, String name, String out) throws Exception {
        DocumentEntry de = (DocumentEntry) dir.getEntry(name);
        byte[] data = new byte[de.getSize()];
        try (InputStream is = new DocumentInputStream(de)) {
            int t = 0; while (t < data.length) { int r = is.read(data, t, data.length - t); if (r < 0) break; t += r; }
        }
        Files.write(Paths.get(out), data);
        System.out.println("  " + name + " -> " + out + " (" + data.length + " bytes)");
    }
}

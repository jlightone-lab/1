package kr.n.nframe.test;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.reader.HWPReader;
import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwp2hwpx.Hwp2Hwpx;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * 한/글 .hwp 를 .hwpx 로 변환하고 zip 내부 XML 파일을 풀어서 보여줌.
 */
public class HwpToHwpxDump {
    public static void main(String[] args) throws Exception {
        String hwp = args.length > 0 ? args[0] : "hwp/0427/dummy_file/01.원본hwp/case1_8p.hwp";
        String outDir = args.length > 1 ? args[1] : "build/v1412/dump";
        Files.createDirectories(Paths.get(outDir));

        HWPFile h = HWPReader.fromFile(hwp);
        HWPXFile hx = Hwp2Hwpx.toHWPX(h);
        Path tmpHwpx = Paths.get(outDir + "/dump.hwpx");
        HWPXWriter.toFilepath(hx, tmpHwpx.toString());
        System.out.println("[ok] HWPX 생성: " + tmpHwpx + " (" + Files.size(tmpHwpx) + " bytes)");

        try (ZipFile zf = new ZipFile(tmpHwpx.toFile())) {
            java.util.Enumeration<? extends ZipEntry> en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                Path out = Paths.get(outDir, e.getName());
                Files.createDirectories(out.getParent() == null ? Paths.get(outDir) : out.getParent());
                try (InputStream is = zf.getInputStream(e); OutputStream os = Files.newOutputStream(out)) {
                    byte[] buf = new byte[8192]; int r;
                    while ((r = is.read(buf)) >= 0) os.write(buf, 0, r);
                }
                System.out.println("  " + e.getName() + " (" + e.getSize() + " bytes)");
            }
        }
    }
}

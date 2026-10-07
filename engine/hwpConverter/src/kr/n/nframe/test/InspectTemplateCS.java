package kr.n.nframe.test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import kr.dogfoot.hwplib.object.HWPFile;
import kr.dogfoot.hwplib.object.docinfo.CharShape;
import kr.dogfoot.hwplib.object.docinfo.DocInfo;
import kr.dogfoot.hwplib.reader.HWPReader;

/**
 * Dump every CharShape in a HWP DocInfo (template.hwp by default), so we can
 * pick template-resident charShape IDs for headings and bold text without
 * polluting our DocInfo with new shapes that get clobbered after the
 * Section0 splice into template.hwp.
 */
public class InspectTemplateCS {
    public static void main(String[] args) throws Exception {
        String resourcePath = args.length > 0
                ? args[0]
                : "/kr/n/nframe/resources/hwp-streams/template.hwp";

        byte[] bytes;
        try (InputStream in = InspectTemplateCS.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("Resource missing: " + resourcePath);
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) >= 0) baos.write(buf, 0, n);
            bytes = baos.toByteArray();
        }
        Path tmp = Files.createTempFile("inspect-cs-", ".hwp");
        Files.write(tmp, bytes);
        try {
            HWPFile hwp = HWPReader.fromFile(tmp.toString());
            DocInfo di = hwp.getDocInfo();
            int count = di.getCharShapeList().size();
            System.out.println("[InspectTemplateCS] charShape count = " + count);

            List<int[]> sizeIds = new ArrayList<>();
            List<Integer> boldIds = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                CharShape cs = di.getCharShapeList().get(i);
                long propValue = cs.getProperty().getValue() & 0xFFFFFFFFL;
                boolean bold = (propValue & 0x01L) != 0L;
                boolean italic = (propValue & 0x02L) != 0L;
                boolean underline = (propValue & 0x04L) != 0L;
                int base = cs.getBaseSize();
                long colorValue = cs.getCharColor().getValue() & 0xFFFFFFFFL;
                System.out.printf(
                        "CS[%2d] baseSize=%5d  prop=0x%08x  bold=%-5s italic=%-5s underline=%-5s color=0x%08x%n",
                        i, base, propValue, bold, italic, underline, colorValue);
                sizeIds.add(new int[]{base, i});
                if (bold) boldIds.add(i);
            }

            // Pick H1..H6 candidates: descending baseSize (>= 1100), prefer non-bold
            // when ties so the heading shape stays clean and bold can be layered
            // separately. (We just need 6 distinct, monotonically decreasing
            // sizes; if fewer exist, we'll repeat the smallest.)
            List<int[]> sorted = new ArrayList<>(sizeIds);
            // Sort by baseSize desc; tie-break: smaller index first (more stable)
            Collections.sort(sorted, (a, b) -> {
                if (a[0] != b[0]) return Integer.compare(b[0], a[0]);
                return Integer.compare(a[1], b[1]);
            });
            System.out.println();
            System.out.println("[InspectTemplateCS] Top distinct sizes (baseSize, id):");
            List<int[]> distinct = new ArrayList<>();
            int last = Integer.MIN_VALUE;
            for (int[] e : sorted) {
                if (e[0] != last) {
                    distinct.add(e);
                    last = e[0];
                }
            }
            for (int i = 0; i < Math.min(distinct.size(), 12); i++) {
                int[] e = distinct.get(i);
                System.out.printf("  rank %2d: baseSize=%5d -> id=%d%n", i, e[0], e[1]);
            }

            // Suggest H1..H6 picks (top 6 distinct sizes)
            System.out.println();
            System.out.println("[InspectTemplateCS] Suggested heading IDs (H1..H6):");
            int[] picks = new int[6];
            for (int i = 0; i < 6; i++) {
                int[] e = distinct.get(Math.min(i, distinct.size() - 1));
                picks[i] = e[1];
                System.out.printf("  H%d -> CS[%d] baseSize=%d%n", i + 1, e[1], e[0]);
            }
            StringBuilder picksLine = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) picksLine.append(", ");
                picksLine.append(picks[i]);
            }
            System.out.println("  CS_HEADINGS = { " + picksLine + " };");

            // Bold pick: prefer the first bold-property charShape with a normal
            // ish baseSize (close to 1000pt) so it doesn't double as a heading.
            int boldPick = -1;
            int targetSize = 1000;
            for (int id : boldIds) {
                CharShape cs = di.getCharShapeList().get(id);
                if (boldPick < 0
                        || Math.abs(cs.getBaseSize() - targetSize) < Math.abs(
                                di.getCharShapeList().get(boldPick).getBaseSize() - targetSize)) {
                    boldPick = id;
                }
            }
            System.out.println();
            System.out.println("[InspectTemplateCS] Bold candidates: " + boldIds);
            if (boldPick >= 0) {
                CharShape cs = di.getCharShapeList().get(boldPick);
                System.out.println("[InspectTemplateCS] Suggested CS_BOLD = "
                        + boldPick + " (baseSize=" + cs.getBaseSize() + ")");
            } else {
                System.out.println(
                        "[InspectTemplateCS] No bold-property charShape in template.");
            }
        } finally {
            try {
                Files.deleteIfExists(tmp);
            } catch (Exception ignored) {
            }
        }
    }
}

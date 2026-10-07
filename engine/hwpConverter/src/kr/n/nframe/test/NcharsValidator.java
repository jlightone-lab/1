package kr.n.nframe.test;
import org.apache.poi.poifs.filesystem.*;
import java.io.*;

/** PARA_HEADER의 nChars가 PARA_TEXT size와 일치하는지 검증한다 */
public class NcharsValidator {
    public static void main(String[] args) throws Exception {
        POIFSFileSystem fs = new POIFSFileSystem(new FileInputStream(args[0]));
        DirectoryEntry bt = (DirectoryEntry) fs.getRoot().getEntry("BodyText");
        byte[] raw = StreamExtractor.readStream(bt, "Section0");
        byte[] data = StreamExtractor.decompress(raw);

        int pos = 0, recIdx = 0, errors = 0;
        int lastNchars = -1, lastParaIdx = -1;
        boolean expectText = false;

        while (pos + 4 <= data.length) {
            int header = le32(data, pos);
            int tagId = header & 0x3FF;
            int level = (header >> 10) & 0x3FF;
            int size = (header >> 20) & 0xFFF;
            pos += 4;
            if (size == 0xFFF) { size = le32(data, pos); pos += 4; }
            if (pos + size > data.length) {
                System.out.printf("ERROR: Record #%d truncated at pos %d%n", recIdx, pos);
                errors++;
                break;
            }

            if (tagId == 0x042) { // PARA_HEADER
                long nChars = le32(data, pos) & 0x7FFFFFFFL;
                lastNchars = (int) nChars;
                lastParaIdx = recIdx;
                expectText = nChars > 1;
            } else if (tagId == 0x043 && lastParaIdx == recIdx - 1) { // PARA_HEADER 바로 뒤의 PARA_TEXT
                int expectedBytes = lastNchars * 2;
                if (size != expectedBytes) {
                    System.out.printf("MISMATCH: Record #%d PARA_TEXT size=%d but nChars=%d (expected %d bytes) at offset %d%n",
                            recIdx, size, lastNchars, expectedBytes, pos);
                    errors++;
                }
            }

            pos += size;
            recIdx++;
        }
        System.out.printf("Total records: %d, errors: %d%n", recIdx, errors);
        fs.close();
    }
    static int le32(byte[] d, int o) { return (d[o]&0xFF)|((d[o+1]&0xFF)<<8)|((d[o+2]&0xFF)<<16)|((d[o+3]&0xFF)<<24); }
}

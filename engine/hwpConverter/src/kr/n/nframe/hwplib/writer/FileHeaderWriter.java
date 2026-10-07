package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.model.HwpDocument;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Builds the 256-byte FileHeader stream for an HWP 5.0 file.
 */
public class FileHeaderWriter {

    private FileHeaderWriter() {}

    /**
     * Build the FileHeader stream bytes.
     *
     * @param doc the HWP document (currently unused; version/properties are fixed)
     * @return 256 bytes representing the FileHeader
     */
    public static byte[] build(HwpDocument doc) {
        byte[] header = new byte[256];
        ByteBuffer buf = ByteBuffer.wrap(header);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        // Bytes 0-31: signature "HWP Document File" + null padding to 32 bytes
        byte[] sig = "HWP Document File".getBytes(StandardCharsets.US_ASCII);
        buf.put(sig);
        buf.position(32);

        // Bytes 32-35: version UINT32 LE = 5.1.1.0 = 0x05010100
        buf.putInt(0x05010100);

        // Bytes 36-39: properties UINT32 LE = 0x00000001 (compressed)
        buf.putInt(0x00000001);

        // Bytes 40-43: license properties (0)
        buf.putInt(0x00000000);

        // Bytes 44-47: encrypt version = 4 (HWP 7.0+ method)
        buf.putInt(0x00000004);

        // Bytes 48-255: remain zero (already zeroed by default)

        return header;
    }
}

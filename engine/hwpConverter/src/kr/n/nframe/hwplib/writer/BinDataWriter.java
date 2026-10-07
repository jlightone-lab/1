package kr.n.nframe.hwplib.writer;

import kr.n.nframe.hwplib.binary.ZlibCompressor;
import kr.n.nframe.hwplib.model.BinDataItem;
import kr.n.nframe.hwplib.model.HwpDocument;
import org.apache.poi.poifs.filesystem.DirectoryEntry;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Writes embedded binary data entries (images, OLE objects) into the
 * BinData storage directory of an HWP OLE2 compound file.
 *
 * When the FileHeader compressed flag is set, BinData items with
 * compress=default are zlib-compressed before storage.
 */
public class BinDataWriter {

    private BinDataWriter() {}

    /**
     * Write all embedded binary data items to the given OLE2 directory.
     * Data is compressed with zlib raw deflate (matching DocInfo/Section compression).
     */
    public static void write(DirectoryEntry binDataDir, HwpDocument doc) throws IOException {
        for (BinDataItem item : doc.binDataItems) {
            if (item.data != null && item.data.length > 0) {
                String streamName = String.format("BIN%04X.%s", item.binDataId, item.extension);
                // Compress BinData with zlib (same as DocInfo/Section0)
                // FileHeader compressed=true means all default-compress BinData should be compressed
                byte[] compressed = ZlibCompressor.compress(item.data);
                binDataDir.createDocument(streamName, new ByteArrayInputStream(compressed));
            }
        }
    }
}

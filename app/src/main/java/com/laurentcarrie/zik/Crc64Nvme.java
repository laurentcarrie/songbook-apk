package com.laurentcarrie.zik;

import java.io.InputStream;
import java.util.Base64;

/** CRC-64/NVME, the checksum S3 stores with uploads: reflected, init and final xor all ones. */
final class Crc64Nvme {
    private static final long POLY = 0x9A6C9329AC4BC9B5L; // 0xAD93D23594C93659, reflected
    private static final long[] TABLE = new long[256];

    static {
        for (int i = 0; i < 256; i++) {
            long c = i;
            for (int k = 0; k < 8; k++) c = (c & 1) != 0 ? (c >>> 1) ^ POLY : c >>> 1;
            TABLE[i] = c;
        }
    }

    private long crc = -1L;

    void update(byte[] b, int off, int len) {
        long c = crc;
        for (int i = off; i < off + len; i++) c = TABLE[(int) ((c ^ b[i]) & 0xFF)] ^ (c >>> 8);
        crc = c;
    }

    /** Base64 of the 8 big-endian bytes, the format S3 uses. */
    String base64() {
        long v = ~crc;
        byte[] out = new byte[8];
        for (int i = 7; i >= 0; i--) { out[i] = (byte) v; v >>>= 8; }
        return Base64.getEncoder().encodeToString(out);
    }

    static String of(InputStream in) throws java.io.IOException {
        Crc64Nvme c = new Crc64Nvme();
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) c.update(buf, 0, n);
        return c.base64();
    }
}

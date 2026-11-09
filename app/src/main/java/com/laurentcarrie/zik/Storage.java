package com.laurentcarrie.zik;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Location of the delivery on the tablet, and the storage permission it needs. */
final class Storage {
    static final File DELIVERY = new File("/sdcard/Download/songbook/delivery");
    static final File WORLD = new File(DELIVERY, "world.yml");


    /** Returned by syncArchive when the archive has not changed since the last sync. */
    static final int UNCHANGED = -1;
    static final String ARCHIVE_URL = "https://move-the-line.org/api/delivery-archive/";

    private Storage() { }

    /** What the server says about an archive: presigned link, ETag and checksum. */
    static final class ArchiveInfo {
        String url, etag, crc;
    }

    /** Asks the server about an archive (one small request, nothing is downloaded). */
    static ArchiveInfo archiveInfo(String name) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(ARCHIVE_URL + name).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            StringBuilder sb = new StringBuilder();
            try (InputStream in = c.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
            }
            JSONObject j = new JSONObject(sb.toString());
            ArchiveInfo info = new ArchiveInfo();
            info.url = j.getString("url");
            info.etag = j.optString("etag", null);
            info.crc = j.optString("checksum_crc64nvme", null);
            return info;
        } finally {
            c.disconnect();
        }
    }

    /** True when the archive is unchanged since the last sync and its files are still there. */
    static boolean isUpToDate(String name, SharedPreferences prefs, ArchiveInfo info) {
        String[] there = new File(DELIVERY, name).list();
        return info.etag != null && info.etag.equals(prefs.getString("etag_" + name, null))
                && there != null && there.length > 0;
    }

    /** Fetches the presigned link of the pdf tarball, downloads it from S3 and unpacks it. Returns files written. */
    static int syncArchive(String name, SharedPreferences prefs, boolean force, Progress p) throws Exception {
        p.update("Recherche du lien…", -1);
        ArchiveInfo info = archiveInfo(name);
        String url = info.url, etag = info.etag, crc = info.crc;
        String key = "etag_" + name;
        if (!force && isUpToDate(name, prefs, info)) return UNCHANGED;
        File tgz = new File(DELIVERY.getParentFile(), "delivery-" + name + ".tar.gz");
        download(url, tgz, p, "Téléchargement " + name);
        if (crc != null && !crc.isEmpty()) {
            p.update("Vérification…", -1);
            String got;
            try (InputStream in = new java.io.FileInputStream(tgz)) { got = Crc64Nvme.of(in); }
            if (!crc.equals(got)) {
                tgz.delete();
                throw new Exception("archive " + name + " corrompue (checksum différent), réessayez");
            }
        }
        p.update("Décompression…", -1);
        try {
            int n = untar(tgz, DELIVERY.getParentFile());
            if (etag != null) prefs.edit().putString(key, etag).apply();
            return n;
        } finally {
            tgz.delete();
        }
    }

    /** Minimal tar.gz extraction (regular files only); entries stay under destDir. */
    static int untar(File tgz, File destDir) throws Exception {
        int count = 0;
        String longName = null;
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new java.io.FileInputStream(tgz)))) {
            byte[] h = new byte[512];
            while (true) {
                in.readFully(h);
                if (h[0] == 0) break;
                String name = field(h, 0, 100);
                String prefix = field(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
                long size = Long.parseLong(field(h, 124, 12).trim().isEmpty() ? "0" : field(h, 124, 12).trim(), 8);
                char type = (char) h[156];
                long padded = (size + 511) / 512 * 512;
                if (type == 'L') {
                    byte[] b = new byte[(int) padded];
                    in.readFully(b);
                    longName = new String(b, 0, (int) size, "UTF-8").replace("\0", "");
                    continue;
                }
                if (longName != null) { name = longName; longName = null; }
                File out = new File(destDir, name);
                boolean safe = out.getCanonicalPath().startsWith(destDir.getCanonicalPath() + File.separator);
                if ((type == '0' || type == 0) && safe) {
                    out.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new java.io.EOFException();
                            os.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    in.skipBytes((int) (padded - size));
                    count++;
                } else {
                    in.skipBytes((int) padded);
                }
            }
        }
        return count;
    }

    private static String field(byte[] h, int off, int len) {
        int end = off;
        while (end < off + len && h[end] != 0) end++;
        return new String(h, off, end - off, java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Downloads url to dest (via a temp file, so a failed download keeps the old file). */
    interface Progress { void update(String text, int percent); }

    static void download(String url, File dest) throws Exception {
        download(url, dest, null, "");
    }

    /** Returns the ETag the server sent, if any. */
    static String download(String url, File dest, Progress p, String label) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(20000);
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            dest.getParentFile().mkdirs();
            long total = c.getContentLengthLong();
            long done = 0;
            File tmp = new File(dest.getPath() + ".tmp");
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    done += n;
                    if (p != null) p.update(label + " " + (done >> 20) + (total > 0 ? " / " + (total >> 20) : "") + " Mo",
                            total > 0 ? (int) (done * 100 / total) : -1);
                }
            }
            if (!tmp.renameTo(dest)) throw new Exception("rename failed");
            return c.getHeaderField("ETag");
        } finally {
            c.disconnect();
        }
    }

    /** True when files can be read; otherwise opens the settings page to grant it. */
    static boolean ensureAccess(Activity a) {
        if (Environment.isExternalStorageManager()) return true;
        Toast.makeText(a, "Autorisez « Accès à tous les fichiers », puis revenez", Toast.LENGTH_LONG).show();
        a.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:" + a.getPackageName())));
        return false;
    }
}

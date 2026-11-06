package com.laurentcarrie.zik;

import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Where each bar of a song is on its PDF: delivery/pdfcellpositions/&lt;pdf stem&gt;.yml,
 * written by the generator. Positions are in PDF points from the top-left of the page.
 */
final class BarCells {
    /** Size of the page the positions refer to. */
    final float pageW, pageH;
    /** Number of the last bar; bars are 1..count. */
    final int count;
    /** Per bar (index bar-1): 1-based page, or 0 when the bar has no position. */
    final int[] page;
    final float[] x, y, w, h;
    /** Per bar: the title of its section ("" when unknown). */
    final String[] section;

    private BarCells(float pageW, float pageH, int count) {
        this.pageW = pageW;
        this.pageH = pageH;
        this.count = count;
        page = new int[count];
        x = new float[count];
        y = new float[count];
        w = new float[count];
        h = new float[count];
        section = new String[count];
        java.util.Arrays.fill(section, "");
    }

    /** The cells of this song's PDF, or null when there are none (not delivered, or no chord chart). */
    static BarCells load(File pdf) {
        String name = pdf.getName();
        if (name.endsWith(".pdf")) name = name.substring(0, name.length() - 4);
        File f = new File(Storage.DELIVERY, "pdfcellpositions/" + name + ".yml");
        if (!f.isFile()) return null;
        try (InputStream in = new FileInputStream(f)) {
            Object root = new Yaml().load(in);
            if (!(root instanceof Map)) return null;
            Map<?, ?> m = (Map<?, ?>) root;
            Object list = m.get("cells");
            if (!(list instanceof List) || ((List<?>) list).isEmpty()) return null;
            int max = 0;
            for (Object o : (List<?>) list) max = Math.max(max, num(((Map<?, ?>) o).get("bar")).intValue());
            if (max <= 0) return null;
            BarCells c = new BarCells(num(m.get("page_width_pt")).floatValue(),
                    num(m.get("page_height_pt")).floatValue(), max);
            for (Object o : (List<?>) list) {
                Map<?, ?> e = (Map<?, ?>) o;
                int i = num(e.get("bar")).intValue() - 1;
                if (i < 0) continue;
                c.page[i] = num(e.get("page")).intValue();
                c.x[i] = num(e.get("x")).floatValue();
                c.y[i] = num(e.get("y")).floatValue();
                c.w[i] = num(e.get("w")).floatValue();
                c.h[i] = num(e.get("h")).floatValue();
                if (e.get("section") != null) c.section[i] = String.valueOf(e.get("section"));
            }
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static Number num(Object o) {
        return o instanceof Number ? (Number) o : Double.valueOf(0);
    }
}

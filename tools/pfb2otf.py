"""Convert a Type 1 .pfb font to an OpenType/CFF .otf (needs fonttools).

usage: pfb2otf.py in.pfb out.otf
"""
import sys
from fontTools.agl import toUnicode
from fontTools.fontBuilder import FontBuilder
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.t2CharStringPen import T2CharStringPen
from fontTools.pens.transformPen import TransformPen
from fontTools.t1Lib import T1Font

src, dst = sys.argv[1:3]
t1 = T1Font(src)
t1.parse()
gs = t1.getGlyphSet()
fm = t1.font["FontMatrix"]  # usually [0.001 0 slant 0.001 0 0]
m = tuple(v * 1000 for v in fm)
ps_name = t1.font["FontName"]

names = [".notdef"] + [n for n in gs.keys() if n != ".notdef"]
charstrings, metrics, cmap = {}, {}, {}
for n in names:
    g = gs[n]
    rec = RecordingPen()
    g.draw(rec)  # also sets g.width (from hsbw)
    width = round(g.width * m[0])
    pen = T2CharStringPen(width, None)
    rec.replay(TransformPen(pen, m))
    charstrings[n] = pen.getCharString()
    metrics[n] = (width, 0)
    if n != ".notdef":
        try:
            u = toUnicode(n)
        except Exception:
            u = ""
        if len(u) == 1:
            cmap.setdefault(ord(u), n)
if ord(" ") not in cmap and "space" in names:
    cmap[32] = "space"

fb = FontBuilder(1000, isTTF=False)
fb.setupGlyphOrder(names)
fb.setupCharacterMap(cmap)
fb.setupCFF(ps_name, {"FullName": ps_name}, charstrings, {})
fb.setupHorizontalMetrics(metrics)
fb.setupHorizontalHeader(ascent=800, descent=-250)
fb.setupNameTable({"familyName": ps_name, "styleName": "Regular"})
fb.setupOS2(sTypoAscender=800, sTypoDescender=-250, usWinAscent=1000, usWinDescent=300)
fb.setupPost()
fb.save(dst)
print(dst, len(names), "glyphs,", len(cmap), "mapped chars")

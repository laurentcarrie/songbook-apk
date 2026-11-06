#!/usr/bin/env python3
"""Generate a drum practice loop as MIDI and MP3.

    drum_loop.py [--style rock|funk|shuffle] [--bpm 100] [--bars 8] [--out DIR] [--count-in]

The MP3 is cut exactly at the end of the last bar, so it loops without a gap.
Needs mido, fluidsynth with a General MIDI soundfont, and ffmpeg.
"""
import argparse
import subprocess
import sys
from pathlib import Path

import mido

TPB = 480                      # ticks per quarter note
KICK, SIDE, SNARE, HAT, OPEN, TOM_HI, TOM_MID, TOM_LO, CRASH = 36, 37, 38, 42, 46, 50, 47, 43, 49
SOUNDFONT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"


def bar(style, bar_index, last):
    """Notes of one 4/4 bar: (position in 16ths for straight styles / 12ths for shuffle, note, velocity)."""
    n = []
    if style == "shuffle":                       # 12 steps per bar, 3 per beat
        for beat in range(4):
            n.append((beat * 3, HAT, 90 if beat % 2 == 0 else 75))
            n.append((beat * 3 + 2, HAT, 60))     # swung off-beat
        n += [(0, KICK, 110), (6, KICK, 100), (3, SNARE, 105), (9, SNARE, 105)]
        if last:
            n = [x for x in n if x[0] < 9]
            n += [(9, TOM_HI, 100), (10, TOM_MID, 100), (11, TOM_LO, 105)]
        return n, 12
    # 16 steps per bar, 4 per beat
    if style == "funk":
        for i in range(16):
            n.append((i, HAT, 95 if i % 4 == 0 else 70 if i % 2 == 0 else 50))
        n += [(0, KICK, 115), (3, KICK, 95), (6, KICK, 100), (10, KICK, 105), (11, KICK, 90)]
        n += [(4, SNARE, 115), (12, SNARE, 115), (7, SNARE, 45), (9, SNARE, 45), (15, SNARE, 55)]
        if last:
            n = [x for x in n if x[0] < 12]
            n += [(12, TOM_HI, 105), (13, TOM_MID, 105), (14, TOM_LO, 110), (15, TOM_LO, 115)]
        return n, 16
    # rock
    for i in range(0, 16, 2):
        n.append((i, HAT, 95 if i % 4 == 0 else 75))
    n += [(0, KICK, 115), (8, KICK, 110), (10, KICK, 100)]
    n += [(4, SNARE, 115), (12, SNARE, 115)]
    if last:
        n = [x for x in n if x[0] < 12]
        n += [(12, SNARE, 105), (13, TOM_HI, 100), (14, TOM_MID, 105), (15, TOM_LO, 110)]
    return n, 16


def build(style, bpm, bars, count_in):
    mid = mido.MidiFile(ticks_per_beat=TPB)
    track = mido.MidiTrack()
    mid.tracks.append(track)
    track.append(mido.MetaMessage("set_tempo", tempo=mido.bpm2tempo(bpm), time=0))
    track.append(mido.MetaMessage("time_signature", numerator=4, denominator=4, time=0))
    events = []                                   # (tick, note, velocity)
    start = 0
    if count_in:                                  # one bar of side stick on the beats
        events += [(beat * TPB, SIDE, 100) for beat in range(4)]
        start = 4 * TPB
    for b in range(bars):
        notes, steps = bar(style, b, b == bars - 1)
        step_ticks = 4 * TPB // steps
        base = start + b * 4 * TPB
        if b == 0:
            notes = notes + [(0, CRASH, 110)]
        events += [(base + pos * step_ticks, note, vel) for pos, note, vel in notes]
    msgs = []
    for tick, note, vel in events:
        msgs.append((tick, mido.Message("note_on", channel=9, note=note, velocity=vel)))
        msgs.append((tick + TPB // 8, mido.Message("note_off", channel=9, note=note, velocity=0)))
    msgs.sort(key=lambda m: (m[0], m[1].type == "note_on"))
    last = 0
    for tick, msg in msgs:
        msg.time = tick - last
        last = tick
        track.append(msg)
    end = start + bars * 4 * TPB
    track.append(mido.MetaMessage("end_of_track", time=max(0, end - last)))
    return mid, (end / TPB) * 60 / bpm


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--style", choices=["rock", "funk", "shuffle"], default="rock")
    ap.add_argument("--bpm", type=int, default=100)
    ap.add_argument("--bars", type=int, default=8)
    ap.add_argument("--out", default="drum-loops")
    ap.add_argument("--count-in", action="store_true")
    a = ap.parse_args()

    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=True)
    name = f"drums-{a.style}-{a.bpm}bpm-{a.bars}bars"
    mid, seconds = build(a.style, a.bpm, a.bars, a.count_in)
    mid_path, wav, mp3 = out / f"{name}.mid", out / f"{name}.wav", out / f"{name}.mp3"
    mid.save(mid_path)
    subprocess.run(["fluidsynth", "-ni", "-g", "1.0", "-r", "44100", "-F", str(wav), SOUNDFONT, str(mid_path)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-t", f"{seconds:.3f}",
                    "-codec:a", "libmp3lame", "-b:a", "192k", str(mp3)], check=True)
    wav.unlink()
    print(f"{mid_path}\n{mp3}  ({seconds:.1f} s)")


if __name__ == "__main__":
    sys.exit(main())

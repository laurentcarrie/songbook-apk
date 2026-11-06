#!/usr/bin/env python3
"""Render the one-shot drum samples of the app from the FluidR3_GM soundfont (MIT licence).

Each hit is played alone through fluidsynth (no reverb, no chorus), trimmed, faded out and
peak-normalised, then written as a 16-bit mono 44.1 kHz WAV in app/src/main/assets/drums/.
Needs mido, fluidsynth, ffmpeg.
"""
import subprocess
import tempfile
import wave
from pathlib import Path

import mido
import numpy as np

SOUNDFONT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/drums"
# name, GM drum note, length in seconds
HITS = [
    ("kick", 36, 0.45),
    ("snare", 38, 0.40),
    ("hat", 42, 0.15),
    ("open", 46, 0.60),
    ("tom_hi", 50, 0.70),
    ("tom_mid", 47, 0.75),
    ("tom_lo", 43, 0.80),
    ("crash", 49, 2.00),
]


def render(name, note, length, tmp):
    mid = mido.MidiFile(ticks_per_beat=480)
    tr = mido.MidiTrack()
    mid.tracks.append(tr)
    tr.append(mido.MetaMessage("set_tempo", tempo=500000, time=0))
    tr.append(mido.Message("note_on", channel=9, note=note, velocity=110, time=0))
    tr.append(mido.Message("note_off", channel=9, note=note, velocity=0, time=240))
    tr.append(mido.MetaMessage("end_of_track", time=int(length * 2 * 480)))
    mid_path, raw, out = tmp / f"{name}.mid", tmp / f"{name}-raw.wav", OUT / f"{name}.wav"
    mid.save(mid_path)
    subprocess.run(["fluidsynth", "-ni", "-g", "1.0", "-r", "44100",
                    "-o", "synth.reverb.active=0", "-o", "synth.chorus.active=0",
                    "-F", str(raw), SOUNDFONT, str(mid_path)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    fade = min(0.12, length / 3)
    trimmed = tmp / f"{name}-trim.wav"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(raw), "-ac", "1", "-ar", "44100",
                    "-af", f"silenceremove=start_periods=1:start_threshold=-55dB,atrim=0:{length},"
                           f"afade=t=out:st={length - fade}:d={fade}",
                    "-acodec", "pcm_s16le", str(trimmed)], check=True)
    with wave.open(str(trimmed)) as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    peak = float(np.max(np.abs(data))) or 1.0
    data = np.clip(data * (0.9 * 32767 / peak), -32768, 32767).astype(np.int16)
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(data.tobytes())
    print(f"{out.name}: {len(data) / 44100:.2f} s, {out.stat().st_size // 1024} KB")


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory() as t:
        for name, note, length in HITS:
            render(name, note, length, Path(t))


if __name__ == "__main__":
    main()

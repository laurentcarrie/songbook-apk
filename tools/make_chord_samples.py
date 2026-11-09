#!/usr/bin/env python3
"""Render the chord instruments of the app from the FluidR3_GM soundfont (MIT licence).

Per instrument, one note every 6 semitones (the app pitch-shifts to the notes it needs), each played
alone through fluidsynth (no reverb, no chorus), trimmed, faded out and peak-normalised, then written as
a 16-bit mono 44.1 kHz WAV in app/src/main/assets/chords/<instrument>/<midi note>.wav.
Needs mido, fluidsynth, ffmpeg.
"""
import subprocess
import tempfile
import wave
from pathlib import Path

import mido
import numpy as np

SOUNDFONT = "/usr/share/sounds/sf2/FluidR3_GM.sf2"
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/chords"
# directory name -> General MIDI program
INSTRUMENTS = {"piano": 0, "epiano": 4, "nylon": 24, "steel": 25}
NOTES = [36, 42, 48, 54, 60, 66, 72]
LENGTH = 2.0  # seconds kept


def render(name, program, note, tmp):
    mid = mido.MidiFile(ticks_per_beat=480)
    tr = mido.MidiTrack()
    mid.tracks.append(tr)
    tr.append(mido.MetaMessage("set_tempo", tempo=500000, time=0))
    tr.append(mido.Message("program_change", channel=0, program=program, time=0))
    tr.append(mido.Message("note_on", channel=0, note=note, velocity=100, time=0))
    tr.append(mido.Message("note_off", channel=0, note=note, velocity=0, time=int(LENGTH * 2 * 480)))
    tr.append(mido.MetaMessage("end_of_track", time=960))
    mid_path, raw = tmp / f"{name}-{note}.mid", tmp / f"{name}-{note}-raw.wav"
    mid.save(mid_path)
    subprocess.run(["fluidsynth", "-ni", "-g", "1.0", "-r", "44100",
                    "-o", "synth.reverb.active=0", "-o", "synth.chorus.active=0",
                    "-F", str(raw), SOUNDFONT, str(mid_path)],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    trimmed = tmp / f"{name}-{note}-trim.wav"
    fade = 0.25
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(raw), "-ac", "1", "-ar", "44100",
                    "-af", f"silenceremove=start_periods=1:start_threshold=-55dB,atrim=0:{LENGTH},"
                           f"afade=t=out:st={LENGTH - fade}:d={fade}",
                    "-acodec", "pcm_s16le", str(trimmed)], check=True)
    with wave.open(str(trimmed)) as w:
        data = np.frombuffer(w.readframes(w.getnframes()), dtype=np.int16).astype(np.float32)
    peak = float(np.max(np.abs(data))) or 1.0
    data = np.clip(data * (0.9 * 32767 / peak), -32768, 32767).astype(np.int16)
    out = OUT / name / f"{note}.wav"
    out.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(out), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(44100)
        w.writeframes(data.tobytes())
    return out


def main():
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        for name, program in INSTRUMENTS.items():
            total = 0
            for note in NOTES:
                out = render(name, program, note, tmp)
                total += out.stat().st_size
            print(f"{name}: {len(NOTES)} notes, {total // 1024} KB")


if __name__ == "__main__":
    main()

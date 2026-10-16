# Folio

Local, project-oriented meeting and lecture transcripts for macOS (Apple Silicon). Nothing leaves the Mac.

## Run

```bash
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt
.venv/bin/python -m folio          # http://127.0.0.1:8765
```

Speaker and VAD models go in `models/` (see `folio/diarize.py` for the files; all from the
[sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)). The speech model
(`mlx-community/parakeet-tdt-0.6b-v2`) downloads from Hugging Face on first use.

## Data

Everything is plain files under `~/Folio` (override with `FOLIO_HOME`):

```
projects/<project>/project.json, project.md (knowledge), glossary.tsv
projects/<project>/meetings/<id>/original.<ext>, audio.m4a, transcript.json, voices.npz, meeting.json
```

Deleting from the app moves things to the macOS Trash.

## Pipeline

1. Decode anything (PyAV, bundled FFmpeg) to 16 kHz mono.
2. Transcribe with Parakeet TDT 0.6B v2 on MLX (≈55× real time on an M2 Pro).
3. Speakers: Silero VAD → TitaNet embeddings on 1.5 s windows → spectral clustering with the
   speaker count chosen by eigengap → each word gets the speaker that overlaps it most.

`spike/` holds the benchmarks behind these choices (`python -m spike.bench asr parakeet ami`).

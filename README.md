# Folio

Local, project-oriented meeting and lecture recorder for macOS (Apple Silicon). Nothing leaves the Mac.

- **Projects** (a group, a course, a client) hold recordings, a knowledge note, a glossary, a living
  "where things stand" document and to-dos.
- **Record** the mic, system audio (Zoom, Teams, Meet…), or both, with a live draft transcript; or
  **import** any audio/video file.
- **Speakers** are told apart and matched to **people** you've named before (voices are shared across
  projects). Search everything by words or by who said it.
- **Fix transcript**: text (local AI proofread), audio (Whisper listens again with your glossary), or
  both. Every fix is shown for review first.
- **Summaries** use the project's knowledge, its state, open to-dos, the people present and earlier
  meetings. Action items become to-dos; the project state updates itself. **Ask this project** answers
  questions with links to the moment it was said.

## Run

```bash
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt
sh capture/build.sh                # Swift capture helper → bin/folio-capture
sh app/build.sh                    # optional: dist/Folio.app launcher
.venv/bin/python -m folio          # http://127.0.0.1:8765
```

**Mac app** (download at https://folio.qshlab.com): `sh app/build.sh` makes `dist/Folio-mac.dmg`, the
update zip and `dist/mac.json`; `android/site/deploy.sh` publishes them. Bump `app/VERSION` first; set
`NOTES="…"` for the update message. The app is tiny: on first launch it installs uv, Python 3.12, the
packages in `app/requirements.lock` and the voice models into `~/Library/Application Support/Folio`
(`app/setup.sh`), then runs the engine from its own copy of the code. It's signed with a self-made
certificate in `app/signing/` (not in git; backed up). Updates are checked against it, and keeping
it keeps the macOS permissions across updates.

Models: speaker/VAD models go in `models/` (`pyannote-segmentation-3-0.onnx`, `silero_vad.onnx`,
`nemo_en_titanet_small.onnx`, from the [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)).
Parakeet, Whisper and Qwen download from Hugging Face on first use (~2.5 + 1.6 + 4.6 GB).

## Data

Plain files under `~/Folio` (override with `FOLIO_HOME`):

```
people/<id>.json, <id>.voices.npy                    name, aliases, notes, projects, voice prints
projects/<p>/project.json, project.md, glossary.tsv  settings, knowledge, glossary
projects/<p>/state.md, state-history/, todos.json    AI-maintained state (editable), to-dos
projects/<p>/meetings/<id>/                          original.* or mic/system.flac, audio.m4a,
    transcript.json, live.jsonl, voices.npz, corrections.json, summary.json/.md, meeting.json
```

Deleting from the app moves things to the macOS Trash.

## How it works

| Step | Model | Notes |
|---|---|---|
| Transcription | Parakeet TDT 0.6B v2 (MLX) | ≈55× real time on an M2 Pro; live: VAD pieces + partials |
| Speakers | Silero VAD → TitaNet → spectral clustering | eigengap picks the count; same-voice clusters merged (cos > 0.7) |
| People | TitaNet voice prints | ≥ 0.85 auto-link, ≥ 0.7 suggest; only confirmed links add prints |
| Audio fix | Whisper large-v3-turbo (MLX) | prompted with glossary terms and names |
| Text fix, summaries, Q&A | Qwen3 8B 4-bit (MLX) | fixes pass a sound-alike / real-word filter |

`spike/` holds the benchmarks behind these choices (`python -m spike.bench asr parakeet ami`).

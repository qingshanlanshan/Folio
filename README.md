# Folio

A meeting and lecture recorder organised by project, for Mac and Android. It records, transcribes, tells
speakers apart, and writes summaries that know the project: its notes, its glossary, earlier meetings and
open to-dos. Transcription runs on your own device; nothing is uploaded unless you choose Claude for
summaries or turn on sync to your own storage.

**Download: <https://folio.qshlab.com>** · Mac (Apple Silicon, macOS 14.4+) and Android (10+, 64-bit).
Both apps update themselves.

## Features

- **Projects** (a research group, a course, a client) hold recordings, a knowledge note, a glossary, a
  living "where things stand" document and to-dos. Recordings you don't file go to **Unsorted**.
- **Record** the microphone, the computer's / phone's own audio (Zoom, Teams, Meet…), or both, with a live
  draft transcript; or **import** audio and video files.
- **Speakers** are told apart and matched to **people** you've named before; voices are recognised across
  projects. Tell Folio when two speakers, or two people, are the same person. Search everything by words or
  by who said it.
- **Fix transcript**: check the text with AI, listen again with a second model (Whisper, prompted with your
  glossary), or both. Every fix is shown for you to accept or reject.
- **Summaries** use the project's knowledge, its state, open to-dos, the people present and earlier
  meetings. Action items become to-dos and the project state updates itself. **Ask this project** answers
  questions with links to the moment it was said.
- Choose several recordings, projects or people at once (long-press on Android, right-click → Select or
  ⌘-click on the Mac) to move, delete, summarize or rename them.
- **Sync** (optional) between your Mac and phone through your own Cloudflare R2 bucket, end-to-end encrypted.

## Privacy

- **Mac**: transcription, speakers and summaries all run on the Mac (MLX models). You can choose Claude for
  summaries instead, with your own API key.
- **Android**: transcription and speakers run on the phone (sherpa-onnx); summaries, fixes and questions use
  Claude with your own API key.
- When Claude is used, only text is sent (transcripts, your project notes), never audio. A project can be
  kept local-only.
- Sync is off by default. When on, everything is encrypted on the device with a key that only travels in the
  pairing code (a QR code on the Mac), so the storage provider can't read it.

## How it works

| Step | Mac | Android |
|---|---|---|
| Transcription | Parakeet TDT 0.6B v2 (MLX), ≈55× real time on an M2 Pro | Parakeet TDT 0.6B (sherpa-onnx) |
| Live draft text | Parakeet on VAD pieces | Streaming Zipformer |
| Speakers | Silero VAD → TitaNet → spectral clustering (eigengap picks the count) | same models, sherpa-onnx |
| People | TitaNet voice prints: ≥ 0.85 links, ≥ 0.7 suggests; only confirmed links add prints | same |
| Listen-again fixes | Whisper large-v3-turbo (MLX) | Whisper base.en (optional) |
| Text fixes, summaries, Q&A | Qwen3 8B 4-bit (MLX), or Claude | Claude |

`spike/` holds the benchmarks behind these choices (`python -m spike.bench asr parakeet ami`).

## Code

```
folio/        the Mac engine: FastAPI server, jobs, ASR, diarization, people, summaries, sync (Python)
web/          the Mac interface (Preact + htm, no build step), shown in the app's window
app/          the Mac app: Swift launcher and window (app/launcher), build and signing (app/build.sh),
              first-run setup that installs the engine (app/setup.sh, requirements.lock)
capture/      Swift helper that records system audio (Core Audio process tap)
android/      the Android app (Kotlin, Jetpack Compose, sherpa-onnx), and android/site: the download page
spike/        benchmarks
```

The Mac app is small: on first launch it installs uv, Python 3.12, the packages in
`app/requirements.lock` and the speech models into `~/Library/Application Support/Folio`, then runs the
engine from its own copy of the code. Updates are checked against the app's signing certificate.

### Run the Mac engine from source

```bash
python3.12 -m venv .venv && .venv/bin/pip install -r requirements.txt
sh capture/build.sh                # system-audio helper → bin/folio-capture
.venv/bin/python -m folio          # http://127.0.0.1:8765
```

Speaker / VAD models go in `models/` (`pyannote-segmentation-3-0.onnx`, `silero_vad.onnx`,
`nemo_en_titanet_small.onnx`, from the [sherpa-onnx releases](https://github.com/k2-fsa/sherpa-onnx/releases)).
Parakeet, Whisper and Qwen download from Hugging Face on first use (~2.5 + 1.6 + 4.6 GB).

### Build the Android app

```bash
cd android
./gradlew assembleDebug            # or assembleRelease (signed when keystore.properties exists)
```

Needs JDK 17 and the Android SDK. The speech models (~780 MB) are downloaded by the app on first start.

### Release (maintainer)

Bump `app/VERSION` and `android/app/build.gradle.kts`, then
`sh app/build.sh` (Mac: dmg, update zip, `mac.json`), `cd android && ./gradlew assembleRelease`, and
`android/site/deploy.sh` (publishes both to folio.qshlab.com on Cloudflare Workers). The signing
certificate (`app/signing/`) and Android keystore are not in git.

## Data

Plain files, in `~/Folio` on the Mac (override with `FOLIO_HOME`) and in the app's storage on Android:

```
people/<id>.json, <id>.voices.*                      name, aliases, notes, projects, voice prints
projects/<p>/project.json, project.md, glossary.tsv  settings, knowledge, glossary
projects/<p>/state.md, state-history/, todos.json    AI-maintained state (editable), to-dos
projects/<p>/meetings/<id>/                          audio, transcript.json, voices, summary, fixes
```

Sync protocol (same format in `folio/sync.py` and `android/…/sync/Sync.kt`): each device only adds new
objects (change batches and content-addressed 8 MiB pieces, AES-256-GCM); per file, the highest
(clock, device) wins; deletions go to the trash.

## License

MIT ([LICENSE](LICENSE)). The speech and language models are downloaded at run time and keep their own
licenses.

---

More from qshlab: <https://qshlab.com>

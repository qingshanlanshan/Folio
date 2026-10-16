"""Speech to text. Every backend returns words with times and a confidence, so the rest of the app
doesn't care which model ran (and a Chinese backend can be added later)."""
from dataclasses import dataclass, asdict
from typing import Callable, Optional

import numpy as np

from .audio import SR

Progress = Optional[Callable[[float], None]]


@dataclass
class Word:
    text: str          # includes trailing punctuation, no leading space
    start: float
    end: float
    conf: float = 1.0
    speaker: Optional[int] = None

    def dict(self):
        return asdict(self)


class Parakeet:
    """NVIDIA Parakeet TDT through MLX. English-only v2 is the most accurate English model here."""

    def __init__(self, repo: str = "mlx-community/parakeet-tdt-0.6b-v2"):
        from parakeet_mlx import from_pretrained
        self.model = from_pretrained(repo)

    def transcribe(self, pcm: np.ndarray, prompt: str | None = None, progress: Progress = None,
                   chunk: float = 120.0, overlap: float = 15.0) -> list[Word]:
        # Same chunk-and-merge as parakeet_mlx's transcribe(), which only takes a file path.
        import mlx.core as mx
        from parakeet_mlx.audio import get_logmel
        from parakeet_mlx.alignment import merge_longest_common_subsequence, merge_longest_contiguous
        cfg = self.model.preprocessor_config
        audio = mx.array(pcm)  # float32: get_logmel views the complex STFT as the input dtype
        size, step = int(chunk * SR), int((chunk - overlap) * SR)
        tokens = []
        for start in range(0, len(pcm), step):
            end = min(start + size, len(pcm))
            if end - start < cfg.hop_length:
                break
            res = self.model.generate(get_logmel(audio[start:end], cfg))[0]
            off = start / SR
            for t in res.tokens:
                t.start += off
                t.end = t.start + t.duration
            if not tokens:
                tokens = res.tokens
            else:
                try:
                    tokens = merge_longest_contiguous(tokens, res.tokens, overlap_duration=overlap)
                except RuntimeError:
                    tokens = merge_longest_common_subsequence(tokens, res.tokens, overlap_duration=overlap)
            if progress:
                progress(end / len(pcm))
            if end == len(pcm):
                break
        return _tokens_to_words(tokens)


def _tokens_to_words(tokens) -> list[Word]:
    words: list[Word] = []
    for t in tokens:
        if not t.text.strip():
            continue
        # A new word starts at a space, or after sentence punctuation ("Paul." + "'Cause").
        if t.text.startswith(" ") or not words or (words[-1].text[-1] in ".?!,;:" and not t.text[0].isdigit()):
            words.append(Word(t.text.strip(), t.start, t.end, t.confidence))
        else:
            w = words[-1]
            w.text += t.text
            w.end = t.end
            w.conf = min(w.conf, t.confidence)
    return words


class Whisper:
    """Whisper large-v3-turbo through MLX. Accepts a prompt (names, jargon) to bias spelling."""

    def __init__(self, repo: str = "mlx-community/whisper-large-v3-turbo"):
        self.repo = repo

    def transcribe(self, pcm: np.ndarray, prompt: str | None = None, progress: Progress = None) -> list[Word]:
        import mlx_whisper
        r = mlx_whisper.transcribe(
            pcm, path_or_hf_repo=self.repo, language="en", word_timestamps=True,
            initial_prompt=prompt, condition_on_previous_text=False,
            hallucination_silence_threshold=2.0, verbose=None)
        words = []
        for seg in r["segments"]:
            if seg.get("no_speech_prob", 0) > 0.6 and seg.get("avg_logprob", 0) < -1:
                continue
            for w in seg.get("words", []):
                if w["word"].strip():
                    words.append(Word(w["word"].strip(), w["start"], w["end"], w.get("probability", 1.0)))
        return words


BACKENDS = {"parakeet": Parakeet, "whisper": Whisper}

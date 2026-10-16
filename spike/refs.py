"""Reference transcripts and speaker turns for the test clips."""
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path

T = Path(__file__).resolve().parent.parent / "testdata"
FILLERS = {"uh", "um", "hmm", "mm", "mhm", "mm-hmm", "uh-huh", "ah", "er", "erm", "huh", "hm", "uhm", "eh"}


def norm(text: str) -> str:
    text = text.lower().replace("’", "'")
    text = re.sub(r"[^a-z0-9' ]+", " ", text)
    return " ".join(w for w in text.split() if w.strip("'") and w not in FILLERS)


def ami():
    words, turns = [], []
    for f in sorted((T / "ami/ann/words").glob("ES2004a.*.words.xml")):
        for w in ET.parse(f).getroot():
            if w.tag == "w" and w.get("punc") != "true" and w.get("starttime"):
                words.append((float(w.get("starttime")), w.text or ""))
    words.sort()
    for line in open(T / "ami/ES2004a.rttm"):
        p = line.split()
        turns.append((float(p[3]), float(p[3]) + float(p[4]), p[7]))
    return T / "ami/ES2004a.Mix-Headset.wav", " ".join(w for _, w in words), turns


def singlish(name="tdk62"):
    segs = json.load(open(T / f"singlish/{name}.ref.json"))
    return (T / f"singlish/{name}.wav", " ".join(s["text"] for s in segs),
            [(s["start"], s["end"], s["speaker"]) for s in segs])


CLIPS = {"ami": ami, "singlish": singlish}

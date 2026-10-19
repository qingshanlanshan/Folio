import os
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
# The app keeps models and the capture helper outside the code folder; a dev run uses the repo's.
MODELS = Path(os.environ.get("FOLIO_MODELS", ROOT / "models"))
CAPTURE = Path(os.environ.get("FOLIO_CAPTURE", ROOT / "bin" / "folio-capture"))

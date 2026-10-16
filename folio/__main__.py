"""python -m folio  →  http://127.0.0.1:8765"""
import os

import uvicorn

if __name__ == "__main__":
    port = int(os.environ.get("FOLIO_PORT", 8765))
    print(f"Folio on http://127.0.0.1:{port}")
    uvicorn.run("folio.server:app", host="127.0.0.1", port=port, log_level="warning")

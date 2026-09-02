import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

os.environ["DATABASE_URL"] = "sqlite+aiosqlite:///./.test.db"
os.environ["BLEEP_REGION"] = "ZA-JHB"
os.environ["BLEEP_TOKEN_SECRET"] = "test-secret"
os.environ["BLEEP_OPS_TOKEN"] = "test-ops"
os.environ["BLEEP_ENV"] = "test"

test_db = ROOT / ".test.db"
if test_db.exists():
    test_db.unlink()

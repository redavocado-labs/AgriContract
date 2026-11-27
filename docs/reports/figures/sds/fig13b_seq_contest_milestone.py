"""Export fig13b_seq_contest_milestone from the approved Draw.io source."""

import subprocess
import sys
from pathlib import Path

if __name__ == "__main__":
    subprocess.run(
        [
            sys.executable,
            str(Path(__file__).with_name("export_sds_518.py")),
            "fig13b_seq_contest_milestone",
        ],
        check=True,
    )

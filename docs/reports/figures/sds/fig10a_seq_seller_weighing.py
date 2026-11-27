"""Export fig10a_seq_seller_weighing from the approved Draw.io source."""

import subprocess
import sys
from pathlib import Path

if __name__ == "__main__":
    subprocess.run(
        [
            sys.executable,
            str(Path(__file__).with_name("export_sds_518.py")),
            "fig10a_seq_seller_weighing",
        ],
        check=True,
    )

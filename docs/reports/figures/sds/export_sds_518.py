"""Regenerate approved SDS diagrams for Issue #518.

The editable Draw.io files are the source of truth.
Export PNG and SVG using the Draw.io Desktop CLI.
"""

import argparse
import os
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from xml.etree import ElementTree

BASE = Path(__file__).resolve().parent

DRAWIO = Path(os.environ.get(
    "DRAWIO_CLI",
    r"C:\Program Files\draw.io\draw.io.exe"
))

FIGURES = [
    "fig02_backend_packages",
    "fig03_frontend_packages",
    "fig06_class_signing",
    "fig07_seq_initiate_sign",
    "fig08_seq_verify_sign",
    "fig09_class_milestone",
    "fig10a_seq_seller_weighing",
    "fig10b_seq_buyer_receipt",
    "fig11_seq_money_reconcile",
    "fig12_class_dispute",
    "fig13a_seq_flag_milestone",
    "fig13b_seq_contest_milestone",
    "fig13c_seq_inspector_assignment",
    "fig14_seq_report_remedy",
]


def validate_sources():
    if not DRAWIO.is_file():
        raise FileNotFoundError(DRAWIO)

    for name in FIGURES:
        source = BASE / f"{name}.drawio"

        if not source.is_file():
            raise FileNotFoundError(source)

        ElementTree.parse(source)

    print("Validated 14/14 Draw.io sources")


def export_figure(name, fmt, output_dir):
    source = BASE / f"{name}.drawio"
    output = output_dir / f"{name}.{fmt}"

    command = [
        str(DRAWIO),
        "--export",
        "--format", fmt,
        "--border", "10",
    ]

    if fmt == "png":
        command.extend(["--scale", "3"])

    command.extend([
        "--output", str(output),
        str(source),
    ])

    subprocess.run(
        command,
        check=True,
        timeout=120,
    )

    if not output.is_file() or output.stat().st_size == 0:
        raise RuntimeError(f"Export failed: {output.name}")

    print(f"OK: {output.name}")


def main():
    parser = argparse.ArgumentParser(
        description="Export approved SDS diagrams"
    )

    parser.add_argument(
        "figures",
        nargs="*",
        choices=FIGURES,
        help="Figures to export; default: all figures"
    )

    args = parser.parse_args()
    selected = args.figures or FIGURES

    validate_sources()

    with TemporaryDirectory(dir=BASE) as temp:
        output_dir = Path(temp)

        for name in selected:
            for fmt in ("png", "svg"):
                export_figure(name, fmt, output_dir)

        for output in output_dir.iterdir():
            output.replace(BASE / output.name)

    print(
        f"Completed: {len(selected)} PNG + "
        f"{len(selected)} SVG"
    )


if __name__ == "__main__":
    main()

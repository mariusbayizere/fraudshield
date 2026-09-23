# Extraction tool for the SRS .docx files in this directory. Kept beside them so the Markdown
# extractions can be reproduced: python3 docs/srs/extract_srs.py docs/srs/<file>.docx > <file>.md
"""Extract a .docx SRS to the Markdown shape docs/srs/FraudShield_SRS_v1_0.md uses:
paragraphs as plain lines, tables as pipe tables with <br> for in-cell paragraph breaks.
Standard library only."""

import re
import sys
import zipfile
from xml.etree import ElementTree as ET
from xml.etree.ElementTree import Element

W = "{http://schemas.openxmlformats.org/wordprocessingml/2006/main}"


def text_of(paragraph: Element) -> str:
    parts: list[str] = []
    for node in paragraph.iter():
        if node.tag == W + "t":
            parts.append(node.text or "")
        elif node.tag == W + "tab":
            parts.append("\t")
        elif node.tag in (W + "br", W + "cr"):
            parts.append("\n")
    return "".join(parts)


def cell_text(cell: Element) -> str:
    lines = [text_of(p).strip() for p in cell.findall(f"{W}p")]
    lines = [line for line in lines if line != ""]
    return " <br> ".join(lines).replace("|", "\\|")


def render_table(table: Element) -> list[str]:
    rows: list[list[str]] = []
    for row in table.findall(f"{W}tr"):
        cells = [cell_text(c) for c in row.findall(f"{W}tc")]
        if cells:
            rows.append(cells)
    if not rows:
        return []
    width = max(len(r) for r in rows)
    rows = [r + [""] * (width - len(r)) for r in rows]
    out = ["| " + " | ".join(rows[0]) + " |", "|" + "---|" * width]
    for cells in rows[1:]:
        out.append("| " + " | ".join(cells) + " |")
    return out


def extract(path: str) -> str:
    with zipfile.ZipFile(path) as archive:
        xml = archive.read("word/document.xml")
    # S314: the input is an SRS document the owner supplies, parsed by hand on this machine and
    # never from a network source; adding defusedxml for a one-off local tool would put a
    # dependency in the repository for no gain.
    body = ET.fromstring(xml).find(f"{W}body")  # noqa: S314
    if body is None:
        raise ValueError(f"{path}: no word/document.xml body")
    out: list[str] = []
    for child in body:
        if child.tag == W + "p":
            line = text_of(child).strip()
            if line:
                out.extend(line.split("\n"))
            elif out and out[-1] != "":
                out.append("")
        elif child.tag == W + "tbl":
            if out and out[-1] != "":
                out.append("")
            out.extend(render_table(child))
            out.append("")
    text = "\n".join(out)
    return re.sub(r"\n{3,}", "\n\n", text).strip() + "\n"


if __name__ == "__main__":
    sys.stdout.write(extract(sys.argv[1]))

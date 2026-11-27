# SRS Diagram Sources

## Context Diagram and Business Flows (BF-01–BF-07)

The editable `.drawio` files are the authoritative sources for the Context Diagram and BF-01 through BF-07.

- `.drawio`: Editable diagram source.
- `.png`: Raster export embedded in Report 3.
- `.svg`: Vector export for inspection and reuse.

Edit the `.drawio` source in Draw.io Desktop, then regenerate both PNG and SVG from that source. Do not edit the PNG or SVG separately. Re-embed the exported PNG in the matching Report 3 section and verify byte equality between the embedded image and the repository PNG. Preserve document styles, version and unrelated content; refresh the table of contents if pagination changes.

## Legacy generators

The former `context.py`, `context.dot` and `bf01.py` through `bf07.py` generators have been retired to avoid overwriting the maintained Draw.io diagrams. Their history remains available in Git.

The ERD, Use Case Diagram and SDS generation workflows are unchanged.

## Related issue

#517 — SRS Context Diagram and Business Flow corrections.

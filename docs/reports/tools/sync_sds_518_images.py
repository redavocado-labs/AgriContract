"""Replace Issue #518 figures in the reviewed SDS, using official Draw.io exports."""
import argparse
import copy
import os
import re
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory
from xml.etree import ElementTree as ET
from docx import Document
from docx.oxml.ns import qn

FIGURES = dict(zip(('2','3','6','7','8','9','10a','10b','11','12','13a','13b','13c'), (
    'fig02_backend_packages','fig03_frontend_packages','fig06_class_signing',
    'fig07_seq_initiate_sign','fig08_seq_verify_sign','fig09_class_milestone','fig10a_seq_seller_weighing',
    'fig10b_seq_buyer_receipt','fig11_seq_money_reconcile','fig12_class_dispute',
    'fig13a_seq_flag_milestone','fig13b_seq_contest_milestone','fig13c_seq_inspector_assignment')))


def split_panels(source, directory):
    tree = ET.parse(source)
    cells = tree.find('.//mxGraphModel/root')
    if cells is None or len(tree.findall('.//diagram')) != 1:
        raise ValueError('Expected approved single-page Figure 14 XML')
    groups = [set(), set(), set()]
    bounds = [1460, 3536]  # approved baseline panel headings
    for cell in cells:
        if cell.get('id') in ('0','1'):
            continue
        g = cell.find('mxGeometry')
        if g is None or cell.get('parent') != '1':
            raise ValueError('Unexpected Figure 14 cell: ' + str(cell.get('id')))
        if cell.get('vertex') == '1':
            y = float(g.get('y','0'))
            ys = [y, y + float(g.get('height','0'))]
        else:
            ys = [float(p.get('y','0')) for p in g.iter('mxPoint')]
        if not ys or any(min(ys) < b <= max(ys) for b in bounds):
            raise ValueError('Cell crosses panel boundary: ' + str(cell.get('id')))
        groups[sum(min(ys) >= b for b in bounds)].add(cell.get('id'))
    paths = {}
    for letter, ids in zip('abc', groups):
        if not ids:
            raise ValueError('Empty panel')
        clone = copy.deepcopy(tree)
        root = clone.find('.//mxGraphModel/root')
        keep = ids | {'0','1'}
        for cell in list(root):
            if cell.get('id') not in keep:
                root.remove(cell)
            elif any(cell.get(a) and cell.get(a) not in keep for a in ('source','target','parent')):
                raise ValueError('Cross-panel reference')
        path = directory / f'fig14_{letter}.drawio'
        clone.write(path, encoding='utf-8', xml_declaration=True)
        paths[f'14({letter})'] = path
    return paths


def replace_images(doc, images):
    paragraphs = doc.paragraphs
    found = {}
    for i, p in enumerate(paragraphs):
        m = re.match(r'^Figure (\d+[a-c]?|14\([a-c]\))\.', p.text.strip())
        if not m or m[1] not in images:
            continue
        key = m[1]
        if key in found or i == 0:
            raise ValueError(f'Duplicate or invalid caption: {key}')
        pic = paragraphs[i-1]._p
        blips = pic.xpath('.//a:blip')
        extents = pic.xpath('.//wp:extent')
        if len(blips) != 1 or len(extents) != 1:
            raise ValueError(f'Expected exactly one picture before Figure {key}')
        found[key] = (pic, blips[0], extents[0])
    if set(found) != set(images):
        raise ValueError(f'Missing captions: {set(images)-set(found)}')
    for key, (pic, blip, extent) in found.items():
        path = images[key]
        rid, image = doc.part.get_or_add_image(str(path))
        old_w, old_h = int(extent.get('cx')), int(extent.get('cy'))
        ratio = image.px_width / image.px_height
        w = min(old_w, int(old_h * ratio))
        h = round(w / ratio)
        blip.set(qn('r:embed'), rid)
        for svg in blip.xpath('.//*[local-name()="svgBlip"]'):
            svg.getparent().remove(svg)  # avoid stale vector alternative after replacing PNG
        for e in [extent, *pic.xpath('.//pic:spPr/a:xfrm/a:ext')]:
            e.set('cx', str(w)); e.set('cy', str(h))
        print(f'OK: Figure {key} <- {path.name}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.input.resolve() == args.output.resolve() or args.output.exists():
        raise ValueError('Choose a new output file; input will not be overwritten')
    fig = Path(__file__).resolve().parents[1] / 'figures' / 'sds'
    cli = Path(os.environ.get('DRAWIO_CLI', r'C:\Program Files\draw.io\draw.io.exe'))
    if not cli.is_file():
        raise FileNotFoundError(cli)
    images = {key: fig / (name + '.png') for key, name in FIGURES.items()}
    for path in images.values():
        if not path.is_file() or not path.stat().st_size:
            raise FileNotFoundError(path)
    with TemporaryDirectory() as tmp:
        for key, source in split_panels(fig / 'fig14_seq_report_remedy.drawio', Path(tmp)).items():
            png = source.with_suffix('.png')
            subprocess.run([str(cli),'--export','--format','png','--border','10','--scale','3','--output',str(png),str(source)],check=True,timeout=120)
            if not png.is_file() or not png.stat().st_size:
                raise RuntimeError(f'Panel export failed: {key}')
            images[key] = png
        doc = Document(args.input)
        replace_images(doc, images)
        doc.save(args.output)
    print('Completed: 16 embedded images (14 figures, Figure 14 has 3 panels)')


if __name__ == '__main__':
    main()

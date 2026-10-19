#!/usr/bin/env python3
"""Convert installed structure-guide NBT to an opt-in local data pack (stdlib only).

Does not distribute third-party templates, inventories, block-entity NBT or entities.
Imported layouts are CREATIVE ONLY until individually reviewed by a pack author.
"""
import argparse
import fnmatch
import gzip
import hashlib
import io
import json
import posixpath
from pathlib import Path, PurePosixPath
import re
import struct
import zipfile


class NbtReader:
    def __init__(self, data):
        self.stream = io.BytesIO(data)

    def read(self, count):
        if count < 0 or count > 8_000_000:
            raise ValueError('NBT length limit')
        data = self.stream.read(count)
        if len(data) != count:
            raise ValueError('Truncated NBT')
        return data

    def number(self, fmt):
        return struct.unpack('>' + fmt, self.read(struct.calcsize(fmt)))[0]

    def string(self):
        return self.read(self.number('H')).decode('utf-8')

    def count(self):
        count = self.number('i')
        if not 0 <= count <= 200_000:
            raise ValueError('NBT collection limit')
        return count

    def payload(self, kind, depth=0):
        if depth > 64:
            raise ValueError('NBT nesting limit')
        if kind in range(1, 7):
            return self.number({1:'b',2:'h',3:'i',4:'q',5:'f',6:'d'}[kind])
        if kind == 7:
            return self.read(self.count())
        if kind == 8:
            return self.string()
        if kind == 9:
            child = self.number('B')
            return [self.payload(child, depth+1) for _ in range(self.count())]
        if kind == 10:
            out = {}
            while True:
                child = self.number('B')
                if child == 0:
                    return out
                name = self.string()
                if name in out:
                    raise ValueError('Duplicate NBT key')
                out[name] = self.payload(child, depth+1)
                if len(out) > 200_000:
                    raise ValueError('NBT compound limit')
        if kind in (11, 12):
            return [self.number('i' if kind == 11 else 'q') for _ in range(self.count())]
        raise ValueError(f'Unknown NBT tag: {kind}')


def parse_nbt(data):
    if data[:2] == b'\x1f\x8b':
        with gzip.GzipFile(fileobj=io.BytesIO(data)) as source:
            data = source.read(8_000_001)
    if len(data) > 8_000_000:
        raise ValueError('NBT size limit')
    reader = NbtReader(data)
    if reader.number('B') != 10:
        raise ValueError('Structure root must be compound')
    reader.string()
    result = reader.payload(10)
    if reader.stream.read(1):
        raise ValueError('Trailing NBT data')
    return result


def convert(nbt, title, survival=False):
    size = nbt['size']
    if len(size) != 3 or any(not isinstance(x, int) or not 1 <= x <= 64 for x in size):
        raise ValueError('Size outside supported 1..64 range')
    palette = nbt.get('palette')
    if not palette:
        raise ValueError('Missing single palette (random palettes are not build plans)')
    cells = []
    seen = set()
    stripped = 0
    for block in nbt['blocks']:
        index = block['state']
        if not isinstance(index, int) or index < 0 or index >= len(palette):
            raise ValueError('Invalid palette index')
        state = palette[index]
        name = state['Name']
        if name in ('minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:structure_void'):
            continue
        if not re.fullmatch(r'[a-z0-9_.-]+:[a-z0-9/._-]+', name):
            raise ValueError('Invalid block registry ID')
        pos = block['pos']
        if len(pos) != 3 or any(not isinstance(v,int) or v < 0 or v >= size[i] for i,v in enumerate(pos)):
            raise ValueError('Out-of-bounds cell')
        if tuple(pos) in seen:
            raise ValueError('Duplicate cell')
        seen.add(tuple(pos))
        cell = {'pos': pos, 'block': name}
        if state.get('Properties'):
            cell['properties'] = state['Properties']
        cells.append(cell)
        stripped += 'nbt' in block
    if not 1 <= len(cells) <= 4096:
        raise ValueError('Non-air cell count outside 1..4096')
    return {'title': title[:160], 'size': size, 'survival': survival, 'blocks': cells}, stripped


def export(jar, output, pattern='*', reviewed=()):
    if output.exists():
        raise ValueError('Output already exists; choose a new directory to preserve previous data')
    report = {'source_jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'converted': [], 'skipped': []}
    output.mkdir(parents=True)
    (output/'pack.mcmeta').write_text(json.dumps({'pack':{'pack_format':48,'description':'Local structure-guide blueprints; imported layouts default to creative-only'}},indent=2), encoding='utf-8')
    prefix = 'assets/atm_structures/guides/atm_structures/library/structures/'
    with zipfile.ZipFile(jar) as archive:
        titles = {}
        for info in archive.infolist():
            if not info.filename.startswith('assets/atm_structures/guides/') or not info.filename.endswith(('.md','.mdx')) or info.file_size>1_000_000:
                continue
            page=archive.read(info).decode('utf-8')
            title=re.search(r'^\s*title:\s*("[^"\n]*")',page,re.M)
            if not title: continue
            for src in re.findall(r'<ImportStructure\s+[^>]*src="([^"]+)"',page):
                path=posixpath.normpath(posixpath.join(posixpath.dirname(info.filename),src))
                titles.setdefault(path,json.loads(title.group(1)))
        for info in sorted(archive.infolist(), key=lambda i:i.filename):
            name = info.filename
            if not name.startswith(prefix) or not name.endswith('.nbt'):
                continue
            relative = PurePosixPath(name[len(prefix):-4])
            slug = relative.as_posix()
            if not fnmatch.fnmatch(slug, pattern):
                continue
            try:
                if '..' in relative.parts or not re.fullmatch(r'[a-z0-9_./-]+', slug) or info.file_size > 8_000_000:
                    raise ValueError('Invalid resource path/size')
                data = archive.read(info)
                blueprint, stripped = convert(parse_nbt(data), titles.get(name,slug.replace('_',' ')), slug in reviewed)
                target = output/'data/guide_import/multiblock_blueprints'/Path(*relative.parts)
                target = Path(str(target)+'.json')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(json.dumps(blueprint,ensure_ascii=False,separators=(',',':'))+'\n', encoding='utf-8')
                report['converted'].append({'id':'guide_import:'+slug,'cells':len(blueprint['blocks']), 'stripped_block_entities':stripped,'survival':blueprint['survival'],'source':name,'sha256':hashlib.sha256(data).hexdigest()})
            except (ValueError, KeyError, TypeError) as ex:
                report['skipped'].append({'source':name,'reason':str(ex)})
    (output/'import-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('guide_jar', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--include', default='*', help='Template path glob, e.g. ae_family/*')
    parser.add_argument('--reviewed-survival', action='append', default=[], metavar='TEMPLATE_PATH', help='Explicitly approve one audited template for survival; never use a wildcard')
    args = parser.parse_args()
    report = export(args.guide_jar,args.output,args.include,args.reviewed_survival)
    print(f"Converted {len(report['converted'])}; skipped {len(report['skipped'])}. See import-report.json.")


if __name__ == '__main__':
    main()

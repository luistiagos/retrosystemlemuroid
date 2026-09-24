"""Replace only the lifecycle classes in an existing AAR; preserve native fixes."""
import argparse
from copy import copy
import hashlib
import io
from pathlib import Path
from zipfile import ZipFile


PACKAGE = 'com/swordfish/libretrodroid/'


def lifecycle_class(name):
    return any(name == PACKAGE + base + '.class' or
               (name.startswith(PACKAGE + base + '$') and name.endswith('.class'))
               for base in ('GLRetroView', 'CoreWorkGuard'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aar', type=Path, required=True)
    parser.add_argument('--classes-directory', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.aar.resolve() == args.output.resolve():
        parser.error('--output must differ from --aar; validate before replacing it')

    compiled = {
        p.relative_to(args.classes_directory).as_posix(): p.read_bytes()
        for p in args.classes_directory.rglob('*.class')
        if lifecycle_class(p.relative_to(args.classes_directory).as_posix())
    }
    for name in ('GLRetroView.class', 'CoreWorkGuard.class',
                 'GLRetroView$Renderer$onDrawFrame$1.class'):
        if PACKAGE + name not in compiled:
            raise ValueError('Missing compiled lifecycle class: ' + name)

    with ZipFile(args.aar) as original:
        jar_bytes = io.BytesIO()
        with ZipFile(io.BytesIO(original.read('classes.jar'))) as old_jar:
            with ZipFile(jar_bytes, 'w') as new_jar:
                for entry in old_jar.infolist():
                    if not lifecycle_class(entry.filename):
                        new_jar.writestr(copy(entry), old_jar.read(entry.filename))
                for name, data in sorted(compiled.items()):
                    new_jar.writestr(name, data)
        with ZipFile(args.output, 'w') as output:
            for entry in original.infolist():
                output.writestr(copy(entry), jar_bytes.getvalue() if entry.filename == 'classes.jar'
                                else original.read(entry.filename))

        # Native libraries, resources, manifest and unrelated classes must stay identical.
        with ZipFile(args.output) as output:
            if set(original.namelist()) != set(output.namelist()):
                raise ValueError('AAR entries changed')
            for name in original.namelist():
                if name != 'classes.jar' and original.read(name) != output.read(name):
                    raise ValueError('Unexpected AAR change: ' + name)
            with ZipFile(io.BytesIO(original.read('classes.jar'))) as old_jar, \
                    ZipFile(io.BytesIO(output.read('classes.jar'))) as new_jar:
                expected = {n for n in old_jar.namelist() if not lifecycle_class(n)} | set(compiled)
                if set(new_jar.namelist()) != expected:
                    raise ValueError('Unexpected class entries')
                for name in expected:
                    expected_bytes = compiled[name] if name in compiled else old_jar.read(name)
                    if new_jar.read(name) != expected_bytes:
                        raise ValueError('Unexpected class content: ' + name)
    print(f'Updated {len(compiled)} lifecycle classes; all other AAR content is identical.')
    print('SHA-256:', hashlib.sha256(args.output.read_bytes()).hexdigest())


if __name__ == '__main__':
    main()

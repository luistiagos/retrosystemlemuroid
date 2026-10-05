"""
Lista as chamadas a APIs java.*/javax.* acima do minSdk no bytecode de um APK, por classe chamadora.

POR QUE: o `NewApi` do lint só lê o código-fonte do projeto. Biblioteca que chama API mais nova
que o minSdk (21) passa calada pelo build e só aparece como NoClassDefFoundError /
NoSuchMethodError num aparelho velho -- foi assim que o padkit derrubou todo jogo com analógico
no Android 5-7 (`java.time`, API 26). O core library desugaring do :lemuroid-app reescreve
`java.time`, `java.util.stream`/`function`, `Optional` etc. para `j$.*`, mas NÃO cobre
`java.nio.file`, `java.lang.invoke` e as APIs novas de `javax.net.ssl`/`java.security.cert`.

O script percorre o bytecode de todos os métodos do APK, resolve cada tipo/campo/método
`java/...` referenciado contra o `api-versions.xml` do SDK (subindo superclasses e interfaces,
como o lint) e lista o que está acima do minSdk. Achado não é bug automático: biblioteca costuma
guardar a chamada (SDK_INT, Class.forName, try/catch). Cada classe nova na lista tem que ser
triada abrindo o código que chama.

Com --android entram também as APIs `android.*` -- foi assim que apareceu o
`Context.getSystemService(Class)` (API 23) do haptics do padkit, que derruba todo jogo no
Android 5.0-5.1. A lista fica grande (androidx guarda tudo em classes `*ApiNNImpl`): usar
--skip Landroidx/ --skip 'Lj$/' e triar o resto.

Ver: documentacao/bugs/done/2026-10-05-padkit-kotlinx-datetime-java-time-android-7.md
     (triagem do release 1.17.24 -- o que já está lá não precisa ser revisto)

USO: rodar no APK de RELEASE (é o código que o R8 manteve, o único que pode executar):
    python audit_dex_api_level.py lemuroid-app/build/outputs/apk/freeBundle/release/<apk> \
        <SDK>/platforms/android-35/data/api-versions.xml \
        --mapping lemuroid-app/build/outputs/mapping/freeBundleRelease/mapping.txt
No release o R8 move cada chamada a API nova para uma classe sintética
(`...$$ExternalSyntheticApiModelOutlineN`) e REAPROVEITA o mesmo outline entre classes, com o nome
de uma delas só; o script atribui a chamada a quem invoca o outline (precisa do --mapping).
Opções: --min-sdk N (default 21), --by-api (agrupa por API em vez de por classe),
--android, --skip PREFIXO (repetível).
"""

import argparse
import struct
import sys
import zipfile
import xml.etree.ElementTree as ET
from collections import defaultdict


class ApiDatabase:
    """Nível de API de classes e membros, lido do api-versions.xml do SDK."""

    def __init__(self, path):
        self.classes = {}
        for cls in ET.parse(path).getroot().iter("class"):
            since = int(cls.get("since", "1"))
            edges = [(e.get("name"), int(e.get("since", "1"))) for e in cls if e.tag in ("extends", "implements")]
            methods = {m.get("name"): int(m.get("since", since)) for m in cls.iter("method")}
            fields = {f.get("name"): int(f.get("since", since)) for f in cls.iter("field")}
            self.classes[cls.get("name")] = (since, edges, methods, fields)
        self._memo = {}

    def class_since(self, name):
        entry = self.classes.get(name)
        return entry[0] if entry else None

    def member_since(self, name, member, kind):
        """Menor nível em que `member` existe na classe `name`, por qualquer caminho de herança."""
        key = (name, member, kind)
        if key not in self._memo:
            self._memo[key] = None  # guarda contra ciclo
            self._memo[key] = self._member_since(name, member, kind)
        return self._memo[key]

    def _member_since(self, name, member, kind):
        entry = self.classes.get(name)
        if entry is None:
            return None
        since, edges, methods, fields = entry
        table = methods if kind == "method" else fields
        candidates = [table[member]] if member in table else []
        for super_name, edge_since in edges:
            found = self.member_since(super_name, member, kind)
            if found is not None:
                candidates.append(max(found, edge_since))
        if not candidates:
            return None
        return max(since, min(candidates))


def uleb128(buf, off):
    result = shift = 0
    while True:
        byte = buf[off]
        off += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, off
        shift += 7


def _opcode_table():
    """Tamanho (em unidades de 16 bits) e tipo de índice de cada opcode Dalvik."""
    table = [(1, None)] * 256

    def put(first, last, size, kind=None):
        for op in range(first, last + 1):
            table[op] = (size, kind)

    put(0x02, 0x02, 2)
    put(0x03, 0x03, 3)
    put(0x05, 0x05, 2)
    put(0x06, 0x06, 3)
    put(0x08, 0x08, 2)
    put(0x09, 0x09, 3)
    put(0x13, 0x13, 2)
    put(0x14, 0x14, 3)
    put(0x15, 0x16, 2)
    put(0x17, 0x17, 3)
    put(0x18, 0x18, 5)
    put(0x19, 0x1A, 2)
    put(0x1B, 0x1B, 3)
    put(0x1C, 0x1C, 2, "type")  # const-class
    put(0x1F, 0x20, 2, "type")  # check-cast, instance-of
    put(0x22, 0x23, 2, "type")  # new-instance, new-array
    put(0x24, 0x25, 3, "type")  # filled-new-array(/range)
    put(0x26, 0x26, 3)
    put(0x29, 0x29, 2)
    put(0x2A, 0x2C, 3)
    put(0x2D, 0x3D, 2)
    put(0x44, 0x51, 2)
    put(0x52, 0x6D, 2, "field")  # iget/iput/sget/sput
    put(0x6E, 0x72, 3, "method")  # invoke-*
    put(0x74, 0x78, 3, "method")  # invoke-*/range
    put(0x90, 0xAF, 2)
    put(0xD0, 0xE2, 2)
    put(0xFA, 0xFB, 4, "method")  # invoke-polymorphic(/range)
    put(0xFC, 0xFD, 3)
    put(0xFE, 0xFF, 2)
    return table


OPCODES = _opcode_table()


class Dex:
    def __init__(self, buf):
        self.buf = buf
        (n_str, off_str, n_type, off_type, n_proto, off_proto, n_field, off_field, n_method, off_method,
         n_class, off_class) = struct.unpack_from("<12I", buf, 0x38)
        self.strings = [self._string(struct.unpack_from("<I", buf, off_str + 4 * i)[0]) for i in range(n_str)]
        self.types = [self.strings[struct.unpack_from("<I", buf, off_type + 4 * i)[0]] for i in range(n_type)]
        self.protos = []
        for i in range(n_proto):
            _, ret, params_off = struct.unpack_from("<III", buf, off_proto + 12 * i)
            self.protos.append("(" + "".join(self._type_list(params_off)) + ")" + self.types[ret])
        self.fields = []
        for i in range(n_field):
            cls, _, name = struct.unpack_from("<HHI", buf, off_field + 8 * i)
            self.fields.append((self.types[cls], self.strings[name]))
        self.methods = []
        for i in range(n_method):
            cls, proto, name = struct.unpack_from("<HHI", buf, off_method + 8 * i)
            self.methods.append((self.types[cls], self.strings[name] + self.protos[proto]))
        self.class_defs = [struct.unpack_from("<8I", buf, off_class + 32 * i) for i in range(n_class)]

    def _string(self, off):
        _, start = uleb128(self.buf, off)
        return self.buf[start:self.buf.index(b"\x00", start)].decode("utf-8", errors="replace")

    def _type_list(self, off):
        if not off:
            return []
        (n,) = struct.unpack_from("<I", self.buf, off)
        return [self.types[struct.unpack_from("<H", self.buf, off + 4 + 2 * k)[0]] for k in range(n)]

    def references(self):
        """(classe chamadora, tipo de índice, índice) de supertipos e de toda instrução indexada."""
        buf = self.buf
        for class_idx, _, super_idx, interfaces_off, _, _, data_off, _ in self.class_defs:
            caller = self.types[class_idx]
            if super_idx != 0xFFFFFFFF:
                yield caller, "type", super_idx
            if interfaces_off:
                (n,) = struct.unpack_from("<I", buf, interfaces_off)
                for k in range(n):
                    yield caller, "type", struct.unpack_from("<H", buf, interfaces_off + 4 + 2 * k)[0]
            if not data_off:
                continue
            p = data_off
            n_static_fields, p = uleb128(buf, p)
            n_instance_fields, p = uleb128(buf, p)
            n_direct_methods, p = uleb128(buf, p)
            n_virtual_methods, p = uleb128(buf, p)
            for _ in range(n_static_fields + n_instance_fields):
                _, p = uleb128(buf, p)
                _, p = uleb128(buf, p)
            for _ in range(n_direct_methods + n_virtual_methods):
                _, p = uleb128(buf, p)
                _, p = uleb128(buf, p)
                code_off, p = uleb128(buf, p)
                if code_off:
                    yield from self._code_refs(caller, code_off)

    def _code_refs(self, caller, code_off):
        buf = self.buf
        (insns_size,) = struct.unpack_from("<I", buf, code_off + 12)
        base = code_off + 16
        pc = 0
        while pc < insns_size:
            unit = struct.unpack_from("<H", buf, base + 2 * pc)[0]
            op = unit & 0xFF
            if op == 0x00 and unit != 0x0000:  # payloads de switch / fill-array-data
                if unit == 0x0100:
                    pc += 4 + struct.unpack_from("<H", buf, base + 2 * pc + 2)[0] * 2
                elif unit == 0x0200:
                    pc += 2 + struct.unpack_from("<H", buf, base + 2 * pc + 2)[0] * 4
                elif unit == 0x0300:
                    width = struct.unpack_from("<H", buf, base + 2 * pc + 2)[0]
                    (size,) = struct.unpack_from("<I", buf, base + 2 * pc + 4)
                    pc += 4 + (size * width + 1) // 2
                else:
                    pc += 1
                continue
            size, kind = OPCODES[op]
            if kind is not None:
                yield caller, kind, struct.unpack_from("<H", buf, base + 2 * pc + 2)[0]
            pc += size


def resolve(dex, api, kind, index, min_sdk, defined, packages):
    if kind == "type":
        descriptor = dex.types[index].lstrip("[")
        if not descriptor.startswith(packages) or descriptor in defined:
            return None
        since, label = api.class_since(descriptor[1:-1]), descriptor
    else:
        owner, member = (dex.fields if kind == "field" else dex.methods)[index]
        if not owner.startswith(packages) or owner in defined:
            return None
        since, label = api.member_since(owner[1:-1], member, kind), owner + "->" + member
    if since is None or since <= min_sdk:
        return None
    return since, label


def audit(apk_path, api, min_sdk, packages, is_outline):
    hits = defaultdict(set)  # (nível, API) -> classes chamadoras
    invokers = defaultdict(set)  # outline do R8 -> classes que chamam métodos dele
    with zipfile.ZipFile(apk_path) as apk:
        names = sorted(n for n in apk.namelist() if n.startswith("classes") and n.endswith(".dex"))
        dexes = [Dex(apk.read(name)) for name in names]
    # Classe java.* que o próprio APK define existe em qualquer nível: a desugar_jdk_libs 2.x embarca
    # o pacote java.util.function inteiro para minSdk < 24 (da 24 em diante vence a cópia do sistema).
    defined = {dex.types[class_def[0]] for dex in dexes for class_def in dex.class_defs}
    for dex in dexes:
        cache = {}
        for caller, kind, index in dex.references():
            if kind == "method" and is_outline(dex.methods[index][0]):
                invokers[dex.methods[index][0]].add(caller)
            if (kind, index) not in cache:
                cache[(kind, index)] = resolve(dex, api, kind, index, min_sdk, defined, packages)
            if cache[(kind, index)] is not None:
                hits[cache[(kind, index)]].add(caller)
    # O R8 move cada chamada a API nova para um outline e REAPROVEITA o mesmo outline entre classes
    # (o nome vem de uma delas só): o haptics do padkit chamava `getSystemService(Class)` por um outline
    # batizado de `AndroidAutofill`. A chamadora de verdade é quem invoca o outline.
    for api_ref, classes in hits.items():
        hits[api_ref] = {real for cls in classes for real in (invokers.get(cls) or {cls})}
    return hits


def read_mapping(path):
    """Classe ofuscada -> classe original, do mapping.txt do R8 (só linhas de classe)."""
    names = {}
    with open(path, encoding="utf-8") as mapping:
        for line in mapping:
            if line[:1] in (" ", "#") or " -> " not in line:
                continue
            original, obfuscated = line.rstrip().rstrip(":").split(" -> ")
            names["L" + obfuscated.replace(".", "/") + ";"] = "L" + original.replace(".", "/") + ";"
    return names


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk")
    parser.add_argument("api_versions")
    parser.add_argument("--min-sdk", type=int, default=21)
    parser.add_argument("--mapping", help="mapping.txt do R8, para mostrar os nomes originais")
    parser.add_argument("--by-api", action="store_true", help="agrupa por API em vez de por classe")
    parser.add_argument("--android", action="store_true", help="inclui APIs android.* (framework)")
    parser.add_argument("--skip", action="append", default=[], metavar="PREFIXO",
                        help="ignora chamadoras com este prefixo, ex.: Landroidx/ (repetível)")
    args = parser.parse_args()

    packages = ("Ljava/", "Ljavax/") + (("Landroid/",) if args.android else ())
    names = read_mapping(args.mapping) if args.mapping else {}

    def is_outline(descriptor):
        return "$$ExternalSyntheticApiModelOutline" in names.get(descriptor, descriptor)

    hits = audit(args.apk, ApiDatabase(args.api_versions), args.min_sdk, packages, is_outline)
    callers = defaultdict(set)
    for api_ref, classes in list(hits.items()):
        kept = {names.get(cls, cls) for cls in classes}
        kept = {cls for cls in kept if not cls.startswith(tuple(args.skip))}
        if kept:
            hits[api_ref] = kept
        else:
            del hits[api_ref]
        for cls in kept:
            callers[cls].add(api_ref)

    print(f"{len(hits)} APIs acima da {args.min_sdk}, chamadas por {len(callers)} classes")
    if args.by_api:
        for (since, label), classes in sorted(hits.items()):
            print(f"API {since:2d}  {label}")
            for cls in sorted(classes):
                print(f"          <- {cls}")
    else:
        for cls in sorted(callers):
            print(cls)
            for since, label in sorted(callers[cls]):
                print(f"    API {since:2d}  {label}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

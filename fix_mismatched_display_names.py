#!/usr/bin/env python3
"""Reescreve a coluna de nome de exibicao quando ela nao bate com o titulo do arquivo.

`catalog_manifest.txt` guarda, para cada linha, o titulo curado na coluna 1 (formato
`system/arquivo|display|capa|popularidade|flag`). Em sistemas de computador de 8-bit
catalogados em TOSEC (zxspectrum, c64, msx, amstradcpc, ...) o nome de arquivo JA e o
titulo do jogo (so falta tirar as tags); quando o processo que preencheu essa coluna
nao achou o jogo certo, ele nao deixou vazio -- gravou o titulo de um jogo MODERNO e de
OUTRA plataforma que so compartilha uma palavra ou substring com o arquivo:
`zxspectrum/MASK (1987)(Erbe)...tap` (jogo de 1987, so "Mask") tinha
"The Legend of Zelda: Majora's Mask" (N64) na coluna de display. Mesmo defeito
encontrado e corrigido no catalogo irmao do RetroBatNew (retrobatnew, mesma familia de
dados) em 2026-09-19/20.

Medido em 2026-09-20 no `catalog_manifest.txt` deste repo: `zxspectrum` (793/10.356),
`c64` (78/324), `msx` (272/928) e `amstradcpc` (1100/4228) tem a mesma assinatura de
defeito -- os numeros de c64/msx/amstradcpc batem byte a byte com os do retrobatnew,
confirmando a mesma origem de dado. Todas as ~800 amostradas a mao entre os quatro
sistemas sao erro do processo de preenchimento, nenhuma curadoria legitima (fora as
excecoes listadas em display_name_keep.txt).

CRITERIO -- por que titulo exato normalizado, e nao similaridade de string. Foi
testado com `difflib.SequenceMatcher` primeiro (no retrobatnew): ate ratio 0.75
("Bomb Jack" vs "Mighty Bomb Jack", "Gauntlet" vs "Gear Gauntlet") toda amostra era
erro, e mesmo ratio 0.92 ainda pegava erro ("Final Fight" vs "Final Fight 3" --
sequencia da mesma familia, jogo diferente). Nao ha limiar que separe os dois lados.
Em vez disso: tira tudo que nao e letra/digito dos dois lados (parenteses, colchetes,
pontuacao, espacos) e exige IGUALDADE. Aceita variacao de pontuacao/hifen/maiuscula
que e o mesmo titulo ("Front-Line" == "Front Line") e rejeita qualquer titulo que
adicione ou troque palavra.

Efeito colateral bom: neste manifest especifico existe TAMBEM um defeito diferente,
de um passo de "limpeza de titulo" anterior (ver catalog_manifest.before_title_clean.txt)
que truncou titulo com ponto no meio pensando que era extensao/versao --
`17.11.1989 (1989)(DoubleSOFT)...tap` tinha display truncado em `17`,
`Atartris v1.01...zip` em `Atartris v1`. A mesma regra de igualdade normalizada pega
esses casos e devolve o titulo completo, porque o criterio nao e "IGDB errou" e sim
"o display nao e o titulo do arquivo".

CUIDADO COM EXTENSAO DUPLA (".atr.zip", ".xex.zip", ".p8.png", ...) -- NAO USADO AQUI
DE PROPOSITO. atari800 tem 8 ".atr.zip" e 2 ".xex.zip": o regex de extensao so tira
a ULTIMA (".zip"), sobra ".atr"/".xex" grudado no titulo derivado, e a linha entraria
como "diferente" por um bug do comparador, nao por erro do dado -- aplicar corromperia
titulo que ja esta certo ("Archon.atr.zip" == "Archon" ficaria "Archon .atr" errado).
zxspectrum, c64, msx e amstradcpc foram conferidos e NAO tem esse padrao (contagem de
nomes com duas extensoes seguidas deu 0 nos quatro). Rodar isto num sistema novo sem
conferir o mesmo padrao primeiro arrisca essa classe de falso positivo.

NAO USAR EM SISTEMAS DE CARTUCHO/CONSOLE (nes/snes/gba/psx/...) SEM AMOSTRAR. Ali o
nome do arquivo No-Intro costuma ser o titulo JAPONES e a coluna de display traz a
renomeacao OFICIAL de lancamento ocidental -- divergencia legitima, nao erro
("Kirby no Pinball (Japan)" -> "Kirby's Pinball Land"). A regra so serve para sistemas
onde o nome de arquivo e o proprio titulo.

O QUE ENTRA NO LUGAR: o titulo derivado do proprio arquivo (extensao e tags fora), a
mesma convencao que ja vale nas linhas onde o display bate com o arquivo.
`MASK (1987)(Erbe)(48K-128K)(ES)(en)[m tzxtools][re-release].tap` vira `MASK`.

O app le `title` incondicionalmente de volta pra linhas ja existentes no banco
(ManifestQuickLoader.kt, `updateManifestFieldsWithTitle`, sem checagem de "e o nome
default?") a cada bump de MANIFEST_SCHEMA_VERSION -- diferente do retrobatnew, aqui
so o dado precisa mudar; nao ha logica de app pra alterar.

Idempotente: uma segunda passada nao muda nada. Grava UTF-8 **sem BOM** e preserva CRLF.

Uso:
  python fix_mismatched_display_names.py --manifest catalog_manifest.txt --system zxspectrum --dry-run
  python fix_mismatched_display_names.py --manifest catalog_manifest.txt --system zxspectrum --system c64 --system msx --system amstradcpc
"""
import argparse, os, re, shutil, sys, time

DISPLAY_COL = 1  # `system/file|display|capa|popularidade|flag`

TAG_RE = re.compile(r"[\(\[][^\)\]]*[\)\]]")
EXT_RE = re.compile(r"\.[A-Za-z0-9]{1,6}$")
NONALNUM_RE = re.compile(r"[^a-z0-9]+")


def base_title(name, strip_ext=False):
    """Titulo sem tags e sem pontuacao/espacos. `''` quando nao sobra nada.

    strip_ext so vale para o NOME DE ARQUIVO. Aplicar o mesmo regex de extensao no
    display faria EXT_RE comer um `.NNNN` final que nao e extensao nenhuma -- version
    string (`4 on a Row v2.0a`) ou data (`17.11.1989`) -- e produzir falso positivo. O
    nome de arquivo sempre tem extensao real; o display, nunca.
    """
    if strip_ext:
        name = EXT_RE.sub("", name)
    return NONALNUM_RE.sub("", TAG_RE.sub(" ", name).lower())


def derived_title(filename):
    """Titulo legivel do arquivo: sem extensao, sem tags, espacos colapsados."""
    name = TAG_RE.sub(" ", EXT_RE.sub("", filename))
    return re.sub(r"\s+", " ", name).strip()


def split_line(line):
    """`system/file|...` -> (system, filename, colunas, cr). None se nao for entrada."""
    cr = ""
    if line.endswith("\r"):
        cr, line = "\r", line[:-1]
    cols = line.split("|")
    if len(cols) <= DISPLAY_COL:
        return None
    i = cols[0].find("/")
    if i < 1:
        return None
    return cols[0][:i], cols[0][i + 1:], cols, cr


def load_keep(path):
    """`system/titulo derivado` -> conjunto (minusculas). Vazio se nao houver arquivo."""
    keep = set()
    if not path or not os.path.isfile(path):
        return keep
    with open(path, encoding="utf-8-sig") as f:
        for raw in f:
            line = raw.strip()
            if line and not line.startswith("#"):
                keep.add(line.lower())
    return keep


def fix_file(path, systems, dry_run, keep):
    with open(path, encoding="utf-8-sig", errors="replace") as f:
        lines = f.read().split("\n")

    fixed = 0
    kept = 0
    examples = []

    for idx, line in enumerate(lines):
        parts = split_line(line)
        if not parts:
            continue
        system, filename, cols, cr = parts
        if system.lower() not in systems:
            continue

        display = cols[DISPLAY_COL].strip()
        if not display:
            continue

        if base_title(filename, strip_ext=True) == base_title(display):
            continue

        if (system + "/" + derived_title(filename)).lower() in keep:
            kept += 1
            continue

        # O pipe e o separador do manifest: um titulo que contenha um partiria a
        # linha em duas colunas. Nenhum nome de arquivo tem, mas o dado e externo --
        # na duvida, esvazia em vez de gravar uma linha malformada.
        novo = derived_title(filename)
        if "|" in novo:
            novo = ""

        if len(examples) < 10:
            examples.append((system, filename, display, novo))

        cols[DISPLAY_COL] = novo
        lines[idx] = "|".join(cols) + cr
        fixed += 1

    if fixed and not dry_run:
        backup = "%s.bak_%s" % (path, time.strftime("%Y%m%d_%H%M%S"))
        shutil.copy2(path, backup)
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write("\n".join(lines))
        print("    backup: %s" % os.path.basename(backup))

    return fixed, kept, examples


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--manifest", action="append", required=True,
                    help="caminho do manifest (repetir para varios arquivos)")
    ap.add_argument("--system", action="append", required=True,
                    help="systemId do manifest a corrigir, ex. zxspectrum (repetir para varios)")
    ap.add_argument("--keep", default=os.path.join(os.path.dirname(os.path.abspath(__file__)),
                                                   "display_name_keep.txt"),
                    help="lista `system/titulo` a preservar (default: display_name_keep.txt ao lado)")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    systems = {s.lower() for s in args.system}
    keep = load_keep(args.keep)
    total = 0
    for path in args.manifest:
        if not os.path.isfile(path):
            print("!! nao encontrado: %s" % path, file=sys.stderr)
            return 2

        fixed, kept, examples = fix_file(path, systems, args.dry_run, keep)
        print("%s" % os.path.basename(path))
        if kept:
            print("    %d linhas preservadas pela lista --keep" % kept)
        if fixed:
            print("    %d linhas corrigidas (sistemas: %s)" % (fixed, ", ".join(sorted(systems))))
            for system, filename, display, novo in examples:
                print("      %s: %r -> %r" % (filename, display, novo))
            if fixed > len(examples):
                print("      (+%d outras)" % (fixed - len(examples)))
        else:
            print("    nada a consertar")
        total += fixed

    print("\n%s: %d linhas afetadas" % ("DRY-RUN" if args.dry_run else "APLICADO", total))
    return 0


if __name__ == "__main__":
    sys.exit(main())

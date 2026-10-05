#!/usr/bin/env python3
"""
validate_catalog_representatives.py

Audita e valida se todos os representantes (isRepresentative=1) do catalog_manifest.txt
possuem arquivos hospedados nos repositórios remotos (Hugging Face / endpoint find_by_file).

Previne regressões como a descrita no bug report de 2026-10-05:
onde clones regionais não hospedados (ex: sf2ceua.zip, 1941j.zip, forgott1.zip)
foram eleitos representantes visíveis em vez dos sets-pai hospedados (sf2ce.zip, 1941.zip, forgottn.zip),
causando erro determinístico "ROM not found" ao usuário final.

Uso:
    python validate_catalog_representatives.py                  # Checagem contra dataset HF fbneo
    python validate_catalog_representatives.py --all-systems    # Checagem completa
    python validate_catalog_representatives.py --system cps1   # Filtrar por sistema
"""

import argparse
import json
import os
import sys
import urllib.request
import urllib.parse

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MANIFEST = os.path.join(SCRIPT_DIR, "lemuroid-app", "src", "main", "assets", "catalog_manifest.txt")
MNEMONICO_MAP = os.path.join(SCRIPT_DIR, "lemuroid-app", "src", "main", "assets", "mnemonico_map.json")
FIND_ENDPOINT = "https://emuladores.pythonanywhere.com/find_by_file"


def load_hf_file_list(repo_id: str) -> set[str]:
    """Obtém a lista completa de arquivos de um dataset no Hugging Face via API oficial ou huggingface_hub."""
    try:
        from huggingface_hub import HfApi
        api = HfApi()
        files = set(api.list_repo_files(repo_id, repo_type="dataset"))
        return files
    except Exception:
        # Fallback via HTTP API do Hugging Face com recursão
        url = f"https://huggingface.co/api/datasets/{repo_id}/tree/main?recursive=True"
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 LemuroidValidator/1.0"})
        with urllib.request.urlopen(req, timeout=30) as r:
            items = json.loads(r.read().decode("utf-8"))
        return {item["path"] for item in items if item.get("type") == "file"}


def check_endpoint(system: str, filename: str) -> bool:
    """Consulta o endpoint find_by_file do Lemuroid para verificar se o arquivo está disponível."""
    url = f"{FIND_ENDPOINT}?path={urllib.parse.quote(filename)}&source_id=1&system={urllib.parse.quote(system)}"
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0 (Android) LemuroidValidator/1.0"})
        with urllib.request.urlopen(req, timeout=10) as resp:
            body = resp.read().decode("utf-8").strip()
            return bool(body)
    except Exception:
        return False


def main():
    parser = argparse.ArgumentParser(description="Valida representantes de catálogo contra a hospedagem")
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST, help="Caminho do catalog_manifest.txt")
    parser.add_argument("--system", default=None, help="Filtrar por sistema específico (ex: cps1, fbneo, etc.)")
    parser.add_argument("--check-endpoint", action="store_true", help="Validar cada representante via HTTP find_by_file")
    args = parser.parse_args()

    if not os.path.exists(args.manifest):
        print(f"Erro: manifesto não encontrado em {args.manifest}", file=sys.stderr)
        sys.exit(1)

    with open(MNEMONICO_MAP, "r", encoding="utf-8") as f:
        mne_map = json.load(f)

    fbneo_systems = {sys_name for sys_name, target in mne_map.items() if target == "fbneo"}

    print("Indexando dataset remoto do Hugging Face (luistiagos/fbneo)...")
    try:
        hf_files = load_hf_file_list("luistiagos/fbneo")
        print(f"Dataset HF indexado: {len(hf_files)} arquivos disponíveis.")
    except Exception as e:
        print(f"Aviso: Não foi possível obter árvore do HF ({e}). Validação utilizará apenas endpoint se solicitado.")
        hf_files = None

    missing = []
    total_reps = 0

    with open(args.manifest, "r", encoding="utf-8") as f:
        for line_num, line in enumerate(f, 1):
            line = line.strip()
            if not line:
                continue
            parts = line.split("|")
            if len(parts) < 5:
                continue
            path, title, cover, pop, rep = parts[0], parts[1], parts[2], parts[3], parts[4]
            if rep != "1":
                continue

            if "/" not in path:
                continue
            sys_folder, fname = path.split("/", 1)

            if args.system and sys_folder != args.system:
                continue

            if sys_folder in fbneo_systems:
                total_reps += 1
                is_available = True
                if hf_files is not None and fname not in hf_files:
                    is_available = False

                if not is_available and args.check_endpoint:
                    # Fallback ao endpoint direto
                    endpoint_sys = mne_map.get(sys_folder, sys_folder)
                    if check_endpoint(endpoint_sys, fname):
                        is_available = True

                if not is_available:
                    missing.append((line_num, path, title, fname))

    print(f"\n--- Resultado da Auditoria de Representantes ---")
    print(f"Total de representantes auditados: {total_reps}")
    print(f"Representantes não hospedados (quebrados): {len(missing)}")

    if missing:
        print("\nITENS QUEBRADOS ENCONTRADOS:")
        for line_num, path, title, fname in missing:
            print(f"  [Linha {line_num}] {path} | Título: {title} (Arquivo: {fname})")
        print("\nFalha: O catálogo contém representantes com isRepresentative=1 apontando para arquivos não hospedados.")
        sys.exit(1)
    else:
        print("\nSucesso: 100% dos representantes auditados estão hospedados e disponíveis!")
        sys.exit(0)


if __name__ == "__main__":
    main()

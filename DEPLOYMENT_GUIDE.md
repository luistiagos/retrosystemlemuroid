# 🚀 Deployment do Backfill Service - Instruções

## Pré-requisitos
✅ Código já está committed no GitHub  
✅ Arquivos `.fetch_missing_medias_csv.py` e `flask_app.py` já estão no repositório

## Passos para Deploy no PythonAnywhere

### 1️⃣ Acesse o console web do PythonAnywhere
```
https://www.pythonanywhere.com/user/emuladores/webapps/
```

### 2️⃣ Clique na web app
```
emuladores.pythonanywhere.com
```

### 3️⃣ Abra o Web Console
- Scroll até "Code"
- Clique em "Go to Web Console"

### 4️⃣ No console, execute:
```bash
cd /home/emuladores/romsrepository
git pull origin master
```

### 5️⃣ Volte para a página da web app e clique em "Reload"
- O botão verde "Reload emuladores.pythonanywhere.com" fica no topo

### 6️⃣ Aguarde ~10 segundos

## ✅ Verificar Deployment

Após completar os passos acima, execute:

```powershell
cd E:\projects\lemuroid\Lemuroid
python test_backfill_endpoint.py
```

Isto testará se o endpoint está funcionando corretamente.

## 🎯 Próximo Passo

Após confirmação que o endpoint está live, execute o backfill automático:

```powershell
cd E:\projects\lemuroid\Lemuroid
python backfill_cover2d_auto.py
```

Este script:
- ✅ Faz upload do CSV de itens faltantes
- ✅ Regenera o manifesto automaticamente
- ✅ Extrai novos itens faltantes
- ✅ Repete até maximizar cobertura
- ✅ Para quando < 100 itens faltam

---

**Tiempo estimado:** ~5 minutos para a primeira iteração  
**Resultado esperado:** Cover2d passar de 61% (13.635/22.301) para ~85%+ na primeira rodada


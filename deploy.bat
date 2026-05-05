@echo off
setlocal
echo ========================================================
echo Iniciando build e deploy no dispositivo conectado...
echo ========================================================

:: Vai para o diretório raiz do projeto (onde está o gradlew.bat)
cd /d "%~dp0"

:: Executa a task do gradle para fazer o build e instalar o app (variante Free Bundle Debug)
call gradlew.bat installFreeBundleDebug

if %errorlevel% neq 0 (
    echo.
    echo [ERRO] Falha durante o build ou instalacao. Verifique se o dispositivo esta conectado e com a depuracao USB ativada.
    exit /b %errorlevel%
)

echo.
echo [SUCESSO] App instalado com sucesso no dispositivo!
echo.

:: Opcional: Iniciar o aplicativo apos o deploy
set /p START_APP="Deseja iniciar o aplicativo agora? (s/n): "
if /i "%START_APP%"=="s" (
    echo Iniciando o Lemuroid...
    adb shell monkey -p com.swordfish.lemuroid.debug -c android.intent.category.LAUNCHER 1 >nul 2>&1
    if %errorlevel% neq 0 (
        echo Nao foi possivel iniciar o app automaticamente. Certifique-se de que o ADB esta no PATH.
    ) else (
        echo Aplicativo iniciado!
    )
)

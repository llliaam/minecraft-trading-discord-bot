@echo off
REM ============================================================================
REM  start.bat — Pola 3: server MC proses utama, bot Discord di background.
REM  Kamu ketik "stop" di server (atau server berhenti) -> bot ikut dimatikan.
REM
REM  CARA PAKAI:
REM   1. Isi 3 variabel di bawah sesuai setup-mu.
REM   2. Taruh file ini di mana saja, klik dua kali (atau jalankan dari cmd).
REM ============================================================================

REM === KONFIGURASI (WAJIB DIISI) ===============================================
REM Folder proyek bot (tempat package.json berada).
set "BOT_DIR=D:\Ngoding\bot discord"

REM Folder server Minecraft (tempat file .jar server berada).
set "MC_DIR=D:\path\ke\server-minecraft"

REM Perintah menjalankan server MC. Sesuaikan nama jar & alokasi RAM (-Xmx).
set "MC_CMD=java -Xmx4G -Xms2G -jar fabric-server-launch.jar nogui"
REM ============================================================================

echo [start.bat] Menyalakan bot Discord di background...
cd /d "%BOT_DIR%"
if not exist "package.json" (
  echo [start.bat] ERROR: package.json tidak ditemukan di "%BOT_DIR%". Cek BOT_DIR.
  pause
  exit /b 1
)

REM Jalankan bot di jendela terpisah berjudul "MC-Bot" supaya gampang dimatikan.
start "MC-Bot" /min cmd /c "node src/index.js"

echo [start.bat] Menyalakan server Minecraft (proses utama)...
cd /d "%MC_DIR%"
if not exist "*.jar" (
  echo [start.bat] PERINGATAN: tidak ada file .jar di "%MC_DIR%". Cek MC_DIR.
)

REM Baris ini MEMBLOKIR sampai server MC berhenti (stop / crash / window ditutup).
%MC_CMD%

REM ↓↓↓ Dijalankan SETELAH server MC berhenti ↓↓↓
echo [start.bat] Server MC berhenti. Mematikan bot Discord...
REM Tutup jendela bot berjudul "MC-Bot" beserta proses node di dalamnya.
taskkill /fi "WINDOWTITLE eq MC-Bot*" /t /f >nul 2>&1

echo [start.bat] Selesai. Bot & server sudah mati.

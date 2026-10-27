#!/usr/bin/env bash
# Relatório só de leitura de uma TV, para decidir como adaptar o Free My TV a ela (ver "Adaptando para outra TV" no
# CLAUDE.md). Não muda nada na TV. Seguro de colar num chat ou num issue: IPs e MACs saem mascarados e não lê SSID,
# serial, contas nem chaves.
#
#   tools/diagnose.sh [IP]     # sem IP, usa o único aparelho que o adb já enxerga
#
# Imprime na tela e salva em out/diagnose-<modelo>.txt.
set -uo pipefail
cd "$(dirname "$0")/.."

export MSYS_NO_PATHCONV=1   # no Git Bash, senão os caminhos da TV viram C:/Program Files/Git/...

ADB="${ADB:-adb}"
if ! command -v "$ADB" >/dev/null 2>&1; then
  # Windows: o winget instala o adb fora do PATH do Git Bash
  ADB="$(ls "$LOCALAPPDATA"/Microsoft/WinGet/Packages/Google.PlatformTools_*/platform-tools/adb.exe 2>/dev/null | head -1)"
fi
if [ -z "$ADB" ] || ! command -v "$ADB" >/dev/null 2>&1; then
  echo "adb não encontrado. Instale as platform-tools do Android (veja o README)." >&2
  exit 1
fi

if [ $# -ge 1 ]; then
  SERIAL="$1:5555"
  "$ADB" connect "$SERIAL" >/dev/null 2>&1 || true
else
  mapfile -t found < <("$ADB" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')
  if [ "${#found[@]}" -eq 0 ]; then
    echo "Nenhum aparelho no adb. Rode: tools/diagnose.sh <IP da TV>" >&2
    exit 1
  elif [ "${#found[@]}" -gt 1 ]; then
    echo "Mais de um aparelho no adb. Rode: tools/diagnose.sh <IP da TV>" >&2
    exit 1
  fi
  SERIAL="${found[0]}"
fi

if ! "$ADB" -s "$SERIAL" get-state >/dev/null 2>&1; then
  echo "Não consegui falar com $SERIAL. A TV pergunta 'Permitir depuração USB?': aceite e marque 'Sempre permitir'." >&2
  exit 1
fi

# Roda um comando na TV, sem o \r do adb e sem ler o stdin do terminal
S() { "$ADB" -s "$SERIAL" shell "$@" </dev/null 2>&1 | tr -d '\r'; }
prop() { S getprop "$1"; }
sec() { printf '\n== %s ==\n' "$1"; }

report() {
  echo "Free My TV: diagnóstico de TV ($(date +%Y-%m-%d))"

  sec "Aparelho"
  for p in ro.product.brand ro.product.manufacturer ro.product.model ro.product.device ro.build.version.release \
           ro.build.version.sdk ro.product.cpu.abi ro.build.fingerprint; do
    echo "$p: $(prop "$p")"
  done

  sec "Tela e memória"
  S wm size
  S wm density
  for p in ro.config.low_ram ro.surface_flinger.max_graphics_width ro.surface_flinger.max_graphics_height; do
    echo "$p: $(prop "$p")"
  done
  S grep MemTotal /proc/meminfo

  sec "Tela inicial"
  S cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME | tail -1
  role="$(S cmd role get-role-holders android.app.role.HOME | head -1)"
  case "$role" in *"Can't find service"*|"") role="sem papéis (Android 9 ou anterior)" ;; esac
  echo "papel Home: $role"

  sec "Tela de descanso e acessibilidade"
  echo "screensaver_components: $(S settings get secure screensaver_components)"
  echo "screensaver_default_component: $(S settings get secure screensaver_default_component)"
  echo "enabled_accessibility_services: $(S settings get secure enabled_accessibility_services)"

  sec "Pacotes que decidem como adaptar (instalado = sim)"
  installed="$(S pm list packages -u)"
  for p in io.freemytv com.google.android.tungsten.setupwraith fusion.android.tv.demo \
           com.jm.jmlauncher com.htc.htcsettings com.master.accessibility com.sony.dtv.sonyvendorservice; do
    if grep -q "package:$p\$" <<<"$installed"; then echo "$p: sim"; else echo "$p: não"; fi
  done
  echo "tclappboot (qualquer pacote com 'appboot'): $(grep -i appboot <<<"$installed" | sed 's/^package://' | tr '
' ' ')"
  S dumpsys package io.freemytv | grep -m1 versionName

  sec "Apps que salvaram redes Wi-Fi (só o pacote; o Original não consegue restaurar se forem desligados)"
  S dumpsys wifi | grep -oE '(cname|lname)=[A-Za-z0-9_.]+' | sort | uniq -c

  sec "Apps de sistema"
  S pm list packages -s | sed 's/^package://' | sort

  sec "Apps instalados pela pessoa"
  S pm list packages -3 | sed 's/^package://' | sort

  echo
  echo "Fim. IPs e MACs foram mascarados; não há SSID, serial, contas nem chaves neste relatório."
}

model="$(prop ro.product.model | tr -c 'A-Za-z0-9\n' '-' | sed 's/-*$//')"
mkdir -p out
file="out/diagnose-${model:-tv}.txt"

report | sed -E \
  -e 's/([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}/xx:xx:xx:xx:xx:xx/g' \
  -e 's/\b([0-9]{1,3}\.){3}[0-9]{1,3}\b/x.x.x.x/g' | tee "$file"

echo
echo "Salvo em $file"

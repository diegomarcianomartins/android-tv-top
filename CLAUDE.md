# Free My TV: notas para o Claude

Uma tela inicial para Google TV e Android TV que substitui a de fábrica, mais um app de configurações que alterna entre
o Free My TV e a tela inicial original da TV. Um app Android simples em Java, compilado sem Gradle pelo `build.sh`.
TVs LG (webOS) e Samsung (Tizen) ganham a mesma tela inicial como app web: `web/`, empacotado por `webos/` e `tizen/`
(veja abaixo).
O site (freemytv.io) fica em `docs/`: HTML, CSS e JS estáticos, sem etapa de build. Um push na `main` que mexa nele
publica no GitHub Pages (`.github/workflows/pages.yml`). `docs/index.html` (inglês) e `docs/pt/index.html`
(português do Brasil) são a mesma página em dois idiomas, então altere as duas; quem visita do Brasil cai sozinho na
portuguesa, e a escolha PT/EN no menu fica lembrada.
Antes de dar push, rode `tools/check.sh`: o build do Google TV, `translate.py --check`, os pacotes LG e Samsung e o ES5
em `web/` (sintaxe e as chamadas mais novas que TVs antigas não têm). Todo pull request roda isso também
(`.github/workflows/check.yml`).

A pessoa que você está ajudando pode não ser técnica. Seja breve e simpático, diga o que vai fazer na TV dela antes de
fazer, e deixe que ela mesma aperte os botões na TV.

## Instalando para alguém

1. **Pergunte** o IP da TV e a cidade, se ela não disse.
2. **Ferramentas:** um JDK 17+, as ferramentas de linha de comando do Android SDK (build-tools 34+, platform android-34)
   e o adb. Veja o que já existe (`java -version`, `$ANDROID_HOME`, `adb version`) e instale só o que falta.
   - macOS: `brew install --cask temurin android-commandlinetools android-platform-tools`, depois
     `sdkmanager "build-tools;34.0.0" "platforms;android-34"`.
   - Linux: OpenJDK 17, as ferramentas de linha de comando do Android e o adb pelo gerenciador de pacotes, depois a
     mesma linha do `sdkmanager`.
   - Windows: Git Bash (o que o Claude Code usa lá; o `build.sh` funciona nele) ou WSL, depois como no Linux. Pelo
     `winget`: `EclipseAdoptium.Temurin.17.JDK` e `Google.PlatformTools`; as ferramentas de linha de comando vêm de
     dl.google.com (`commandlinetools-win-*_latest.zip`, em `%LOCALAPPDATA%\Android\Sdk\cmdline-tools\latest`).
3. **Configurações:** rode `./build.sh` uma vez; ele cria o `config.properties`. Preencha `latitude` e `longitude`
   (graus decimais) e `city` com a cidade da pessoa, `tmdb_region` (país, duas letras), `show_crypto`, `currency` e
   `temperature` de acordo.
4. **A chave do TMDB é da pessoa e é secreta.** Peça que ela crie uma (grátis: themoviedb.org → Settings → API) e cole
   depois de `tmdb_key=` no `config.properties` ela mesma. Não peça a chave no chat, nunca mostre, nunca faça commit
   dela. Se ela colar no chat mesmo assim, coloque só no `config.properties`.
   Sem a chave tudo funciona, menos as sugestões e as fotos da tela de descanso.
   O mesmo vale para a `watchmode_key` opcional (grátis, watchmode.com; faz as sugestões abrirem a página do título):
   da pessoa, secreta, só no `config.properties`. E para os endereços das câmeras (`dvr_cameras`), que levam a senha do
   DVR: quem preenche é a pessoa; leia o arquivo sem mostrar a senha.
5. **Build:** `./build.sh` → `FreeMyTV.apk`. Guarde o `freemytv.keystore`: as atualizações precisam ser assinadas com a
   mesma chave.
6. **Instalação:** `adb connect <IP>:5555`. A TV pergunta *Permitir depuração USB?*: diga para aceitar e marcar
   *Sempre permitir*. Depois `adb install -r FreeMyTV.apk`. Na TCL, também
   `adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"`
   (alguns firmwares chamam a operação de `APP_AUTO_START`; isso tenta as duas):
   o `TclAppBoot` da TCL bloqueia tarefas em segundo plano por padrão, então a troca (e o watchdog) nunca rodam. O log
   mostra `forbid bind service ... ModeJob`. Alguns firmwares TCL não têm nenhuma das duas (um Google TV MediaTek de
   2024, Android 14, não tem `TclAppBoot`): os dois nomes respondem `Unknown operation string`, e não há nada a liberar.
   No Mac, o `adb connect` pode falhar com `No route to host` mesmo com a TV respondendo (`nc -z <IP> 5555` conecta):
   a privacidade de Rede Local do macOS bloqueia o servidor do adb que o adb inicia em segundo plano. Inicie-o no mesmo
   terminal (`adb kill-server; adb nodaemon server &`), ou libere o app de terminal em Ajustes do Sistema → Privacidade
   e Segurança → Rede Local.
7. **Explique o que a troca faz** antes que ela aconteça: a tela inicial e a tela de descanso mudam, uma lista curta de
   apps de propaganda é desligada (nunca desinstalada), e *Original* traz tudo de volta. Na TCL, os botões Netflix,
   YouTube e Prime do controle continuam funcionando por um pequeno serviço de acessibilidade que só lê esses botões
   (*Free My TV* → *Remote Buttons* escolhe o que cada um abre); *Original* desliga o serviço.
8. **A troca é feita na TV:** *Free My TV* → *Home Screen* → *Free My TV*. O app usa o ADB da própria TV em 127.0.0.1
   com sua própria chave, então a TV pergunta mais uma vez se permite a depuração. Não troque de modo pelo computador.
   Se o Wi-Fi da TV foi salvo na primeira configuração, a troca para antes de mudar qualquer coisa e pede que a pessoa
   esqueça a rede e conecte de novo em Configurações (Rede e Internet); aí a troca vai até o fim. Redes salvas fora de
   alcance não aparecem em Configurações: ache as que têm `cname=com.google.android.tungsten.setupwraith` em
   `adb shell dumpsys wifi` (cada bloco começa com o seu `ID:`) e, com o OK da pessoa, remova com
   `adb shell cmd wifi forget-network <id>`.
9. **Confira:** `adb logcat -s FreeMyTV` mostra o que a troca fez.

## Instalando numa TV LG (webOS)

Na LG é um app, instalado pelo Modo Desenvolvedor da LG, e não muda mais nada na TV: a tela inicial da LG continua, e o
Free My TV abre com um toque longo numa tecla numérica.

1. **Pergunte** o IP da TV e a cidade, se ela não disse. Ferramentas: Python 3 e OpenSSH (`ssh`, `ssh-keygen`), que já
   vêm no macOS, Linux e Windows 10+.
2. **Na TV** (a pessoa faz): LG Content Store → *Developer Mode* → entrar com uma conta grátis de desenvolvedor LG
   (webostv.developer.lge.com) → *Dev Mode Status* ligado (a TV reinicia) → *Key Server* ligado. Aparecem o IP e uma
   senha (passphrase).
3. **Configurações:** o mesmo `config.properties` do Android (o primeiro `python3 webos/build.py` cria o arquivo). As
   regras da chave do TMDB acima valem aqui.
4. **Build:** `python3 webos/build.py` tem que terminar com `Built FreeMyTV-webos.ipk`.
5. **Instalação:** a primeira vez precisa da senha da TV, digitada pela pessoa: peça que ela rode
   `python3 webos/install.py <IP>` no terminal dela (o seu não consegue responder ao pedido, e a senha não vai no chat).
   Ele guarda a chave liberada em `~/.ssh/freemytv_webos_<IP>`, então as atualizações seguintes rodam do seu terminal
   com o mesmo comando.
6. **Explique:** com o Free My TV aberto, segurar uma tecla numérica prende o app nela. O Modo Desenvolvedor dura uns
   40 dias; *EXTEND* naquele app renova (se acabar, a TV remove os apps de desenvolvedor, e instalar de novo traz de
   volta). `--uninstall` remove o Free My TV.

## Instalando numa TV Samsung (Tizen)

Na Samsung também é um app (2017 em diante, Tizen 3+), instalado pelo Modo Desenvolvedor da Samsung; a tela inicial da
Samsung continua. Dois caminhos:

- **A versão publicada, sem ferramentas:** o TizenBrew Installer (github.com/reisxd/TizenBrewInstaller) instala a
  última release do GitHub quando a pessoa digita `simbasjungles/free-my-tv` e, em TVs 2023+, assina com a conta
  Samsung dela. Essa versão não tem chave do TMDB (sem sugestões) e pega a cidade pelo fuso horário da TV. Guie a
  pessoa pelos passos do README.
- **Uma versão própria** (com a cidade e a chave do TMDB dela), abaixo.

1. **Pergunte** o IP da TV e a cidade, se ela não disse.
2. **Na TV** (a pessoa faz): *Apps* → digitar `1 2 3 4 5` no controle → *Developer mode* ligado, *Host PC IP* = o IP
   deste computador → reiniciar a TV.
3. **Ferramentas:** o comando `tizen` da Samsung para assinar (a CLI do Tizen Studio, ou o plugin da Samsung para
   Claude Code, `tizen-sdk-skills`, de github.com/Samsung/tizen-agent-skills, que instala a CLI e cria certificados
   Samsung), e `python3 tizen/install.py` para instalar, que fala SDB por conta própria. O `tizen` roda em Java; o
   `sdb` da Samsung, o novo `tz` e o instalador do Tizen Studio (com o próprio JDK) são binários Intel no macOS. Em
   Apple Silicon sem Rosetta, pegue a CLI do repositório de pacotes da Samsung (download.tizen.org/sdk/tizenstudio/official,
   `pkg_list_macos-64`; confira o SHA256 de cada zip ali): descompacte o `data/` de `new-common-cli`,
   `certificate-generator`, `sdk-utils` e `sdk-utils-core` em `~/tizen-studio`, depois escreva `~/tizen-studio/sdk.info`
   com três linhas, `TIZEN_SDK_INSTALLED_PATH=<essa pasta>`, `TIZEN_SDK_DATA_PATH=<home>/tizen-studio-data` e
   `JDK_PATH=<a pasta .jdk de um JDK 17 nativo>` (Homebrew: `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk`). Esse
   `tizen` assina de forma nativa.
4. **Certificado, uma vez:** TVs 2023+ (Tizen 7+) precisam de um certificado Samsung, feito com a conta Samsung da
   pessoa para esta TV (o DUID dela): o perfil Samsung do plugin abre o navegador para ela entrar, ou o Certificate
   Manager do Tizen Studio faz isso. TVs 2017-2022 aceitam um certificado Tizen (`tizen certificate`, depois
   `tizen security-profiles add` e `tizen cli-config "profiles.path=<home>/tizen-studio-data/profile/profiles.xml"`;
   no macOS as senhas vão para o Keychain de login). As senhas do certificado são da pessoa: ela digita no terminal
   dela, nunca no chat.
5. **Configurações:** o mesmo `config.properties`; as regras da chave do TMDB acima valem aqui.
6. **Build e assinatura:** `python3 tizen/build.py --sign <perfil>` tem que terminar com `Built FreeMyTV-tizen.wgt (...,
   signed with "<perfil>")`.
7. **Instalação:** `python3 tizen/install.py <IP>`: envia o app por SDB (porta 26101), instala com o `0 vd_appinstall`
   da TV e abre com `0 was_execute` (testado em TVs 2017 Tizen 3.0 e 2019 Tizen 5.0); só informa sucesso quando a TV
   diz `install completed` (`cmd_ret:0` aparece também depois de falhas). `--uninstall` remove; o sdbd de algumas TVs
   não responde a isso, e aí a remoção é feita na TV (Apps, Configurações, Free My TV, Excluir).
   Com as ferramentas da Samsung: `sdb connect <IP>`, `tizen install -n FreeMyTV-tizen.wgt -t <serial do sdb devices>`
   e `tizen run -p FreeMyTVio.FreeMyTV -t <serial>`. As atualizações precisam do mesmo certificado: a TV recusa uma
   assinada por outro (desinstale antes). O sdbd de uma TV de varejo só recebe arquivos (`filesync_support:push`):
   nada pode ser puxado dela. Para olhar dentro do app rodando, `0 debug FreeMyTVio.FreeMyTV 300` por SDB abre o app
   com uma porta de Web Inspector (Runtime.evaluate funciona no Tizen 3; capturas de tela não).
8. **Explique:** *Autorun Last App* nas configurações da TV (em Start Screen Options; o lugar muda conforme o ano) faz
   a TV ligar direto no Free My TV.

## Releases

O TizenBrew Installer pega o primeiro `.wgt` da última release do GitHub, então toda release leva o
`FreeMyTV-tizen.wgt`: compilado com `--generic` (sem config.properties: sem chave, cidade pelo fuso horário) e assinado
com um certificado Tizen (`--sign`), que TVs 2017-2022 instalam como está; em TVs 2023+ o instalador assina de novo
com o certificado Samsung da pessoa. Assine toda release com o mesmo certificado de autor: TVs mais antigas recusam
uma atualização assinada por outro. `python3 webos/build.py --generic` gera o `.ipk` da LG que vai junto.

## Adaptando para outra TV

Leia primeiro, mude depois. `tools/diagnose.sh <IP>` junta tudo isso num relatório só de leitura (`out/diagnose-<modelo>.txt`,
com IPs e MACs mascarados, seguro de colar num chat ou issue). Os comandos só de leitura que mostram o que a TV tem:

```bash
adb shell getprop ro.product.brand; adb shell getprop ro.product.model; adb shell getprop ro.build.version.release
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd role get-role-holders android.app.role.HOME
adb shell settings get secure screensaver_components; adb shell settings get secure screensaver_default_component
adb shell pm list packages -s     # apps de sistema (-3 para os que a pessoa instalou)
```

Antes de mudar qualquer coisa numa TV desconhecida, faça um backup do estado dela: `getprop`, `pm list packages -f -u`
(e `-e`, `-d`, `-3`), `settings list global|secure|system`, a tela inicial atual (`cmd package resolve-activity`), e
os APKs dos apps do fabricante (`pm path` + `adb pull`), mais um script que restaure tudo. Guarde fora do git
(`.git/info/exclude`).

Depois, no código:

- `HomeMode.DEBLOAT`: adicione os apps de propaganda, demonstração, modo loja e telemetria da marca, um comentário por
  linha. Só esses.
- `HomeMode.PROTECTED`: adicione as peças de sistema da marca: imagem, som, entradas e HDMI-CEC, o sintonizador e a TV
  ao vivo, pareamento do controle e Bluetooth, configuração inicial, atualizações, interface do sistema. Na dúvida,
  proteja. `mayTurnOff` também recusa as peças do próprio Android (`CORE`: com o controlador de permissões ou o
  instalador de pacotes desligados, uma TV pode não ligar mais) e menus de fábrica (`FACTORY`), e `neededByTv` mantém
  ligado, na própria TV, tudo que pede `TV_INPUT_HARDWARE` (a camada abaixo do HDMI e do sintonizador: desligada, as
  entradas mostram "Sem sinal") ou recebe a tecla Input do controle (`fusion.android.tv.demo` faz isso em várias
  placas MediaTek).
- `HomeApps.SHELF`, `LIVE_TV_APPS` e `HIDDEN`: os apps da barra, o app de TV ao vivo da marca, apps a esconder de Todos
  os Apps. `HomeApps.UNLISTED`: telas de configuração sem ícone, que só a tela inicial de fábrica abria (o ajuste de
  trapézio dos projetores Amlogic aparece em Todos os Apps como "Projeção").
- Build, instale, peça que a pessoa faça a troca na TV, leia `adb logcat -s FreeMyTV`, depois peça que ela volte para
  *Original* uma vez e confirme que tudo voltou.
- **Reinicie a TV pelo menos uma vez** depois da troca e confira que a tela inicial aparece sozinha.

LG e Samsung: o app web acima. Outras TVs que não rodam Google TV nem Android TV: o caminho é um aparelho Google TV no
HDMI (Chromecast com Google TV, Google TV Streamer, onn 4K, NVIDIA Shield). Configure o Free My TV no aparelho.

**Desinstalado enquanto estava no modo Free My TV?** Os apps que ele desligou continuam desligados. Traga de volta com
`adb shell pm enable <pacote>` (a lista `DEBLOAT` e a tela inicial de fábrica), depois
`adb shell cmd package set-home-activity <activity da tela inicial de fábrica>` e
`adb shell settings put secure screensaver_components <tela de descanso de fábrica>`.

### Projetores Amlogic genéricos (launcher HTC/JM, t950s, Android 13)

Projetores baratos, com a marca "Google" falsa nas propriedades, 1 GB de RAM, interface em 1280×720, SELinux
desligado e root de fábrica (`SuperUser`). O que se aprendeu:

- **O boot passa por um app do fabricante** (`com.jm.jmlauncher`, categoria `HOME.JM`, preparado para direct boot) que
  procura a tela inicial uma vez só, cedo, enquanto o usuário ainda está bloqueado: apps que não são direct-boot-aware
  não aparecem, ele não acha nenhuma e a animação de boot fica na tela para sempre. Por isso o `HomeReceiver` escuta
  `LOCKED_BOOT_COMPLETED` (é direct-boot-aware só para isso, sem tocar em arquivos) e agenda `ModeJob.HOME`, que roda
  `HomeMode.showHomeNow`: se a animação ainda estiver de pé (ou o JmLauncher na frente), pede a tela inicial por ADB.
  A tela inicial aparece em ~50 s depois de ligar.
- **`com.htc.htcsettings` salva o Wi-Fi** (`lname` em `dumpsys wifi`) e fica protegido. **`com.master.accessibility`
  é o mouse do controle** (apps de celular como Netflix e Prime precisam dele): também protegido.
- A Netflix nunca funciona nesses aparelhos (não são certificados: erro -1001). Não há como contornar sem burlar o
  DRM, e isso não se faz.
- O chip esquenta: com 6 câmeras ao vivo chega a 90-99 °C. O cartão de sistema mostra a temperatura.
- **Botões do controle:** o `com.master.accessibility` (o mouse) apaga qualquer serviço de acessibilidade em ~1 s
  (o `dumpsys settings` mostra `pkg:com.master.accessibility value:{null}`), então o `RemoteKeyService` não fica de
  pé. O receptor infravermelho (`ir_keypad`, `/dev/input/event0`) é legível por qualquer app nesse aparelho: o
  `RawKeys` (serviço em primeiro plano no `:keys`, só no modo Free My TV) lê os eventos de 16 bytes direto dele.
  Não segura a tecla: o projetor ainda faz o que já fazia (Espelhamento F10 abre o Miracast). O Menu não tem
  conflito. Mouse = `MOUSE` (305), Tela = F11. O `getevent` pelo ADB guarda a saída em lote: não serve para ler ao
  vivo.

### TCL com Android TV 9 (Realtek, "UnionTV", 1,4 GB)

- Tela inicial clássica (`tvlauncher`), sem `TclAppBoot`: `AUTO_START` e `APP_AUTO_START` respondem
  `Unknown operation string`, e não há nada a liberar.
- O Wi-Fi da TV é lento (~85 KB/s, ping de até 400 ms) e ela já travou copiando APKs: copie um por vez, com pausas.
  No Git Bash do Windows, use `MSYS_NO_PATHCONV=1` (senão os caminhos da TV viram `C:/Program Files/Git/...`) e tire
  o `\r` da saída do `adb shell`. Os APKs em `/vendor` não saem por `adb pull`; ficam numa partição que não se apaga.
- Sem a tela `BLUETOOTH_SETTINGS`: o botão Bluetooth abre `CONNECT_INPUT` (Adicionar acessório).
- SELinux ligado: o app não lê `/proc/stat` nem a temperatura. O `SystemStats` lê o `/proc/stat` pelo ADB da própria TV
  (só no modo Free My TV, sem pedir permissão de novo); a temperatura nem o shell lê.
- Sem decodificador H.265: a tela cheia das câmeras cai do stream principal para o substream.
- Poucos decodificadores de vídeo (2 na C06). Uma câmera sem decodificador de hardware livre fica no substream e
  decodifica pelo processador, e na tentativa seguinte tenta o hardware de novo (nunca o stream principal de 2K em
  H.265 pelo processador: tomou a memória da TV de 1 GB inteira). Na janela flutuante, o hardware primeiro: na C06 o
  decodificador pelo processador trava a cada ~6 s ("queue exceeded timeout"). O scrcpy também ocupa o hardware de
  vídeo (o codificador): feche-o antes de assistir vídeos nessas TVs.
- A C06 (940 MB) travou com 4 câmeras, a janela flutuante e o YouTube juntos: memória no limite. Nela, menos câmeras
  na tela inicial (ou "Desativar") e o botão de câmeras.
- **Controles inteiros** (`Remotes`): na C06 e no projetor, todos os botões menos os essenciais (ligar, OK, setas,
  voltar, início, volume, mudo) entram em Botões do Controle, com um desenho do controle que acende o botão da linha.
  Códigos da C06: Marcador 4090, Google 4048, Configurações 4077, TV ao vivo 4001, Entrada TV_INPUT, Menu, P+/P−
  (CHANNEL_UP/DOWN), Camadas 4139, cores 4056, Apps 4137. O registro da C06 é pequeno e se perde em minutos: leia as
  teclas ao vivo (`logcat -s TclPhoneWindowManager:D`). Os botões de app que o Free My TV já segura não aparecem ali,
  só no log dele ("remote key").

## O código

- Java 8 de nível de linguagem, minSdk 26 (Android 8), targetSdk 33. Sem Gradle, sem bibliotecas: só o Android SDK.
  Android 9 e anteriores não têm RenderNode (`Recording` o envolve, com um Picture ali) nem `cmd role`. No Android 8 a
  11 (`Ui.LEGACY`) a tela inicial pula as camadas de GPU, desenha as sombras borradas a partir de bitmaps em cache
  (`Shadows`) e arredonda imagens com um shader em vez de clipPath: as GPUs desses aparelhos (a Mali-450 de um Mi Box 3)
  engasgavam com isso.
  O Android 11 roda a tela de descanso como `ScreensaverAlone`, no processo principal (a DreamActivity dele precisa do
  dream no processo padrão do app, sem uma activity de home); `res/values-v30/bools.xml` escolhe isso, todas as outras
  versões ficam com `Screensaver` em `:home`. Mantenha as duas em sintonia.
  Para conferir APIs mais novas, compile contra `platforms/android-26/android.jar`: só AGSL (`RuntimeShader`, protegido
  por um try/catch), `BitmapShader.setFilterMode` (protegido), `Recording.Node` e `Ui.Weighted` (o peso de fonte do
  Android 9) devem aparecer.
- A tela inicial é desenhada à mão num único Canvas (`HomeView`); o vidro e o céu são shaders AGSL (`Shaders`, Android
  13+, com alternativa para 8 a 12). Mantenha em 60 fps: nada de criar objetos no `onDraw`, nada de trabalho na thread
  da interface.
- Os botões redondos do topo (HDMI, Wi-Fi, Bluetooth, Casa inteligente, Configurações; Configurações → Botões do Topo)
  formam uma coluna na borda direita (`layoutLight`, `L_GUTTER`): o conteúdo, as cotações e as câmeras recuam
  `contentRight()` para a esquerda dela, o nome do botão em foco aparece à esquerda dele, e a camada `status` tem o
  tamanho da coluna (maior que isso ela corta os botões de baixo). Da prateleira, dos cartões e das câmeras, "direita"
  no último item entra na coluna; nela, ↑↓ andam e "esquerda" volta ao conteúdo.
- Algumas TVs desenham a interface em 1280×720 num painel 1080p (os modelos TCL de 1 GB:
  `ro.surface_flinger.max_graphics_width` e `_height`, `ro.config.low_ram`). Ali cada pixel e cada MB contam: o
  `Backdrop` monta suas imagens no tamanho da interface (e as sugestões usam o w1280 do TMDB), e os logos da barra têm
  uma cópia no tamanho em repouso e outra no tamanho com foco, para que os dois sejam desenhados pixel a pixel.
- O `HomeMode` roda toda troca como uma tarefa em segundo plano (`ModeJob`) no processo principal; a interface fica no
  processo `:home`. O `Watchdog` volta para a tela inicial de fábrica depois de 3 travamentos em 5 minutos.
  O arquivo de preferências `settings` (`Prefs`) é lido pelos dois processos, e o principal guarda uma cópia em cache
  que pode sobrescrever o que o `:home` gravou: escolhas que só a tela inicial usa vão em `home-ui`.
- **Foto de fundo própria:** um arquivo em `Android/data/io.freemytv/files/background.jpg` (envie com `adb push`, de
  preferência já reduzido para o tamanho da interface) substitui as fotos das sugestões (`Recs.wallpaper`,
  `Backdrop.wallpaper`). A foto aparece nítida sob um véu, e a coluna da esquerda vira três cartões de vidro
  (`HomeView.drawColumn`): relógio, clima (sai da gaveta) e sistema (`SystemStats`: memória, processador pelo
  `/proc/stat` quando legível, temperatura, armazenamento e o sinal do Wi-Fi). Fotos pessoais nunca vão para o git.
- **Câmeras de DVR** (`dvr_cameras`, até 6): `CameraStream` é um cliente RTSP próprio (RTP sobre TCP, Digest, H.264 e
  H.265) que entrega cada quadro direto ao `MediaCodec`: o `MediaPlayer` do Android trata RTSP como filme e pausa
  para encher o buffer o tempo todo. `Cameras` é o mosaico (2×2 ou 3×2, com a escolha 4/6 em `home-ui`), focável pelo
  controle; OK abre `CameraActivity` em tela cheia, no stream principal, com ←/→ para trocar. Uma câmera que não
  responde vira um quadro escuro e só é tentada de novo em 5 minutos; um substream que não responde cai para o stream
  principal. As câmeras esperam 150 s desde o boot para começar.
  Em cima do mosaico: 4 CAM, 6 CAM, SLIDE e DESLIGADO. O SLIDE mostra uma câmera por vez no espaço todo (um
  decodificador só), 10 s contados de quando a imagem aparece; pula na hora a câmera que desistiu (`onGaveUp`: nenhum
  stream deu imagem) e depois de 12 s a que demora.
  Tela de Descanso (no menu principal, ← →): Free My TV, Câmeras (`CameraDream`: o mesmo SLIDE em tela cheia, no
  stream principal) ou Desligada (`screensaver_enabled = 0` pelo ADB da TV; o valor de antes fica no estado e volta
  com as outras escolhas ou com o Original). A escolha fica em `screensaver.txt`: no Android 11 a tela de descanso
  roda no processo principal, que não vê as SharedPreferences do `:home`.
  Um botão do controle (Botões do Controle → Câmeras; na TCL, Globoplay = 4097 e Disney+ = 4141 também entram na
  lista) abre a primeira câmera em tela cheia de qualquer lugar. Apertado de novo, a câmera da tela vai para a janela
  flutuante e volta o app de antes: a `CameraActivity` é `singleTask` numa tarefa própria, e o segundo aperto chega
  com ela pausada (onPause, onNewIntent, onResume), por isso ela olha se está visível (onStart/onStop).
- **Controle pelo celular** (Ajustes do Início → Controle pelo celular, desligado por padrão): `PhoneRemote` é um
  servidor HTTP minúsculo (porta 8765, só rede local) num serviço em primeiro plano no processo principal; o QR
  (`Qr`, codificador próprio, versões 1 a 6) leva ao endereço com um código de 10 letras. Desligar e ligar de novo
  troca o código; o Original desliga o servidor. O estado fica em `phone-remote.txt` (`Prefs`), pelo mesmo motivo do
  `screensaver.txt`. Nada do celular vira comando: as teclas vêm de uma lista fixa (`KEYS`) e os apps do índice da
  barra, abertos pelo componente exato (`am start -p pacote` falha: a intent implícita exige a categoria DEFAULT, que
  as telas de lançamento não têm). As teclas vão por `KeyInjector`, um processo Java que o `PhoneRemote` deixa vivo no
  shell da TV pelo ADB dela (`app_process` com o próprio APK, `InputManager` por reflexão, como o scrcpy): o
  `input keyevent` sobe uma JVM por tecla e leva quase 2 s na UnionTV; assim cada tecla leva 10 a 50 ms. Se o
  injetor falhar numa TV, cai para o `input keyevent`. O `Adb.shell` confirma a saída de um `Adb.Stream` aberto, senão
  o eco do terminal trava o fluxo.
- **Lembretes** (seção Lembretes da página do celular: texto, data, hora, uma vez/todo dia/toda semana): `Reminders`
  guarda em `reminders.txt` (até 50) e agenda o próximo no `AlarmManager` (exato; sem permissão no Android 12+, o
  inexato); `Reminders.Alarm` e o `HomeReceiver` (boot, atualização) chamam `process`, que dispara os vencidos (menos de
  12 h de atraso), passa os repetidos para a próxima data mantendo a hora do relógio e apaga os de uma vez só.
  `ReminderPopup` mostra o cartão no alto, sobre qualquer app (a mesma permissão da `CameraPopup`), sem foco nem toque,
  30 s, com o som de notificação. Só disparam no modo Free My TV: fora dele ficam guardados sem alarme (o `HomeMode`
  chama `process` ao ligar e ao voltar ao Original). O texto vem do celular e é só dado: limpo por `Reminders.clean`
  (sem controle, 100 caracteres), desenhado em `TextView`, nunca vai para um comando; a lista sai em JSON com `<`, `>` e
  `&` escapados. Para criar ou apagar é preciso o Controle pelo celular ligado; os criados disparam sem ele.
- **Casa inteligente (Tuya / Smart Life)** (botão do topo, em Configurações → Botões do Topo; só aparece com o arquivo de
  aparelhos): `Tuya` lista, liga e desliga pela rede local, sem nuvem; `TuyaActivity` é a lista (OK alterna).
  Os nomes, IDs e chaves locais vêm do `devices.json` do `python -m tinytuya wizard` (conta de desenvolvedor grátis em
  platform.tuya.com), enviado por `adb push` para `Android/data/io.freemytv/files/tuya-devices.json`: o app o copia para
  a área privada e apaga o original. As chaves são secretas: nunca no chat, no log nem no git (o `.gitignore` cobre os
  nomes). O IP não é guardado: cada aparelho avisa o seu em broadcast UDP (6667 e 7000; AES-ECB, e AES-GCM na 3.5, com a
  chave pública de descoberta da Tuya), e o `gwId` liga o aviso ao aparelho. `TuyaProtocol` é o protocolo, sem Android,
  conferido byte a byte contra o `tinytuya` (quadros 3.3 de saída e a leitura do broadcast). Só liga e desliga o que a
  categoria da Tuya diz ser tomada, interruptor, fita, módulo, lâmpada, ventilador ou régua (`SWITCHES`). Os pontos de
  dados que ligam e desligam são os booleanos entre `1` e `8` e o `20`: um interruptor de 4 teclas tem o 1, 2, 3 e 4
  (o 7 a 10 são temporizadores e o 16 é a luz de fundo), e vira um cartão por tecla ("geral sala · 3"; `Tuya.Entry`).
  O arquivo de aparelhos aceita `"channels": {"1": "Luz", "2": "Arandelas"}` em cada um para dar nome às teclas, e
  `"hidden": ["1"]` para esconder uma (o app a lê, mas não mostra o cartão). O resto (hubs infravermelho, sensores, controles virtuais) aparece como
  "Not a switch". Fala as versões 3.3, 3.4 e 3.5 (`TuyaConnection`; a 3.4 e a 3.5 começam com um aperto de mão de três mensagens que dá a
  chave de sessão: AES-ECB com HMAC-SHA256 na 3.4, AES-GCM nos quadros 0x6699 na 3.5, com o comando 13 para mudar e o 16 para
  perguntar). Conferida contra o `tinytuya` (o quadro de comando da 3.4 é idêntico byte a byte; o da 3.5 ele decifra) e
  contra aparelhos de verdade; versões fora dessas aparecem como "Not supported yet".
  **Nuvem como reserva** (`TuyaCloud`, `TuyaCloudSign`): alguns aparelhos anunciam por broadcast mas a TV não os alcança por
  unicast (sem ping nem ARP, e o Smart Life os controla pela nuvem; já se viu com os da garagem). Para esses, e para os
  que nunca anunciam, o app lê e comanda pela API da Tuya (`/v1.0/iot-03/devices/<id>/status` e `/commands`; os códigos
  `switch_1` a `switch_8`, `switch` e `switch_led` viram os pontos de dados 1 a 8 e 20). Precisa do `tinytuya.json` do
  `wizard` (apiKey, apiSecret, apiRegion) enviado por ADB para `tuya-cloud.json` na pasta do app: copiado para a área
  privada e apagado do original, e nunca no log nem no git. A rede local vem primeiro; depois de uma falha ela só é
  tentada de novo em 1 minuto, e a nuvem se lê no máximo a cada 30 s por aparelho (a cota do plano grátis). A assinatura
  é conferida contra o `tinytuya`. Alguns aparelhos nem aceitam a conexão TCP na 6668 (timeout) enquanto outro cliente ocupa o único espaço dela:
  aparecem como "No response" e a lista tenta de novo a cada 10 s.
- Texto na tela: `Ui.tr("English")`, traduzido a partir de `app/assets/i18n/<idioma>.json` (com o inglês como chave;
  uma busca num HashMap, inglês quando um idioma não tem a frase). Texto montado em tempo de execução é um modelo:
  `Ui.trf("{n} min ago", "n", 12)`, nunca palavras concatenadas. Uma chave `"contexto|Texto"` é o mesmo inglês onde um
  idioma pode precisar de outra palavra (`"unit|Automatic"`, `"format|h:mm"` guarda um padrão de SimpleDateFormat).
  Uma contagem com palavra é `Ui.plural("plural|one:{n} season|other:{n} seasons", n)`: cada catálogo dá uma forma por
  categoria de plural CLDR do seu idioma (`one`/`few`/`many`/`other` em russo, ucraniano, polonês; só `other` em
  japonês, chinês…), e as regras da ICU escolhem uma. Português, espanhol, alemão e russo são traduções à mão: a
  referência, editadas à mão, nunca trocadas por tradução automática. Datas e números usam `Ui.locale()`. Texto
  desenhado a cada quadro fica em cache e é refeito quando `Ui.generation` muda. Depois de mexer em textos, rode
  `python3 tools/translate.py`: ele refaz o `en.json` a partir do código e lista o que falta em cada idioma; traduza
  isso em todos os catálogos (ou `--fill` com `ANTHROPIC_API_KEY`, que manda só as frases), e mantenha os marcadores
  como estão. Rode `python3 tools/translate.py --check` para validar todos os catálogos sem gravar arquivos.
  Ainda sem idiomas da direita para a esquerda (o layout não é espelhado); CJK e devanágari usam a fonte do sistema.
  Comentários novos no código em português (os antigos, do projeto original, ficam em inglês), curtos, só onde o
  porquê não é óbvio.
- As configurações vêm do `config.properties` pelo `out/gen/io/freemytv/Config.java` gerado (nunca edite esse).
  O que a pessoa muda na TV (`Prefs`: Home Settings, Shelf, Remote Buttons) vale mais que esses valores iniciais.
- Os botões de app do controle (`RemoteKeys`, `RemoteKeyService`) usam os códigos de tecla de cada marca: só rodam em
  TVs TCL, Vestel, KTC (NextStar) e Sony e em projetores Formovie, o serviço só fica ligado no modo Free My TV, e
  *Original* o tira dos serviços de acessibilidade ativos. O sistema espera o serviço a cada tecla, então ele vive
  sozinho no processo `:keys`: o que ele lê fica em `remote-keys.txt` (`Prefs.keyApp`), nunca em SharedPreferences, e
  o `onKeyEvent` não faz trabalho pesado.
  A Vestel (F&U e outras marcas de loja; MediaTek, Android TV 14 testado) mapeia o botão Netflix para `KEYCODE_STEM_1`,
  uma tecla global (`dumpsys window policy` lista essas) que o filtro de teclas vê primeiro; os botões YouTube, Prime
  Video e Google Play ficam com o sistema. As listas de fábrica não precisaram de entradas Vestel.
  A KTC (NextStar; MediaTek, Google TV 14 testado) mapeia o botão Tabii para `KEYCODE_BUTTON_8`, também uma tecla
  global, com a qual o receptor de atalhos da própria KTC abriria o Tabii; os botões Netflix, YouTube e Prime Video
  ficam com o sistema.
  A Sony (BRAVIA VH22, Android TV 12 testado) manda `KEYCODE_BUTTON_9` para os botões BRAVIA CORE, Disney+, Prime
  Video, YouTube e Crunchyroll, `KEYCODE_BUTTON_4` para a Netflix e `KEYCODE_BUTTON_8` para o botão Google Play de
  controles mais antigos (BRAVIA 4K UR3, Android 10 testado), teclas globais de `com.sony.dtv.sonyvendorservice`;
  só o scan code diferencia uma da outra, então o `Prefs` as guarda como `SONY_KEY` + scan code. "Sony default"
  (vazio) deixa a tecla para a TV. A Sony tem seu próprio build do Apple TV, `com.apple.atve.sony.appletv`.
  Projetores Xiaomi feitos pela Formovie (Fengmi; L1, MediaTek, Google TV 14 testado) mandam o mesmo `KEYCODE_STEM_1`
  para a Netflix (`RemoteKeys.STEM_NETFLIX`); o botão YouTube vai para o `GlobalKeyReceiver` da MediaTek e fica com o
  sistema.
- Depois de qualquer mudança, `./build.sh` tem que terminar com `Built FreeMyTV.apk`.
- LG e Samsung (`web/`): JavaScript ES5 puro e CSS sem variáveis, grid ou backdrop-filter, porque TVs mais antigas
  (webOS 3 e 4, Tizen 3) rodam um Chromium de 2015 a 2017; só transform e opacity animam. `web/tv.js` é o lado da TV:
  na LG, Luna (`listLaunchPoints`, `launch`, as entradas; onde o webOS 6 e mais novos negam `listLaunchPoints` a apps
  do Modo Desenvolvedor, a lista vem da API de controle remoto da TV em `ws://127.0.0.1:3000` depois que a TV permitiu
  (ela pergunta uma vez; um "não" não é perguntado de novo), e até lá do `getAppLoadStatus` sobre uma lista de ids de
  apps conhecidos, que mostra os nomes sem ícones); na Samsung, `tizen.application` (`getAppsInfo`, `launch`), a
  propriedade `VIDEOSOURCE` e `tizen.tvwindow.setSource` para as entradas, depois o visualizador da Samsung
  (`org.tizen.tv-viewer`) para mostrá-las. Os dados da própria Samsung não são confiáveis: no Tizen 3 nenhum
  `iconPath` de app carrega (no Tizen 4 e 5, os do YouTube e do Prime Video são genéricos), então os logos vêm junto
  em `web/icons/`, indexados pelo id do app em `SAMSUNG_ICONS` (SVGs precisam de largura e altura para o WebKit
  antigo); alguns nomes vêm vazios ou longos (`SAMSUNG_TITLES`); e o Tizen 3 não responde a nenhum app control de
  "settings", então Configurações da TV abre `org.tizen.menu`. O `web/home.js` segue o layout e o comportamento do
  `HomeView`, então uma mudança de design no Android vale lá também. Textos: os mesmos catálogos do Android, empacotados
  em `i18n.js` pelo `tools/webapp.py` (só as chaves que o `web/` usa): `tr('Live TV')`, `trf('{n} min ago', {n: 12})`,
  `plural('plural|…', n)` e `formatDate('format|h:mm a', at)` (o padrão SimpleDateFormat do catálogo, escrito pelo
  `web/strings.js`), e o `tools/translate.py` lê essas chamadas também. Compilado sem cidade (`--generic`), o
  `web/zones.js` (`tools/make_zones.py`, a partir da base de fusos horários) transforma o fuso da TV em cidade, moeda e
  região. O `tools/webapp.py` é o que os dois builds compartilham. Prévia: `python3 webos/build.py --preview` (ou
  `tizen/build.py`), depois abra `out/webos/index.html` num navegador (uma versão de mentira lista apps de exemplo).

## Sempre

- O app de configuração inicial do Google TV (`setupwraith`) é desligado antes da tela inicial de fábrica e volta
  depois dela, em todo lugar (ordem dos `targets`, `applyOriginal`, `revertHomeNow`, `verifyNow`): com a tela inicial
  desligada e ele ligado, a RecoveryActivity dele toma o Home, e um reinício ali resetou um Xiaomi para o padrão de
  fábrica. Se ele não puder ser desligado, a tela inicial fica ligada.
  A Kalley 43G200 (`R3_GTV`) religa ele a cada inicialização e entrou em loop de boot sem a tela inicial dela: a troca
  se recusa ali.
- Nunca desligue um app que salvou uma rede Wi-Fi: o Android apaga as redes dele e o *Original* não consegue trazer de
  volta (`wifiOwners` em `HomeMode`). Quando o serviço de Wi-Fi não responde, a troca para no Wi-Fi, e a verificação
  do boot deixa o app de configuração inicial e a tela inicial de fábrica para depois.
- Nunca faça commit de `config.properties`, `*.keystore`, nenhuma chave, senha de DVR ou foto pessoal.
- Nunca desinstale nem apague os dados de apps de sistema; só `pm disable-user --user 0`, pelo `HomeMode`, para que o
  *Original* possa trazer de volta.
- Não mexa nas configurações de imagem, som, rede ou conta da TV.
- Mantenha o caminho de volta funcionando: o modo *Original* e o watchdog.
- Nunca ajude a burlar DRM ou certificação de serviços de streaming.

<div align="center">

<img src="docs/assets/icon-512.png" width="96" alt="">

# Free My TV

**Uma tela inicial rápida e sem propaganda para Google TV, TVs LG e Samsung.**

Sugestões do momento dos seus próprios apps, o clima num relance e um visual que acompanha o pôr do sol.<br>
Código aberto. Feito com Claude Code. Um botão para desfazer tudo.

[**freemytv.io**](https://freemytv.io) · [Instalação](#instalação) · [TVs LG](#tvs-lg-webos) · [TVs Samsung](#tvs-samsung-tizen) · [Qualquer TV](#qualquer-tv) · [Do seu jeito](#do-seu-jeito)

<img src=".github/preview.webp" width="880" alt="A tela inicial do Free My TV: uma barra de vidro com os apps, sugestões do momento e uma gaveta com o clima e cotações de criptomoedas">

</div>

## O que você ganha

- **Sem propaganda.** Nada de fileiras patrocinadas, banners ou promoções tocando sozinhas. Nenhuma.
- **Seus apps em primeiro lugar.** Os apps de streaming que você usa de verdade, numa barra de vidro. Escolha quais e em que ordem direto na TV.
- **O que está em alta, não o que pagou para aparecer.** Três sugestões novas a cada hora, do que está em alta na semana e dos últimos lançamentos, só de apps que você já tem. Filmes e Séries têm abas próprias.
- **Clima e cotações a um botão.** A previsão hora a hora, o nascer e o pôr do sol, e cotações de criptomoedas ao vivo.
- **Claro de dia, escuro à noite.** Segue o pôr do sol real da sua cidade, como o seu celular.
- **Uma tela de descanso combinando.** Fotos em tela cheia das sugestões com um grande relógio de vidro.
- **Sua foto de fundo** (opcional). Uma foto sua no fundo, nítida, com relógio, clima e um cartão de memória, processador e armazenamento em vidro ao lado.
- **Câmeras do seu DVR** (opcional). Até 6 câmeras ao vivo na tela inicial (Intelbras, Dahua, Hikvision ou qualquer RTSP), com tela cheia e troca de câmera pelas setas.
- **Rápida.** 60 fps, cada quadro em 16,6 ms, no próprio chip da TV. A tela inicial usa 96 MB de memória em vez de 242 MB (−60%).<br><sub>Medido numa TCL Google TV de 98″ com Android 14.</sub>
- **Configurada na TV.** Idioma, sua cidade, temperatura, tema, os cartões de clima e cotações, quais moedas, e os apps da barra, nas configurações do próprio Free My TV.
- **Segura.** Sem root. Nada é desinstalado. Um botão traz de volta a tela inicial de fábrica.

## Instalação

Uns dez minutos. Você precisa de um Google TV ou Android TV (Android 8 ou mais novo) e de um computador no mesmo Wi-Fi.

### O jeito fácil: um assistente de IA

1. **Na TV**, ative o modo desenvolvedor: Configurações → Sistema → Sobre → aperte *Build do Android TV OS* 7 vezes. Depois Configurações → Sistema → Opções do desenvolvedor → ative a **Depuração USB**. Anote o IP da TV (Configurações → Rede e Internet).
2. **No computador**, abra um assistente de programação com IA ([Claude Code](https://claude.com/claude-code), Codex ou Antigravity) e diga:
   > Instale o Free My TV (github.com/simbasjungles/free-my-tv) na minha TV no 192.168.1.50. Moro em Lisboa.

   Ele baixa o código, instala as ferramentas que faltam, compila o app e coloca na sua TV, e pergunta antes de qualquer coisa importante. Troque pelo IP da sua TV e pela sua cidade.
3. **Na TV**, abra *Free My TV* → *Home Screen* → *Free My TV*. É aí que a TV pergunta *Permitir depuração USB?* para o Free My TV: marque *Sempre permitir* e aceite.

### O jeito manual

1. **Na TV**, faça o passo 1 acima.
2. **Ferramentas:** um JDK (17+), as ferramentas de linha de comando do Android SDK (build-tools 34, platform android-34) e o adb. No Mac:
   ```bash
   brew install --cask temurin android-commandlinetools android-platform-tools
   sdkmanager "build-tools;34.0.0" "platforms;android-34"
   ```
   No Linux, instale o OpenJDK 17, as ferramentas de linha de comando do Android e o adb pelo gerenciador de pacotes, depois rode a mesma linha do `sdkmanager`. No Windows, use o Git Bash (que é o que o Claude Code usa lá) ou o WSL.
3. **Compile:**
   ```bash
   ./build.sh
   ```
   A primeira vez cria o `config.properties`. Coloque ali a sua cidade e a sua [chave do TMDB](#a-chave-do-tmdb), e rode de novo.
4. **Instale:**
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV.apk
   ```
   Em TVs TCL, libere também a execução em segundo plano (a TCL bloqueia por padrão, e a troca nunca começa):
   ```bash
   adb shell "appops set io.freemytv AUTO_START allow || appops set io.freemytv APP_AUTO_START allow"
   ```
5. **Na TV**, abra *Free My TV* → *Home Screen* → *Free My TV*. A TV pergunta *Permitir depuração USB?* mais uma vez, agora para o próprio Free My TV: marque *Sempre permitir* e aceite.

## TVs LG (webOS)

A mesma tela inicial como app da LG: as sugestões, a barra de vidro com os seus apps, a gaveta com entradas, clima e cotações, dia e noite, Home Settings e o editor da barra. Instala pelo próprio Modo Desenvolvedor da LG, sem root e sem mudar nada na TV. A tela inicial da LG continua onde está, e o Free My TV fica a um toque numa tecla numérica.

Você precisa de uma TV LG (webOS 3 ou mais novo) e de um computador no mesmo Wi-Fi, com Python 3.

1. **Na TV**, abra a LG Content Store e instale o **Developer Mode**. Entre com uma [conta de desenvolvedor LG](https://webostv.developer.lge.com) grátis, ative o **Dev Mode Status** (a TV reinicia) e depois o **Key Server**. Anote o IP e a senha (passphrase) que aparecem.
2. **No computador**, baixe o código e compile. O `config.properties` funciona como no Google TV (sua cidade, sua [chave do TMDB](#a-chave-do-tmdb)):
   ```bash
   git clone https://github.com/simbasjungles/free-my-tv && cd free-my-tv
   python3 webos/build.py
   ```
3. **Instale.** Na primeira vez ele pede a senha da TV; nas atualizações, não:
   ```bash
   python3 webos/install.py 192.168.1.50
   ```
4. **Na TV**, com o Free My TV aberto, segure uma tecla numérica do controle. A partir daí, essa tecla abre o app de qualquer lugar.

Com um assistente de IA, diga *"Instale o Free My TV (github.com/simbasjungles/free-my-tv) na minha TV LG no 192.168.1.50. Moro em Lisboa."* Ele compila o app e te passa o comando de instalação, para você mesmo digitar a senha.

O Modo Desenvolvedor dura uns 40 dias: abra o app Developer Mode de vez em quando e aperte **EXTEND**. Para remover o Free My TV, rode `python3 webos/install.py 192.168.1.50 --uninstall`.

## TVs Samsung (Tizen)

A mesma tela inicial como app da Samsung, com tudo o que a da LG tem: as sugestões, a barra, a gaveta com as entradas, o clima e as cotações, dia e noite, e as próprias configurações. Instala pelo próprio Modo Desenvolvedor da Samsung, sem root e sem mudar nada na TV, e a tela inicial da Samsung continua onde está.

Você precisa de uma TV Samsung de 2017 ou mais nova (Tizen 3+) e de um computador no mesmo Wi-Fi.

1. **Na TV**, abra **Apps** e digite `1 2 3 4 5` no controle. Ative o **Developer mode**, digite o IP do seu computador em **Host PC IP** e reinicie a TV.
2. **No computador**, baixe o [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller/releases/latest), o instalador da comunidade para TVs Samsung, abra e conecte à sua TV.
3. **Instale pelo GitHub:** digite `simbasjungles/free-my-tv`. Em TVs 2023 e mais novas, ele pede que você entre na sua conta Samsung uma vez, para assinar o app para a sua TV.
4. **Direto nele:** nas configurações da TV, em Start Screen Options, ative **Autorun Last App**: a TV liga direto no Free My TV.

Essa versão pega a sua cidade pelo fuso horário da TV. Para a sua cidade exata e as sugestões do momento, compile com o seu próprio `config.properties` e a sua [chave do TMDB](#a-chave-do-tmdb): abra a pasta no Claude Code e diga *"Instale o Free My TV na minha TV Samsung no 192.168.1.50. Moro em Lisboa."* Ele configura as ferramentas da Samsung, e em TVs 2023 e mais novas você entra na sua conta Samsung pelo navegador para criar o certificado. A sua versão assinada instala com `python3 tizen/install.py 192.168.1.50`, que só precisa do Python: sem Tizen Studio, e sem Rosetta em Macs com Apple Silicon.

## Configurações

Os primeiros valores vêm do `config.properties`, criado pelo primeiro build (Google TV, LG e Samsung usam o mesmo arquivo). A maioria também pode ser mudada na TV, em *Free My TV* → *Home Settings*, e a escolha feita na TV vale mais:

| Chave | O que faz | Padrão |
|---|---|---|
| `tmdb_key` | A sua chave grátis do TMDB, para as sugestões | vazio |
| `tmdb_region` | Seu país (duas letras): quais títulos estão em qual app | `US` |
| `watchmode_key` | Opcional, chave grátis do [Watchmode](https://api.watchmode.com/): uma sugestão abre a página do título no app | vazio |
| `latitude`, `longitude` | Sua cidade, para o clima e o pôr do sol | Nova York |
| `city` | O nome no cartão do clima | `Clima` |
| `hide_animation` | Tira desenhos e animes das sugestões | `false` |
| `language` | `auto` (segue a TV), ou um idioma com [tradução](#traduções) (`pt`, `en`, `es`, `de`, `ru`, `fr`, `ja`…) | `auto` |
| `show_crypto` | Mostra cotações de criptomoedas da Binance na tela inicial e na tela de descanso | `true` |
| `currency` | Moeda das cotações: `BRL`, `USD`, `EUR`… | `USD` |
| `temperature` | `C`, `F` ou `auto` | `auto` |
| `disable_assistant` | Também desliga o Google Assistente | `false` |
| `hide_services` | Serviços de streaming que ficam fora das sugestões mesmo com o app na TV (`netflix`, `prime`, `disney`…), separados por vírgula; para apps que vêm na TV e que você não assina. Só Google TV | vazio |
| `hide_apps` | Apps que ficam fora da barra, de Todos os Apps e das sugestões, pelo id ou pelo nome (parte dele), separados por vírgula; para apps que vêm na TV e não podem ser desinstalados, como a Netflix de fábrica da Samsung. Só LG e Samsung | vazio |
| `dvr_cameras` | Câmeras ao vivo de um DVR ou câmeras IP (até 6), um endereço RTSP por câmera, separados por vírgula. Use o substream. Só Google TV | vazio |

Mudou alguma coisa? Rode `./build.sh` e instale de novo. Guarde o `freemytv.keystore`: as atualizações são assinadas com ele.

### A chave do TMDB

Grátis, leva dois minutos: crie uma conta em [themoviedb.org](https://www.themoviedb.org/signup), depois abra [Settings → API](https://www.themoviedb.org/settings/api) e peça uma chave de desenvolvedor. Cole o *API Read Access Token* (ou a *API Key*) depois de `tmdb_key=`. A chave é sua: o `config.properties` fica fora do git.

### Abrir o título, não só o app (opcional)

Com uma chave grátis do [Watchmode](https://api.watchmode.com/) (`watchmode_key=` no `config.properties`), uma sugestão abre a própria página no app de streaming em vez da tela inicial do app. O plano grátis (2.500 créditos por mês) basta: cada título custa duas consultas na primeira vez e fica lembrado depois. Funciona onde o app aceita o endereço web do título (testado: Prime Video e JioHotstar); onde não aceita, a sugestão só abre o app, como sem a chave.

### Sua foto de fundo (opcional)

Envie uma foto para a pasta do app na TV e ela vira o fundo da tela inicial, nítida, sob um véu claro de dia e escuro à noite. Com ela, o relógio, o clima e um cartão de memória, processador e armazenamento ficam em três cartões de vidro à esquerda:

```bash
adb push minha-foto.jpg /sdcard/Android/data/io.freemytv/files/background.jpg
```

De preferência já reduzida para o tamanho da tela (1920×1080, ou 1280×720 em aparelhos mais simples). Para voltar ao fundo normal, apague o arquivo.

### Câmeras do DVR (opcional)

Em `dvr_cameras`, um endereço RTSP por câmera (até 6). Intelbras e Dahua: `rtsp://USUARIO:SENHA@IP_DO_DVR:554/cam/realmonitor?channel=1&subtype=1`; Hikvision: `rtsp://USUARIO:SENHA@IP_DO_DVR:554/Streaming/Channels/102`. As câmeras aparecem num mosaico à direita (com mais de 4, você escolhe mostrar 4 ou 6), com o controle você seleciona uma e o OK abre em tela cheia, no stream principal; ← e → trocam de câmera. Uma câmera sem sinal fica escura e é tentada de novo a cada 5 minutos. A senha fica só no `config.properties`, que não vai para o git.

## Voltando atrás

Na TV: *Free My TV* → *Home Screen* → *Original*. A tela inicial de fábrica, a tela de descanso e todos os apps voltam exatamente como estavam. Se a nova tela inicial travar três vezes em cinco minutos, ela volta sozinha.

Para remover de vez, troque para *Original* primeiro e depois desinstale o *Free My TV* (Configurações → Apps). Desinstalou com ele ativo? Instale de novo e escolha *Original*: a lista do que ele desligou fica guardada nas configurações da TV, então tudo volta.

## O que ele muda

- Vira a tela inicial e a tela de descanso.
- Desliga, sem desinstalar:
  - a tela inicial e a tela de descanso de fábrica;
  - a tela de recuperação do Google TV, Play Games e YouTube Music;
  - em TVs TCL, o TCL Channel, mensagens, demonstração de loja, menu de hotel, manuais, espelhamento e apps de registro;
  - em TVs Philco, o AI PONT (canais promocionais e downloads de apps) e o manual;
  - em TVs Multilaser, o reconhecimento de conteúdo da tela da Anoki (ACR), a demonstração de loja e o manual;
  - em TVs fabricadas pela KTC (NextStar), o reconhecimento de conteúdo da tela da Anoki (ACR), a demonstração de loja e o manual;
  - em projetores Amlogic genéricos, as telas de descanso com propaganda, as lojas de apps promocionais, um instalador de apps escondido e um app falso de "Netflix";
  - o Google Assistente, só com `disable_assistant=true`.
- Em TVs TCL, os botões Netflix, YouTube e Prime do controle continuam funcionando por um pequeno serviço de acessibilidade que só lê esses botões. Escolha o que cada um abre em *Free My TV* → *Remote Buttons*; *Original* desliga o serviço. Em TVs fabricadas pela Vestel (F&U e outras marcas de loja) e em projetores Xiaomi (Formovie) vale o mesmo para o botão Netflix, e em TVs fabricadas pela KTC (NextStar) para o botão Tabii. Em TVs Sony, os botões BRAVIA CORE, Netflix, Disney+, Prime Video, YouTube e Crunchyroll (e Google Play, nos controles que têm) podem abrir o app que você escolher, ou manter o padrão da Sony.
- Imagem, som, entradas, pareamento do controle, serviços do Google e a Play Store estão numa lista protegida, conferida antes de cada mudança, assim como as peças do próprio Android e os menus de fábrica. Antes de qualquer coisa ser desligada, a TV é consultada para saber se as entradas ou a tecla Input do controle dependem dela; essas ficam ligadas.
- No Google TV, o app de configuração inicial é desligado antes da tela inicial de fábrica e volta depois dela, para que a tela de recuperação dele nunca tome o botão Home no meio do caminho.
- Apps que salvaram uma rede Wi-Fi nunca são desligados, porque o Android apagaria a rede. Se a primeira configuração da TV salvou a sua, a troca pede que você esqueça a rede e conecte de novo em Configurações antes (redes salvas fora de alcance não aparecem ali; o Claude Code consegue removê-las).

## Qualquer TV

O Free My TV roda em Google TV e Android TV: TCL, Sony, Hisense, Philips, Xiaomi, TVs fabricadas pela Vestel (F&U), TVs fabricadas pela KTC (NextStar), projetores Amlogic genéricos e outras, além de aparelhos como Chromecast com Google TV, Google TV Streamer, onn 4K e NVIDIA Shield. E em TVs [LG](#tvs-lg-webos) e [Samsung](#tvs-samsung-tizen), como app.

**Qualquer outra TV:** ligue um aparelho Google TV no HDMI e libere esse. Mesmos passos, mesma tela inicial.

**Outra marca ou modelo:** peça ao Claude. O Free My TV foi feito para ser adaptado por IA. Abra a pasta no Claude Code e diga *"configure para a minha Sony"*: ele lê a sua TV, acha a tela inicial e os apps de propaganda dela, atualiza as listas abaixo e te guia num teste. O [CLAUDE.md](CLAUDE.md) explica como.

| O quê | Onde |
|---|---|
| Apps a desligar | `DEBLOAT` em [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) |
| Apps que nunca são tocados | `PROTECTED` no mesmo arquivo |
| Apps da barra | `SHELF` em [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) |
| O app de TV ao vivo da sua marca | `LIVE_TV_APPS` no mesmo arquivo |

## Do seu jeito

O código é seu, e o Claude Code se acha nele. Experimente:

- *"Coloque o Crunchyroll na barra."*
- *"Mostre as cotações em reais."*
- *"Adicione catalão."*
- *"Mude para o visual noturno uma hora depois do pôr do sol."*

## Como funciona

- **Um app Android simples, sem Gradle, sem bibliotecas.** O [`build.sh`](build.sh) roda aapt2 → javac → d8 → zipalign → apksigner e gera um APK de 2 MB.
- **Usa o ADB da própria TV, de dentro da TV.** O app se conecta a `127.0.0.1:5555` com sua própria chave (é isso o pedido *Permitir depuração USB?*) e roda os mesmos comandos que você rodaria: `cmd package set-home-activity`, `settings put secure screensaver_components`, `pm disable-user`. Nada escuta a rede.
- **Desenhado à mão num único Canvas**, com shaders do Android 13 para o vidro fosco e o céu (preenchimentos simples no Android 8 a 12, com sombras mais leves e sem camadas de GPU no 8 a 11, por causa das GPUs mais antigas).
- **Câmeras com um cliente RTSP próprio**, que entrega cada quadro direto ao decodificador de vídeo da TV (o player padrão do Android pausa para encher o buffer o tempo todo).
- **Uma rede de segurança.** Um watchdog num processo separado traz de volta a tela inicial de fábrica depois de três travamentos em cinco minutos, e uma verificação no boot mantém a sua escolha depois de atualizações da TV.
- **Na LG e na Samsung, o mesmo design em HTML, CSS e JavaScript puros** ([`web/`](web)), escritos para o Chromium das TVs mais antigas, sem frameworks. O [`webos/build.py`](webos/build.py) empacota com o `config.properties` num `.ipk` sem as ferramentas da LG, e o [`webos/install.py`](webos/install.py) instala pelo Modo Desenvolvedor; na LG, ele lista e abre os apps pelo barramento de serviços da própria TV (Luna), como o launcher da LG faz. O [`tizen/build.py`](tizen/build.py) empacota o mesmo app num `.wgt` da Samsung, que usa as APIs web do Tizen para os apps e as entradas. Para ver no computador: `python3 webos/build.py --preview`, depois abra `out/webos/index.html` (uma versão de mentira lista apps de exemplo).
- **Dados** do TMDB (títulos em alta, lançamentos e qual app tem o quê no seu país), do Open-Meteo (previsão, nascer e pôr do sol) e, se quiser, da Binance (cotações de 24 horas quando `show_crypto=true`).

| Arquivo | O que é |
|---|---|
| [`HomeView.java`](app/src/io/freemytv/HomeView.java) | A tela inicial |
| [`HomeMode.java`](app/src/io/freemytv/HomeMode.java) | A troca entre Free My TV e Original |
| [`Adb.java`](app/src/io/freemytv/Adb.java) | O ADB da própria TV, de dentro da TV |
| [`Recs.java`](app/src/io/freemytv/Recs.java) | As sugestões (TMDB) e a foto de fundo |
| [`HomeData.java`](app/src/io/freemytv/HomeData.java) | Clima e cotações |
| [`HomeApps.java`](app/src/io/freemytv/HomeApps.java) | A barra, as entradas e Todos os Apps |
| [`Cameras.java`](app/src/io/freemytv/Cameras.java), [`CameraStream.java`](app/src/io/freemytv/CameraStream.java), [`CameraActivity.java`](app/src/io/freemytv/CameraActivity.java) | As câmeras do DVR: mosaico, cliente RTSP e tela cheia |
| [`SystemStats.java`](app/src/io/freemytv/SystemStats.java) | Memória, processador e armazenamento |
| [`DreamView.java`](app/src/io/freemytv/DreamView.java) | A tela de descanso |
| [`SettingsActivity.java`](app/src/io/freemytv/SettingsActivity.java) | A tela de configurações (o ícone do app) |
| [`Prefs.java`](app/src/io/freemytv/Prefs.java) | Configurações mudadas na TV |
| [`RemoteKeys.java`](app/src/io/freemytv/RemoteKeys.java) | Os botões de app do controle TCL |
| [`Watchdog.java`](app/src/io/freemytv/Watchdog.java) | A rede de segurança |
| [`web/home.js`](web/home.js) | A tela inicial nas TVs LG |
| [`web/tv.js`](web/tv.js) | Os apps e as entradas da TV, na LG e na Samsung |
| [`webos/build.py`](webos/build.py), [`webos/install.py`](webos/install.py) | O pacote do app LG e a instalação |
| [`tizen/build.py`](tizen/build.py) | O pacote do app Samsung |

O site ([freemytv.io](https://freemytv.io)) fica em [`docs/`](docs): HTML, CSS e JS simples no GitHub Pages.

## Traduções

No Google TV, na LG e na Samsung, cada idioma é um arquivo, [`app/assets/i18n/<idioma>.json`](app/assets/i18n), com o
texto em inglês do código como chave. *Automático* segue o idioma da TV e usa qualquer idioma que tenha arquivo (senão,
inglês); *Home Settings* → *Language* lista todos. Hoje: inglês, português, espanhol, alemão, francês, italiano,
holandês, polonês, sueco, dinamarquês, norueguês, finlandês, tcheco, romeno, turco, russo, ucraniano, grego, japonês,
coreano, chinês simplificado, híndi, indonésio, vietnamita e tailandês. Português, espanhol, alemão, russo e tailandês
foram traduzidos à mão e são a referência (corrija à mão); os outros foram feitos por máquina, e correções de falantes
nativos são bem-vindas.

- **Adicionar um idioma:** `python3 tools/translate.py --new ca`, depois preencha `app/assets/i18n/ca.json` à mão, ou
  com `ANTHROPIC_API_KEY` definida, `python3 tools/translate.py --fill --lang ca` (só as frases vão para a API).
- **Depois de mexer em textos no código:** `python3 tools/translate.py` lista o que falta em cada idioma; `--fill`
  traduz só isso, `--prune` tira frases que o código não usa mais.
  Rode `python3 tools/translate.py --check` para validar todos os catálogos sem gravar arquivos.
- Texto montado em tempo de execução tem marcadores (`"{n} min ago"`), para que cada idioma coloque as palavras na sua
  ordem. Uma chave como `"unit|Automatic"` é o mesmo inglês em outro lugar, onde um idioma pode precisar de outra
  palavra.
- Contagens têm formas de plural: `"plural|one:{n} season|other:{n} seasons"` é traduzido com uma forma por categoria
  de plural do idioma (russo `"one:{n} сезон|few:{n} сезона|many:{n} сезонов|other:{n} сезона"`, japonês só
  `"other:{n}シーズン"`), e as regras de plural do Android escolhem a certa para cada número.
- **Limites:** idiomas da direita para a esquerda (árabe, hebraico, persa) precisariam do layout espelhado, então ainda
  não estão. Chinês, japonês, coreano e híndi são desenhados com a fonte do sistema da TV (a Inter não tem essas letras).

## Privacidade

Sem contas, sem métricas, sem rastreamento. O app fala com o TMDB, com o Open-Meteo (com as coordenadas que você
definiu, ou a cidade principal do seu fuso horário), com a Binance só quando `show_crypto=true`, com o Watchmode só
quando você define uma `watchmode_key`, e com o seu DVR só na sua rede. Suas configurações ficam no seu computador e na
sua TV.

## Créditos

- Feito com [Claude Code](https://claude.com/claude-code).
- A versão LG se apoia no que [@danielthejoker18](https://github.com/danielthejoker18) descobriu no [FreeMyTvWebOS](https://github.com/danielthejoker18/FreeMyTvWebOS) (MIT): instalar pelo Modo Desenvolvedor e abrir apps pelo barramento de serviços da TV.
- Nas TVs Samsung, instala com o [TizenBrew Installer](https://github.com/reisxd/TizenBrewInstaller) de [@reisxd](https://github.com/reisxd).
- Este produto usa a API do TMDB, mas não é endossado nem certificado pelo [TMDB](https://www.themoviedb.org/).
- Links para os títulos nos apps de streaming pelo [Watchmode](https://api.watchmode.com/).
- Dados do clima pelo [Open-Meteo.com](https://open-meteo.com/) (CC BY 4.0). Cotações pela API pública da [Binance](https://www.binance.com/).
- Ícones: [Material Symbols](https://fonts.google.com/icons) (Apache 2.0). Fonte: [Inter](https://rsms.me/inter/) (SIL OFL 1.1). Logos das moedas: [cryptocurrency-icons](https://github.com/spothq/cryptocurrency-icons) (CC0). Logos de apps da Samsung: [Simple Icons](https://simpleicons.org) (CC0).
- Sem vínculo com Google, TCL, LG, Samsung, Intelbras ou qualquer serviço de streaming. Nomes e logos de apps pertencem aos seus donos.

## Licença

[MIT](LICENSE). Faça um fork, mude, publique.

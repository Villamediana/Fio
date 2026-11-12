# Novelo · arquitetura

## O caminho de um pensamento

```
Orb (segurar / tocar / arrastar pra cima)
  → Recorder (AudioRecord 16 kHz mono → WAV; nível 0..1 a cada 20 ms)   MicService segura com a tela apagada
  → Capture (banco: recorded)
  → Mind.process (fila, um por vez)
      Ears.transcribe     Hearing: sempre LFM3-Omni no celular (pedaços ≤ 28 s), quem quer que pense
      Recall.search       palavras sem acento (raras pesam mais) + um salto no grafo (tópicos em comum, links)
      Brain.context       AGORA · item em foco (check-in) · últimas coisas guardadas · tarefas abertas · lembranças · a fala
      Thinker.answer      sempre pela internet (1.20): Inteligência do Novelo (modelos grátis da OpenRouter,
                          NoveloModels; a foto vai como o que o Omni viu nela) ou a API dela (/chat/completions,
                          json_object, foto a 768 px). O mesmo prompt nos dois.
      Brain.parse → Plan  reply · create[] · update[] · search[] · period{from,to} · show[]
        se só procura: Recall com os termos e/ou os itens daqueles dias (guardado, feito ou vence),
        e outra chamada com os resultados; até 2 procuras, somando o que achou
      Keeper.apply        cria/atualiza entries (notícia nova entra no texto do item), tópicos (entities/entry_topics), links; progress só guarda mudança de status
      Reminders.sync      AlarmManager exato (USE_EXACT_ALARM), religado no boot/atualização
  → MindState.Answer (resposta + cartões do que foi criado/mudado/mostrado)
```

Nada se perde: sem rede ou sem Ajustes, a captura fica `recorded`/`failed` com o áudio, e volta à
fila quando o app abre, quando os Ajustes mudam ou no "N coisas esperando · tentar agora".
O áudio é apagado depois que a captura vai até o fim.

O modelo do celular recebe a vez dele já com `<think></think>` (é o que o template do LFM3 escreve com o pensar
desligado; sem isso ele escrevia sozinho no começo de cada trecho) e qualquer bloco de pensar que sobre é tirado
(`LocalEngine.clean`). Ouvir começa enquanto ela ainda fala (1.21): a cada 1,5 s o `Mind` vê se já há um trecho
inteiro gravado (`Pieces.nextWhole`) e o Omni o transcreve na hora (`Ears.ahead`); quando ela para, falta só o
último trecho (até 28 s), e a tela já mostra o que estava pronto. O corte é sempre o mesmo (`Pieces.cut`): até
28 s, no meio da pausa mais longa dos últimos 10 s antes do limite (fim de frase, respiração; ≥ 120 ms abaixo de
~13 dB da voz), senão no instante mais quieto — cada corte só depende dos 28 s depois do começo do trecho, então
o que foi ouvido durante a fala é exatamente o trecho que a gravação inteira teria. O `heardBy` marca a regra do
corte (`local:28p`): trecho ouvido com outro corte recomeça. Ouvir guarda cada trecho pronto na captura (`heard`, `heardPieces`, `heardBy`): se o app cai,
continua do trecho seguinte; `tries` conta execuções que nunca terminaram e, a partir de 3, a captura só volta à mão
(nada de cair em loop ao abrir). O modelo carrega enquanto ela ainda fala (`warm`), o motor usa só os núcleos rápidos
(≥ 70 % da frequência máxima do mais rápido) e a API é pedida com `reasoning_effort: low` (sem ele, se o servidor recusar).
Enquanto ouve, a tela mostra o fim do que já foi transcrito.
Gramática barata: a cada passo o motor pega o token de maior logit e confere só ele na gramática; só quando não
cabe a gramática corre o vocabulário todo (~125k no LFM3). Correr tudo a cada passo deixava o organizar no celular
em minutos. O prompt pede pra deixar de fora campos vazios/null (menos tokens a gerar).
Enquanto organiza, a tela tem um "parar": solta o modelo (cancel nativo) ou o pedido (OkHttp com tag), cancela o
trabalho e a fila segue na hora; a captura fica `stopped` (com o texto), só volta por "Tentar de novo" ou pelo
"esperando · tentar agora". Qualquer falha (até `Error` do motor) termina na tela, nunca num "Organizando…" eterno.
Se a API recusa o pedido (400/415/422), `Thinking` tenta de novo sem a imagem e depois sem
`response_format`. Lembrete que venceu com o celular desligado toca quando ele liga; tarefa feita
perde o lembrete.

## Visual

Aconchego: linho quente com textura de papel (`Modifier.paper()`, um ladrilho de grão desenhado
uma vez), tinta marrom e um acento só (terracota `#C2593A`; `#E8875F` no escuro, que é cacau
sob abajur). Letras: Fraunces (serifa macia, desenhada pro tamanho — `opsz`) nos títulos, nas
abas, na resposta e no texto do diário; Nunito (redonda) pra ler e fazer; Caveat (à mão) só nas
datas do diário. Os cartões, a busca e o "Hoje" têm uma costura tracejada por dentro da borda
(`Modifier.stitched`), as fotos do dia também (linha clara). O botão é um novelo: a bola com as
voltas de linha, o miolo limpo pro microfone, um ponto de costura em volta e a ponta solta embaixo
à direita (`drawYarn`, `drawStitchRing`, `drawLooseEnd` em `ui/home/Orb.kt`, os mesmos na prévia
do Tema). Ícone do app, splash e marca (`NoveloMark`) são o novelo no acento.

Em Ajustes › Tema dá pra trocar o modo (Escuro/Claro/Sistema) e a cor do acento (`NoveloAccents`:
Terracota, Framboesa, Caramelo, Oliva, Azul, Sálvia, Ameixa). Os ids são os de antes (a escolha
salva e as entradas do ícone no launcher continuam valendo): `vermilion` virou terracota,
`graphite` virou caramelo. O que vai sobre o acento é branco quente ou tinta, o que tiver mais
contraste (`onAccentFor`). A Home tem o botão e, embaixo, câmera · Hoje · busca; a biblioteca tem
a busca no alto e uma linha de palavras (Hoje, Tarefas, Notas, Diário, Galeria, Tudo) com uma
costura sob a escolhida. A aba Conexões (o grafo, `ui/graph`) está escondida por enquanto: `TABS`
em `LibraryScreen` deixa `Filter.LINKS` de fora. Cartões do mesmo grupo encostam (2 dp, cantos
redondos só nas pontas). Ajustes (1.22) é uma página só: Inteligência, Tema, Backup e Idioma, cada um uma linha dizendo em
poucas palavras como está (no acento quando falta algo) que abre ali mesmo, um de cada vez (`Fold`): o que abre
fica dentro do mesmo cartão, com os cartões de dentro na cor da página (1.22.1). Embaixo, o Novelo e a atualização.
O atalho por gesto da 1.22.0 saiu na 1.22.1.
Inteligência mostra só "Quem pensa" (Novelo ou API; ouvir é sempre do celular) e o que a escolha pede — o
download do ouvido (sempre) e, quando a API pensa, o endereço e a chave dela, com a conexão testada sozinha um instante depois de mudar. O idioma da fala é
sempre automático (sem opção) e o backup vai sozinho todo dia (sem frequência a escolher). No diário, as fotos do dia vêm antes do texto, que fica numa página
costurada.

O nome mudou de Fio pra Novelo na 1.17.0: muda o que aparece (nome, ícone, textos, a pasta do
backup e a da galeria). Na 1.24.0, rumo à Play Store, virou Novelo em tudo: o app é `com.villamediana.novelo`
(o nome do pacote na Play não muda nunca mais), e o código (`namespace`, pastas, classes `Novelo*`, o motor
`novelo_engine`), o repositório (`Villamediana/Novelo`) e a chave de release (`~/keystores/novelo-release.jks`,
o mesmo certificado) também. Com o id novo o celular trata como outro app — o de antes (`com.villamediana.fio`)
continua instalado, e os dados passam pelo backup (exportar no antigo, importar no novo). Só o que lê backup
antigo segue com o nome de antes: a pasta `Fio/` e os arquivos `fio <data>.json`.

Navegação por deslize: as telas ficam numa linha, câmera · tela principal · biblioteca (Hoje,
Tarefas, …, Tudo). Na principal, deslizar pra direita abre a câmera e pra esquerda a Hoje
(`swipeNav`); na biblioteca as abas são um `HorizontalPager`, e na Hoje deslizar pra direita volta
(o `NestedScrollConnection` pega o que sobra da primeira página; a inclinação volta a zero sempre, mesmo
indo pra tela principal ou com um toque rápido no meio — senão, voltando logo, a Hoje ficava torta). O botão e o grafo ficam com o
próprio toque, então ali não há deslize de tela. As transições seguem o mesmo lado.

Escrever em vez de falar: o lápis leve no canto da tela principal abre um campo; o texto vira uma captura
`source = "text"` com `transcript` já preenchido e vai direto pro organizar (o modelo lê "O QUE ELA ESCREVEU").
O que ela escreve fica guardado enquanto escreve (`data/Draft`, SharedPreferences "draft", 24 h depois da última
mudança): um toque fora que fecha o campo, ou o app fechado, não perde nada; some quando ela manda.

Ações que se entendem pelo desenho são ícones (`IconAction`, com o nome pro leitor de tela): cancelar a
gravação (× solto, sem círculo, bem discreto, no modo toque), parar o organizar (■), tentar de novo (↻), descartar (lixeira), baixar/parar/apagar o
modelo, testar a API, prazo (calendário), lembrete (sino) e etapas (lista). Status, abas e "Ver feitas" seguem em
texto. Na tarefa, etapas só aparecem quando existem e "O que você disse" fica recolhido.

Nada dito duas vezes. O item é um texto só: notícia de algo que já existe entra no próprio texto (`append`,
só o que é novo, como continuação), sem "Andamento" à parte; a situação está nos status. A tela do item não
tem Conexões (os tópicos repetiam o texto e ficam na aba Conexões). O banco v3 (`MIGRATION_2_3`) juntou o
andamento antigo ao texto de cada item. Nos cartões o dia aparece uma vez: lembrete no dia do prazo mostra só a
hora, e na aba Hoje nada diz "hoje" (nem o status em "Em andamento").

## Pacotes

| Pacote | O quê |
|---|---|
| `audio/` | `Recorder`, `Wav` (ler/escrever, `Pieces.cut` no silêncio), `MicService` (um serviço em primeiro plano só: microfone enquanto grava, dataSync + wake lock enquanto transcreve e organiza, pra minimizar não parar nada) |
| `ai/Api` | OpenAI-compatível: `models`, `transcribe`, `chat`; chave em `Authorization: Bearer` e `api-key` |
| `local/` | `LocalEngine` (JNI → `app/src/main/cpp/novelo_engine.cpp`), `LocalModel` (DownloadManager + SHA-256) |
| `mind/` | `Mind` (orquestra), `Brain` (prompt, contexto, parse, datas, GBNF), `Keeper` (grava o Plan), `Recall` (busca + grafo), `Meaning` (busca por sentido com o Omni), `Engines` (Hearing/Thinking por Ajustes), `PhotoText` (ML Kit OCR + JPEG) |
| `data/` | Room: `entries`, `captures`, `entities`, `entry_topics`, `links`, `progress`; `Library` (ações da UI) |
| `remind/` | `Reminders`, `ReminderReceiver` (Feito · Daqui a 1 h · Falar), `RearmReceiver` |
| `settings/` | DataStore + `Vault` (AES-GCM com chave no AndroidKeyStore) |
| `backup/` | `Backup` (salvar/importar), `Readable` (Markdown + snapshot), `Shelf` (pasta SAF ou disco), `Backups` + `BackupWorker` (Ajustes, WorkManager) |
| `update/` | cópia do atualizador do FreeSong (GitHub releases → PackageInstaller), repo `Villamediana/Novelo` |
| `ui/` | `home/Orb` + `HomeScreen`, `library`, `entry`, `settings`, `camera` (CameraX), `graph` (força), `Nav` |

## O prompt

`Brain.SYSTEM` descreve as ideias (o que é tarefa, nota, diário, notícia, pergunta), nunca listas de
palavras-gatilho. O modelo decide; o código só valida (tipos conhecidos, ids que existem, datas futuras).

Fotos (1.13.0): a foto tirada no Novelo (`Capture` source=photo, arquivo em filesDir/captures) é do
dia — se o dia não tem diário, `Mind.diaryFor` cria um com a data de título (`DayDiary.title`). O
diário do dia (o mais novo daquele dia) mostra as fotos do dia num carrossel (`ui/entry/DayPhotos`,
`PhotoRow`), o cartão do Diário mostra até 4 miniaturas, a aba Galeria é um mosaico de duas colunas
(`ui/library/Gallery`, `LazyVerticalStaggeredGrid`) e um toque abre `PhotoViewer` (tela cheia,
desliza, legenda + data). O Omni, no celular, diz o que se vê (`mind/FileAbout.kt`, `About.photo`;
desde a 1.31.1 — antes era o `photo_description` do modelo online) → `Capture.description` (banco v4,
AutoMigration 3→4). `PhotoRecall` acha fotos pela descrição + texto lido (OCR), do mesmo jeito do
`Recall`: na busca da biblioteca (faixa "Fotos" no topo) e nas perguntas — o contexto ganha
`FOTOS QUE PODEM TER A VER` / `FOTOS ACHADAS` (`foto#id`), o modelo devolve `show_photos` e a
resposta na Home mostra as fotos (`Capture.shownPhotos`).

Diário (1.12.0): todo relato do dia vai pro diário, mesmo quando também é notícia de uma tarefa —
aí as duas coisas: o item recebe só o que muda pra ele, o diário o relato inteiro. Um diário por dia:
`Brain.todaysDiary` (o mais novo criado hoje) vai no contexto como `DIÁRIO DE HOJE` (texto inteiro,
fora das outras listas) e o relato entra nele por `update.append` — parágrafo novo no fim, sem mexer
no que já está nem no título; sem diário hoje, cria.

Galeria (1.14.0): ao lado da câmera, na tela principal, o ícone da galeria abre o seletor de fotos do
Android (`PickMultipleVisualMedia`, só imagens, quantas ela quiser — o app não põe limite). Cada foto é
copiada pra filesDir/captures (a da galeria fica onde está) e vai pelo mesmo caminho da foto tirada no
app (`Mind.photos`, uma depois da outra, na ordem escolhida). A data é a de quando foi tirada: o EXIF
(`PhotoDate`, DateTimeOriginal com o fuso dele quando há), senão a data da galeria (MediaStore), senão
agora — então a foto cai no diário daquele dia e na busca por período. Quando é de outro dia, o
contexto diz `O QUE ESTÁ NA FOTO (TIRADA EM …)` pro modelo não tratar como coisa de hoje.

Mais na 1.14.0:

- **Mosaico** (`ui/components/Mosaic`): fotos da mesma câmera têm todas o mesmo formato, então cada uma
  ganha um formato pelo lugar (`Mosaic.shape`, recorte) e as linhas vão de ponta a ponta com larguras
  diferentes (`Mosaic.rows`): galeria, carrossel do diário e a faixa do cartão do diário (`Mosaic.strip`, até 4).
- **Descrição só pra busca**: escrita pelo Omni antes de organizar (uma olhada, no idioma do app; as fotos
  mandadas juntas lado a lado numa imagem só), guardada e passada ao modelo como `O QUE SE VÊ (o celular
  olhou)`. Seja qual for o modelo que pensa (o do Novelo ou a API dela), ele recebe só esse texto: a foto não sai do celular.
  `Capture.description` nunca aparece na tela (a 1.23.0 mostrou
  embaixo da foto; a 1.23.1 tirou a pedido). O prompt diz que o que se vê na foto não vai pro diário nem pra
  item (a foto já está no diário do dia).
- **Girar**: no visualizador, um menu leve embaixo gira a foto um quarto pra direita, com animação e escala
  que acompanha; o giro fica em `captures.turn` (banco v5) e o arquivo não é mexido (`uprightBitmap(turn)`).
- **Câmera da frente**: botão ao lado do disparador quando o celular tem as duas; sem flash na da frente.
- **Um diário por dia de verdade**: criar diário num dia que já tem vira continuação (`Keeper`); só entra
  parágrafo novo, e o dia contado de novo do começo toma o lugar (`DayDiary.merge`, vale pro append de
  diário). Dias que ficaram com dois viram um ao abrir o app e depois de importar (`Library.joinDayDiaries`).
- **ANTES DISSO**: o contexto leva a última conversa (o que ela disse, a resposta, os itens com id) pra
  "essa tarefa" ter referência; o prompt diz que é só pra entender, nunca pra criar de novo.
- **Etapas**: notícia de tarefa se divide — a fazer vira `add_steps`, feito vai em `steps_done`/`status`,
  o que completa vai em `append`, o que muda a tarefa reescreve `update.body`. Nada em dois lugares.
- **Deslize**: a inclinação da tela principal volta a zero sempre que o dedo solta (antes ficava torta
  quando ela voltava antes do fim da animação e a mesma tela era reaproveitada).

Na 1.15.0:

- **Tarefa feita sai do contexto**: o que o modelo recebe sozinho (lembranças, últimas coisas, ANTES DISSO)
  deixa de fora tarefa `done`; só a busca que ele pede (pra responder sobre o passado) traz de volta.
- **Contexto enxuto**: o que já está escrito acima é só apontado (`#id (acima)`); lembranças de 12 pra 8;
  a `Recall` com tudo e o índice das fotos só são montados quando precisam.
- **Foto**: a miniatura segue o giro na hora (`rememberPhoto` decodifica de novo quando o giro muda e mostra
  a de antes até a nova ficar pronta). Foto tirada na câmera do app vai sozinha pra galeria do celular na hora
  (`AppGraph.taken` → `ToGallery.save`, depois `Mind.stage`). No visualizador, **Baixar** (`data/ToGallery`: MediaStore em
  Imagens/Novelo, giro no EXIF da cópia, data em que foi tirada) e **Remover** (pede um toque a mais;
  `Library.removePhoto` tira a captura e o arquivo, o que foi guardado dela fica).
- **Conexões escondida**: `TABS` em `LibraryScreen` deixa `Filter.LINKS` de fora; o grafo fica em `ui/graph`.
- **Áudio que vale guardar**: a gravação continua sumindo depois de ouvida, a não ser que o modelo marque
  `keep_audio` (ela canta uma melodia pra lembrar, mostra um som). Aí `captures.sound` (banco v6) e
  `audioPath` ficam; o áudio aparece nos itens que a gravação criou ou mudou (`data/Sounds`), com um player
  leve (`ui/entry/SoundRow`: barras de `Wav.levels`, só anda enquanto toca), e o backup leva em
  `Áudios/<ano>` (com `.nomedia`), põe `[Áudio](…)` no Markdown do item e traz de volta na importação.

- **Fotos esperam e vão com o que ela diz**: câmera e galeria põem as fotos em `Mind.stage` (arquivos em
  `captures/.staged`, sobrevivem ao app fechar); a Home mostra a faixa (× tira uma, seta = `sendStaged`, o
  caminho de antes). Fala ou texto em seguida levam as fotos (`attach`: capturas de foto `DONE` com `partOf`):
  uma chamada só, a imagem é uma grade com todas (`PhotoReader.jpeg(files)`, até 9), o contexto ganha
  `AS FOTOS QUE ELA MANDOU JUNTO` e o modelo marca `"photos": true` no item a que pertencem
  (`Outcome.photos`; mais fotos depois com `update.photos`). `data/Attached` diz as fotos de cada item:
  `EntryPhotos` na tela, Markdown do item no backup. Se o que ela disse não vai adiante, as fotos seguem
  sozinhas (`letGo`).
- **Modelo local mais rápido**: o estado depois das instruções (~1.700 tokens) fica num arquivo
  (`cacheDir/model/prompt-<modelo>-<tamanho>-<versão>.bin`, `read_cache`/`write_cache` no
  `novelo_engine.cpp`) e é preparado enquanto ela fala (`nativePrime` no `warm`). Antes se perdia quando o
  modelo era solto (5 min parado) ou o app fechava.
- **Tema fora das telas** (`ui/theme/Look`): a abertura segura até as configurações carregarem; claro/escuro
  pelo `UiModeManager.setApplicationNightMode` (12+), splash na cor dela por `setSplashScreenTheme` (13+,
  `Theme.Novelo.Splash.<Cor>`), ícone por `activity-alias` (`.MainActivity` é o vermelhão de sempre, as outras
  cores `.MainActivity<Cor>`), trocado no `onStop`. A tela real é `NoveloActivity`.

Na 1.15.1: o molde de resposta no fim das instruções mostra só campos usados (antes listava todos com
null/[]/false e um modelo pequeno copiava, escrevendo à toa no celular).

## Primeiros passos, provedores e idioma (1.18.0)

- **1.30.0 — sensível e separação das fotos**: Ajustes › **Privacidade** (`PrivacySection`): "Pedir a digital pra
  ver o que é sensível" (`lockSensitive`) — o "Toque pra ver" passa pelo bloqueio do celular (`ui/lock/Unlock`,
  androidx.biometric: digital/rosto ou o PIN do aparelho; ligar e desligar também pedem); a `NoveloActivity` é
  `FragmentActivity` por isso. Num item sensível as fotos ficam borradas (cobertas antes do Android 12) com um
  cadeado até ver; tocar nelas pede como o texto. **De quem é cada foto** (`Attached`): mandada junto com algo
  dito e marcada pro item (`photos: true`), ou mandada sozinha e que virou/mudou o item (`madeFrom`). As que são de
  uma **nota** ou de algo **sensível** (`keptInside`) vivem só dentro do item: fora da Galeria, do diário do dia e
  da busca de fotos (`Library.loosePhotos`, `photos(day)`); as de momentos e de tarefas seguem soltas.

- **1.29.1**: ordem permissões → **ouvido** → backup → quem pensa. O ouvido **nunca baixa sozinho**: ela concorda
  (Baixar no Wi-Fi; fora dele, esperar o Wi-Fi ou usar dados móveis); com o download andando ela segue, e o
  "Começar" do último passo espera o ouvido no celular ("Esperando o ouvido · 63%"). A pasta do backup é
  **obrigatória** (sem "Depois"), com a dica de escolher um drive que apareça na lista (o app do Google Drive em
  geral não aparece no seletor de pastas do Android).

- **1.29.0**: na inteligência própria o botão só segue com tudo pronto (de quem é a IA → chave → conectado →
  modelo; `OwnReady`), dizendo o que falta; de quem é a IA é uma linha que abre a lista (`ChoiceLine`), nada
  marcado antes de ela escolher, e os campos só depois. O ouvido: só baixa sozinho no Wi-Fi (nunca dados
  móveis por conta própria), diz quando espera o Wi-Fi ou a internet, avisa quando falta espaço (`local_no_room`,
  checado antes; e o erro de espaço do DownloadManager) e que dá pra sair do app. Idioma troca na hora, sem
  reiniciar (`Lang` com um estado do Compose lido em cada palavra). Ajustes › Backup é só a pasta (com o último
  backup embaixo); escolher uma pasta com backup sempre traz ele (o que o app tinha fica ao lado, datado).
  Fotos: pinça e dois toques pra aproximar; aproximada, o dedo anda nela em vez de passar pra próxima.

- **Desde a 1.27.0**: quatro passos — permissões; **backup** (escolher a pasta; se ela já tem um backup do Novelo e o
  app está vazio, ele volta na hora e a pasta fica ligada a ele: `Backups.chooseAndBringBack`, nunca substitui o que
  já existe); quem pensa; e o **ouvido** (`EarsStep`): os primeiros passos só terminam com o ouvido no celular
  ("Começar" espera; o download começa sozinho no Wi-Fi já depois das permissões). Em Ajustes, a linha "No celular ·
  Ouvido do Novelo" só aparece se o ouvido sumiu (pra baixar de novo): não dá pra apagar, só desinstalando. Tema
  padrão: **claro** (`ThemeMode.of` sem escolha = LIGHT).

- **Primeiros passos** (`ui/setup/SetupScreen`), uma vez (`setupDone`; quem já usava passa por eles uma vez,
  com o que já tem marcado): 1) idioma no topo + permissões (microfone, notificações, câmera) num pedido
  só; 2) a pasta do backup; 3) quem pensa — "Inteligência do Novelo" (o modelo no celular: no Wi-Fi já
  começa a baixar; fora dele pergunta "Esperar o Wi-Fi" (`download(wifiOnly = true)`) ou "Baixar assim
  mesmo") ou "Sua própria inteligência" (`OwnSetup`, o mesmo de Ajustes › Inteligência). Tudo pode
  ficar pra depois; o que faltar aparece no acento em Ajustes.
- **Provedores** (`ai/Providers`): OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter e Outro. Os que
  têm endereço próprio só pedem a chave; Foundry e Outro pedem o endereço. A conexão é testada sozinha
  (lista de modelos). `Wire.ANTHROPIC` fala a Messages API da própria Anthropic (`x-api-key`,
  `anthropic-version`, `output_config.effort`, `fallbacks: "default"` nos modelos que aceitam); os
  outros, o jeito OpenAI. Ouvir é sempre no celular (desde a 1.19): com a API pensando, os primeiros passos
  baixam só o ouvido do Novelo. Sem provedor salvo (configurado antes) = Outro, com o endereço digitado.
- **Idioma do app** (`settings/Lang`): português por padrão (seja qual for o idioma do celular), inglês
  ou espanhol nos primeiros passos e em Ajustes › Idioma; guardado em SharedPreferences pra valer desde
  o primeiro quadro (`NoveloActivity.attachBaseContext`). Todo texto vem de `t(R.string.…)`
  (`values`, `values-en`, `values-es`), datas com `Lang.locale` e padrões por idioma (`fmt_*`). O que vai
  pro modelo (instruções, contexto) e o Markdown do backup ficam em português; o modelo responde no
  idioma em que ela fala.

## Backup (1.14.0; um arquivo só desde a 1.17.1)

**Desde a 1.28.0** (o Miguel perdeu tudo ao ligar a pasta depois de reinstalar):

- **A cada mudança**: `Backups.watch` observa as tabelas do Room (entries, captures, entities, entry_topics,
  links, progress); 4 s depois da última mudança de uma rajada sai um backup (`soon`), e o `BackupWorker.soon`
  (WorkManager, 2 min, KEEP) termina se o app fechar antes. O diário continua por cima. Nada mudou de verdade
  (mesmo SHA) → nada é escrito.
- **Nunca escreve por cima de um backup que não é dele**: se a pasta já tem "Novelo backup.zip" e este app nunca
  escreveu ali (estado vazio pra essa pasta), o arquivo é copiado antes pra "Novelo backup antes de <data>.zip",
  que aparece em Importar e volta inteiro. Escolher a pasta (primeiros passos ou Ajustes) com o app vazio traz o
  backup de volta na hora (`chooseAndBringBack`); depois de trazer, o estado já é o do arquivo (não regrava).
- **Vai tudo**: itens, fotos, áudios guardados, tópicos e ligações (Conexões), etapas/andamento, e também o que
  foi gravado e ainda não foi ouvido (volta como `RECORDED`, com o som, pra ser ouvido).

Ajustes › Backup: ela escolhe uma pasta no seletor do Android (`OpenDocumentTree`, permissão guardada
com `takePersistableUriPermission`) e o backup é **um arquivo só**, `Novelo backup.zip`, direto nessa
pasta, trocado a cada backup (só existe o último). Ao escolher, faz um na hora; depois sozinho
(`BackupWorker`, WorkManager, todo dia, com bateria e espaço; sem pasta, nada). "Fazer agora" e o
resultado do último ficam na mesma linha.

```
Novelo backup.zip
  backup.json                       tudo, com os ids: é o que o importar lê
  Diário/2026/2026-11-11.md         um por diário, com as fotos do dia (links relativos)
  Notas/<título>.md
  Tarefas/Abertas/<título>.md       status, prazo, lembrete, etapas [x]/[ ]
  Tarefas/Feitas/<título>.md
  Fotos/2026/<data hora> (<id>).jpg
  Áudios/2026/<data hora> (<id>).wav
```

- `backup/Backup.kt` (testável com `FileShelf`): lê tudo numa transação (`BackupDao`), monta o zip no
  celular (filesDir/backup-work, apagada no fim) e copia por cima do que está na pasta — só quando algo
  mudou (hash do snapshot em filesDir/backup-state.json). App vazio não escreve nada: instalação nova
  nunca cobre um backup bom. Fala ainda não ouvida (só o áudio) não vai.
- Até a 1.17.0 o backup era uma árvore de pastas (`Novelo/` ou `Fio/` com `Backups/fio <data>.json`).
  Enquanto essa pasta existir, os snapshots dela aparecem no importar e ainda entram; nada mais é escrito
  nela (pode ser apagada à mão).
- `backup/Shelf.kt`: a pasta. `TreeShelf` vai direto no `DocumentsContract` (uma consulta por pasta),
  cria como octet-stream pra nenhum provedor trocar a extensão e escreve com "wt".
- Importar: lista o arquivo (e os snapshots antigos, se houver), mostra quantos itens/fotos tem e troca
  tudo pelo escolhido, com os mesmos ids. O zip abre numa pasta do app (nada sai dela, seja qual for o
  nome lá dentro). Só com o Novelo parado (`Mind.idle`); fotos copiadas antes de mexer no banco,
  lembretes refeitos (`ReminderSync`), e o arquivo segue o que voltou. Backup de versão mais nova
  (`format`) ou estragado não entra. Com um arquivo só, o que estava no app antes de importar não
  fica guardado.
- As entidades do Room são `@Serializable`: o snapshot é o próprio banco em JSON (campos com padrão, chaves
  desconhecidas ignoradas).

## Economia (1.13.0)

- **Celular**: `Brain.chatmlHead(SYSTEM)` (as instruções, ~1.250 tokens) é o começo fixo de toda
  resposta. `nativeGenerate(keep=bytes)` roda esse começo uma vez e guarda o estado do modelo
  (`llama_state_seq_get_data`, retrato inteiro — o LFM tem camadas de convolução que não se cortam
  numa posição); nas próximas respostas `start_from` põe o retrato de volta e só roda o resto.
  Retrato que não volta certo (`seq_pos_max` diferente) é descartado e o começo roda de novo.
  Vale também com foto (`mtmd_helper_eval_chunks` a partir da posição do retrato).
- **Contexto na ordem do cache**: tarefas abertas → diário de hoje → últimas → lembranças/busca →
  fotos → item em foco → AGORA → a fala. O que muda a cada minuto fica no fim: o cache de prompt
  do servidor (automático acima de 1.024 tokens) pega instruções + tarefas + diário.
- **API**: `ApiAsks` — tudo primeiro (JSON, `reasoning_effort: low`, foto com `detail: low`); o
  servidor recusou algo → pede sem aquilo e guarda (por servidor+modelo, nesta execução) só o que
  fez dar certo, pra não pagar a recusa de novo. A foto nunca fica marcada como recusada.
- **Foto pro modelo**: 768 px (o texto dela vem lido pelo ML Kit). Outras fotos no contexto só pra
  fala/texto (não quando a captura é uma foto), até 4 no primeiro olhar, 8 quando o modelo pede busca.
- **Tela**: índice de busca de fotos montado uma vez por lista; proporção das fotos lida fora da
  thread principal (`rememberAspects`); miniaturas decodificadas no tamanho certo e guardadas num LRU.

## Motor no celular

`novelo_engine.cpp` usa só as APIs C de `llama.h`, `mtmd.h` e `mtmd-helper.h` (llama.cpp v0.9.0):

- `transcribe`: `mtmd_bitmap_init_from_audio` + prompt ChatML com `Perform ASR.` (idioma automático),
  `mtmd_helper_eval_chunks`, guloso, sem `<|audio_start|>`/`<|mixed_start|>` (resposta só texto).
- `generate`: um prompt pronto e uma foto no marcador de mídia, texto saindo: o Omni diz o que vê na foto (pra
  Inteligência do Novelo, que pensa só com texto).

O prompt de ASR é o mesmo do `llama-server` pro LFM3 (e o do card do LFM3-Omni) (`common_chat_get_asr_prompt`): sistema
`Perform ASR in <idioma>.` e o áudio sozinho na fala do usuário.

O build só inclui o nativo com `-Pnovelo.llama=<pasta do llama.cpp>`. O `release.sh` baixa o llama.cpp
numa pasta temporária (`tools/fetch-llama.sh`), compila e apaga tudo no fim (a pasta, `app/.cxx` e o
que o CMake gerou): nada do motor nem do modelo fica no servidor; o modelo só existe no celular,
baixado do Hugging Face. Sem o nativo, `LocalEngine.available` é falso e Ajustes diz que não vem.
Arquivos (`LocalModel`): `LFM3-Omni-2B-Q4_0.gguf` (1,05 GB) + `mmproj-LFM3-Omni-2B-Q8_0.gguf` (507 MB, visão e
áudio), o "Ouvido do Novelo". Uma barra só: as partes vêm uma depois da outra (`wanted.txt` lembra o pedido se o
app fechar), a notificação diz "Novelo" e não o nome de modelo, cada parte tem seu `.ok` com o SHA-256 conferido.
Fora do Wi-Fi o "Baixar" pergunta antes (esperar o Wi-Fi ou baixar assim mesmo), nos primeiros passos e em
Ajustes. Quando termina, o que esperava por ele vai sozinho (`AppGraph` → `retryPending`). Ouvir depende dele:
desde a 1.20.1 não há como apagá-lo em Ajustes (ele vem nos primeiros passos e fica). Até a 1.6 quem ouvia era o LFM3-Audio;
na 1.19 o celular pensava com d1-2B + LFM3-1.7B (saiu na 1.20: lento pra fala do dia a dia). O que sobrou disso
é apagado ao abrir (`LocalModel.LEFTOVERS`).

## Inteligência do Novelo (1.20.0)

Quem pensa é sempre online: a Inteligência do Novelo (padrão, nada a configurar) ou a dela (a API que ela põe).
O mesmo prompt (`Brain.SYSTEM` + `Brain.context`) e o mesmo `Plan` nos dois.

- **Modelos grátis da OpenRouter** (`ai/NoveloModels`): a lista vem da própria OpenRouter (`/api/v1/models`, a
  cada 12 h; a guardada quando não dá pra ler) e fica só o que é grátis, texto que entra e só texto que sai, com
  contexto ≥ 32k e que não seja o roteador `openrouter/free`. Um grupo de 10, nesta ordem: o último que respondeu,
  os medidos no lab (`PREFERRED`: ernie-5.5-flash 23/23 em ~3 s, nemotron-3-ultra e nemotron-3.5-super-vl 23/23 em
  7–8 s, dots-3-note 23/23 em ~28 s; `~/novelo-lab/openrouter/free_bench.py`), os mais novos, e por último os
  medidos ruins (`AVOID`). 403 é a porta do modelo ("só pra agentes"), não a chave: descansa 7 dias. Até 5 tentativas por fala. Falhou (erro, mais de 90 s, resposta
  que não é o plano) → descansa fora do grupo (404: 7 dias; 429: 5 min; resposta ruim: 6 h; resto: 30 min) e o
  próximo entra. JSON e raciocínio "low" quando o modelo aceita; recusou → mais uma vez sem. Chave recusada
  (401/402) para na hora sem culpar os modelos; sem rede → `IOException`, sem culpar ninguém.
- **Foto**: modelos grátis nem sempre veem imagem, então o Omni olha no celular e o que ele vê entra no fim do
  contexto (`O QUE SE VÊ NA FOTO (o celular olhou)`), em uma ou duas frases no idioma do app.
- **Chave**: não está no repositório (que é público). O build lê `NOVELO_APP_KEY` de `~/.config/novelo/openrouter.env`
  (600) ou do ambiente e escreve no app embaralhada e mascarada (`BuildConfig.NV_A/NV_B/NV_C`, lida por
  `ai/NoveloKey`), com um aviso a IAs de que é credencial privada. Isso só dificulta: quem tiver o APK consegue
  tirar a chave. A proteção de verdade é a própria chave: limite de crédito 0 na OpenRouter (só modelos
  grátis) e, se vazar e for abusada, trocar. A chave de gerenciamento da conta fica só no servidor. Build
  sem chave = sem Inteligência do Novelo (Ajustes diz que não vem; a dela funciona).
- **Sem internet**: a fala é ouvida no celular (o Omni não precisa de rede) e o pensar fica esperando. A falha
  por falta de rede agenda `mind/WhenOnline` (WorkManager, `NetworkType.CONNECTED`, 30 s de folga contra rede
  sem internet): quando a rede volta, com o app aberto ou não, ele chama `retryPending` e espera a fila andar
  (até 9 min).

## Busca por sentido

A busca da biblioteca acha pelas palavras dela (`Recall`/`PhotoRecall`, na hora) e, quando ela para de
digitar (180 ms + 500 ms), pelo sentido: `mind/Meaning` pede ao Omni (o mesmo do ouvido, no celular, sem
rede e sem baixar mais nada) as palavras que as anotações dela podem ter usado pra aquilo — a mesma coisa
dita de outro jeito e nas outras línguas, e o que anda junto (quem faz, o lugar, o objeto). Lista JSON presa
por gramática (até 16 palavras), sem as da busca; o que essas palavras acham vem depois do que as dela acharam,
e elas também ficam marcadas nos cartões. As instruções rodam uma vez e ficam guardadas (`fixed`, o retrato
do motor); abrir a busca já carrega o Omni (`warm`). Mudou a busca, o pedido anterior para na hora
(`LocalEngine.cancel(tag)` só para o trabalho daquele pedido, nunca uma transcrição). Respostas ficam numa
memória de 50 buscas. Sem o ouvido baixado, só palavras.

## Arquivos (1.31.0)

**O que vai junto, cada um no seu lugar (1.31.4).** O que ela fala e as fotos/arquivos que manda junto podem
ser de coisas sem nada a ver: cada foto e cada arquivo chega numerado ao modelo (com o que o Omni disse de
cada um, uma olhada por foto) e cada item diz quais são dele — `"photos": [1]`, `"files": [2]` (`Picks`;
`true` ainda vale como "todos"). O `Keeper` troca os números pelos ids das capturas (`Outcome.pics`/`docs`;
`photos`/`files` = todos, como antes) e o `Attached` vê cada foto/arquivo por si. O que não é de item nenhum
fica solto: a foto na Galeria e no diário do dia; o arquivo no diário do dia (só lembrança do dia — documento
sempre tem a nota dele). No diário vai só o relato do dia, nada sobre o que ela mandou. O que vai junto não
conta como "a última conversa" (`lastDone` ignora `partOf`).

O ícone de folha na barra de baixo (ao lado da busca) anexa arquivos de qualquer tipo
(`OpenMultipleDocuments`). `AppGraph.fromFiles` copia cada um pro app e eles esperam na bandeja das fotos
(`Mind.stageFiles`, `Staged.name` ≠ null): enviados assim, cada um vai sozinho (`Capture.FILE`); com o que
ela fala ou escreve, vão junto (`partOf`). O arquivo fica em `captures/files/<quando>-<n>/<nome dele>`, com
nome, tipo e tamanho na captura (Room v7: `filePath`, `fileName`, `fileMime`, `fileSize`).

O app não lê o arquivo: só o começo da primeira página (`mind/FilePeek`, até `Brain.PEEK` = 800
caracteres): texto puro; Word/Excel/PowerPoint/OpenDocument pelo XML de dentro do zip (primeira página,
palavras da planilha, primeiro slide); PDF com a primeira página desenhada (`PdfRenderer`) e lida pelo ML
Kit; imagem pelo ML Kit. Com isso, o nome e o que ela disse, o Omni escreve no celular o que o arquivo é
(`About.file`, uma frase; sem texto nenhum, ele olha a primeira página como imagem — `FilePeek.look`). Isso
fica na captura (a busca) e é o que o modelo online recebe ("o que é: …"), nunca o começo do arquivo (só se o
Omni não disse nada). O modelo online só classifica: põe o número do arquivo em `"files": [n]` do único item onde o arquivo mora — em geral uma nota sobre ele. Sem item marcado (ou com o item apagado depois), o arquivo é do
diário do dia (`Attached.fileHomes`: o primeiro item marcado que ainda existe, senão nenhum).

Na tela: o item mostra seus arquivos debaixo do texto (`ui/entry/Files.kt`, um toque abre no app que ela
escolher, via `FileProvider` em `captures/`); o diário do dia mostra os soltos do dia. Aba **Arquivos**
(`Filter.FILES`): todos, por dia, dizendo onde moram; os de item sensível ficam fora da aba e da busca (no
item, borrados como o resto). A busca acha pelo nome e pela descrição (`FileRecall`, com as palavras do Omni
também). Backup: `Snapshot.files` → `Arquivos/<ano>/<data hora> (<id>) <nome>`, link no Markdown do item, e
o import devolve com o nome dele.

## Lançar versão

Igual ao FreeSong: bump de `versionCode`/`versionName` → commit → branch + PR + merge → na main limpa
`tools/release.sh -n $'- novidade'`. O script confere git, versão, pacote e o certificado
(SHA-256 `f538e500…48bffc`, chave em `~/keystores/novelo-release.jks`), publica e só diz `published`
quando `/releases/latest` bate. Notas = o texto da tela "Nova versão" no celular.

O botão "Atualizar" baixa a versão nova com o app segurando um serviço em primeiro plano (`update/UpdateService`,
dataSync, progresso numa notificação silenciosa, CPU acordada até 20 min): minimizado ou com a tela apagada, o
Android congelava o app e o download caía no meio ("A conexão caiu"). Liga só durante o download
(`Updates.keepAlive`); a instalação é a tela do Android, de volta no app.

## Testes

`./gradlew :app:testDebugUnitTest`: Recall, Brain, Keeper, o caminho inteiro do Mind com motores falsos,
Api contra MockWebServer, Wav/Pieces, o atualizador (copiado do FreeSong) e `ScreensTest`: o app
real com a API num MockWebServer, que salva os prints de cada tela em `app/build/novelo-shots`.

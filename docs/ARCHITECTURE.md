# Fio · arquitetura

## O caminho de um pensamento

```
Orb (segurar / tocar / arrastar pra cima)
  → Recorder (AudioRecord 16 kHz mono → WAV; nível 0..1 a cada 20 ms)   MicService segura com a tela apagada
  → Capture (banco: recorded)
  → Mind.process (fila, um por vez)
      Ears.transcribe     Hearing: LFM3-Omni no celular (pedaços ≤ 28 s) ou API /audio/transcriptions (pedaços ≤ 5 min)
      Recall.search       palavras sem acento (raras pesam mais) + um salto no grafo (tópicos em comum, links)
      Brain.context       AGORA · item em foco (check-in) · últimas coisas guardadas · tarefas abertas · lembranças · a fala
      Thinker.answer      Thinking: LFM3-Omni no celular (gramática JSON GBNF, foto pelo projetor a 768 px) ou API /chat/completions (json_object)
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
(`LocalEngine.clean`). Ouvir guarda cada trecho pronto na captura (`heard`, `heardPieces`, `heardBy`): se o app cai,
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

Papel, tinta e um acento só (vermelhão `#EC4F2A`; `#FF6A43` no escuro, o padrão). Em Ajustes ›
Tema dá pra trocar o modo (Escuro/Claro/Sistema) e a cor do acento (`FioAccents`, como no
FreeSong), com uma prévia da tela principal que muda junto; o que vai sobre o acento usa
`OnAccent` (branco ou tinta, pela luminância da cor). Nada de bloco preto: o
botão, o ícone e as marcações são do acento. A Home tem o botão e, embaixo, câmera · Hoje · busca;
a biblioteca tem a busca no alto e uma linha de palavras (Hoje, Tarefas, Notas, Diário, Tudo,
Conexões) com um ponto do fio sob a escolhida; Conexões é uma aba como as outras (o grafo ocupa o
espaço embaixo). Cartões do mesmo grupo encostam (2 dp, cantos redondos só nas pontas), e os
Ajustes seguem o mesmo desenho: blocos com seletores (`Segmented`) em vez de texto explicando.

Navegação por deslize: as telas ficam numa linha, câmera · tela principal · biblioteca (Hoje,
Tarefas, …, Conexões). Na principal, deslizar pra direita abre a câmera e pra esquerda a Hoje
(`swipeNav`); na biblioteca as abas são um `HorizontalPager`, e na Hoje deslizar pra direita volta
(o `NestedScrollConnection` pega o que sobra da primeira página). O botão e o grafo ficam com o
próprio toque, então ali não há deslize de tela. As transições seguem o mesmo lado.

Escrever em vez de falar: o lápis leve no canto da tela principal abre um campo; o texto vira uma captura
`source = "text"` com `transcript` já preenchido e vai direto pro organizar (o modelo lê "O QUE ELA ESCREVEU").

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
| `local/` | `LocalEngine` (JNI → `app/src/main/cpp/fio_engine.cpp`), `LocalModel` (DownloadManager + SHA-256) |
| `mind/` | `Mind` (orquestra), `Brain` (prompt, contexto, parse, datas, GBNF), `Keeper` (grava o Plan), `Recall` (busca + grafo), `Engines` (Hearing/Thinking por Ajustes), `PhotoText` (ML Kit OCR + JPEG) |
| `data/` | Room: `entries`, `captures`, `entities`, `entry_topics`, `links`, `progress`; `Library` (ações da UI) |
| `remind/` | `Reminders`, `ReminderReceiver` (Feito · Daqui a 1 h · Falar), `RearmReceiver` |
| `settings/` | DataStore + `Vault` (AES-GCM com chave no AndroidKeyStore) |
| `update/` | cópia do atualizador do FreeSong (GitHub releases → PackageInstaller), repo `Villamediana/Fio` |
| `ui/` | `home/Orb` + `HomeScreen`, `library`, `entry`, `settings`, `camera` (CameraX), `graph` (força), `Nav` |

## O prompt

`Brain.SYSTEM` descreve as ideias (o que é tarefa, nota, diário, notícia, pergunta), nunca listas de
palavras-gatilho. O modelo decide; o código só valida (tipos conhecidos, ids que existem, datas futuras).

Fotos (1.13.0): a foto tirada no Fio (`Capture` source=photo, arquivo em filesDir/captures) é do
dia — se o dia não tem diário, `Mind.diaryFor` cria um com a data de título (`DayDiary.title`). O
diário do dia (o mais novo daquele dia) mostra as fotos do dia num carrossel (`ui/entry/DayPhotos`,
`PhotoRow`), o cartão do Diário mostra até 4 miniaturas, a aba Galeria é um mosaico de duas colunas
(`ui/library/Gallery`, `LazyVerticalStaggeredGrid`) e um toque abre `PhotoViewer` (tela cheia,
desliza, legenda + data). O modelo que olha a foto escreve `photo_description` (o que se vê, no
idioma dela, com as palavras que ela usaria pra procurar) → `Capture.description` (banco v4,
AutoMigration 3→4). `PhotoRecall` acha fotos pela descrição + texto lido (OCR), do mesmo jeito do
`Recall`: na busca da biblioteca (faixa "Fotos" no topo) e nas perguntas — o contexto ganha
`FOTOS QUE PODEM TER A VER` / `FOTOS ACHADAS` (`foto#id`), o modelo devolve `show_photos` e a
resposta na Home mostra as fotos (`Capture.shownPhotos`).

Diário (1.12.0): todo relato do dia vai pro diário, mesmo quando também é notícia de uma tarefa —
aí as duas coisas: o item recebe só o que muda pra ele, o diário o relato inteiro. Um diário por dia:
`Brain.todaysDiary` (o mais novo criado hoje) vai no contexto como `DIÁRIO DE HOJE` (texto inteiro,
fora das outras listas) e o relato entra nele por `update.append` — parágrafo novo no fim, sem mexer
no que já está nem no título; sem diário hoje, cria.

## Motor no celular

`fio_engine.cpp` usa só as APIs C de `llama.h`, `mtmd.h` e `mtmd-helper.h` (llama.cpp v0.9.0):

- `transcribe`: `mtmd_bitmap_init_from_audio` + prompt ChatML com `Perform ASR [in <idioma>].`,
  `mtmd_helper_eval_chunks`, guloso, sem `<|audio_start|>`/`<|mixed_start|>` (resposta só texto).
- `generate`: o mesmo modelo como texto, com a gramática JSON.

O prompt de ASR é o mesmo do `llama-server` pro LFM3 (e o do card do LFM3-Omni) (`common_chat_get_asr_prompt`): sistema
`Perform ASR in <idioma>.` e o áudio sozinho na fala do usuário.

O build só inclui o nativo com `-Pfio.llama=<pasta do llama.cpp>`. O `release.sh` baixa o llama.cpp
numa pasta temporária (`tools/fetch-llama.sh`), compila e apaga tudo no fim (a pasta, `app/.cxx` e o
que o CMake gerou): nada do motor nem do modelo fica no servidor; o modelo só existe no celular,
baixado do Hugging Face. Sem o nativo, `LocalEngine.available` é falso e Ajustes diz que não vem.
Arquivos: `LFM3-Omni-2B-Q4_0.gguf` (1,05 GB) + `mmproj-LFM3-Omni-2B-Q8_0.gguf` (507 MB, visão e áudio). Um modelo
só ouve (mesmo prompt de ASR), organiza e vê a foto (`nativeGenerate` com a imagem no marcador de mídia; se a
foto não decodificar, segue só o texto). Até a 1.6 quem ouvia era o LFM3-Audio; os arquivos dele são apagados
do celular ao abrir (`Spec.LEFTOVERS`).

## Lançar versão

Igual ao FreeSong: bump de `versionCode`/`versionName` → commit → branch + PR + merge → na main limpa
`tools/release.sh -n $'- novidade'`. O script confere git, versão, pacote e o certificado
(SHA-256 `f538e500…48bffc`, chave em `~/keystores/fio-release.jks`), publica e só diz `published`
quando `/releases/latest` bate. Notas = o texto da tela "Nova versão" no celular.

## Testes

`./gradlew :app:testDebugUnitTest`: Recall, Brain, Keeper, o caminho inteiro do Mind com motores falsos,
Api contra MockWebServer, Wav/Pieces, o atualizador (copiado do FreeSong) e `ScreensTest`: o app
real com a API num MockWebServer, que salva os prints de cada tela em `app/build/fio-shots`.

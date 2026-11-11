# Fio · arquitetura

## O caminho de um pensamento

```
Orb (segurar / tocar / arrastar pra cima)
  → Recorder (AudioRecord 16 kHz mono → WAV; nível 0..1 a cada 20 ms)   MicService segura com a tela apagada
  → Capture (banco: recorded)
  → Mind.process (fila, um por vez)
      Ears.transcribe     Hearing: LFM3 no celular (pedaços ≤ 28 s) ou API /audio/transcriptions (pedaços ≤ 5 min)
      Recall.search       palavras sem acento (raras pesam mais) + um salto no grafo (tópicos em comum, links)
      Brain.context       AGORA · item em foco (check-in) · últimas coisas guardadas · tarefas abertas · lembranças · a fala
      Thinker.answer      Thinking: LFM3 (gramática JSON GBNF) ou API /chat/completions (json_object)
      Brain.parse → Plan  reply · create[] · update[] · search[] · period{from,to} · show[]
        se só procura: Recall com os termos e/ou os itens daqueles dias (guardado, feito ou vence),
        e outra chamada com os resultados; até 2 procuras, somando o que achou
      Keeper.apply        cria/atualiza entries, tópicos (entities/entry_topics), links, progress
      Reminders.sync      AlarmManager exato (USE_EXACT_ALARM), religado no boot/atualização
  → MindState.Answer (resposta + cartões do que foi criado/mudado/mostrado)
```

Nada se perde: sem rede ou sem Ajustes, a captura fica `recorded`/`failed` com o áudio, e volta à
fila quando o app abre, quando os Ajustes mudam ou no "N coisas esperando · tentar agora".
O áudio é apagado depois que a captura vai até o fim.

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

## Motor no celular

`fio_engine.cpp` usa só as APIs C de `llama.h`, `mtmd.h` e `mtmd-helper.h` (llama.cpp v0.9.0):

- `transcribe`: `mtmd_bitmap_init_from_audio` + prompt ChatML com `Perform ASR [in <idioma>].`,
  `mtmd_helper_eval_chunks`, guloso, sem `<|audio_start|>`/`<|mixed_start|>` (resposta só texto).
- `generate`: o mesmo modelo como texto, com a gramática JSON.

O prompt de ASR é o mesmo do `llama-server` pro LFM3 (`common_chat_get_asr_prompt`): sistema
`Perform ASR in <idioma>.` e o áudio sozinho na fala do usuário.

O build só inclui o nativo com `-Pfio.llama=<pasta do llama.cpp>`. O `release.sh` baixa o llama.cpp
numa pasta temporária (`tools/fetch-llama.sh`), compila e apaga tudo no fim (a pasta, `app/.cxx` e o
que o CMake gerou): nada do motor nem do modelo fica no servidor; o modelo só existe no celular,
baixado do Hugging Face. Sem o nativo, `LocalEngine.available` é falso e Ajustes diz que não vem.
Arquivos: `LFM3-Audio-2B-Q4_0.gguf` (1,05 GB) + `mmproj-LFM3-Audio-2B-Q8_0.gguf` (304 MB).

## Lançar versão

Igual ao FreeSong: bump de `versionCode`/`versionName` → commit → branch + PR + merge → na main limpa
`tools/release.sh -n $'- novidade'`. O script confere git, versão, pacote e o certificado
(SHA-256 `f538e500…48bffc`, chave em `~/keystores/fio-release.jks`), publica e só diz `published`
quando `/releases/latest` bate. Notas = o texto da tela "Nova versão" no celular.

## Testes

`./gradlew :app:testDebugUnitTest`: Recall, Brain, Keeper, o caminho inteiro do Mind com motores falsos,
Api contra MockWebServer, Wav/Pieces, o atualizador (copiado do FreeSong) e `ScreensTest`: o app
real com a API num MockWebServer, que salva os prints de cada tela em `app/build/fio-shots`.

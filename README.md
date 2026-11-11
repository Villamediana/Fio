# Fio

Um botão pra tirar as coisas da cabeça.

Segure e fale (ou toque pra deixar ouvindo). O Fio transcreve, entende o que é e guarda no lugar certo:

- **Tarefas**: com prazo, lembrete, etapas e status (a fazer, fazendo, esperando, feito).
- **Notas**: ideias, informações, senhas (escondidas até você tocar).
- **Diário**: o relato do dia. As tarefas e notas que aparecem nele viram itens próprios.

Também dá pra **perguntar** ("o que eu tenho pra hoje?", "quando falei do contrato?", "qual era a senha do wifi da minha mãe?"): o Fio procura no que você guardou e mostra o trecho. Pode **dar notícia** de algo ("terminei a planilha", "o fornecedor respondeu"): ele atualiza o item, marca etapas e guarda o andamento. Em tarefas longas, ele mesmo marca um momento pra perguntar como está.

Pessoas, lugares, projetos e assuntos citados viram nós de um **grafo**. É ele que liga as lembranças entre si e ajuda a achar as coisas.

A **câmera** do app lê o que está na foto (um papel, um quadro, um recibo) e guarda do mesmo jeito.

## Onde roda

Em Ajustes você escolhe, separadamente, quem **ouve** (transcreve) e quem **pensa** (organiza):

- **No celular**: LFM3-Audio 2B (Liquid AI) via llama.cpp. São 1,35 GB baixados uma vez e funciona sem internet.
- **Por uma API compatível com OpenAI** (OpenAI, Azure AI Foundry, OpenRouter, um servidor local): você informa o endereço e a chave, carrega a lista e escolhe o modelo. A chave fica cifrada no aparelho (AndroidKeyStore).

Tudo o que você guarda fica no celular. Sai dele só o que vai pra API escolhida.

## Atualização

O app procura versões novas nas [releases](../../releases) deste repositório e se atualiza com um toque. Baixe a APK da última release pra instalar a primeira vez.

## Build

JDK 21, Android SDK 37 e NDK 29 (o NDK só pro motor no celular).

```bash
tools/fetch-llama.sh                         # opcional: llama.cpp pro motor no celular
./gradlew :app:testDebugUnitTest             # testes (FIO_SHOTS: prints das telas em app/build/fio-shots)
./gradlew :app:assembleDebug -Pfio.native=true
tools/release.sh -n $'- novidade'            # versão nova (ver docs/ARCHITECTURE.md)
```

Licenças: llama.cpp (MIT); o modelo LFM3 é baixado do Hugging Face sob a LFM Open License v1.0.

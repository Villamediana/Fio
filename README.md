# Novelo

Um botão pra tirar as coisas da cabeça. (Antes se chamava Fio; o repositório e o pacote `com.villamediana.fio` seguem com o nome antigo.)

Segure e fale (ou toque pra deixar ouvindo). O Novelo transcreve, entende o que é e guarda no lugar certo:

- **Tarefas**: com prazo, lembrete, etapas e status (a fazer, fazendo, esperando, feito).
- **Notas**: ideias, informações, senhas (escondidas até você tocar).
- **Diário**: o relato do dia. As tarefas e notas que aparecem nele viram itens próprios.

Também dá pra **perguntar** ("o que eu tenho pra hoje?", "quando falei do contrato?", "qual era a senha do wifi da minha mãe?"): o Novelo procura no que você guardou e mostra o trecho. Pode **dar notícia** de algo ("terminei a planilha", "o fornecedor respondeu"): ele atualiza o item, marca etapas e guarda o andamento. Em tarefas longas, ele mesmo marca um momento pra perguntar como está.

Pessoas, lugares, projetos e assuntos citados viram nós de um **grafo**. É ele que liga as lembranças entre si e ajuda a achar as coisas.

A **câmera** do app lê o que está na foto (um papel, um quadro, um recibo) e guarda do mesmo jeito.

## Onde roda

Em Ajustes › Inteligência você escolhe, separadamente, quem **ouve** (transcreve) e quem **pensa** (organiza). O idioma da fala é reconhecido sozinho.

- **No celular** (Liquid AI, via llama.cpp, sem internet): o LFM3-Omni 2B ouve, organiza e vê as fotos. São 1,56 GB baixados uma vez, em Ajustes.
- **Por uma API compatível com OpenAI** (OpenAI, Azure AI Foundry, OpenRouter, um servidor local): você informa o endereço e a chave (a conexão é testada sozinha) e escolhe o modelo. A chave fica cifrada no aparelho (AndroidKeyStore).

Tudo o que você guarda fica no celular. Sai dele só o que vai pra API escolhida.

## Atualização

O app procura versões novas nas [releases](../../releases) deste repositório e se atualiza com um toque. Baixe a APK da última release pra instalar a primeira vez.

## Build

JDK 21, Android SDK 37 e NDK 29 (o NDK só pro motor no celular).

```bash
./gradlew :app:testDebugUnitTest             # testes (FIO_SHOTS: prints das telas em app/build/fio-shots)
tools/release.sh -n $'- novidade'            # versão nova, com o motor no celular (ver docs/ARCHITECTURE.md)
```

Licenças: llama.cpp (MIT); os modelos LFM3 são baixados do Hugging Face sob a LFM Open License v1.0.

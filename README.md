# Novelo

Um botão pra tirar as coisas da cabeça. (Antes se chamava Fio; o repositório e o pacote `com.villamediana.fio` seguem com o nome antigo.)

Segure e fale (ou toque pra deixar ouvindo). O Novelo transcreve, entende o que é e guarda no lugar certo:

- **Tarefas**: com prazo, lembrete, etapas e status (a fazer, fazendo, esperando, feito).
- **Notas**: ideias, informações, senhas (escondidas até você tocar).
- **Diário**: o relato do dia. As tarefas e notas que aparecem nele viram itens próprios.

Também dá pra **perguntar** ("o que eu tenho pra hoje?", "quando falei do contrato?", "qual era a senha do wifi da minha mãe?"): o Novelo procura no que você guardou e mostra o trecho. Pode **dar notícia** de algo ("terminei a planilha", "o fornecedor respondeu"): ele atualiza o item, marca etapas e guarda o andamento. Em tarefas longas, ele mesmo marca um momento pra perguntar como está.

Pessoas, lugares, projetos e assuntos citados viram nós de um **grafo**. É ele que liga as lembranças entre si e ajuda a achar as coisas.

A **câmera** do app lê o que está na foto (um papel, um quadro, um recibo) e guarda do mesmo jeito.

## Primeiros passos

Na primeira vez o app pede as permissões, a pasta do backup e quem vai pensar: a inteligência do Novelo (no celular) ou a sua (chave da OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter ou outro endereço). O app fala português, inglês ou espanhol.

## Onde roda

Ouvir é sempre no celular: o LFM3-Omni 2B (Liquid AI, via llama.cpp) transcreve, e o idioma da fala é reconhecido sozinho. Em Ajustes › Inteligência você escolhe só quem **pensa** (organiza):

- **Inteligência do Novelo** (no celular, sem internet): o d1-2B decide o que há na fala (dia, tarefa, nota, pergunta, tarefa feita ou com novidade), o LFM3-1.7B escreve só isso, e o Omni vê as fotos. São 3,7 GB baixados uma vez, numa barra só.
- **Sua própria inteligência** (OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter ou outro endereço): você informa a chave (a conexão é testada sozinha) e escolhe o modelo; o celular baixa só o ouvido (1,56 GB). A chave fica cifrada no aparelho (AndroidKeyStore).

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

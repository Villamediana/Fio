# Novelo

Um botão pra tirar as coisas da cabeça. (Antes se chamava Fio; o repositório e o pacote `com.villamediana.fio` seguem com o nome antigo.)

Segure e fale (ou toque pra deixar ouvindo). O Novelo transcreve, entende o que é e guarda no lugar certo:

- **Tarefas**: com prazo, lembrete, etapas e status (a fazer, fazendo, esperando, feito).
- **Notas**: ideias, informações, senhas (escondidas até você tocar).
- **Diário**: o relato do dia. As tarefas e notas que aparecem nele viram itens próprios.

Também dá pra **perguntar** ("o que eu tenho pra hoje?", "quando falei do contrato?", "qual era a senha do wifi da minha mãe?"): o Novelo procura no que você guardou e mostra o trecho. Pode **dar notícia** de algo ("terminei a planilha", "o fornecedor respondeu"): ele atualiza o item, marca etapas e guarda o andamento. Em tarefas longas, ele mesmo marca um momento pra perguntar como está.

Pessoas, lugares, projetos e assuntos citados viram nós de um **grafo**. É ele que liga as lembranças entre si e ajuda a achar as coisas.

A **câmera** do app lê o que está na foto (um papel, um quadro, um recibo) e guarda do mesmo jeito; a foto também vai pra galeria do celular na hora, e embaixo dela fica escrito o que ela é.

A **busca** acha pelas palavras e pelo sentido: o Omni, no próprio celular, lembra as outras palavras que podem ter sido usadas pra mesma coisa.

## Primeiros passos

Na primeira vez o app pede as permissões, a pasta do backup e quem vai pensar: a inteligência do Novelo (no celular) ou a sua (chave da OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter ou outro endereço). O app fala português, inglês ou espanhol.

## Onde roda

Ouvir é sempre no celular: o LFM3-Omni 2B (Liquid AI, via llama.cpp) transcreve sem internet, e o idioma da fala é reconhecido sozinho. O "Ouvido do Novelo" tem 1,5 GB e é baixado uma vez.

Quem **pensa** (organiza) é escolhido em Ajustes › Inteligência:

- **Inteligência do Novelo** (padrão): pronta pra usar, sem chave e sem custo. Usa modelos grátis da OpenRouter, escolhidos e trocados sozinhos. A foto vai como o que o Omni viu nela.
- **Sua própria inteligência** (OpenAI, Anthropic, Kimi, Azure Foundry, OpenRouter ou outro endereço): você informa a chave (a conexão é testada sozinha) e escolhe o modelo. A chave fica cifrada no aparelho (AndroidKeyStore).

Pensar é pela internet. Sem ela, o que você disser fica guardado e vai sozinho quando a rede voltar. Tudo o que você guarda fica no celular; sai dele só o que vai pra organizar (a fala, as tarefas abertas e o que tem a ver), para a OpenRouter ou para a API escolhida.

## Atualização

O app procura versões novas nas [releases](../../releases) deste repositório e se atualiza com um toque. Baixe a APK da última release pra instalar a primeira vez.

## Build

JDK 21, Android SDK 37 e NDK 29 (o NDK só pro motor no celular). A chave da Inteligência do Novelo não fica no repositório: o build lê `NOVELO_APP_KEY` de `~/.config/novelo/openrouter.env` (sem ela, o app sai só com a inteligência própria).

```bash
./gradlew :app:testDebugUnitTest             # testes (FIO_SHOTS: prints das telas em app/build/fio-shots)
tools/release.sh -n $'- novidade'            # versão nova, com o motor no celular (ver docs/ARCHITECTURE.md)
```

Licenças: llama.cpp (MIT); os modelos LFM3 são baixados do Hugging Face sob a LFM Open License v1.0.

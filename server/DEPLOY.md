# Deploy do servidor do Novelo

Guia pra quem (pessoa ou agente) for colocar este servidor no ar. Ele é **Node puro**: nada de
`npm install`, nada de banco externo. Precisa de **Node 22.5+** (ou Docker) e de um endereço **HTTPS**
público (o app Android só fala com HTTPS).

O que ele faz: o app Novelo não leva chave nenhuma. Ele pede aqui; o servidor sabe o plano de cada
pessoa (teste de 7 dias com modelos grátis → Novelo+ com modelos pagos), conta o dia e a semana
dela, chama o modelo com a chave certa, guarda a telemetria (custo, modelos, falhas) e confere as
assinaturas da Google Play. Tem um painel em `/admin` (chave + código do autenticador).
**Não guarda nada do que a pessoa fala ou escreve** — nem o texto, nem a resposta.

## 1. Pegar o código

```bash
git clone https://github.com/Villamediana/Novelo.git
cd Novelo/server
npm test            # 20 testes, sem rede (devem passar todos)
```

## 2. Segredos

```bash
npm run setup       # cria .env (permissão 600) com TOKEN_SECRET, ADMIN_KEY, ADMIN_TOTP_SECRET e RTDN_KEY
```

O comando mostra **a chave do painel** e o **link do autenticador** (`otpauth://…`). Entregue os dois ao
dono (Miguel) por um canal seguro e **não** os deixe em log, issue ou chat público. Depois, no `.env`:

| Variável | O que é | Onde conseguir |
|---|---|---|
| `OPENROUTER_FREE_KEY` | chave **sem crédito** (limite 0): só modelos grátis — o teste | openrouter.ai → Keys → nova chave com limite US$ 0 |
| `OPENROUTER_PAID_KEY` | chave **com limite de gasto** (ex.: US$ 20/mês): Novelo+ | openrouter.ai → Keys → nova chave com limite |
| `PLAY_PACKAGE` | `com.villamediana.novelo` | já vem |
| `GOOGLE_SERVICE_ACCOUNT_JSON` ou `_FILE` | conta de serviço com acesso ao Play Console | passo 6 (só quando o app estiver na Play) |
| `TRUST_PROXY` | `1` atrás de um proxy HTTPS (o normal) | — |
| `DB_PATH` | o arquivo SQLite (padrão `./data/novelo.db`) | — |

Sem a chave grátis, o teste não funciona; sem a paga, o Novelo+ não funciona; sem a Google, a compra
pela Play responde `billing_not_configured` (o resto funciona).

## 3. Rodar

**Docker (recomendado):**

```bash
docker compose up -d --build      # ouve em 127.0.0.1:8787; os dados ficam em ./data
curl -s http://127.0.0.1:8787/health   # {"ok":true}
```

**Ou direto, com systemd** (`/etc/systemd/system/novelo.service`):

```ini
[Unit]
Description=Novelo server
After=network-online.target

[Service]
WorkingDirectory=/opt/novelo/server
ExecStart=/usr/bin/node --no-warnings src/main.js
Restart=always
User=novelo
EnvironmentFile=/opt/novelo/server/.env

[Install]
WantedBy=multi-user.target
```

## 4. HTTPS na frente

O servidor ouve HTTP só na máquina. Ponha um proxy HTTPS na frente — qualquer um destes:

- **Caddy** (`/etc/caddy/Caddyfile`): `api.seudominio.com { reverse_proxy 127.0.0.1:8787 }`
- **nginx**: `proxy_pass http://127.0.0.1:8787;` com `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` e `X-Forwarded-Proto $scheme;`
- **Cloudflare Tunnel**: `cloudflared tunnel` apontando pra `http://127.0.0.1:8787`.

Com `TRUST_PROXY=1`, o servidor lê o endereço de quem pede do `X-Forwarded-For` (usado pra limitar
contas novas por endereço e tentativas no painel). **Não** exponha a porta 8787 direto na internet.

Opcional e recomendado: proteja `/admin` também na borda (Cloudflare Access, ou uma allowlist de IP no
proxy). O painel já pede chave + código do autenticador, limita 10 tentativas erradas por dia por
endereço, usa cookie `HttpOnly; Secure; SameSite=Strict` de 12 h e uma CSP estrita.

## 5. Ligar o app ao servidor

No repositório do app, `gradle.properties`:

```
novelo.server=https://api.seudominio.com
```

Commit, e o app é publicado de novo (`tools/release.sh` no GitHub, `tools/play-bundle.sh` na Play).
Sem esse endereço o app sai só com "Sua própria inteligência".

Depois de entrar no painel uma vez, marque o celular do dono como **Dono** (Pessoas → Mudar… → Dono):
fica sem limite e fora do teto de gasto.

## 6. Google Play (quando o app estiver na loja)

1. **Assinatura**: Play Console → Monetizar → Assinaturas → produto `novelo_plus`, plano base
   `monthly` (os nomes ficam em Ajustes no painel, se mudar).
2. **Conta de serviço**: Google Cloud → IAM → Contas de serviço → nova → chave JSON. No Play Console →
   Usuários e permissões → convidar o e-mail da conta de serviço com "Ver dados financeiros" e
   "Gerenciar pedidos e assinaturas". Ponha o JSON em `GOOGLE_SERVICE_ACCOUNT_FILE` (arquivo 600) ou
   `GOOGLE_SERVICE_ACCOUNT_JSON`.
3. **Avisos em tempo real (RTDN)**: Google Cloud → Pub/Sub → tópico `novelo-play` → dar a
   `google-play-developer-notifications@system.gserviceaccount.com` o papel "Publicador do Pub/Sub" →
   assinatura **push** pra `https://api.seudominio.com/play/rtdn?key=<RTDN_KEY do .env>`. No Play
   Console → Monetização → Notificações em tempo real → o tópico. "Enviar notificação de teste" deve
   chegar (o servidor responde 204).

O app manda o comprovante da compra pra `/v1/purchase`; o servidor confere com a Google, confirma a
compra (sem isso a Google devolve o dinheiro em 3 dias) e acompanha renovações e cancelamentos.

## 7. Antes do público

No painel → Ajustes:

- **Compras de teste**: o app do GitHub simula a assinatura enquanto isso estiver ligado. **Desligue**
  quando o app estiver aberto ao público (senão qualquer um com o APK do GitHub ganha Novelo+).
- **Modo testadores**: ligado = todo mundo é testador (sem plano nem cobrança no app). Bom no teste
  fechado da Play; desligue no lançamento.
- **Teto de gasto por dia** e os **limites** de cada plano.

## 8. Backup e operação

- O estado inteiro é o arquivo SQLite (`data/novelo.db`, mais `-wal`/`-shm`). Backup quente:
  `sqlite3 data/novelo.db ".backup '/backup/novelo-$(date +%F).db'"` num cron diário (guarde 30 dias).
- Logs: o servidor só escreve uma linha ao subir. Ele nunca loga pedidos (podem ter o que a pessoa disse).
- Atualizar: `git pull && docker compose up -d --build` (as migrações em `migrations/` rodam sozinhas, uma vez cada).
- Trocar `TOKEN_SECRET` desloga todos os apps (eles pegam um passe novo sozinhos). Trocar `ADMIN_KEY` ou
  `ADMIN_TOTP_SECRET` exige reiniciar.

## API (pro app)

| Rota | Pra quê |
|---|---|
| `POST /v1/session {install, device?}` | passe (30 dias) + situação |
| `GET /v1/me` | situação: plano, teste até, Novelo+ até, limites do dia/semana com o usado |
| `POST /v1/organize {system, user, kind, turn}` | a resposta do modelo + situação. 429 `daily_limit`/`weekly_limit`, 402 `no_plan` (teste acabou), 503 `busy` (teto do dia), 502 `model_unavailable` |
| `POST /v1/purchase {store: play\|test, token?}` | assinatura conferida + situação |
| `POST /play/rtdn?key=…` | avisos da Google (Pub/Sub push) |
| `GET /health` | `{"ok":true}` |

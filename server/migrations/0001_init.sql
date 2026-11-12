-- O servidor do Novelo guarda só o necessário pra contar, cobrar e saber quanto custa. Nada do que a
-- pessoa fala, escreve ou fotografa fica aqui: nem o texto, nem a resposta do modelo.

-- Cada pessoa (um celular): o código do aparelho cifrado pelo servidor (ou a instalação, sem ele).
-- plan: o que o painel impõe (tester, owner, blocked); vazio = pelo teste e pela assinatura.
CREATE TABLE accounts (
  id TEXT PRIMARY KEY,
  plan TEXT,
  trial_start INTEGER,
  sub_until INTEGER,
  sub_store TEXT,
  created_at INTEGER NOT NULL,
  last_at INTEGER,
  agent TEXT
);

-- Cada instalação do app (um código aleatório), e de quem ela é.
CREATE TABLE installs (
  id TEXT PRIMARY KEY,
  account TEXT NOT NULL,
  created_at INTEGER NOT NULL,
  last_at INTEGER
);
CREATE INDEX installs_account ON installs (account);

-- O que cada pessoa organizou num dia (horário de Brasília): uma linha por fala/texto ou foto
-- ("turn"), e quantos pedidos ela levou (uma pergunta que procura algo leva dois).
CREATE TABLE usage (
  account TEXT NOT NULL,
  day TEXT NOT NULL,
  kind TEXT NOT NULL,
  turn TEXT NOT NULL,
  requests INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (account, day, kind, turn)
);

-- A telemetria: por dia, plano e modelo, quantos pedidos, quantos deram certo, tokens, custo e tempo.
CREATE TABLE calls (
  day TEXT NOT NULL,
  tier TEXT NOT NULL,
  model TEXT NOT NULL,
  requests INTEGER NOT NULL DEFAULT 0,
  ok INTEGER NOT NULL DEFAULT 0,
  failed INTEGER NOT NULL DEFAULT 0,
  tokens_in INTEGER NOT NULL DEFAULT 0,
  tokens_out INTEGER NOT NULL DEFAULT 0,
  usd REAL NOT NULL DEFAULT 0,
  ms INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, tier, model)
);

-- As assinaturas (Google Play, ou de teste no app do GitHub): o comprovante e até quando vale.
CREATE TABLE subscriptions (
  token TEXT PRIMARY KEY,
  account TEXT NOT NULL,
  store TEXT NOT NULL,
  product TEXT,
  state TEXT,
  until INTEGER,
  updated_at INTEGER NOT NULL
);
CREATE INDEX subscriptions_account ON subscriptions (account);

-- O que o painel muda (limites, modelos, teto, modo testadores…), por cima do padrão do código.
CREATE TABLE config (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL,
  updated_at INTEGER NOT NULL
);

-- Pessoas novas por endereço de internet (cifrado) num dia: ninguém fabrica testes aos montes.
CREATE TABLE mints (
  day TEXT NOT NULL,
  ip TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, ip)
);

-- Tentativas erradas de entrar no painel, por endereço (cifrado) e dia.
CREATE TABLE admin_fails (
  day TEXT NOT NULL,
  ip TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, ip)
);

-- O que foi feito no painel, e quando.
CREATE TABLE audit (
  at INTEGER NOT NULL,
  action TEXT NOT NULL,
  detail TEXT
);

-- O servidor do Novelo guarda só o necessário pra contar e cobrar: quem é cada instalação (um código
-- aleatório do app, sem nome nem e-mail), quanto ela usou em cada dia e quanto o dia custou.
-- Nada do que ela fala ou escreve fica aqui.

-- Cada instalação do app: o plano (free, plus, owner) e quando apareceu.
CREATE TABLE installs (
  id TEXT PRIMARY KEY,
  plan TEXT NOT NULL DEFAULT 'free',
  created_at INTEGER NOT NULL,
  last_at INTEGER,
  agent TEXT
);

-- O que ela organizou num dia (horário de Brasília): uma linha por fala/texto ou foto ("turn"),
-- quantos pedidos ao modelo ela levou (uma pergunta que procura algo leva dois).
CREATE TABLE usage (
  install TEXT NOT NULL,
  day TEXT NOT NULL,
  kind TEXT NOT NULL,
  turn TEXT NOT NULL,
  requests INTEGER NOT NULL DEFAULT 1,
  PRIMARY KEY (install, day, kind, turn)
);

-- Quanto o dia custou no modelo (dólares, como a OpenRouter cobra), pro teto de gasto.
CREATE TABLE spend (
  day TEXT PRIMARY KEY,
  usd REAL NOT NULL DEFAULT 0,
  requests INTEGER NOT NULL DEFAULT 0
);

-- Instalações novas por endereço de internet (cifrado) num dia: ninguém fabrica códigos aos montes.
CREATE TABLE mints (
  day TEXT NOT NULL,
  ip TEXT NOT NULL,
  n INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY (day, ip)
);

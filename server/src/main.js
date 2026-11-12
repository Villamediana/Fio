/**
 * Roda o servidor do Novelo: `node src/main.js` (Node 22.5+). Configuração por variáveis de
 * ambiente ou por um arquivo .env ao lado (veja .env.example e DEPLOY.md).
 */
import { createServer } from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Readable } from 'node:stream';
import { open } from './db.js';
import { handle } from './app.js';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');

/** Um .env simples (CHAVE=valor por linha; # comenta), sem passar por cima do que o ambiente já tem. */
function loadEnv(file) {
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*)\s*$/);
    if (!m || line.trim().startsWith('#')) continue;
    let v = m[2];
    if ((v.startsWith('"') && v.endsWith('"')) || (v.startsWith("'") && v.endsWith("'"))) v = v.slice(1, -1);
    if (process.env[m[1]] === undefined) process.env[m[1]] = v;
  }
}

loadEnv(process.env.ENV_FILE || join(root, '.env'));

const env = { ...process.env };
if (!env.GOOGLE_SERVICE_ACCOUNT_JSON && env.GOOGLE_SERVICE_ACCOUNT_FILE && existsSync(env.GOOGLE_SERVICE_ACCOUNT_FILE)) {
  env.GOOGLE_SERVICE_ACCOUNT_JSON = readFileSync(env.GOOGLE_SERVICE_ACCOUNT_FILE, 'utf8');
}
for (const need of ['TOKEN_SECRET']) {
  if (!env[need] || env[need].length < 32) {
    console.error(`novelo: falta ${need} (32+ caracteres). Rode: npm run setup`);
    process.exit(1);
  }
}

env.DB = open(env.DB_PATH || join(root, 'data', 'novelo.db'), join(root, 'migrations'));
const panel = join(here, 'panel');
env.ASSETS = {
  'index.html': { type: 'text/html; charset=utf-8', body: readFileSync(join(panel, 'index.html')) },
  'panel.js': { type: 'text/javascript; charset=utf-8', body: readFileSync(join(panel, 'panel.js')) },
  'panel.css': { type: 'text/css; charset=utf-8', body: readFileSync(join(panel, 'panel.css')) },
  'fraunces.ttf': { type: 'font/ttf', body: readFileSync(join(panel, 'fonts', 'fraunces.ttf')) },
  'nunito.ttf': { type: 'font/ttf', body: readFileSync(join(panel, 'fonts', 'nunito.ttf')) },
};

const MAX_BODY = 512 * 1024;
const trustProxy = env.TRUST_PROXY === '1';

const server = createServer(async (req, res) => {
  try {
    const size = Number(req.headers['content-length'] || 0);
    if (size > MAX_BODY) {
      res.writeHead(413, { 'content-type': 'application/json' }).end('{"error":"too_long"}');
      return;
    }
    // Quem pede: o primeiro endereço do X-Forwarded-For atrás de um proxy confiável; senão, a conexão.
    const forwarded = trustProxy ? String(req.headers['x-forwarded-for'] || '').split(',')[0].trim() : '';
    const ip = forwarded || req.socket.remoteAddress || '';
    const headers = new Headers();
    for (const [k, v] of Object.entries(req.headers)) {
      if (k === 'x-novelo-ip') continue;
      if (Array.isArray(v)) v.forEach((x) => headers.append(k, x));
      else if (v != null) headers.set(k, v);
    }
    headers.set('x-novelo-ip', ip);
    const proto = trustProxy ? String(req.headers['x-forwarded-proto'] || 'http') : 'http';
    const url = `${proto}://${req.headers.host || 'localhost'}${req.url}`;
    const hasBody = req.method !== 'GET' && req.method !== 'HEAD';
    const request = new Request(url, { method: req.method, headers, body: hasBody ? Readable.toWeb(req) : undefined, duplex: 'half' });
    const response = await handle(request, env);
    const out = {};
    response.headers.forEach((v, k) => {
      out[k] = v;
    });
    res.writeHead(response.status, out);
    res.end(Buffer.from(await response.arrayBuffer()));
  } catch {
    if (!res.headersSent) res.writeHead(500, { 'content-type': 'application/json' });
    res.end('{"error":"server_error"}');
  }
});

const port = Number(env.PORT || 8787);
const host = env.HOST || '0.0.0.0';
server.listen(port, host, () => console.log(`novelo: ouvindo em http://${host}:${port}`));

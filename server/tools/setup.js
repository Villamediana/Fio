#!/usr/bin/env node
/**
 * Gera os segredos do servidor num .env novo (não mexe num que já existe):
 *   TOKEN_SECRET (assina os passes), ADMIN_KEY (a chave do painel), ADMIN_TOTP_SECRET (o autenticador),
 *   RTDN_KEY (os avisos da Google Play).
 * Mostra o link otpauth:// pra pôr no app autenticador (Google Authenticator, 1Password, Authy…).
 *
 *   node tools/setup.js            # cria .env a partir de .env.example
 *   node tools/setup.js --print    # só mostra segredos novos, sem gravar
 */
import { randomBytes } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync, chmodSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const target = join(root, '.env');

const b32 = (bytes) => {
  const a = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567';
  let bits = '';
  for (const b of bytes) bits += b.toString(2).padStart(8, '0');
  let out = '';
  for (let i = 0; i + 5 <= bits.length; i += 5) out += a[parseInt(bits.slice(i, i + 5), 2)];
  return out;
};

const secrets = {
  TOKEN_SECRET: randomBytes(48).toString('base64url'),
  ADMIN_KEY: randomBytes(32).toString('base64url'),
  ADMIN_TOTP_SECRET: b32(randomBytes(20)),
  RTDN_KEY: randomBytes(24).toString('base64url'),
};
const otpauth = `otpauth://totp/Novelo:painel?secret=${secrets.ADMIN_TOTP_SECRET}&issuer=Novelo&algorithm=SHA1&digits=6&period=30`;

if (process.argv.includes('--print')) {
  for (const [k, v] of Object.entries(secrets)) console.log(`${k}=${v}`);
  console.log(`\nAutenticador: ${otpauth}`);
  process.exit(0);
}

if (existsSync(target)) {
  console.error('.env já existe: nada foi mudado. (Pra segredos novos: node tools/setup.js --print)');
  process.exit(1);
}

let text = readFileSync(join(root, '.env.example'), 'utf8');
for (const [k, v] of Object.entries(secrets)) text = text.replace(new RegExp(`^${k}=.*$`, 'm'), `${k}=${v}`);
writeFileSync(target, text, { mode: 0o600 });
chmodSync(target, 0o600);
console.log('.env criado (permissão 600). Falta pôr as chaves da OpenRouter e, depois, as da Google Play.');
console.log('\nChave do painel (guarde num gerenciador de senhas):');
console.log(`  ${secrets.ADMIN_KEY}`);
console.log('\nAutenticador do painel (abra no celular ou digite o código no app autenticador):');
console.log(`  ${otpauth}`);
console.log(`  código: ${secrets.ADMIN_TOTP_SECRET}`);

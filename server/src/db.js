/**
 * O banco: um arquivo SQLite (node:sqlite, que vem no Node 22.5+; nada a instalar), com a mesma
 * forma de uso do D1 (prepare/bind/first/run/all, batch). As migrações de migrations/ rodam na
 * ordem, uma vez cada, ao abrir.
 */
import { DatabaseSync } from 'node:sqlite';
import { readdirSync, readFileSync, mkdirSync } from 'node:fs';
import { dirname, join } from 'node:path';

export function open(path, migrationsDir) {
  if (path !== ':memory:') mkdirSync(dirname(path), { recursive: true });
  const db = new DatabaseSync(path);
  db.exec('PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON; PRAGMA busy_timeout = 5000;');
  db.exec('CREATE TABLE IF NOT EXISTS _migrations (name TEXT PRIMARY KEY, at INTEGER NOT NULL)');
  const done = new Set(db.prepare('SELECT name FROM _migrations').all().map((r) => r.name));
  for (const name of readdirSync(migrationsDir).filter((f) => f.endsWith('.sql')).sort()) {
    if (done.has(name)) continue;
    db.exec('BEGIN');
    try {
      db.exec(readFileSync(join(migrationsDir, name), 'utf8'));
      db.prepare('INSERT INTO _migrations (name, at) VALUES (?, ?)').run(name, Date.now());
      db.exec('COMMIT');
    } catch (e) {
      db.exec('ROLLBACK');
      throw e;
    }
  }
  return wrap(db);
}

/** A forma do D1 por cima do node:sqlite. */
export function wrap(db) {
  return {
    raw: db,
    prepare(sql) {
      const st = db.prepare(sql);
      let args = [];
      const s = {
        bind(...a) {
          args = a.map((v) => (v === undefined ? null : typeof v === 'boolean' ? Number(v) : v));
          return s;
        },
        async first(col) {
          const r = st.get(...args);
          return r ? (col ? r[col] : { ...r }) : null;
        },
        async run() {
          const r = st.run(...args);
          return { success: true, meta: { changes: Number(r.changes) } };
        },
        async all() {
          return { results: st.all(...args).map((r) => ({ ...r })) };
        },
      };
      return s;
    },
    async batch(statements) {
      db.exec('BEGIN');
      try {
        const out = [];
        for (const s of statements) out.push(await s.run());
        db.exec('COMMIT');
        return out;
      } catch (e) {
        db.exec('ROLLBACK');
        throw e;
      }
    },
  };
}

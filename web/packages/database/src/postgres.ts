import type { DatabaseClient } from "./index.js";

export interface PgPoolLike {
  query<T extends object = Record<string, unknown>>(sql: string, parameters?: readonly unknown[]): Promise<{ rows: T[] }>;
  connect(): Promise<PgConnectionLike>;
}

export interface PgConnectionLike {
  query<T extends object = Record<string, unknown>>(sql: string, parameters?: readonly unknown[]): Promise<{ rows: T[] }>;
  release(): void;
}

/** PostgreSQL adapter boundary; the concrete pg pool is injected by the API process. */
export class PostgresDatabaseClient implements DatabaseClient {
  constructor(private readonly pool: PgPoolLike) {}

  async query<T extends object = Record<string, unknown>>(sql: string, parameters: readonly unknown[] = []): Promise<readonly T[]> {
    return (await this.pool.query<T>(sql, parameters)).rows;
  }

  async transaction<T>(work: (client: DatabaseClient) => Promise<T>): Promise<T> {
    const connection = await this.pool.connect();
    try {
      await connection.query("begin");
      const client: DatabaseClient = {
        query: async <R extends object = Record<string, unknown>>(sql: string, parameters: readonly unknown[] = []) => (await connection.query<R>(sql, parameters)).rows,
        transaction: async <R>(nested: (nestedClient: DatabaseClient) => Promise<R>) => nested(client),
      };
      const result = await work(client);
      await connection.query("commit");
      return result;
    } catch (error) {
      await connection.query("rollback");
      throw error;
    } finally {
      connection.release();
    }
  }
}

export async function applyMigrations(client: DatabaseClient, migrations: readonly { version: number; name: string; upSql: string }[]): Promise<void> {
  await client.query("select pg_advisory_lock(hashtext($1))", ["muse-schema-migrations"]);
  try {
    await client.query("create table if not exists schema_migrations (version integer primary key, name text not null, applied_at timestamptz not null default now())");
    const applied = await client.query<{ version: number }>("select version from schema_migrations order by version");
    const appliedVersions = new Set(applied.map((row) => row.version));
    for (const migration of migrations) {
      if (appliedVersions.has(migration.version)) continue;
      await client.transaction(async (transaction) => {
        await transaction.query(migration.upSql);
        await transaction.query("insert into schema_migrations(version, name) values ($1, $2)", [migration.version, migration.name]);
      });
    }
  } finally {
    await client.query("select pg_advisory_unlock(hashtext($1))", ["muse-schema-migrations"]);
  }
}

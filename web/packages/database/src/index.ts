export * from "./postgres.js";
export * from "./repositories.js";
export * from "./billing.js";
export * from "./payment.js";
export * from "./assistant.js";
export * from "./secrets.js";
export * from "./domains.js";
export * from "./push.js";
export * from "./jobs.js";

export interface DatabaseClient {
  query<T extends object = Record<string, unknown>>(sql: string, parameters?: readonly unknown[]): Promise<readonly T[]>;
  transaction<T>(work: (client: DatabaseClient) => Promise<T>): Promise<T>;
}

export interface Migration {
  readonly version: number;
  readonly name: string;
  readonly upSql: string;
}

/** Ordered SQL migrations; runner must hold an advisory lock before applying them. */
export const migrations: readonly Migration[] = [
  {
    version: 1,
    name: "foundation",
    upSql: `
      create table if not exists schema_migrations (
        version integer primary key,
        name text not null,
        applied_at timestamptz not null default now()
      );
      create table if not exists user_accounts (
        id uuid primary key,
        email text not null unique,
        password_hash text not null,
        role text not null default 'user',
        created_at timestamptz not null default now()
      );
      create table if not exists auth_sessions (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        token_hash text not null unique,
        expires_at timestamptz not null,
        revoked_at timestamptz,
        created_at timestamptz not null default now()
      );
      create index if not exists auth_sessions_user_id_idx on auth_sessions(user_id);
    `,
  },
  {
    version: 2,
    name: "personal_chat",
    upSql: `
      create table if not exists assistants (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        name text not null,
        identity_prompt text not null default '',
        relationship_prompt text not null default '',
        style_prompt text not null default '',
        provider_id text,
        model_id text,
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now()
      );
      create table if not exists sessions (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        assistant_id uuid references assistants(id) on delete set null,
        title text not null default '新对话',
        archived boolean not null default false,
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now()
      );
      create index if not exists sessions_user_updated_idx on sessions(user_id, updated_at desc);
      create table if not exists messages (
        id uuid primary key,
        session_id uuid not null references sessions(id) on delete cascade,
        role text not null,
        content text not null,
        reasoning text,
        client_message_id text,
        status text not null default 'completed',
        created_at timestamptz not null default now(),
        unique(session_id, client_message_id)
      );
      create index if not exists messages_session_created_idx on messages(session_id, created_at);
    `,
  },
  {
    version: 3,
    name: "billing_ledger",
    upSql: `
      create table if not exists plans (
        id text primary key,
        name text not null,
        currency char(3) not null default 'CNY',
        included_credits bigint not null default 0,
        byok_allowed boolean not null default true,
        hosted_models_allowed boolean not null default false,
        limits_json jsonb not null default '{}'::jsonb,
        archived boolean not null default false,
        created_at timestamptz not null default now()
      );
      create table if not exists plan_prices (
        id uuid primary key,
        plan_id text not null references plans(id) on delete restrict,
        monthly_price_fen bigint not null,
        annual_price_fen bigint not null,
        effective_at timestamptz not null,
        created_by uuid not null references user_accounts(id),
        created_at timestamptz not null default now()
      );
      create index if not exists plan_prices_effective_idx on plan_prices(plan_id, effective_at desc);
      create table if not exists credit_ledger (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete restrict,
        delta bigint not null,
        reason text not null,
        idempotency_key text not null unique,
        created_at timestamptz not null default now()
      );
      create index if not exists credit_ledger_user_idx on credit_ledger(user_id, created_at desc);
    `,
  },
  {
    version: 4,
    name: "runtime_integrations",
    upSql: `
      create table if not exists account_plans (
        user_id uuid primary key references user_accounts(id) on delete cascade,
        plan_id text not null references plans(id) on delete restrict,
        assigned_at timestamptz not null default now()
      );
      create table if not exists provider_configs (
        id text primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        type text not null,
        display_name text not null,
        base_url text not null,
        model_ids jsonb not null default '[]'::jsonb,
        secret_ciphertext text,
        secret_key_version text,
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now(),
        unique(user_id, id)
      );
      create table if not exists payment_orders (
        id text primary key,
        user_id uuid not null references user_accounts(id) on delete restrict,
        plan_id text not null references plans(id) on delete restrict,
        price_id uuid not null references plan_prices(id) on delete restrict,
        amount_fen bigint not null,
        currency char(3) not null default 'CNY',
        provider text not null,
        provider_order_id text not null unique,
        status text not null default 'pending',
        paid_at timestamptz,
        refunded_at timestamptz,
        created_at timestamptz not null default now()
      );
      create table if not exists audit_logs (
        id uuid primary key,
        actor_user_id uuid references user_accounts(id) on delete set null,
        action text not null,
        target text not null,
        metadata_json jsonb not null default '{}'::jsonb,
        created_at timestamptz not null default now()
      );
      create index if not exists audit_logs_created_idx on audit_logs(created_at desc);
    `,
  },
  {
    version: 5,
    name: "billing_seed_and_webhooks",
    upSql: `
      insert into user_accounts(id, email, password_hash, role)
      values ('00000000-0000-0000-0000-000000000001', 'system@muse.internal', 'disabled-system-account', 'platform_admin')
      on conflict (id) do nothing;
      insert into plans(id, name, currency, included_credits, byok_allowed, hosted_models_allowed, limits_json, archived)
      values
        ('free', '免费 BYOK', 'CNY', 0, true, false, '{"storageBytes":104857600,"mcpServers":2,"agentRuns":20}'::jsonb, false),
        ('pro', 'Muse Pro', 'CNY', 1000, true, true, '{"storageBytes":5368709120,"mcpServers":10,"agentRuns":500}'::jsonb, false)
      on conflict (id) do nothing;
      insert into plan_prices(id, plan_id, monthly_price_fen, annual_price_fen, effective_at, created_by)
      values
        ('00000000-0000-0000-0000-000000000011', 'free', 0, 0, now(), '00000000-0000-0000-0000-000000000001'),
        ('00000000-0000-0000-0000-000000000012', 'pro', 1999, 19990, now(), '00000000-0000-0000-0000-000000000001')
      on conflict (id) do nothing;
      create table if not exists payment_webhook_events (
        idempotency_key text primary key,
        provider_order_id text not null,
        status text not null,
        created_at timestamptz not null default now()
      );
    `,
  },
  {
    version: 6,
    name: "memory_rag_files",
    upSql: `
      create table if not exists memory_spaces (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        name text not null,
        scope text not null default 'main',
        created_at timestamptz not null default now()
      );
      create index if not exists memory_spaces_user_idx on memory_spaces(user_id);
      create table if not exists memory_facts (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        assistant_id text not null,
        space_id uuid not null references memory_spaces(id) on delete cascade,
        scope text not null,
        content text not null,
        entity_key text,
        importance smallint not null default 0,
        confidence real not null default 1,
        source text not null,
        hit_count integer not null default 0,
        last_hit_at timestamptz,
        expires_at timestamptz,
        pinned_at timestamptz,
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now()
      );
      create index if not exists memory_facts_scope_idx on memory_facts(user_id, assistant_id, space_id, importance desc);
      create table if not exists rag_chunks (
        id text primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        document_id text not null,
        ordinal integer not null,
        text text not null,
        token_estimate integer not null,
        unique(user_id, document_id, ordinal)
      );
      create index if not exists rag_chunks_document_idx on rag_chunks(user_id, document_id, ordinal);
      create table if not exists file_descriptors (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        object_key text not null unique,
        original_name text not null,
        mime_type text not null,
        size_bytes bigint not null,
        sha256 text not null,
        created_at timestamptz not null default now()
      );
      create index if not exists file_descriptors_user_idx on file_descriptors(user_id, created_at desc);
    `,
  },
  {
    version: 7,
    name: "price_idempotency",
    upSql: `
      alter table plan_prices add column if not exists idempotency_key text;
      create unique index if not exists plan_prices_idempotency_idx on plan_prices(idempotency_key) where idempotency_key is not null;
    `,
  },
  {
    version: 8,
    name: "push_subscriptions",
    upSql: `
      create table if not exists push_subscriptions (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        endpoint text not null,
        p256dh text not null,
        auth text not null,
        user_agent text,
        created_at timestamptz not null default now(),
        last_seen_at timestamptz not null default now(),
        unique(user_id, endpoint)
      );
      create index if not exists push_subscriptions_user_idx on push_subscriptions(user_id);
    `,
  },
  {
    version: 9,
    name: "job_queue",
    upSql: `
      create table if not exists jobs (
        id uuid primary key,
        user_id uuid not null references user_accounts(id) on delete cascade,
        type text not null,
        payload jsonb not null default '{}'::jsonb,
        status text not null default 'queued',
        attempts integer not null default 0,
        max_attempts integer not null default 3,
        run_after timestamptz not null default now(),
        idempotency_key text not null unique,
        locked_by text,
        locked_at timestamptz,
        last_error text,
        created_at timestamptz not null default now(),
        updated_at timestamptz not null default now()
      );
      create index if not exists jobs_claim_idx on jobs(status, run_after, created_at);
      create index if not exists jobs_user_idx on jobs(user_id, created_at desc);
    `,
  },
];

export function validateMigrationChain(items: readonly Migration[]): void {
  const versions = items.map((item) => item.version);
  if (versions.length === 0 || versions.some((version, index) => version !== index + 1)) throw new Error("database migrations must be contiguous from version 1");
}

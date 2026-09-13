import { migrations, validateMigrationChain } from "@muse/database";

validateMigrationChain(migrations);
const url = process.env.DATABASE_URL;
if (!url) {
  console.error("DATABASE_URL is required for database migrations");
  process.exitCode = 1;
} else {
  // The actual PostgreSQL adapter is injected in the persistence phase.
  // Never print the full URL because it can contain a password.
  const safeUrl = url.replace(/:[^:@/]+@/, ":***@");
  console.log(`Migration plan is valid for ${safeUrl}: ${migrations.length} versions`);
  for (const migration of migrations) console.log(`${migration.version}: ${migration.name}`);
}

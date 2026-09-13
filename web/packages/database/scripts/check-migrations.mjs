import { migrations, validateMigrationChain } from "../dist/index.js";
validateMigrationChain(migrations);
console.log(`database migration chain valid: ${migrations.length} versions`);

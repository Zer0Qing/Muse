import test from "node:test";
import assert from "node:assert/strict";
import { AuthService } from "../dist/auth.js";

test("auth stores only a redacted password digest in returned user data", async () => {
  const auth = new AuthService();
  const user = await auth.register("user@example.com", "a-strong-password-123");
  assert.equal(user.passwordDigest, "[redacted]");
  const session = await auth.login("USER@example.com", "a-strong-password-123");
  assert.equal((await auth.authenticate(session.token))?.email, "user@example.com");
  assert.equal(await auth.authenticate("invalid"), undefined);
});

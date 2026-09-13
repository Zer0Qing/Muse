import test from "node:test";
import assert from "node:assert/strict";
import { objectKey, sanitizeDownloadName, validateUpload } from "../dist/index.js";

test("file policy rejects path traversal and unsupported types", () => {
  assert.throws(() => validateUpload({ name: "../secret.txt", mimeType: "text/plain", sizeBytes: 3 }), /file name/);
  assert.throws(() => validateUpload({ name: "x.exe", mimeType: "application/octet-stream", sizeBytes: 3 }), /file type/);
  assert.equal(objectKey("a1", "f1", "note.md"), "accounts/a1/files/f1.md");
  assert.equal(sanitizeDownloadName("a\n\"b.txt"), "a__b.txt");
});

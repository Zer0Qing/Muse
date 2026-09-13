import test from "node:test";
import assert from "node:assert/strict";
import { S3ObjectStorage } from "../dist/index.js";
import { S3Client } from "@aws-sdk/client-s3";

test("S3 storage enforces tenant object prefix", async () => {
  const client = new S3Client({ endpoint: "http://localhost:9000", region: "us-east-1", forcePathStyle: true, credentials: { accessKeyId: "test", secretAccessKey: "test-secret" } });
  const storage = new S3ObjectStorage(client, "muse");
  const result = await storage.createUploadUrl({ accountId: "a1", objectKey: "accounts/a1/files/f1.txt", mimeType: "text/plain", sizeBytes: 3 });
  assert.match(result.url, /^http:\/\/localhost:9000\//);
  await assert.rejects(storage.createUploadUrl({ accountId: "a1", objectKey: "accounts/a2/files/f1.txt", mimeType: "text/plain", sizeBytes: 3 }), /account boundary/);
});

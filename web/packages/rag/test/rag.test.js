import test from "node:test";
import assert from "node:assert/strict";
import { chunkText, searchChunks, toCitations } from "../dist/index.js";

test("RAG chunks and searches within account scope", () => {
  const chunks = chunkText("a1", "doc-1", "Muse 记忆系统\n\n用户偏好应当被保留。", 100, 10);
  const other = chunkText("a2", "doc-2", "用户偏好应当被保留。", 100, 10);
  assert.equal(searchChunks([...chunks, ...other], "偏好", "a1").length, 1);
  assert.equal(toCitations(chunks, { "doc-1": "Memory" })[0].title, "Memory");
});

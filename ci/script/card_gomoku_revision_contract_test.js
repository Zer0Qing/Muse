#!/usr/bin/env node
'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(
  path.join(__dirname, '../../app/src/main/java/io/zer0/muse/ui/agentbot/WorkbenchSpacesTab.kt'),
  'utf8',
);
const script = source.slice(source.indexOf('id="gm-report"')).match(/<script>([\s\S]*?)<\/script>/)[1]
  .replace(/\$(turnBlack|turnWhite|winBlack|winWhite|thinkingLabel|movePrompt)\b/g, (_, key) => JSON.stringify(key));
const clone = input => JSON.parse(JSON.stringify(input));
function view(initial = null, canAsk = true) {
  const events = {}, saved = [], reports = [];
  const board = { cells: [], set innerHTML(_) { this.cells = []; }, appendChild(cell) { this.cells.push(cell); } };
  const elements = { 'gm-board': board, 'gm-status': {}, 'gm-comment': {} };
  for (const id of ['gm-report', 'gm-reset']) elements[id] = { addEventListener(_, cb) { events[id] = cb; } };
  let observe, answer, asks = 0;
  const document = {
    getElementById: id => elements[id],
    createElement: () => ({ style: {}, addEventListener(_, click) { this.click = click; } }),
  };
  const muse = {
    getJSON: () => clone(initial),
    data: { onChange: observer => { observe = observer; } },
    saveState: value => { saved.push(clone(value)); return true; },
    send: (_, data) => reports.push(JSON.parse(data)),
    capabilities: () => ({ ask: canAsk ? 'available' : 'requires_host' }),
    ask: () => { asks++; return new Promise(resolve => { answer = resolve; }); },
  };
  vm.runInNewContext(script, { document, window: { muse } });
  return { saved, reports, events, board, push: data => observe(clone(data)), asks: () => asks, answer: value => answer(value) };
}
(async () => {
  const first = view();
  first.board.cells[60].click();
  assert.equal(first.asks(), 1);
  assert.equal(first.saved.length, 1);
  const awaitingWhite = first.saved[0];
  assert.equal(awaitingWhite.pendingWhite, true);
  first.push(awaitingWhite);
  assert.equal(first.saved.length, 1, 'own push must not finish an active turn');
  const second = view();
  second.push(awaitingWhite);
  assert.equal(second.asks(), 0, 'remount does not make a paid retry');
  const recovered = second.saved[0];
  assert.deepEqual(recovered.moves.map(move => move.p), ['B', 'W']);
  assert.equal(recovered.pendingWhite, false);
  second.push(awaitingWhite);
  assert.equal(second.saved.length, 1, 'stale push must not repeat recovery');
  first.push(recovered);
  first.answer({ ok: true, json: { r: 5, c: 4 } });
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(first.saved.length, 1, 'late model response must not add another white move');
  second.events['gm-report']();
  assert.deepEqual(second.reports[0].moves, recovered.moves);
  second.events['gm-reset']();
  assert.equal(second.saved[1].moves.length, 0);
  assert.ok(second.saved[1].__museGomokuRevision > recovered.__museGomokuRevision);
  const local = view(null, false);
  local.board.cells[60].click();
  const reopened = view(local.saved[0], false);
  assert.equal(reopened.saved.length, 0, 'two-player games must retain the manual white turn');
  reopened.events['gm-report']();
  assert.equal(reopened.reports[0].next, 'W');
  assert.equal(reopened.reports[0].moves.length, 1);
  console.log('Gomoku remount, late push, late reply, reset, and two-player turn: PASS');
})().catch(error => { console.error(error); process.exitCode = 1; });

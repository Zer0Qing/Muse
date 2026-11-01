#!/usr/bin/env node
/**
 * card_script_contract_test.js — 卡片脚本「数据订阅」契约测试。
 *
 * ## 为什么需要它
 *
 * 卡片脚本是注入到 WebView 里的一段 JS 字符串（`CardScript.FACADE_JS`）。它决定
 * 卡片绑定数据的刷新方式：宿主能推送时走事件（变化才触发），不能推送时才退回
 * 800ms 轮询。这段逻辑没有任何 Kotlin 单测能覆盖 —— 编译通过不代表行为对，
 * 而它一旦退化成「无论如何都开轮询」，每张卡片都会常驻一个定时器。
 *
 * 这里把 Kotlin 里的那段 JS 抽出来，在 Node 的 vm 沙箱里跑两种模式，断言：
 *   1. 推送模式：不创建任何 interval，只在数据变化时回调，重复通知不重复回调；
 *   2. 轮询模式：创建 800ms interval，且只在值变化时回调；
 *   3. 取消订阅后不再回调。
 *
 * ## 用法（未接入 CI lanes，手动执行）
 *
 *     node ci/script/card_script_contract_test.js
 *
 * 退出码 0 = 全部通过。失败时打印具体断言。
 */

'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');

const PROJECT_ROOT = path.resolve(__dirname, '..', '..');
const SCRIPT_KT = path.join(
  PROJECT_ROOT,
  'app/src/main/java/io/zer0/muse/ui/markdown/card/CardScript.kt',
);

function extractFacadeJs() {
  const source = fs.readFileSync(SCRIPT_KT, 'utf8');
  const match = source.match(/private const val FACADE_JS: String = """([\s\S]*?)"""/);
  if (!match) throw new Error('CardScript.kt 里找不到 FACADE_JS');
  return match[1];
}

function makeSandbox(pushEnabled, initialData) {
  const intervals = [];
  const timeouts = [];
  const state = { data: initialData };
  const bridge = {
    getCardId: () => 'card-1',
    capabilities: () =>
      JSON.stringify({
        emit: 'available',
        state: 'available',
        data: 'available',
        height: 'available',
        cardId: 'card-1',
      }),
    getData: () => state.data,
    send: () => {},
    sendWithData: () => {},
  };
  const sandbox = {
    JSON,
    Date,
    String,
    Boolean,
    Number,
    Math,
    console,
    MuseCardBridge: bridge,
    document: { addEventListener: () => {}, body: {} },
    setTimeout: (fn) => {
      timeouts.push(fn);
      return timeouts.length;
    },
    setInterval: (fn, ms) => {
      intervals.push({ fn, ms });
      return intervals.length;
    },
    clearInterval: () => {},
  };
  sandbox.window = sandbox;
  sandbox.window.__museDataPush = pushEnabled;
  vm.createContext(sandbox);
  vm.runInContext(extractFacadeJs(), sandbox);
  return { sandbox, state, intervals, timeouts };
}

const failures = [];

function check(name, condition, detail) {
  if (condition) {
    console.log(`  ok   ${name}`);
  } else {
    console.log(`  FAIL ${name}${detail ? ` — ${detail}` : ''}`);
    failures.push(name);
  }
}

// ---------- 1. 推送模式 ----------
{
  console.log('[push mode]');
  const { sandbox, state, intervals, timeouts } = makeSandbox(true, 'A');
  const seen = [];
  const unsubscribe = sandbox.window.muse.data.onChange((value) => seen.push(value));

  check('不创建 800ms 轮询', intervals.length === 0, `实际创建 ${intervals.length} 个`);
  check('订阅时补读一次（异步）', timeouts.length === 1);
  timeouts[0]();
  check('补读拿到当前值', seen.length === 1 && seen[0] === 'A', JSON.stringify(seen));

  state.data = 'B';
  sandbox.window.__museDataEvent();
  check('数据变化后回调新值', seen.length === 2 && seen[1] === 'B', JSON.stringify(seen));

  sandbox.window.__museDataEvent();
  check('值未变时不重复回调', seen.length === 2, JSON.stringify(seen));

  state.data = 'C';
  sandbox.window.__museDataEvent();
  check('再次变化仍然回调', seen.length === 3 && seen[2] === 'C', JSON.stringify(seen));

  unsubscribe();
  state.data = 'D';
  sandbox.window.__museDataEvent();
  check('取消订阅后不再回调', seen.length === 3, JSON.stringify(seen));
}

// ---------- 2. 轮询模式（无推送能力时的降级） ----------
{
  console.log('[poll mode]');
  const { sandbox, state, intervals } = makeSandbox(false, 'A');
  const seen = [];
  const unsubscribe = sandbox.window.muse.data.onChange((value) => seen.push(value));

  check('创建 800ms 轮询', intervals.length === 1 && intervals[0].ms === 800, JSON.stringify(intervals.map((i) => i.ms)));

  intervals[0].fn();
  check('首次轮询拿到当前值', seen.length === 1 && seen[0] === 'A', JSON.stringify(seen));

  intervals[0].fn();
  check('值未变时不重复回调', seen.length === 1, JSON.stringify(seen));

  state.data = 'B';
  intervals[0].fn();
  check('值变化后回调', seen.length === 2 && seen[1] === 'B', JSON.stringify(seen));

  unsubscribe();
  check('取消订阅不抛异常', true);
}

// ---------- 3. 桥不可用时不抛 ----------
{
  console.log('[no bridge]');
  const sandbox = {
    JSON,
    Date,
    String,
    Boolean,
    Number,
    Math,
    console,
    document: { addEventListener: () => {}, body: {} },
    setTimeout: () => 0,
    setInterval: () => 0,
    clearInterval: () => {},
  };
  sandbox.window = sandbox;
  vm.createContext(sandbox);
  let threw = false;
  try {
    vm.runInContext(extractFacadeJs(), sandbox);
    const off = sandbox.window.muse.data.onChange(() => {});
    off();
  } catch (error) {
    threw = true;
    console.log(`    ${error.message}`);
  }
  check('无桥页面不抛异常', !threw);
  check('无桥时能力标为 requires_host', sandbox.window.muse.capabilities().emit === 'requires_host');
}

console.log('');
if (failures.length > 0) {
  console.error(`FAILED: ${failures.length} 项`);
  process.exit(1);
}
console.log('card script contract: PASS');

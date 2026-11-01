#!/usr/bin/env node
/**
 * card_observer_contract_test.js — 卡片高度观测脚本的契约测试。
 *
 * ## 为什么需要它
 *
 * 高度观测是注入到卡片 WebView 里的一段 JS（`CardScript.observerJs()`）。它决定
 * 卡片高度什么时候上报给宿主，而这块逻辑有三条互相牵制的规矩：
 *   1. 首次测量必须上报（否则卡片被裁）；
 *   2. 变高要即时上报，变矮要连续两次一致才信（避开折叠动画中间态）；
 *   3. 首次上报之后，150/400/900ms 三个兜底定时器不应该再触发测量
 *      —— 每次测量都会强制一次布局，卡片多时是纯浪费。
 *
 * 第 3 条是 2026-11-01 性能整改新加的，最容易在后续改动里被顺手删回
 * `setTimeout(schedule, ...)`，而且删掉之后功能完全正常、没有任何测试会红。
 *
 * ## 用法（未接入 CI lanes，手动执行）
 *
 *     node ci/script/card_observer_contract_test.js
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

function extractObserverJs() {
  const source = fs.readFileSync(SCRIPT_KT, 'utf8');
  const match = source.match(/private fun observerJs\(\): String \{\s*return """([\s\S]*?)"""\.trimIndent\(\)/);
  if (!match) throw new Error('CardScript.kt 里找不到 observerJs');
  return match[1];
}

/** 造一个可控的页面环境：高度、定时器、ResizeObserver 都能手动驱动。 */
function makeSandbox(initialHeight) {
  const state = { height: initialHeight, reported: [], timers: [], observers: [], looper: null };
  const sink = {
    reportHeight: (px) => state.reported.push(px),
  };
  const body = {
    getBoundingClientRect: () => ({ height: state.height }),
    // 必须用 getter：measure() 取的是「盒子高度」和「内容高度」的较大值，
    // 若这里写成快照值，测出来的永远是初始高度，收缩路径根本测不到。
    get scrollHeight() {
      return state.height;
    },
    get offsetHeight() {
      return state.height;
    },
  };
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      state.observers.push(this);
    }
    observe() {}
    fire() {
      this.callback();
    }
  }
  const sandbox = {
    JSON,
    Date,
    Math,
    console,
    MuseHeightSink: sink,
    document: { body },
    window: {
      innerWidth: 360,
      innerHeight: 640,
      addEventListener: (name, handler) => {
        if (name === 'load') state.looper = handler;
      },
    },
    ResizeObserver: FakeResizeObserver,
    setTimeout: (fn) => {
      state.timers.push(fn);
      return state.timers.length;
    },
  };
  sandbox.window.window = sandbox.window;
  sandbox.globalThis = sandbox;
  vm.createContext(sandbox);
  vm.runInContext(extractObserverJs(), sandbox);
  return { sandbox, state };
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

/** 依次执行已经排队的定时器（模拟时间推进）。 */
function runTimers(state, count) {
  const running = state.timers.splice(0, count ?? state.timers.length);
  running.forEach((fn) => fn());
}

// ---------- 1. 首次测量 ----------
{
  console.log('[first measure]');
  const { state } = makeSandbox(200);
  check('启动时排了一次测量', state.timers.length >= 1, `timers=${state.timers.length}`);
  runTimers(state, 1); // start 里的 schedule → evaluate 的 80ms
  runTimers(state); // 真正执行 evaluate
  check('首次测量立即上报', state.reported.length === 1 && state.reported[0] === 200, JSON.stringify(state.reported));
}

// ---------- 2. 变高即时、变矮两次 ----------
{
  console.log('[growth and shrink]');
  const { state } = makeSandbox(200);
  runTimers(state, 1);
  runTimers(state);
  const observer = state.observers[0];
  check('已注册 ResizeObserver', !!observer);

  state.height = 260;
  observer.fire();
  runTimers(state);
  check('变高立即上报', state.reported[state.reported.length - 1] === 260, JSON.stringify(state.reported));

  state.height = 180;
  observer.fire();
  runTimers(state);
  check('变矮第一次不上报', state.reported[state.reported.length - 1] === 260, JSON.stringify(state.reported));

  observer.fire();
  runTimers(state);
  check('变矮连续两次一致后上报', state.reported[state.reported.length - 1] === 180, JSON.stringify(state.reported));

  state.height = 182;
  observer.fire();
  runTimers(state);
  check('8px 以内抖动不上报', state.reported[state.reported.length - 1] === 180, JSON.stringify(state.reported));
}

// ---------- 3. 兜底定时器在首次上报后不再测量（本轮新增） ----------
{
  console.log('[fallback timers]');
  const { state } = makeSandbox(300);
  runTimers(state, 1);
  runTimers(state);
  check('首次上报完成', state.reported.length === 1, JSON.stringify(state.reported));

  // 清空队列，剩下的应当就是 150/400/900 三个兜底 + load 回调
  const before = state.reported.length;
  state.timers.splice(0).forEach((fn) => fn());
  if (state.looper) state.looper();
  // 兜底若还在调度，会往队列里塞新的 evaluate 定时器
  const scheduled = state.timers.length;
  runTimers(state);

  check(
    '首次上报之后兜底不再调度测量',
    scheduled === 0,
    `兜底调度了 ${scheduled} 次`,
  );
  check('兜底没有产生额外上报', state.reported.length === before, JSON.stringify(state.reported));
}
console.log('');
if (failures.length > 0) {
  console.error(`FAILED: ${failures.length} 项`);
  process.exit(1);
}
console.log('card observer contract: PASS');
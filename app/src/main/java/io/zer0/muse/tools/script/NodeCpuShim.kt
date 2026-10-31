package io.zer0.muse.tools.script

import io.zer0.muse.runtime.MuseRuntime

/**
 * 内置 Node 的 CPU 信息兜底脚本。
 *
 * 部分设备/沙盒里 libuv 读不到 CPU 列表：`os.cpus()` 返回空数组、`os.availableParallelism()`
 * 返回 0，依赖核数做线程池/并行度的 npm 包会静默退化成单核，或直接按 0 计算导致崩。
 *
 * 兜底数据来自 [MuseRuntime] 注入的环境变量（[MuseRuntime.ENV_CPU_COUNT]），只在原生取不到时生效：
 *  - `os.cpus()` 返回空数组时合成等长条目（`model`/`speed`/`times` 齐全，够依赖方读取）；
 *  - `os.availableParallelism()` 返回 0 或抛错时返回合成核数。
 *
 * 原生能正常返回时原样保留，不改真实值。脚本被包在自执行函数里，不向脚本作用域
 * 泄漏任何变量，也不改变包装体的返回值。
 */
internal object NodeCpuShim {

    /** 环境变量名（与 [MuseRuntime.ENV_CPU_COUNT] 同源，此处只做常量引用）。 */
    val ENV_KEY: String = MuseRuntime.ENV_CPU_COUNT

    /** 返回可直接置于脚本最前面的兜底代码（纯函数，无副作用）。 */
    fun script(): String = """
        ;(function () {
          var os = require('node:os');
          var envCount = parseInt(process.env.$ENV_KEY, 10);
          var nativeCpus = os.cpus;
          var nativeParallelism = os.availableParallelism;
          function readNativeCpus() {
            try {
              var list = nativeCpus ? nativeCpus.call(os) : null;
              return Array.isArray(list) ? list : [];
            } catch (e) {
              return [];
            }
          }
          if (envCount > 0 && readNativeCpus().length === 0) {
            os.cpus = function () {
              var list = [];
              for (var i = 0; i < envCount; i++) {
                list.push({
                  model: 'Muse virtual CPU',
                  speed: 0,
                  times: { user: 0, nice: 0, sys: 0, idle: 0, irq: 0 }
                });
              }
              return list;
            };
          }
          var nativeValue = 0;
          try { nativeValue = nativeParallelism ? nativeParallelism.call(os) : 0; } catch (e) { nativeValue = 0; }
          if (!(nativeValue > 0)) {
            var detected = readNativeCpus().length;
            var effective = detected > 0 ? detected : (envCount > 0 ? envCount : 1);
            os.availableParallelism = function () { return effective; };
          }
        })();
    """.trimIndent()

    /** 把兜底代码拼到 [body] 前面（脚本第一句执行，早于任何用户代码）。 */
    fun wrap(body: String): String = script() + "\n" + body
}

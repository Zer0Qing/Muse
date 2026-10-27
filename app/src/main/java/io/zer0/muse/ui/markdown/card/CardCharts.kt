package io.zer0.muse.ui.markdown.card

/**
 * 卡片里的图表渲染器。
 *
 * 八种图共用一个数据形状：
 *
 * ```
 * { labels: [...], series: [{id, label, values}], unit?, title?, ariaLabel?, legend?, minWidth? }
 * ```
 *
 * 让形状统一的好处是换图只要改 `kind`，不用重排数据；模型也只需要记住一种写法。
 *
 * 三条硬约定：
 *
 *  - 数值一律过 `Intl.NumberFormat`，浮点尾巴不上屏；
 *  - 非法数据直接抛错，不把 NaN 当 0 画出来。降级画出来的图看着正常其实全错，
 *    比报错危险得多；
 *  - 每张图都有可读的数据表兜底，图表不是唯一的信息载体。
 */
internal object CardCharts {

    /** 全局挂载名。卡片里用 `MuseCharts.render(el, spec)`。 */
    const val GLOBAL_NAME: String = "MuseCharts"

    /** 完整脚本。 */
    fun build(): String = SCRIPT

    private const val SCRIPT: String = """
(function(){
  'use strict';
  if (window.MuseCharts) return;

  var NS = 'http://www.w3.org/2000/svg';
  var nf = new Intl.NumberFormat('zh-CN', {maximumFractionDigits: 2});

  function fail(msg){ throw new Error('[MuseCharts] ' + msg); }

  function fmt(v){
    if (typeof v !== 'number' || !isFinite(v)) fail('数值不是有限数字: ' + v);
    return nf.format(v);
  }

  function el(tag, attrs, text){
    var node = document.createElementNS(NS, tag);
    if (attrs) { for (var k in attrs) { if (attrs[k] != null) node.setAttribute(k, attrs[k]); } }
    if (text != null) node.textContent = text;
    return node;
  }

  function checkSpec(spec){
    if (!spec) fail('缺少 spec');
    var labels = spec.labels;
    if (!Array.isArray(labels) || labels.length === 0) fail('labels 必须是非空数组');
    if (!Array.isArray(spec.series) || spec.series.length === 0) fail('series 必须是非空数组');
    if (spec.series.length > 6) fail('series 最多 6 条，再多请分组或改用堆叠图');
    var seen = {};
    spec.series.forEach(function(s, i){
      if (!s || typeof s.id !== 'string' || !s.id) fail('series[' + i + '] 缺少 id');
      if (seen[s.id]) fail('series id 重复: ' + s.id);
      seen[s.id] = true;
      if (!Array.isArray(s.values) || s.values.length !== labels.length) {
        fail('series[' + s.id + '] 的 values 长度必须等于 labels');
      }
      s.values.forEach(function(v){
        if (typeof v !== 'number' || !isFinite(v)) fail('series[' + s.id + '] 含非法数值');
      });
    });
    if (spec.minWidth != null) {
      if (typeof spec.minWidth !== 'number' || !isFinite(spec.minWidth) || spec.minWidth < 0) {
        fail('minWidth 必须是不小于 0 的有限数字');
      }
    }
    return spec;
  }

  /** 1/2/5 分档，保证域始终包含 0。 */
  function niceDomain(min, max){
    if (min > 0) min = 0;
    if (max < 0) max = 0;
    if (min === max) { max = min + 1; }
    var span = max - min;
    var rough = span / 4;
    var mag = Math.pow(10, Math.floor(Math.log(rough) / Math.LN10));
    var norm = rough / mag;
    var step = (norm <= 1 ? 1 : norm <= 2 ? 2 : norm <= 5 ? 5 : 10) * mag;
    var lo = Math.floor(min / step) * step;
    var hi = Math.ceil(max / step) * step;
    if (lo === hi) hi = lo + step;
    return { lo: lo, hi: hi, step: step };
  }

  function color(i){ return 'var(--muse-d' + ((i % 6) + 1) + ')'; }

  function dashes(i){
    var patterns = ['', '8 4', '1 4', '8 3 1 3', '2 3', '12 5'];
    return patterns[i % patterns.length];
  }

  /** 图下数据表：图表之外的兜底读法。 */
  function dataTable(labels, series, unit){
    var d = document.createElement('details');
    d.className = 'm-disclosure';
    var s = document.createElement('summary');
    s.textContent = '查看数据';
    d.appendChild(s);
    var wrap = document.createElement('div');
    wrap.className = 'm-disclosure-body m-table-region';
    var t = document.createElement('table');
    t.className = 'm-table';
    var head = document.createElement('tr');
    head.appendChild(el('th', null, ''));
    series.forEach(function(se){
      head.appendChild(el('th', null, se.label + (unit ? ' (' + unit + ')' : '')));
    });
    t.appendChild(head);
    labels.forEach(function(lb, i){
      var tr = document.createElement('tr');
      tr.appendChild(el('th', null, String(lb)));
      series.forEach(function(se){ tr.appendChild(el('td', null, fmt(se.values[i]))); });
      t.appendChild(tr);
    });
    wrap.appendChild(t);
    d.appendChild(wrap);
    return d;
  }

  function legendFor(root, series, onToggle){
    var box = document.createElement('div');
    box.className = 'm-flow';
    box.style.marginTop = 'var(--muse-s2)';
    series.forEach(function(se, i){
      var b = document.createElement('button');
      b.type = 'button';
      b.className = 'm-button m-button--quiet';
      b.style.minHeight = '24px';
      b.style.padding = '0 10px';
      b.style.fontSize = '11.5px';
      b.setAttribute('aria-pressed', 'true');
      var dot = document.createElement('span');
      dot.style.cssText = 'width:8px;height:8px;border-radius:50%;background:' + color(i);
      b.appendChild(dot);
      b.appendChild(document.createTextNode(se.label));
      b.addEventListener('click', function(){
        var on = b.getAttribute('aria-pressed') === 'true';
        b.setAttribute('aria-pressed', on ? 'false' : 'true');
        onToggle(se.id, !on);
      });
      box.appendChild(b);
    });
    root.appendChild(box);
  }

  function destroyable(nodes, listeners){
    return {
      destroy: function(){
        listeners.forEach(function(fn){ try { fn(); } catch (e) { } });
        nodes.forEach(function(n){ if (n && n.parentNode) n.parentNode.removeChild(n); });
      }
    };
  }

  // ── 折线 / 面积 / 柱状 / 堆叠 共用坐标系 ──────────────
  function cartesian(root, spec, kind){
    checkSpec(spec);
    var labels = spec.labels, series = spec.series;
    var isColumn = kind === 'column' || kind === 'stacked';
    var stacked = kind === 'stacked';

    if (stacked) {
      var hasPos = false, hasNeg = false;
      series.forEach(function(s){ s.values.forEach(function(v){ if (v > 0) hasPos = true; if (v < 0) hasNeg = true; }); });
      if (hasPos && hasNeg) fail('堆叠柱状图不能正负混合，请改用 column');
    }

    var min = 0, max = 0;
    if (stacked) {
      labels.forEach(function(_, i){
        var pos = 0, neg = 0;
        series.forEach(function(s){ var v = s.values[i]; if (v > 0) pos += v; else neg += v; });
        if (pos > max) max = pos;
        if (neg < min) min = neg;
      });
    } else {
      series.forEach(function(s){ s.values.forEach(function(v){ if (v > min) min = v; if (v < max) max = v; }); });
    }
    var dom = niceDomain(min, max);

    var W = 100, H = 60, PAD_L = 7, PAD_R = 1;
    var innerW = W - PAD_L - PAD_R;
    var svg = el('svg', {
      viewBox: '0 0 ' + W + ' ' + H,
      preserveAspectRatio: 'none',
      role: 'img',
      style: 'width:100%;height:150px;display:block;overflow:visible;'
    });
    if (spec.title) svg.appendChild(el('title', null, spec.title));

    function x(i){ return PAD_L + (labels.length === 1 ? innerW / 2 : innerW * i / (labels.length - 1)); }
    function y(v){ return H - ((v - dom.lo) / (dom.hi - dom.lo)) * H; }

    // 网格
    for (var g = dom.lo; g <= dom.hi + 0.0001; g += dom.step) {
      svg.appendChild(el('line', {
        x1: 0, x2: W, y1: y(g), y2: y(g),
        stroke: 'var(--muse-card-line-soft)', 'stroke-width': 0.3
      }));
    }
    // 零基线
    if (dom.lo < 0) {
      svg.appendChild(el('line', {
        x1: 0, x2: W, y1: y(0), y2: y(0),
        stroke: 'var(--muse-border)', 'stroke-width': 0.5
      }));
    }

    var hidden = {};

    function rebuild(){
      while (svg.firstChild) svg.removeChild(svg.firstChild);
      if (spec.title) svg.appendChild(el('title', null, spec.title));
      for (var gg = dom.lo; gg <= dom.hi + 0.0001; gg += dom.step) {
        svg.appendChild(el('line', {
          x1: 0, x2: W, y1: y(gg), y2: y(gg),
          stroke: 'var(--muse-card-line-soft)', 'stroke-width': 0.3
        }));
      }
      if (dom.lo < 0) {
        svg.appendChild(el('line', { x1: 0, x2: W, y1: y(0), y2: y(0), stroke: 'var(--muse-border)', 'stroke-width': 0.5 }));
      }
      var visible = series.filter(function(s){ return !hidden[s.id]; });
      if (visible.length === 0) {
        svg.appendChild(el('text', {
          x: W / 2, y: H / 2, 'text-anchor': 'middle',
          fill: 'var(--muse-faint)', 'font-size': 2.6
        }, '所有系列已隐藏'));
        return;
      }
      if (isColumn) {
        var band = innerW / labels.length;
        var bw = Math.min(band * 0.72 / visible.length, band * 0.5);
        labels.forEach(function(_, i){
          var base = PAD_L + band * i + band / 2;
          if (stacked) {
            var posAcc = 0, negAcc = 0;
            visible.forEach(function(se, si){
              var v = se.values[i];
              var from = v >= 0 ? posAcc : negAcc;
              var to = from + v;
              if (v >= 0) posAcc = to; else negAcc = to;
              var top = y(Math.max(from, to));
              var h = Math.abs(y(from) - y(to));
              svg.appendChild(el('rect', {
                x: base - bw / 2, y: top, width: bw, height: Math.max(h, 0.2),
                fill: color(series.indexOf(se)), opacity: 0.92
              }));
            });
          } else {
            visible.forEach(function(se, si){
              var v = se.values[i];
              var x0 = base - (visible.length * bw) / 2 + si * bw;
              var top = y(Math.max(v, 0));
              var h = Math.abs(y(v) - y(0));
              svg.appendChild(el('rect', {
                x: x0, y: top, width: Math.max(bw - 0.3, 0.4), height: Math.max(h, 0.2),
                fill: color(series.indexOf(se)), rx: 0.4
              }));
            });
          }
        });
      } else {
        visible.forEach(function(se){
          var idx = series.indexOf(se);
          var d = '';
          se.values.forEach(function(v, i){
            d += (i === 0 ? 'M' : 'L') + x(i).toFixed(2) + ' ' + y(v).toFixed(2) + ' ';
          });
          if (kind === 'area') {
            var area = d + 'L' + x(labels.length - 1).toFixed(2) + ' ' + y(0).toFixed(2) +
              ' L' + x(0).toFixed(2) + ' ' + y(0).toFixed(2) + ' Z';
            svg.appendChild(el('path', { d: area, fill: color(idx), opacity: 0.14, stroke: 'none' }));
          }
          var path = el('path', {
            d: d, fill: 'none', stroke: color(idx), 'stroke-width': 0.9,
            'stroke-linejoin': 'round', 'stroke-linecap': 'round', 'vector-effect': 'non-scaling-stroke'
          });
          var dash = dashes(idx);
          if (dash) path.setAttribute('stroke-dasharray', dash);
          svg.appendChild(path);
          se.values.forEach(function(v, i){
            svg.appendChild(el('circle', {
              cx: x(i), cy: y(v), r: 0.9, fill: color(idx)
            }));
          });
        });
      }
    }

    rebuild();

    var wrap = document.createElement('div');
    if (spec.minWidth != null) wrap.style.setProperty('--muse-chart-min', spec.minWidth + 'px');
    wrap.style.width = '100%';
    wrap.appendChild(svg);
    root.appendChild(wrap);

    var nodes = [wrap];
    if (spec.legend !== false) {
      legendFor(root, series, function(id, on){ hidden[id] = !on; rebuild(); });
    }
    var table = dataTable(labels, series, spec.unit);
    root.appendChild(table);
    nodes.push(table);
    return destroyable(nodes, []);
  }

  // ── 横向条形 ────────────────────────────────────────
  function bar(root, spec){
    var items = spec.items;
    var labels, series;
    if (Array.isArray(items)) {
      checkSpec({ labels: items.map(function(x){ return x.label; }),
                  series: [{ id: 'v', label: spec.title || '数值', values: items.map(function(x){ return x.value; }) }] });
      labels = items.map(function(x){ return x.label; });
      series = [{ id: 'v', label: spec.title || '数值', values: items.map(function(x){ return x.value; }) }];
    } else {
      checkSpec(spec);
      if (spec.series.length !== 1) fail('条形图只接受单个系列，多系列请用 column 或 stacked');
      labels = spec.labels;
      series = spec.series;
    }
    var values = series[0].values;
    var min = 0, max = 0;
    values.forEach(function(v){ if (v > max) max = v; if (v < min) min = v; });
    var dom = niceDomain(min, max);
    var rowH = 26;
    var H = Math.max(labels.length * rowH, rowH);
    var W = 100;
    var labelW = 26;
    var svg = el('svg', {
      viewBox: '0 0 ' + W + ' ' + H,
      preserveAspectRatio: 'none',
      role: 'img',
      style: 'width:100%;height:' + H + 'px;display:block;'
    });
    if (spec.title) svg.appendChild(el('title', null, spec.title));
    var zero = labelW + (W - labelW - 4) * ((0 - dom.lo) / (dom.hi - dom.lo));
    function bx(v){ return labelW + (W - labelW - 4) * ((v - dom.lo) / (dom.hi - dom.lo)); }
    labels.forEach(function(lb, i){
      var v = values[i];
      var top = i * rowH + 4;
      svg.appendChild(el('text', {
        x: 0, y: top + 12, fill: 'var(--muse-muted)', 'font-size': 3.4
      }, String(lb)));
      var x0 = Math.min(zero, bx(v));
      var w = Math.abs(bx(v) - zero);
      svg.appendChild(el('rect', {
        x: x0, y: top, width: Math.max(w, 0.4), height: 16,
        fill: color(i), rx: 0.8, opacity: 0.9
      }));
      var tx = v >= 0 ? x0 + w + 1 : x0 - 1;
      var anchor = v >= 0 ? 'start' : 'end';
      svg.appendChild(el('text', {
        x: tx, y: top + 12, fill: 'var(--muse-faint)', 'font-size': 3.2,
        'text-anchor': anchor
      }, fmt(v)));
    });
    root.appendChild(svg);
    var table = dataTable(labels, series, spec.unit);
    root.appendChild(table);
    return destroyable([svg, table], []);
  }

  // ── 饼图 / 环形 ─────────────────────────────────────
  function pie(root, spec, donut){
    checkSpec(spec);
    if (spec.series.length !== 1) fail('饼图只接受一个系列');
    var values = spec.series[0].values;
    var total = 0;
    values.forEach(function(v){ if (v < 0) fail('饼图不接受负数'); total += v; });
    if (total <= 0) fail('饼图合计为 0，无法计算占比');
    var labels = spec.labels;
    var inner = donut ? 0.6 : (typeof spec.innerRadius === 'number' ? spec.innerRadius : 0);
    if (inner < 0 || inner > 0.9) fail('innerRadius 超出 0–0.9');

    var S = 100, C = 50, R = 40, IR = R * inner;
    var svg = el('svg', {
      viewBox: '0 0 ' + S + ' ' + S,
      role: 'img',
      style: 'width:100%;max-width:180px;height:auto;display:block;margin:0 auto;'
    });
    if (spec.title) svg.appendChild(el('title', null, spec.title));

    function arc(from, to, r0, r1){
      function pt(a, r){ return [C + Math.cos(a) * r, C + Math.sin(a) * r]; }
      var large = (to - from) > Math.PI ? 1 : 0;
      var p0 = pt(from, r1), p1 = pt(to, r1), p2 = pt(to, r0), p3 = pt(from, r0);
      return 'M' + p0[0].toFixed(2) + ' ' + p0[1].toFixed(2) +
        ' A' + r1 + ' ' + r1 + ' 0 ' + large + ' 1 ' + p1[0].toFixed(2) + ' ' + p1[1].toFixed(2) +
        (r0 > 0
          ? ' L' + p2[0].toFixed(2) + ' ' + p2[1].toFixed(2) +
            ' A' + r0 + ' ' + r0 + ' 0 ' + large + ' 0 ' + p3[0].toFixed(2) + ' ' + p3[1].toFixed(2)
          : ' L' + C + ' ' + C) +
        ' Z';
    }

    var acc = -Math.PI / 2;
    labels.forEach(function(lb, i){
      var v = values[i];
      if (v <= 0) return;
      var sweep = (v / total) * Math.PI * 2;
      var path = el('path', {
        d: arc(acc, acc + sweep, IR, R),
        fill: color(i), stroke: 'var(--muse-surface)', 'stroke-width': 0.4
      });
      path.appendChild(el('title', null, lb + ' · ' + fmt(v)));
      svg.appendChild(path);
      acc += sweep;
    });
    if (donut && inner >= 0.4) {
      svg.appendChild(el('text', {
        x: C, y: C - 2, 'text-anchor': 'middle',
        fill: 'var(--muse-fg)', 'font-size': 7, 'font-weight': 600
      }, fmt(total)));
      svg.appendChild(el('text', {
        x: C, y: C + 7, 'text-anchor': 'middle',
        fill: 'var(--muse-faint)', 'font-size': 4
      }, '合计'));
    }
    root.appendChild(svg);
    var legendBox = document.createElement('div');
    legendBox.className = 'm-stack';
    legendBox.style.marginTop = 'var(--muse-s3)';
    labels.forEach(function(lb, i){
      var row = document.createElement('div');
      row.className = 'm-flow';
      row.style.gap = '6px';
      var dot = document.createElement('span');
      dot.style.cssText = 'width:8px;height:8px;border-radius:2px;background:' + color(i) + ';flex:0 0 8px;';
      var name = document.createElement('span');
      name.style.cssText = 'flex:1 1 auto;font-size:12.5px;color:var(--muse-fg);';
      name.textContent = String(lb);
      var val = document.createElement('span');
      val.style.cssText = 'font-size:12px;color:var(--muse-faint);';
      val.textContent = fmt(values[i]) + ' · ' + (values[i] / total * 100).toFixed(1) + '%';
      row.appendChild(dot); row.appendChild(name); row.appendChild(val);
      legendBox.appendChild(row);
    });
    root.appendChild(legendBox);
    var table = dataTable(labels, [spec.series[0]], spec.unit);
    root.appendChild(table);
    return destroyable([svg, legendBox, table], []);
  }

  var api = {
    renderLineChart: function(root, spec){ return cartesian(root, spec, 'line'); },
    renderAreaChart: function(root, spec){ return cartesian(root, spec, 'area'); },
    renderColumnChart: function(root, spec){ return cartesian(root, spec, 'column'); },
    renderStackedColumnChart: function(root, spec){ return cartesian(root, spec, 'stacked'); },
    renderBarChart: function(root, spec){ return bar(root, spec); },
    renderPieChart: function(root, spec){ return pie(root, spec, false); },
    renderDonutChart: function(root, spec){ return pie(root, spec, true); },
    renderChart: function(root, spec){
      var kind = spec && spec.kind;
      if (kind === 'line') return api.renderLineChart(root, spec);
      if (kind === 'area') return api.renderAreaChart(root, spec);
      if (kind === 'column') return api.renderColumnChart(root, spec);
      if (kind === 'stacked') return api.renderStackedColumnChart(root, spec);
      if (kind === 'bar') return api.renderBarChart(root, spec);
      if (kind === 'pie') return api.renderPieChart(root, spec);
      if (kind === 'donut') return api.renderDonutChart(root, spec);
      fail('未知 kind: ' + kind + '（可用 line/area/column/stacked/bar/pie/donut）');
    }
  };
  window.MuseCharts = api;
})();
"""
}

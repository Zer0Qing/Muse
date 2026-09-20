#!/usr/bin/env python3
"""
test_font_scale_clipping.py — check_font_scale_clipping.py 的单元测试。

运行: py -3 ci/test/test_font_scale_clipping.py
"""

import json
import sys
import tempfile
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parents[1] / "script"
sys.path.insert(0, str(SCRIPT_DIR))

from check_font_scale_clipping import (  # noqa: E402
    MIN_RISK_DP,
    evaluate,
    scan_roots,
    update_baseline,
)

UI_REL = "app/src/main/java/io/zer0/muse/ui"

# 会裁字的写法：固定高度 + 容器体内有 Text
RISKY = """
@Composable
fun Card() {
    Box(modifier = Modifier.fillMaxWidth().height(56.dp)) {
        Text("标题")
    }
}
"""

# 已改造：heightIn(min=) 保底高度不变但随字号增长
SAFE_HEIGHT_IN = """
@Composable
fun Card() {
    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("标题")
    }
}
"""

# 小尺寸固定高度：图标/圆点级，不承载文本
SAFE_SMALL = """
@Composable
fun Dot() {
    Box(modifier = Modifier.height(16.dp)) { Text("x") }
}
"""

# Spacer：间距不承载文本
SAFE_SPACER = """
@Composable
fun Gap() {
    Spacer(Modifier.height(48.dp))
    Text("下面是相邻组件的文字")
}
"""

# 细线分隔条：宽 0.5dp + 固定高度，不承载文本
SAFE_DIVIDER = """
@Composable
fun Hairline() {
    Box(modifier = Modifier.width(0.5.dp).height(40.dp).background(Color.Gray))
    Text("相邻组件的文字")
}
"""

# 媒体容器：高度由 Canvas 决定，不随字号撑高
SAFE_MEDIA = """
@Composable
fun Chart() {
    Box(modifier = Modifier.fillMaxWidth().height(100.dp)) {
        val cd = stringResource(R.string.chart_cd)
        Canvas(modifier = Modifier.fillMaxSize().semantics { contentDescription = cd }) {
            drawLine(Color.Red, Offset.Zero, Offset(10f, 10f))
        }
    }
}
"""

# 相邻组件的文字不算容器体内文本（文本在媒体窗口之后、距固定高度 >12 行）
SAFE_NEIGHBOUR_TEXT = """
@Composable
fun Slider() {
    Box(modifier = Modifier.weight(1f).height(40.dp)) {
        detectHorizontalDragGestures(onDragStart = { }, onDragEnd = { }, onDragCancel = { }, onHorizontalDrag = { _, _ -> })
    }
    Canvas(modifier = Modifier.fillMaxSize()) { drawCircle(Color.Red) }
    Text("滑块说明")
}
"""


# 无界 heightIn + fillMaxSize 子项：子项会请求父级最大高度(1.3x 走查实测的顶栏中岛事故)
RISKY_UNBOUNDED = """
@Composable
fun Island() {
    Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
        Box(modifier = Modifier.fillMaxSize()) { Text("Muse") }
    }
}
"""

# 有上界的 heightIn：不会无限长高,属正确写法
SAFE_BOUNDED = """
@Composable
fun Capsule() {
    Surface(modifier = Modifier.heightIn(min = 48.dp, max = 72.dp)) {
        Box(modifier = Modifier.fillMaxSize()) { Text("Tasks") }
    }
}
"""


def make_root(files: dict[str, str]) -> Path:
    root = Path(tempfile.mkdtemp())
    ui = root / UI_REL
    ui.mkdir(parents=True)
    for name, src in files.items():
        (ui / name).write_text(src, encoding="utf-8")
    return root


def counts(files: dict[str, str]) -> dict[str, int]:
    return {s.file: s.occurrences for s in scan_roots(make_root(files)) if s.occurrences}


def test_risky_fixed_height_on_text_is_counted():
    got = counts({"Card.kt": RISKY})
    assert got == {f"{UI_REL}/Card.kt": 1}, got


def test_height_in_is_not_counted():
    assert counts({"Card.kt": SAFE_HEIGHT_IN}) == {}


def test_small_fixed_height_is_not_counted():
    assert MIN_RISK_DP >= 32
    assert counts({"Dot.kt": SAFE_SMALL}) == {}


def test_spacer_is_not_counted():
    assert counts({"Gap.kt": SAFE_SPACER}) == {}


def test_hairline_divider_is_not_counted():
    assert counts({"Hairline.kt": SAFE_DIVIDER}) == {}


def test_media_container_is_not_counted():
    assert counts({"Chart.kt": SAFE_MEDIA}) == {}


def test_neighbour_text_is_not_counted():
    assert counts({"Slider.kt": SAFE_NEIGHBOUR_TEXT}) == {}


def test_comments_do_not_count():
    src = """
@Composable
fun Card() {
    // Box(modifier = Modifier.height(64.dp)) { Text("x") }
    Box(modifier = Modifier.fillMaxWidth())
}
"""
    assert counts({"Card.kt": src}) == {}


def test_missing_ui_dir_fails_closed():
    root = Path(tempfile.mkdtemp())
    try:
        scan_roots(root)
    except FileNotFoundError:
        return
    raise AssertionError("UI 目录缺失时必须抛 FileNotFoundError(不得静默通过)")


def test_evaluate_flags_new_file_and_total_increase():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    failures, _ = evaluate(current, {"files": {}, "totals": {"occurrences": 0}, "min_risk_dp": MIN_RISK_DP})
    assert any("新增文件" in f for f in failures), failures


def test_evaluate_detects_rule_drift():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    failures, _ = evaluate(current, {"files": {}, "totals": {"occurrences": 0}, "min_risk_dp": 99})
    assert any("识别口径已变化" in f for f in failures), failures


def test_update_baseline_refuses_increase():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    path = Path(tempfile.mkdtemp()) / "baseline.json"
    path.write_text(json.dumps({"files": {}, "totals": {"occurrences": 0}}), encoding="utf-8")
    rc = update_baseline(path, current, allow_increase=False)
    assert rc == 1, "基线上升必须被拒绝"
    assert json.loads(path.read_text(encoding="utf-8"))["files"] == {}, "拒绝时不得改写基线"


def test_update_baseline_writes_and_round_trips():
    root = make_root({"Card.kt": RISKY})
    current = [s for s in scan_roots(root) if s.occurrences]
    path = Path(tempfile.mkdtemp()) / "baseline.json"
    assert update_baseline(path, current, allow_increase=False) == 0
    payload = json.loads(path.read_text(encoding="utf-8"))
    assert payload["totals"]["occurrences"] == 1
    assert payload["files"] == {f"{UI_REL}/Card.kt": 1}
    failures, _ = evaluate(current, payload)
    assert failures == [], failures



def test_unbounded_height_in_with_fill_max_size_is_counted():
    scans = {x.file: x.unbounded for x in scan_roots(make_root({"Island.kt": RISKY_UNBOUNDED}))}
    assert scans == {f"{UI_REL}/Island.kt": 1}, scans


def test_bounded_height_in_is_not_counted():
    scans = {x.file: x.unbounded for x in scan_roots(make_root({"Capsule.kt": SAFE_BOUNDED}))}
    assert scans == {}, scans


TESTS = [v for k, v in sorted(globals().items()) if k.startswith("test_")]

if __name__ == "__main__":
    failed = 0
    for fn in TESTS:
        try:
            fn()
        except Exception as e:  # noqa: BLE001
            failed += 1
            print(f"FAIL {fn.__name__}: {type(e).__name__}: {e}")
        else:
            print(f"PASS {fn.__name__}")
    print("all tests passed" if not failed else f"{failed} test(s) failed")
    sys.exit(1 if failed else 0)

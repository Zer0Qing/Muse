#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""生成 Muse 自己的模型清单（ModelCatalog 格式）。

## 为什么自己攒

之前这份清单是别人的产物：要么整份抄 HANA，要么等外部更新。
两边各只覆盖一半事实 —— HANA 知道「这家的工具调用是什么方言、结果怎么回」，
models.dev 知道「上下文多长、最大输出多少、吃不吃图」。
分开看都有洞，合起来才完整。

## 两路数据的角色

- **骨架**：HANA 的 providers。那是 Muse 实际支持的厂商集合，
  也只有它带 toolUse.dialect（openai / anthropic / gemini 的差异）。
- **补充**：models.dev 的 limit / modalities / tool_call / reasoning。

字段优先级：HANA 条目本身 > HANA fallbacks > models.dev 本渠道 > models.dev 跨渠道。
理由：越靠前越贴近「在我们这套调用路径上验证过的」事实。

## 输出格式

与 `io.zer0.ai.core.ModelCatalog` 对齐：
    { schemaVersion, publishedAt, providers: { pid: { mid: entry } } }
entry 字段：name / context / maxOutput / image / reasoning / toolUse / thinkingLevels

## 用法

    python tools/model-catalog/build_catalog.py \\
        --hana  /path/to/model-catalog.v2.json \\
        --modelsdev /path/to/models_dev_api.json \\
        --out app/src/main/assets/model-catalog.json
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
from datetime import datetime, timezone

SCHEMA_VERSION = 1

# HANA 的 provider id → models.dev 的 provider key。
# 只收能确认的：错配比缺失更难发现，确认不了的宁可留空只用 HANA 数据。
PROVIDER_ALIAS = {
    "dashscope": "alibaba",
    "dashscope-coding": "alibaba-coding-plan",
    "dashscope-token-plan": "alibaba-token-plan",
    "fireworks": "fireworks-ai",
    "gemini": "google",
    "kimi-coding": "kimi-code-plan-cn",
    "minimax-token-plan": "minimax-coding-plan",
    "moonshot": "moonshotai",
    "openai-codex-oauth": "openai",
    "together": "togetherai",
    "volcengine-coding": "volcengine-coding-plan",
    "zhipu": "zhipuai",
    "zhipu-coding": "zhipuai-coding-plan",
}


def load(path: pathlib.Path) -> dict:
    return json.loads(path.read_text(encoding="utf-8"))


def pick(*values):
    for v in values:
        if v is not None:
            return v
    return None


def bare_id(model_id: str) -> str:
    """去掉聚合商前缀与 `:free` 之类后缀。"""
    return model_id.split("/")[-1].split(":")[0]


def build_dev_index(models: dict) -> dict[str, dict]:
    """一家渠道内的多路索引：原名 / canonical id / 裸名。"""
    index: dict[str, dict] = {}
    for mid, model in models.items():
        if not isinstance(model, dict):
            continue
        index.setdefault(mid, model)
        canonical = model.get("canonical_model_id")
        if isinstance(canonical, str) and canonical:
            index.setdefault(canonical, model)
        index.setdefault(bare_id(mid), model)
    return index


def lookup(index: dict, model_id: str) -> dict:
    hit = index.get(model_id)
    if hit is not None:
        return hit
    return index.get(bare_id(model_id)) or {}


def build_global_limits(dev: dict) -> dict[str, dict]:
    """跨渠道的 limit 索引。

    最大输出是模型属性，不是渠道属性：openrouter 条目普遍没有 output，
    但同款模型在官方渠道条目里有。本渠道查不到时用别家的值，比留空有用。
    """
    index: dict[str, dict] = {}
    for provider in dev.values():
        if not isinstance(provider, dict):
            continue
        for mid, model in (provider.get("models") or {}).items():
            if not isinstance(model, dict):
                continue
            limit = model.get("limit") or {}
            keys = {mid, bare_id(mid)}
            canonical = model.get("canonical_model_id")
            if isinstance(canonical, str) and canonical:
                keys.add(canonical)
            for key in keys:
                if not key:
                    continue
                current = index.get(key) or {}
                index[key] = {
                    "context": limit.get("context") or current.get("context"),
                    "output": limit.get("output") or current.get("output"),
                }
    return index


def dev_vision(dev_model: dict) -> bool | None:
    mod = (dev_model.get("modalities") or {}).get("input") or []
    if not isinstance(mod, list):
        return None
    return True if "image" in mod else None


def build_entry(model_id: str, hana_model: dict, fallbacks: dict, dev_index: dict, global_limits: dict) -> dict:
    dev_model = lookup(dev_index, model_id)
    global_limit = lookup(global_limits, model_id)
    fb = fallbacks.get(model_id) or {}
    dev_limit = dev_model.get("limit") or {}

    entry: dict = dict(hana_model)  # 先原样保留 HANA 条目，含 thinkingLevelMap 这类我们没建模的字段
    name = pick(hana_model.get("name"), dev_model.get("name"), model_id)
    if name:
        entry["name"] = name

    context = pick(hana_model.get("context"), fb.get("context"), dev_limit.get("context"), global_limit.get("context"))
    if context:
        entry["context"] = context

    max_output = pick(
        hana_model.get("maxOutput"),
        fb.get("maxOutput"),
        dev_limit.get("output"),
        global_limit.get("output"),
    )
    if max_output:
        entry["maxOutput"] = max_output

    image = pick(hana_model.get("image"), fb.get("image"), dev_vision(dev_model))
    if image is not None:
        entry["image"] = image

    reasoning = pick(hana_model.get("reasoning"), fb.get("reasoning"), dev_model.get("reasoning"))
    if reasoning is not None:
        entry["reasoning"] = reasoning

    tool_use = hana_model.get("toolUse")
    if isinstance(tool_use, dict):
        entry["toolUse"] = tool_use
    elif dev_model.get("tool_call"):
        entry["toolUse"] = {"supportsTools": True, "dialect": "openai", "toolResultFormat": "message"}

    levels = hana_model.get("thinkingLevels")
    if isinstance(levels, list) and levels:
        entry["thinkingLevels"] = levels
        default_level = hana_model.get("defaultThinkingLevel")
        if isinstance(default_level, str) and default_level:
            entry["defaultThinkingLevel"] = default_level

    compat = hana_model.get("compat")
    if isinstance(compat, dict) and compat:
        entry["compat"] = compat

    return entry


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--hana", required=True)
    ap.add_argument("--modelsdev", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--published-at", default="", help="覆盖 publishedAt；默认取当前时间")
    args = ap.parse_args()

    hana = load(pathlib.Path(args.hana))
    dev = load(pathlib.Path(args.modelsdev))
    fallbacks = hana.get("fallbacks") or {}
    global_limits = build_global_limits(dev)

    providers: dict[str, dict[str, dict]] = {}
    for provider_id, models in hana.get("providers", {}).items():
        dev_provider = dev.get(PROVIDER_ALIAS.get(provider_id, provider_id)) or {}
        dev_index = build_dev_index(dev_provider.get("models") or {})
        bucket: dict[str, dict] = {}
        for model_id, hana_model in models.items():
            if not isinstance(hana_model, dict):
                continue
            bucket[model_id] = build_entry(model_id, hana_model, fallbacks, dev_index, global_limits)
        providers[provider_id] = bucket

    published = args.published_at or datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")
    doc = {"schemaVersion": SCHEMA_VERSION, "publishedAt": published, "providers": providers}

    out = pathlib.Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(doc, ensure_ascii=False, indent=0, separators=(",", ":")), encoding="utf-8")

    total = sum(len(v) for v in providers.values())
    with_ctx = sum(1 for v in providers.values() for e in v.values() if e.get("context"))
    with_out = sum(1 for v in providers.values() for e in v.values() if e.get("maxOutput"))
    with_tools = sum(1 for v in providers.values() for e in v.values() if e.get("toolUse"))
    print(f"生成 {out}")
    print(f"  渠道 {len(providers)} 家 / 模型 {total} 个")
    print(f"  上下文 {with_ctx} / 最大输出 {with_out} / 工具规格 {with_tools}")
    print(f"  文件大小 {out.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    sys.exit(main())
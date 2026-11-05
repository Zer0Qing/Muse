#!/usr/bin/env python3
"""Muse 插件市场离线签名工具。

与 App 侧算法逐字对齐（app/src/main/java/io/zer0/muse/data/plugin/...）：

  * 规范化 JSON  = AppJson（紧凑、无空格、字段按声明顺序、含默认值、中文原样 UTF-8；
    可选字段 contributes/uiPanel/toolCards 在默认值时不参与序列化，与 App 端逐字一致）
  * manifestSha256 = PluginSecurityGate.contentSha256
      digest = SHA-256，对每个部分依次：
          path.utf8 | 0x00 | len(bytes) 十进制 ASCII | 0x00 | bytes | 0x00
      顺序：("manifest.json", 含签名的 manifest 规范 JSON) → (entry, 入口源码) → 其余文件按路径升序
  * 发行者签名输入 = PluginSecurityGate.signaturePayload
      同样的边界编码，但每部分先各自取 SHA-256 摘要，再把 32 字节摘要串接
      manifest 使用把 signature 值置空后的规范 JSON
  * 目录签名输入 = PluginCatalogVerifier.canonicalPayload（PluginCatalog 的规范 JSON）
  * 算法 SHA256withECDSA（P-256），公钥 X.509 SubjectPublicKeyInfo，
    公钥与签名一律标准 Base64 单行（App 会强制规范化比较）

私钥永不离开本机：脚本只读取本地私钥文件，输出产物与公钥信息。

用法：
  python sign.py keygen   --key FILE
  python sign.py pack     --src DIR --key FILE --publisher-id ID --out-dir DIR [--base-url URL]
  python sign.py catalog  --packages-dir DIR --key FILE --key-id ID --base-url URL
                          --catalog-id ID --out FILE [--sequence N] [--days 30]
"""

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

SIGNATURE_ALGORITHM = "SHA256withECDSA"
CURVE = "prime256v1"  # P-256 / secp256r1
SCHEMA_VERSION = 1

MANIFEST_ORDER = [
    "id", "name", "version", "description", "author", "minAppVersion",
    "entry", "kind", "trust", "hidden", "capabilities", "permissions",
    "activationEvents", "hooks", "enabled", "tools", "signature",
    # ── 可选字段：默认值不参与序列化（与 App 端 PluginManifest 逐字对齐）──
    #  · contributes / uiPanel：App 侧 explicitNulls=false，值为 null 时不输出；
    #  · hooks：App 侧 @EncodeDefault(NEVER)，空列表不输出；
    #  · toolCards：App 侧 @EncodeDefault(NEVER)，空对象不输出。
    # 漏跳任何一个，App 验签都会因字节序列不同而失败（v2.0.0 市场事故根因）。
    # hooks 的存放位置由上面那一行决定（须在 enabled 之前），此处不重复列出。
    # uiView 的位置按 App 端字段声明序排在 uiPanel 与 toolCards 之间 —— 这是有序列表，放错位置等于改协议。
    "contributes", "uiPanel", "uiView", "toolCards",
    # commands / schedules 在 App 端是最后两个字段：有序列表里也必须同序排最后。
    "commands", "schedules",
]

# 缺失时允许直接跳过的可选字段（不要求出现在 manifest 里，也不填默认值）。
OPTIONAL_MANIFEST_FIELDS = ("contributes", "uiPanel", "uiView", "hooks", "toolCards", "commands", "schedules")

# 作者可写入 manifest、但不进入签名载荷的字段。
# App 端 PluginManifest.runtime 带 @EncodeDefault(NEVER) 且不在
# SIGNED_TOP_LEVEL_FIELDS 里，任何取值都不会出现在签名 JSON 中；
# 这里识别它只为校验入口扩展名与收集包内文件，序列化时丢弃。
UNSIGNED_MANIFEST_FIELDS = ("runtime",)
RUNTIME_JS = "js"
RUNTIME_PYTHON = "python"
# App 侧三档：js / node / python（PluginFunctionInvoker 按 manifest.runtime 分派，
# 且交互面板强制 node）。以前这里没有 node —— 三个官方示例都写 runtime=node，
# 结果 sign.py pack 第一步就把自家示例拒了。
RUNTIME_NODE = "node"
KNOWN_RUNTIMES = (RUNTIME_JS, RUNTIME_NODE, RUNTIME_PYTHON)
MANIFEST_DEFAULTS = {
    "version": "0.1.0",
    "description": "",
    "author": "",
    "minAppVersion": "1.0.0",
    "entry": "main.js",
    "kind": "tool",
    "trust": "sandboxed",
    "hidden": False,
    "capabilities": [],
    "permissions": [],
    "activationEvents": ["onStartup"],
    "hooks": [],
    "enabled": True,
    "tools": [],
    "signature": None,
}
TOOL_ORDER = ["name", "description", "parametersJson", "requiredJson", "functionName"]
TOOL_DEFAULTS = {"parametersJson": "{}", "requiredJson": "[]"}
SIGNATURE_ORDER = ["publisherId", "publicKey", "signature", "algorithm"]
ENTRY_ORDER = [
    "id", "version", "name", "description", "publisherId", "publisherKeyFingerprint",
    "artifactUrl", "artifactBytes", "artifactSha256", "manifestSha256",
    "capabilities", "permissions",
]
CATALOG_ORDER = [
    "catalogId", "schemaVersion", "sequence", "generatedAtEpochMs", "expiresAtEpochMs", "entries",
]

PLUGIN_ID_REGEX = re.compile(r"^[a-z0-9][a-z0-9_-]*$")
PUBLISHER_ID_REGEX = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
TOOL_NAME_REGEX = re.compile(r"^[a-zA-Z0-9_-]{1,64}$")
FUNCTION_NAME_REGEX = re.compile(r"^[a-zA-Z_$][a-zA-Z0-9_$]{0,63}$")
VERSION_REGEX = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
SHA256_REGEX = re.compile(r"^[0-9a-f]{64}$")
# 外部插件允许声明的能力名。App 安装侧已改为开放声明（仅用于安装预览展示，
# 不再是拒绝条件），这里同步放宽到 App 清单文档列出的集合，
# 避免作者声明 network 等常用能力时被签名工具单方面拒绝。
# 与 App 的能力键对齐（PluginCapabilityGateway.CAPABILITY_ACTIONS 的键 + 事件/钩子/调度常量）。
# 以前这张表是手抄的，抄漏的都在 App 里真实存在：ui.interactive 与 storage 是官方示例声明的，
# 结果作者照示例打包会被签名工具单方面拒绝。新增条目由 PluginCapabilityVocabularyTest 盯住两侧一致。
ALLOWED_CAPABILITIES = {
    "resource.read", "resource.write", "network",
    "storage.read", "storage.write", "clipboard.read", "clipboard.write",
    "notify", "device.info", "ui", "ui.mood", "ui.skin", "clipboard",
    "storage", "config.write", "host.tool.invoke", "chat.read", "host.model.call",
    "app.navigate", "app.messages", "app.theme", "app.settings",
    "ui.interactive", "ui.slot", "network.local",
    "event.onMessage", "event.onToolCall", "hook.toolCallBefore", "hook.systemPrompt",
    "schedule", "runtime.long", "runtime.service",
}
# 共享空间声明是带冒号的前缀形式，不能逐项枚举：名字空间允许中文（见 PluginSharedStorage）。
SHARED_CAPABILITY_PREFIX = "storage.shared:"

# ── commands / schedules 的判据：与 App 端**同一套数字**，不是这里另定一套 ──
# 见 io.zer0.muse.data.plugin.PluginCommandRegistry（NAME_REGEX / MAX_COMMANDS_PER_PLUGIN /
# MAX_DESCRIPTION_CHARS）与 PluginScheduleDue.MIN_INTERVAL_MINUTES。
# 这些限制在 App 侧的表现是「静默」的：超过 20 条的命令被 take() 截掉、
# 间隔小于 15 分钟被 coerceAtLeast 抬高、名字非法的那条整条不注册。
# 作者在打包机上什么都看不见，到设备上才发现「命令少了两条」——所以在这里提前拒。
COMMAND_NAME_REGEX = re.compile(r"^[a-z][a-z0-9_-]{0,31}$")
MAX_COMMANDS_PER_PLUGIN = 20
MAX_COMMAND_DESCRIPTION_CHARS = 120
MIN_SCHEDULE_INTERVAL_MINUTES = 15
MAX_SCHEDULES_PER_PLUGIN = 10
LOADED_EXTENSIONS = (".js", ".py", ".json", ".md")
UI_SKIN_KIND = "ui-skin"
UI_SKIN_ENTRY = "skin.json"
MAX_SIGNATURE_BYTES = 256


def fail(message: str) -> None:
    print(f"ERROR: {message}", file=sys.stderr)
    raise SystemExit(1)


def openssl_binary() -> str:
    configured = os.environ.get("MUSE_OPENSSL", "").strip()
    if configured:
        if not os.path.isfile(configured):
            fail(f"MUSE_OPENSSL 指向的文件不存在: {configured}")
        return configured
    found = shutil.which("openssl")
    if not found:
        fail("找不到 openssl，可设置环境变量 MUSE_OPENSSL 指向可执行文件")
    return found


def openssl(args, stdin_bytes: bytes = None) -> bytes:
    result = subprocess.run(
        [openssl_binary()] + args,
        input=stdin_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    if result.returncode != 0:
        fail(f"openssl {' '.join(args)} 失败: {result.stderr.decode('utf-8', 'replace').strip()}")
    return result.stdout


# ─────────────────────────── 规范化与摘要（与 App 对齐） ───────────────────────────

def canonical_bytes(payload) -> bytes:
    """AppJson.encodeToString：紧凑、含默认值、非 ASCII 原样输出。"""
    return json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def length_prefixed_part(path: str, data: bytes) -> bytes:
    return (
        path.encode("utf-8") + b"\x00"
        + str(len(data)).encode("ascii") + b"\x00"
        + data + b"\x00"
    )


def content_sha256(parts) -> str:
    digest = hashlib.sha256()
    for path, data in parts:
        digest.update(length_prefixed_part(path, data))
    return digest.hexdigest()


def signature_input(parts) -> bytes:
    return b"".join(hashlib.sha256(length_prefixed_part(path, data)).digest() for path, data in parts)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def b64_standard(data: bytes) -> str:
    return base64.b64encode(data).decode("ascii")


def decode_b64_strict(text: str) -> bytes:
    return base64.b64decode(text, validate=True)


# ─────────────────────────── 密钥 ───────────────────────────

def generate_key(key_path: Path) -> None:
    if key_path.exists():
        fail(f"私钥已存在，不覆盖: {key_path}")
    key_path.parent.mkdir(parents=True, exist_ok=True)
    key_path.write_bytes(openssl(["ecparam", "-name", CURVE, "-genkey", "-noout"]))
    try:
        os.chmod(key_path, 0o600)
    except OSError:
        pass


def spki_der(key_path: Path) -> bytes:
    return openssl(["ec", "-in", str(key_path), "-pubout", "-outform", "DER"])


def public_key_info(key_path: Path) -> dict:
    der = spki_der(key_path)
    return {"publicKey": b64_standard(der), "fingerprint": sha256_hex(der)}


def sign_bytes(key_path: Path, payload: bytes) -> bytes:
    with tempfile.TemporaryDirectory() as tmp:
        payload_file = Path(tmp) / "payload.bin"
        signature_file = Path(tmp) / "signature.der"
        payload_file.write_bytes(payload)
        openssl([
            "dgst", "-sha256", "-sign", str(key_path),
            "-out", str(signature_file), str(payload_file),
        ])
        signature = signature_file.read_bytes()
    if len(signature) > MAX_SIGNATURE_BYTES:
        fail(f"签名长度异常({len(signature)} 字节)")
    return signature


def verify_signature(key_path: Path, payload: bytes, signature: bytes) -> None:
    with tempfile.TemporaryDirectory() as tmp:
        payload_file = Path(tmp) / "payload.bin"
        signature_file = Path(tmp) / "signature.der"
        public_file = Path(tmp) / "public.pem"
        payload_file.write_bytes(payload)
        signature_file.write_bytes(signature)
        public_file.write_bytes(openssl(["ec", "-in", str(key_path), "-pubout"]))
        result = subprocess.run(
            [openssl_binary(), "dgst", "-sha256", "-verify", str(public_file),
             "-signature", str(signature_file), str(payload_file)],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        )
        if result.returncode != 0:
            fail("本地自校验失败：签名无法被对应公钥验证")


# ─────────────────────────── manifest 规范化 ───────────────────────────

def ordered(source: dict, order, defaults=None, label: str = "", optional=(), allowed_extra=()) -> dict:
    defaults = defaults or {}
    unknown = [key for key in source if key not in order and key not in allowed_extra]
    if unknown:
        fail(f"{label} 含未知字段 {unknown}；为避免与 App 序列化不一致，必须删除或改名")
    result = {}
    for key in order:
        if key in source:
            result[key] = source[key]
        elif key in defaults:
            result[key] = defaults[key]
    missing = [
        key for key in order
        if key not in result and key not in defaults and key not in optional
    ]
    if missing:
        fail(f"{label} 缺少必填字段 {missing}")
    return result


def normalize_manifest(raw: dict, with_signature: bool) -> dict:
    manifest = ordered(
        raw, MANIFEST_ORDER, MANIFEST_DEFAULTS, "manifest.json",
        optional=OPTIONAL_MANIFEST_FIELDS,
        allowed_extra=UNSIGNED_MANIFEST_FIELDS,
    )
    manifest["tools"] = [
        ordered(tool, TOOL_ORDER, TOOL_DEFAULTS, f"tools[{index}]")
        for index, tool in enumerate(manifest["tools"])
    ]
    if with_signature and isinstance(manifest["signature"], dict):
        manifest["signature"] = ordered(
            manifest["signature"], SIGNATURE_ORDER, {"algorithm": SIGNATURE_ALGORITHM}, "signature",
        )
    finalize_optional_fields(manifest)
    return manifest


# ─────────────── 可选字段与 App 序列化对齐 ───────────────
# App 端（kotlinx.serialization，AppJson: encodeDefaults=true / explicitNulls=false）：
#   · contributes / uiPanel 为 null 时不输出；
#   · toolCards 为空对象时不输出（@EncodeDefault(NEVER)）；
#   · contributes 非空时输出全部默认字段（configuration / ConfigItem / SelectOption）。
# 本段逻辑必须与上述行为逐字一致，否则签名包无法通过 App 验签。

CONFIG_ITEM_ORDER = ["key", "type", "defaultVal", "description", "options"]
SELECT_OPTION_ORDER = ["value", "label"]


def normalize_select_option(source: dict, label: str) -> dict:
    if not isinstance(source, dict) or "value" not in source:
        fail(f"{label} 缺少 value")
    unknown = [key for key in source if key not in SELECT_OPTION_ORDER]
    if unknown:
        fail(f"{label} 含未知字段 {unknown}；为避免与 App 序列化不一致，必须删除或改名")
    value = source["value"]
    # SelectOption.label 的默认值是 value 本身
    return {"value": value, "label": source.get("label", value)}


def normalize_config_item(source: dict, label: str) -> dict:
    if not isinstance(source, dict) or "key" not in source:
        fail(f"{label} 缺少 key")
    unknown = [key for key in source if key not in CONFIG_ITEM_ORDER]
    if unknown:
        fail(f"{label} 含未知字段 {unknown}；为避免与 App 序列化不一致，必须删除或改名")
    result = {"key": source["key"], "type": source.get("type", "string")}
    # defaultVal 是 JsonElement?：null 时 App 不输出该键
    if source.get("defaultVal") is not None:
        result["defaultVal"] = source["defaultVal"]
    result["description"] = source.get("description", "")
    options = source.get("options", [])
    result["options"] = [
        normalize_select_option(option, f"{label}.options[{index}]")
        for index, option in enumerate(options)
    ]
    return result


def normalize_contributes(source: dict, label: str) -> dict:
    if not isinstance(source, dict):
        fail(f"{label} 必须是对象")
    unknown = [key for key in source if key != "configuration"]
    if unknown:
        fail(f"{label} 含未知字段 {unknown}；为避免与 App 序列化不一致，必须删除或改名")
    configuration = source.get("configuration", [])
    return {
        "configuration": [
            normalize_config_item(item, f"{label}.configuration[{index}]")
            for index, item in enumerate(configuration)
        ],
    }


def finalize_optional_fields(manifest: dict) -> None:
    """让可选字段的空值与 App 序列化行为一致（默认值不进入 JSON 字节序列）。"""
    contributes = manifest.get("contributes")
    if contributes is None:
        manifest.pop("contributes", None)
    else:
        manifest["contributes"] = normalize_contributes(contributes, "contributes")
    if manifest.get("uiPanel") is None:
        manifest.pop("uiPanel", None)
    if manifest.get("toolCards") is None or manifest.get("toolCards") == {}:
        manifest.pop("toolCards", None)
    # hooks 带 @EncodeDefault(NEVER)：空列表同样不进入字节序列。
    # 漏掉这一步会让 manifest.json 多出 "hooks":[]，与 App 序列化结果不一致，
    # 所有签名包都会被判“发行者签名与插件内容不匹配”。
    if not manifest.get("hooks"):
        manifest.pop("hooks", None)


def declared_runtime(raw: dict) -> str:
    """读取作者声明的运行时；缺省按 App 端默认值 js。

    该字段不参与签名载荷，只影响入口扩展名校验与包内文件收集。
    """
    value = raw.get("runtime", RUNTIME_JS)
    if value not in KNOWN_RUNTIMES:
        fail(f"不支持的 runtime: {value}（可用：{', '.join(KNOWN_RUNTIMES)}）")
    return value


def validate_extensions(manifest: dict) -> None:
    """commands / schedules 的结构判据（与 App 端同源，见上面的常量注释）。

    刻意**不**检查 handler 是否真的定义在入口代码里：工具那类有 App 侧硬要求，
    而命令与定时任务常被写成路由表动态分发（`handlers[name](args)`），
    在这里强制存在性会误杀合法插件。宁可漏判，不造新墙。
    """
    commands = manifest.get("commands") or []
    if len(commands) > MAX_COMMANDS_PER_PLUGIN:
        fail(
            f"commands 超过上限 {MAX_COMMANDS_PER_PLUGIN} 条（当前 {len(commands)}）："
            "App 装载时只取前 20 条，多出来的会在设备上静默消失"
        )
    seen_names = set()
    for command in commands:
        name = command.get("name", "")
        if not COMMAND_NAME_REGEX.match(name):
            fail(
                f"命令名 {name!r} 不合法：需小写字母开头，只含小写字母/数字/_/-，最长 32 字符"
                "（与 App 的 PluginCommandRegistry.NAME_REGEX 同一判据）"
            )
        if name in seen_names:
            fail(f"命令名重复: {name}")
        seen_names.add(name)
        if not (command.get("handler") or "").strip():
            fail(f"命令 /{name} 缺 handler（装载后这条命令不会被注册）")
        description = command.get("description") or ""
        if len(description) > MAX_COMMAND_DESCRIPTION_CHARS:
            fail(
                f"命令 /{name} 的说明 {len(description)} 字，超过上限 {MAX_COMMAND_DESCRIPTION_CHARS}："
                "它要进命令列表，属于上下文预算而不是排版偏好"
            )

    schedules = manifest.get("schedules") or []
    if len(schedules) > MAX_SCHEDULES_PER_PLUGIN:
        fail(f"schedules 超过上限 {MAX_SCHEDULES_PER_PLUGIN} 条（当前 {len(schedules)}）")
    seen_schedule_names = set()
    for schedule in schedules:
        sname = schedule.get("name", "")
        if not sname.strip():
            fail("定时任务缺 name（同一插件内用它记录上次执行时间）")
        if sname in seen_schedule_names:
            fail(f"定时任务名重复: {sname}（App 按名字记录上次执行时间，重名会互相顶掉）")
        seen_schedule_names.add(sname)
        if not (schedule.get("handler") or "").strip():
            fail(f"定时任务 {sname} 缺 handler")
        interval = schedule.get("intervalMinutes", 60)
        if not isinstance(interval, int) or isinstance(interval, bool):
            fail(f"定时任务 {sname} 的 intervalMinutes 必须是整数（当前 {interval!r}）")
        if interval < MIN_SCHEDULE_INTERVAL_MINUTES:
            fail(
                f"定时任务 {sname} 的 intervalMinutes={interval} 低于下限 "
                f"{MIN_SCHEDULE_INTERVAL_MINUTES}：App 会 coerceAtLeast 抬到 15，"
                "作者以为设的是 1 分钟、实际跑的是 15 分钟"
            )


def validate_manifest(manifest: dict, runtime: str = RUNTIME_JS) -> None:
    if not PLUGIN_ID_REGEX.match(manifest["id"]):
        fail(f"插件 id 非法: {manifest['id']}")
    if not manifest["name"]:
        fail("manifest 缺少 name")
    if manifest["trust"] != "sandboxed":
        fail("外部插件 trust 必须是 sandboxed")
    is_skin = manifest["kind"] == UI_SKIN_KIND
    if is_skin:
        if runtime != RUNTIME_JS:
            fail("ui-skin 插件只支持 js 运行时")
        if manifest["tools"]:
            fail("ui-skin 插件不得声明工具")
        if "ui.skin" not in manifest["capabilities"]:
            fail("ui-skin 插件必须在 capabilities 中声明 ui.skin")
    else:
        if not manifest["tools"]:
            fail("插件必须声明至少一个工具")
        expected_suffix = ".py" if runtime == RUNTIME_PYTHON else ".js"
        if not manifest["entry"].lower().endswith(expected_suffix):
            fail(f"runtime={runtime} 的入口必须是 {expected_suffix} 文件: {manifest['entry']}")
    validate_extensions(manifest)
    for capability in manifest["capabilities"] + manifest["permissions"]:
        if capability.startswith(SHARED_CAPABILITY_PREFIX):
            # 具体名字空间由 App 判（能不能安全当目录名）；签名工具只认这个形状，
            # 免得在这里再抄一套 ASCII 规则、把中文名字空间挡掉。
            if not capability[len(SHARED_CAPABILITY_PREFIX):].strip():
                fail(f"共享空间声明缺少名字空间: {capability}")
        elif capability not in ALLOWED_CAPABILITIES:
            fail(f"能力不在白名单内: {capability}")
    for tool in manifest["tools"]:
        if not TOOL_NAME_REGEX.match(tool["name"]) or not FUNCTION_NAME_REGEX.match(tool["functionName"]):
            fail(f"工具名或函数名非法: {tool['name']}/{tool['functionName']}")


# ─────────────────────────── 打包 ───────────────────────────

def collect_files(src: Path, manifest: dict) -> tuple:
    """返回 (entry_path, entry_bytes, extras{path: bytes})；只收集 loader 支持的扩展名。"""
    entry_name = manifest["entry"].replace("\\", "/")
    is_skin = manifest["kind"] == UI_SKIN_KIND
    entry_path = src / entry_name
    if is_skin:
        # 皮肤包没有 JS 入口：App 载入 zip 时把 entryCode 固定为空串，
        # 但仍会用 manifest.entry 这个名字参与内容摘要，签名必须与之一致。
        entry_bytes = b""
    else:
        if not entry_path.is_file():
            fail(f"找不到入口文件: {entry_path}")
        entry_bytes = entry_path.read_bytes()

    extras = {}
    for path in sorted(src.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(src).as_posix()
        if relative == "manifest.json":
            continue
        if not is_skin and relative == entry_name:
            continue
        if not relative.endswith(LOADED_EXTENSIONS):
            print(f"  提示：跳过不被加载的文件 {relative}")
            continue
        if ".." in relative or relative.startswith("/"):
            fail(f"非法路径: {relative}")
        if not relative.isascii():
            fail(f"包内路径必须为 ASCII（排序规则需与 App 逐字节一致）: {relative}")
        extras[relative] = path.read_bytes()
    if is_skin:
        if UI_SKIN_ENTRY not in extras:
            fail(f"ui-skin 插件缺少 {UI_SKIN_ENTRY}")
        for name in extras:
            if name.lower().endswith((".js", ".py")):
                fail(f"ui-skin 插件不得包含脚本: {name}")
        validate_skin_json(extras[UI_SKIN_ENTRY])
    return entry_name, entry_bytes, extras


# ─────────────── 皮肤包校验（与宿主 BubbleSkinValidator 同口径） ───────────────

BUBBLE_ROLES = ("USER", "ASSISTANT", "GROUP_ASSISTANT", "SYSTEM", "TOOL")
MIN_CONTRAST_RATIO = 4.5
SKIN_ID_REGEX = re.compile(r"^[a-z0-9][a-z0-9_-]{0,63}$")


def relative_luminance(argb: int) -> float:
    def linearize(channel: float) -> float:
        return channel / 12.92 if channel <= 0.03928 else ((channel + 0.055) / 1.055) ** 2.4

    red = linearize(((argb >> 16) & 0xFF) / 255.0)
    green = linearize(((argb >> 8) & 0xFF) / 255.0)
    blue = linearize((argb & 0xFF) / 255.0)
    return 0.2126 * red + 0.7152 * green + 0.0722 * blue


def contrast_ratio(foreground: int, background: int) -> float:
    first = relative_luminance(foreground)
    second = relative_luminance(background)
    lighter, darker = max(first, second), min(first, second)
    return (lighter + 0.05) / (darker + 0.05)


def validate_skin_json(raw: bytes) -> None:
    """发布前按宿主规则自检皮肤，避免上架后静默回退成内置气泡。"""
    try:
        skin = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"{UI_SKIN_ENTRY} 不是合法 JSON: {error}")

    if skin.get("schemaVersion", 1) != 1:
        fail(f"{UI_SKIN_ENTRY} schemaVersion 必须为 1")
    if not SKIN_ID_REGEX.match(str(skin.get("id", ""))):
        fail(f"{UI_SKIN_ENTRY} 皮肤 id 非法: {skin.get('id')!r}")
    if not str(skin.get("name", "")).strip():
        fail(f"{UI_SKIN_ENTRY} 缺少皮肤名称")

    for mode in ("light", "dark"):
        roles = skin.get(mode) or {}
        if not isinstance(roles, dict) or not roles:
            fail(f"{UI_SKIN_ENTRY} 缺少 {mode} 配色")
        for role, style in roles.items():
            if role not in BUBBLE_ROLES:
                fail(f"{UI_SKIN_ENTRY} 未知角色 {role}（可用：{', '.join(BUBBLE_ROLES)}）")
            for field, minimum, maximum in (
                ("radiusDp", 0.0, 40.0),
                ("paddingHorizontalDp", 0.0, 48.0),
                ("paddingVerticalDp", 0.0, 48.0),
                ("maxWidthFraction", 0.35, 1.0),
                ("fontScale", 0.75, 1.5),
                ("outlineWidthDp", 0.0, 8.0),
            ):
                if field in style and not (minimum <= float(style[field]) <= maximum):
                    fail(f"{UI_SKIN_ENTRY} {mode}/{role} 的 {field} 超出 {minimum}~{maximum}")
            surface = int(style.get("surfaceArgb", 0))
            content = int(style.get("contentArgb", 0))
            ratio = contrast_ratio(content, surface)
            if ratio < MIN_CONTRAST_RATIO:
                fail(
                    f"{UI_SKIN_ENTRY} {mode}/{role} 正文与底色对比度不足: "
                    f"{ratio:.2f} < {MIN_CONTRAST_RATIO}",
                )



# 入口里出现这些片段通常意味着插件试图绕过沙盒或做危险动作；沙盒本身会拦截，
# 但发布前提示出来，避免把明显可疑的代码送上市场。
SUSPICIOUS_SNIPPETS = (
    "eval(", "new Function", "XMLHttpRequest", "fetch(", "document.", "window.",
    "localStorage", "importScripts", "require(",
)


def check_entry_code(manifest: dict, entry_bytes: bytes, runtime: str = RUNTIME_JS) -> None:
    """校验 manifest 声明的函数确实存在于入口文件，并对可疑片段给出警告。"""
    if entry_bytes.startswith(b"\xef\xbb\xbf"):
        print("  警告：入口文件带 UTF-8 BOM，建议去掉（App 会按 UTF-8 解码）")
    try:
        text = entry_bytes.decode("utf-8")
    except UnicodeDecodeError as error:
        fail(f"入口文件不是合法 UTF-8: {error}")

    for tool in manifest["tools"]:
        function_name = tool["functionName"]
        if not entry_defines_function(text, function_name, runtime=runtime):
            fail(f"入口未定义工具函数 {function_name}（manifest 工具 {tool['name']}）")


# 入口里的函数定义写法必须按**实际语言形态**认，不能只认最朴素的一种：
# App 的 node 引擎要求 ESM 导出，所以自家示例写的就是 `export function noteSave(...)`；
# 以前这里只匹配行首 `function NAME(`，`export ` 一挡就判"未定义"——
# 结果是签名工具签不出 docs/plugin/samples 里的官方示例（而 App 侧完全能装能跑）。
_JS_NAME = r"[A-Za-z_$][A-Za-z0-9_$]*"


def entry_defines_function(text: str, function_name: str, runtime: str = RUNTIME_JS) -> bool:
    """manifest 声明的工具函数是否真的在入口里定义（JS 认四种写法，Python 认 def）。"""
    escaped = re.escape(function_name)
    if runtime == RUNTIME_PYTHON:
        candidates = [r"(^|\n)[ \t]*def\s+" + escaped + r"\s*\("]
    else:
        candidates = [
            # function noteSave(...) / async function / export function / export async function
            r"(^|\n)[ \t]*(export\s+)?(async\s+)?function\s+" + escaped + r"\s*\(",
            # export const noteSave = (args) => ... / const noteSave = function ...
            r"(^|\n)[ \t]*(export\s+)?(?:const|let|var)\s+" + escaped +
            r"\s*=\s*(async\s+)?(function\b|\([^)]*\)\s*=>|" + _JS_NAME + r"\s*=>)",
            # 全局赋值：noteSave = function / noteSave = (...) =>
            r"(^|\n)[ \t]*" + escaped + r"\s*=\s*(async\s+)?(function\b|\([^)]*\)\s*=>)",
        ]
    return any(re.search(pattern, text) for pattern in candidates)

    # 可疑片段表是按 JS 写法列的，Python 入口不套用，避免误报。
    if runtime == RUNTIME_JS:
        for snippet in SUSPICIOUS_SNIPPETS:
            if snippet in text:
                print(f"  警告：入口包含可疑片段 {snippet!r}，请确认是必要且安全的")



def build_parts(manifest: dict, entry_name: str, entry_bytes: bytes, extras: dict, blank_signature: bool) -> list:
    payload_manifest = json.loads(json.dumps(manifest))
    if blank_signature and isinstance(payload_manifest.get("signature"), dict):
        payload_manifest["signature"]["signature"] = ""
    parts = [("manifest.json", canonical_bytes(payload_manifest))]
    parts.append((entry_name, entry_bytes))
    for path in sorted(extras):
        parts.append((path, extras[path]))
    return parts


def command_pack(args) -> None:
    src = Path(args.src)
    key_path = Path(args.key)
    if not src.is_dir():
        fail(f"插件源码目录不存在: {src}")
    if not key_path.is_file():
        fail(f"私钥不存在: {key_path}")
    if not PUBLISHER_ID_REGEX.match(args.publisher_id):
        fail(f"发行者 ID 非法: {args.publisher_id}")

    raw = json.loads((src / "manifest.json").read_text(encoding="utf-8"))
    runtime = declared_runtime(raw)
    manifest = normalize_manifest(raw, with_signature=False)
    validate_manifest(manifest, runtime)

    publisher = public_key_info(key_path)
    manifest["signature"] = {
        "publisherId": args.publisher_id,
        "publicKey": publisher["publicKey"],
        "signature": "",
        "algorithm": SIGNATURE_ALGORITHM,
    }

    entry_name, entry_bytes, extras = collect_files(src, manifest)
    check_entry_code(manifest, entry_bytes, runtime)
    signature = sign_bytes(key_path, signature_input(build_parts(manifest, entry_name, entry_bytes, extras, True)))
    verify_signature(
        key_path,
        signature_input(build_parts(manifest, entry_name, entry_bytes, extras, True)),
        signature,
    )
    manifest["signature"]["signature"] = b64_standard(signature)
    manifest = normalize_manifest(manifest, with_signature=True)

    final_parts = build_parts(manifest, entry_name, entry_bytes, extras, blank_signature=False)
    manifest_sha256 = content_sha256(final_parts)
    manifest_json_bytes = final_parts[0][1]

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    artifact_path = out_dir / f"{manifest['id']}-{manifest['version']}.muse-plugin"
    is_skin = manifest["kind"] == UI_SKIN_KIND
    with zipfile.ZipFile(artifact_path, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.writestr("manifest.json", manifest_json_bytes)
        # 皮肤包不得包含任何 .js：入口只以「manifest.entry + 空内容」参与摘要，不写进 zip。
        if not is_skin:
            archive.writestr(entry_name, entry_bytes)
        for path in sorted(extras):
            archive.writestr(path, extras[path])
    archive_bytes = artifact_path.read_bytes()

    metadata = {
        "entry": {
            **{key: manifest[key] for key in ("id", "version", "name", "description")},
            "publisherId": args.publisher_id,
            "publisherKeyFingerprint": publisher["fingerprint"],
            "artifactFile": artifact_path.name,
            "artifactBytes": len(archive_bytes),
            "artifactSha256": sha256_hex(archive_bytes),
            "manifestSha256": manifest_sha256,
            "capabilities": manifest["capabilities"],
            "permissions": manifest["permissions"],
        },
        "publisherPublicKey": publisher["publicKey"],
    }
    metadata_path = out_dir / f"{manifest['id']}-{manifest['version']}.json"
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"已签名插件包: {artifact_path}")
    print(f"  id={manifest['id']} version={manifest['version']} 工具 {len(manifest['tools'])} 个")
    print(f"  artifactBytes={len(archive_bytes)} artifactSha256={metadata['entry']['artifactSha256']}")
    print(f"  manifestSha256={manifest_sha256}")
    print(f"  publisher={args.publisher_id} keyFingerprint={publisher['fingerprint']}")
    print(f"  元数据: {metadata_path}")


# ─────────────────────────── 目录 ───────────────────────────

def package_parts_from_zip(archive_path: Path) -> list:
    """按 PluginPackageLoader 规则复算包内各部分，用于独立验证 manifestSha256。"""
    with zipfile.ZipFile(archive_path) as archive:
        names = [info.filename for info in archive.infolist() if not info.is_dir()]
        if "manifest.json" not in names:
            fail(f"{archive_path.name} 缺少 manifest.json")
        manifest_bytes = archive.read("manifest.json")
        manifest = json.loads(manifest_bytes.decode("utf-8"))
        entry_name = manifest.get("entry", "main.js")
        is_skin = manifest.get("kind") == UI_SKIN_KIND
        if entry_name in names:
            entry_bytes = archive.read(entry_name)
        elif is_skin:
            # 皮肤包没有 JS 入口：宿主以「入口名 + 空内容」参与摘要。
            entry_bytes = b""
        else:
            fail(f"{archive_path.name} 缺少入口文件 {entry_name}")
        parts = [("manifest.json", manifest_bytes), (entry_name, entry_bytes)]
        for name in sorted(names):
            if name in ("manifest.json", entry_name):
                continue
            if not name.endswith(LOADED_EXTENSIONS):
                continue
            parts.append((name, archive.read(name)))
    return parts


def command_catalog(args) -> None:
    packages_dir = Path(args.packages_dir)
    key_path = Path(args.key)
    if not packages_dir.is_dir():
        fail(f"包目录不存在: {packages_dir}")
    if not key_path.is_file():
        fail(f"私钥不存在: {key_path}")

    base_url = args.base_url.rstrip("/")
    entries = []
    # 只读取与插件包同名的元数据：目录里可能同时存在 signed-catalog.json / public-keys.json
    # 等其它 JSON，按名字匹配可以避免把它们当成插件元数据。
    metadata_paths = []
    for artifact_path in sorted(packages_dir.glob("*.muse-plugin")):
        metadata_path = artifact_path.with_suffix(".json")
        if not metadata_path.is_file():
            fail(f"缺少 {artifact_path.name} 的元数据，请重新执行 pack")
        metadata_paths.append(metadata_path)
    if not metadata_paths:
        fail(f"{packages_dir} 中没有已签名的插件包，请先执行 pack")

    for metadata_path in metadata_paths:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        source = dict(metadata["entry"])
        artifact_name = source.pop("artifactFile", None)
        if not artifact_name:
            fail(f"{metadata_path.name} 缺少 artifactFile")
        artifact_path = packages_dir / artifact_name
        if not artifact_path.is_file():
            fail(f"缺少插件包文件: {artifact_path}")
        source["artifactUrl"] = base_url + "/" + artifact_path.name
        entry = ordered(source, ENTRY_ORDER, {}, f"entry({metadata_path.name})")

        # 独立复算：字节数、包摘要、内容摘要都必须与 pack 阶段记录的值一致。
        archive_bytes = artifact_path.read_bytes()
        if len(archive_bytes) != entry["artifactBytes"]:
            fail(f"{entry['id']} 记录大小与实际不符")
        if sha256_hex(archive_bytes) != entry["artifactSha256"]:
            fail(f"{entry['id']} 记录包摘要与实际不符")
        if content_sha256(package_parts_from_zip(artifact_path)) != entry["manifestSha256"]:
            fail(f"{entry['id']} 记录内容摘要与包内实际内容不符")
        entries.append(entry)

    if not entries:
        fail(f"{packages_dir} 中没有已签名的插件包元数据，请先执行 pack")

    sequence = args.sequence if args.sequence is not None else int(time.time())
    now_ms = int(time.time() * 1000)
    payload = {
        "catalogId": args.catalog_id,
        "schemaVersion": SCHEMA_VERSION,
        "sequence": sequence,
        "generatedAtEpochMs": now_ms,
        "expiresAtEpochMs": now_ms + int(args.days * 86400 * 1000),
        "entries": entries,
    }
    payload = ordered(payload, CATALOG_ORDER, {}, "catalog")
    for entry in payload["entries"]:
        if not PLUGIN_ID_REGEX.match(entry["id"]):
            fail(f"目录条目 id 非法: {entry['id']}")
        if not VERSION_REGEX.match(entry["version"]):
            fail(f"目录条目版本非法: {entry['version']}")
        if not SHA256_REGEX.match(entry["artifactSha256"]) or not SHA256_REGEX.match(entry["manifestSha256"]):
            fail(f"目录条目摘要格式非法: {entry['id']}")
        if not str(entry["artifactUrl"]).startswith("https://"):
            fail(f"目录条目下载地址必须是 https: {entry['artifactUrl']}")

    payload_bytes = canonical_bytes(payload)
    signature = sign_bytes(key_path, payload_bytes)
    verify_signature(key_path, payload_bytes, signature)
    root = public_key_info(key_path)

    signed = {
        "payload": payload,
        "keyId": args.key_id,
        "signatureBase64": b64_standard(signature),
    }
    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(signed, ensure_ascii=False, indent=2), encoding="utf-8")

    publishers = {}
    for metadata_path in metadata_paths:
        metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
        publishers[metadata["entry"]["publisherId"]] = metadata["publisherPublicKey"]
    keys_path = out_path.parent / "public-keys.json"
    keys_path.write_text(json.dumps({
        "catalog": {"keyId": args.key_id, "publicKey": root["publicKey"], "fingerprint": root["fingerprint"]},
        "publishers": publishers,
        "sequence": sequence,
        "expiresAtEpochMs": payload["expiresAtEpochMs"],
        "baseUrl": args.base_url.rstrip("/"),
    }, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"已签名目录: {out_path}")
    print(f"  catalogId={payload['catalogId']} sequence={sequence} 条目 {len(entries)} 个")
    print(f"  有效期至 {time.strftime('%Y-%m-%d %H:%M:%S', time.localtime(payload['expiresAtEpochMs'] / 1000))}")
    print(f"  keyId={args.key_id}")
    print(f"  信任根公钥(Base64): {root['publicKey']}")
    print(f"  信任根指纹: {root['fingerprint']}")
    print(f"  公钥信息: {keys_path}")


def command_keygen(args) -> None:
    key_path = Path(args.key)
    generate_key(key_path)
    info = public_key_info(key_path)
    print(f"已生成 P-256 私钥: {key_path}")
    print(f"  公钥(X.509 SPKI, Base64): {info['publicKey']}")
    print(f"  指纹(SHA-256): {info['fingerprint']}")
    print("  请妥善备份私钥；一旦丢失，已发布目录与用户信任根都无法继续更新。")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Muse 插件市场离线签名工具")
    sub = parser.add_subparsers(dest="command", required=True)

    keygen = sub.add_parser("keygen", help="生成 P-256 私钥")
    keygen.add_argument("--key", required=True, help="私钥输出路径")
    keygen.set_defaults(func=command_keygen)

    pack = sub.add_parser("pack", help="把插件源码目录打成签名后的 .muse-plugin")
    pack.add_argument("--src", required=True, help="插件源码目录（含 manifest.json）")
    pack.add_argument("--key", required=True, help="发行者私钥路径")
    pack.add_argument("--publisher-id", required=True, help="发行者 ID")
    pack.add_argument("--out-dir", required=True, help="产物输出目录")
    pack.set_defaults(func=command_pack)

    catalog = sub.add_parser("catalog", help="用已签名的包生成签名目录")
    catalog.add_argument("--packages-dir", required=True, help="pack 的产物目录")
    catalog.add_argument("--key", required=True, help="目录根私钥路径")
    catalog.add_argument("--key-id", required=True, help="目录信任根 keyId")
    catalog.add_argument("--base-url", required=True, help="插件包对外基础地址（https）")
    catalog.add_argument("--catalog-id", default="muse-official", help="目录标识")
    catalog.add_argument("--out", required=True, help="签名目录输出路径")
    catalog.add_argument("--sequence", type=int, default=None, help="目录序号（默认取当前 Unix 秒）")
    catalog.add_argument("--days", type=float, default=30, help="有效期天数")
    catalog.set_defaults(func=command_catalog)

    return parser


def main() -> None:
    args = build_parser().parse_args()
    args.func(args)


if __name__ == "__main__":
    main()

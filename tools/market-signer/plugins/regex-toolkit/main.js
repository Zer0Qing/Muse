/**
 * Muse 插件：正则工具箱
 *
 * 运行契约（宿主 WebViewSkillEngine / JsSandbox）：
 *  - 宿主把本文件整体 eval 进 WebView 的 V8，再执行
 *    `JSON.stringify(<functionName>.apply(null, [参数对象]))`；
 *  - 每个工具 = 一个顶层函数，只接收一个参数对象，返回普通对象（不要自己 JSON.stringify）；
 *  - 失败返回 { error: "中文原因" }（宿主会把这类结果标记为失败）；
 *  - 只用 ES5 语法，不联网、不读写文件，任何情况下都不向外抛异常。
 *
 * 安全设计：
 *  - 用户正则一律 new RegExp(pattern, flags) 编译并 try/catch；
 *  - 拒绝嵌套量词（如 (a+)+ / (a*)* / (\d+){2,} 这类"星高度 >= 2"的模式），
 *    它们会造成灾难性回溯（ReDoS）；
 *  - 兜底上限：文本 <= 100000 字符、模式 <= 1000 字符、单次最多收集 1000 个匹配。
 */

var REGEX_MAX_TEXT = 100000;
var REGEX_MAX_PATTERN = 1000;
var REGEX_MAX_MATCHES = 1000;
var REGEX_LIMIT_DEFAULT = 20;
var REGEX_LIMIT_MAX = 100;
var REGEX_ALLOWED_FLAGS = "gimsuy";

/** 参数对象兜底，避免 args 为 null/undefined 时抛异常。 */
function regexArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** 异常统一转文本。 */
function regexMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** 宽松布尔读取：接受 true/false 与 "true"/"false"/1/0。 */
function regexBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** flags 规范化：仅允许 g i m s u y，重复字符自动去重。 */
function regexNormalizeFlags(flagsValue) {
    if (flagsValue === undefined || flagsValue === null) return { flags: "" };
    if (typeof flagsValue !== "string") return { error: "flags 必须是字符串" };
    var raw = flagsValue.replace(/\s+/g, "");
    var out = "";
    for (var i = 0; i < raw.length; i++) {
        var ch = raw.charAt(i);
        if (REGEX_ALLOWED_FLAGS.indexOf(ch) < 0) {
            return { error: "不支持的 flags 字符: " + ch + "（仅允许 g i m s u y）" };
        }
        if (out.indexOf(ch) < 0) out += ch;
    }
    return { flags: out };
}

/** 读取紧跟在原子之后的量词，返回 { unbounded, end } 或 null（非量词）。 */
function regexReadQuantifier(pattern, start) {
    var ch = pattern.charAt(start);
    var end;
    if (ch === "*" || ch === "+") {
        end = start + 1;
        if (pattern.charAt(end) === "?") end++;
        return { unbounded: true, end: end };
    }
    if (ch === "?") {
        end = start + 1;
        if (pattern.charAt(end) === "?") end++;
        return { unbounded: false, end: end };
    }
    if (ch === "{") {
        var close = pattern.indexOf("}", start);
        if (close < 0) return null;
        var body = pattern.substring(start + 1, close);
        var parsed = /^(\d+)(,(\d*))?$/.exec(body);
        if (parsed === null) return null;
        end = close + 1;
        if (pattern.charAt(end) === "?") end++;
        var hasComma = parsed[2] !== undefined;
        var upper = parsed[3];
        return { unbounded: hasComma && (upper === undefined || upper === ""), end: end };
    }
    return null;
}

/** 找到与 start 处 '(' 匹配的 ')' 下标，找不到返回 -1。 */
function regexFindGroupEnd(text, start) {
    var depth = 0;
    var inClass = false;
    for (var i = start; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch === "\\") {
            i++;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            continue;
        }
        if (ch === "(") {
            depth++;
            continue;
        }
        if (ch === ")") {
            depth--;
            if (depth === 0) return i;
        }
    }
    return -1;
}

/** 顶层按 | 切分分支（忽略转义、字符类与嵌套组）。 */
function regexSplitBranches(body) {
    var parts = [];
    var depth = 0;
    var inClass = false;
    var current = "";
    for (var i = 0; i < body.length; i++) {
        var ch = body.charAt(i);
        if (ch === "\\") {
            current += ch + body.charAt(i + 1);
            i++;
            continue;
        }
        if (inClass) {
            current += ch;
            if (ch === "]") inClass = false;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            current += ch;
            continue;
        }
        if (ch === "(") {
            depth++;
            current += ch;
            continue;
        }
        if (ch === ")") {
            depth--;
            current += ch;
            continue;
        }
        if (ch === "|" && depth === 0) {
            parts.push(current);
            current = "";
            continue;
        }
        current += ch;
    }
    parts.push(current);
    return parts;
}

/** 去掉组前缀（?: / ?= / ?! / ?<name>），便于分析组体。 */
function regexStripGroupPrefix(body) {
    if (body.charAt(0) !== "?") return body;
    var ch = body.charAt(1);
    if (ch === ":" || ch === "=" || ch === "!") return body.substring(2);
    if (ch === "<") {
        var close = body.indexOf(">", 2);
        if (close >= 0) return body.substring(close + 1);
    }
    return body;
}

/** 分支之间互为前缀（如 a 与 ab）时，配无界量词会指数级回溯。 */
function regexBranchesConflict(branches) {
    for (var i = 0; i < branches.length; i++) {
        for (var j = i + 1; j < branches.length; j++) {
            var a = branches[i];
            var b = branches[j];
            if (a.length === 0 || b.length === 0) return true;
            if (a === b) return true;
            if (a.length < b.length && b.substring(0, a.length) === a) return true;
            if (b.length < a.length && a.substring(0, b.length) === b) return true;
        }
    }
    return false;
}

/** 在组体内（含嵌套组）查找互为前缀的分支。 */
function regexBodyHasPrefixConflict(body) {
    var content = regexStripGroupPrefix(body);
    if (regexBranchesConflict(regexSplitBranches(content))) return true;
    var i = 0;
    while (i < content.length) {
        var ch = content.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (ch === "[") {
            var classEnd = content.indexOf("]", i);
            if (classEnd < 0) return false;
            i = classEnd + 1;
            continue;
        }
        if (ch === "(") {
            var end = regexFindGroupEnd(content, i);
            if (end < 0) return false;
            if (regexBodyHasPrefixConflict(content.substring(i + 1, end))) return true;
            i = end + 1;
            continue;
        }
        i++;
    }
    return false;
}

/**
 * 检测灾难性回溯风险：(1) 嵌套量词（星高度 >= 2）；
 * (2) 被无界量词修饰的组里含互为前缀的分支（如 (a|ab)+）。
 * 这类模式在失配输入上会指数级回溯，必须在执行前拒绝。
 */
function regexHasNestedQuantifier(pattern) {
    var stack = [{ unbounded: false, start: 0 }];
    var i = 0;
    var inClass = false;
    var n = pattern.length;
    while (i < n) {
        var ch = pattern.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            i++;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            i++;
            continue;
        }
        if (ch === "(") {
            stack.push({ unbounded: false, start: i + 1 });
            i++;
            continue;
        }
        if (ch === ")") {
            var frame = stack.length > 1 ? stack.pop() : stack[0];
            var after = regexReadQuantifier(pattern, i + 1);
            if (after !== null) {
                if (after.unbounded) {
                    if (frame.unbounded) return true;
                    if (regexBodyHasPrefixConflict(pattern.substring(frame.start, i))) return true;
                }
                i = after.end;
            } else {
                i++;
            }
            continue;
        }
        var quant = regexReadQuantifier(pattern, i + 1);
        if (quant !== null) {
            if (quant.unbounded) stack[stack.length - 1].unbounded = true;
            i = quant.end;
            continue;
        }
        i++;
    }
    return false;
}

/** 统计捕获组数量（仅用于给模型提示）。 */
function regexCountGroups(pattern) {
    var count = 0;
    var i = 0;
    var inClass = false;
    while (i < pattern.length) {
        var ch = pattern.charAt(i);
        if (ch === "\\") {
            i += 2;
            continue;
        }
        if (inClass) {
            if (ch === "]") inClass = false;
            i++;
            continue;
        }
        if (ch === "[") {
            inClass = true;
            i++;
            continue;
        }
        if (ch === "(") {
            if (pattern.charAt(i + 1) === "?") {
                var kind = pattern.charAt(i + 2);
                if (kind === "<") {
                    var next = pattern.charAt(i + 3);
                    if (next !== "=" && next !== "!") count++;
                } else if (kind !== ":" && kind !== "=" && kind !== "!") {
                    count++;
                }
            } else {
                count++;
            }
        }
        i++;
    }
    return count;
}

/** 提取命名捕获组名称（(?<name>...)）。 */
function regexNamedGroups(pattern) {
    var names = [];
    var re = /\(\?<([A-Za-z_$][A-Za-z0-9_$]*)>/g;
    var m = re.exec(pattern);
    while (m !== null) {
        names.push(m[1]);
        m = re.exec(pattern);
    }
    return names;
}

/** 编译用户正则：长度、flags、嵌套量词与语法错误统一转成 { error }。 */
function regexCompile(pattern, flags) {
    if (typeof pattern !== "string") return { error: "pattern 必须是字符串" };
    if (pattern.length === 0) return { error: "pattern 不能为空" };
    if (pattern.length > REGEX_MAX_PATTERN) {
        return { error: "pattern 过长（上限 " + REGEX_MAX_PATTERN + " 字符，当前 " + pattern.length + "）" };
    }
    var normalized = regexNormalizeFlags(flags);
    if (normalized.error) return normalized;
    if (regexHasNestedQuantifier(pattern)) {
        return {
            error: "检测到可能触发灾难性回溯的模式（嵌套量词如 (a+)+，或互为前缀的分支如 (a|ab)+）；" +
                "请改写模式：去掉外层重复，或把分支写成互不包含的形式"
        };
    }
    try {
        return { re: new RegExp(pattern, normalized.flags), flags: normalized.flags };
    } catch (e) {
        return { error: "正则编译失败: " + regexMessage(e) };
    }
}

/** 校验被测文本长度。 */
function regexCheckText(text) {
    if (typeof text !== "string") return { error: "text 必须是字符串" };
    if (text.length > REGEX_MAX_TEXT) {
        return { error: "text 过长（上限 " + REGEX_MAX_TEXT + " 字符，当前 " + text.length + "）" };
    }
    return { ok: true };
}

/** 读取 limit 参数（缺失取默认值，超上限收敛到上限）。 */
function regexReadLimit(value, def, max) {
    if (value === undefined || value === null || value === "") return { value: def };
    var num = typeof value === "number" ? value : parseInt(String(value), 10);
    if (isNaN(num) || num < 1) return { error: "limit 必须是 1-" + max + " 之间的整数" };
    if (num > max) return { value: max };
    return { value: Math.floor(num) };
}

/** 保证正则有 g 标志（枚举匹配需要），返回新的 RegExp 或 { error }。 */
function regexGlobalCopy(pattern, flags) {
    var finalFlags = flags.indexOf("g") >= 0 ? flags : flags + "g";
    try {
        return { re: new RegExp(pattern, finalFlags), flags: finalFlags };
    } catch (e) {
        return { error: "正则编译失败: " + regexMessage(e) };
    }
}

/**
 * 依次执行匹配，最多收集 max 条。
 * 零长度匹配由脚本手动推进 lastIndex，避免死循环；迭代次数有硬上限。
 */
function regexCollect(re, text, max) {
    var matches = [];
    var truncated = false;
    re.lastIndex = 0;
    var guard = 0;
    var guardMax = max + 2;
    while (guard < guardMax) {
        guard++;
        var m = re.exec(text);
        if (m === null) break;
        if (matches.length >= max) {
            truncated = true;
            break;
        }
        matches.push(m);
        if (m[0].length === 0) {
            re.lastIndex = re.lastIndex + 1;
            if (re.lastIndex > text.length) break;
        }
    }
    return { matches: matches, truncated: truncated };
}

/** 把原生匹配结果转成可 JSON 序列化的普通对象。 */
function regexMatchInfo(m) {
    var groups = [];
    for (var i = 1; i < m.length; i++) {
        groups.push(m[i] === undefined ? null : m[i]);
    }
    var named = null;
    if (m.groups) {
        named = {};
        var keys = Object.keys(m.groups);
        for (var k = 0; k < keys.length; k++) {
            named[keys[k]] = m.groups[keys[k]] === undefined ? null : m.groups[keys[k]];
        }
    }
    return { index: m.index, text: m[0], groups: groups, namedGroups: named };
}

/** 解析 group 参数：不填返回整段匹配，填数字取序号，填名称取命名组。 */
function regexResolveGroup(group, groupCount, groupNames) {
    if (group === undefined || group === null || group === "") return { mode: "full", echo: null };
    if (typeof group === "number") {
        if (isNaN(group) || group < 1 || group !== Math.floor(group)) {
            return { error: "group 必须是 >=1 的整数，或命名捕获组的名称" };
        }
        if (group > groupCount) {
            return { error: "group 超出范围：该模式只有 " + groupCount + " 个捕获组" };
        }
        return { mode: "index", index: group, echo: group };
    }
    if (typeof group === "string") {
        var text = group.trim();
        if (/^\d+$/.test(text)) {
            var index = parseInt(text, 10);
            if (index < 1 || index > groupCount) {
                return { error: "group 超出范围：该模式只有 " + groupCount + " 个捕获组" };
            }
            return { mode: "index", index: index, echo: index };
        }
        var found = false;
        for (var i = 0; i < groupNames.length; i++) {
            if (groupNames[i] === text) found = true;
        }
        if (!found) {
            return {
                error: "未找到命名捕获组 " + text +
                    (groupNames.length > 0 ? "（可用: " + groupNames.join(", ") + "）" : "（该模式没有命名捕获组）")
            };
        }
        return { mode: "named", name: text, echo: text };
    }
    return { error: "group 必须是从 1 开始的整数或命名组名称" };
}

/** 工具 regex_test：测试是否匹配并返回第一个匹配详情与最多 limit 条匹配。 */
function regexTest(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var limit = regexReadLimit(args.limit, REGEX_LIMIT_DEFAULT, REGEX_LIMIT_MAX);
    if (limit.error) return limit;
    var globalCopy = regexGlobalCopy(args.pattern, compiled.flags);
    if (globalCopy.error) return globalCopy;
    var collected = regexCollect(globalCopy.re, args.text, limit.value);
    var matches = [];
    for (var i = 0; i < collected.matches.length; i++) {
        matches.push(regexMatchInfo(collected.matches[i]));
    }
    return {
        result: {
            matched: matches.length > 0,
            matchCount: matches.length,
            firstMatch: matches.length > 0 ? matches[0] : null,
            matches: matches,
            groupCount: regexCountGroups(args.pattern),
            groupNames: regexNamedGroups(args.pattern)
        },
        limit: limit.value,
        truncated: collected.truncated,
        flags: globalCopy.flags,
        note: "文本上限 " + REGEX_MAX_TEXT + " 字符；matches 最多 " + limit.value +
            " 条，truncated 为 true 表示还有更多匹配未返回。"
    };
}

/** 工具 regex_extract：批量提取匹配（可指定捕获组、可去重）。 */
function regexExtract(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var limit = regexReadLimit(args.limit, 200, REGEX_MAX_MATCHES);
    if (limit.error) return limit;
    var globalCopy = regexGlobalCopy(args.pattern, compiled.flags);
    if (globalCopy.error) return globalCopy;
    var groupCount = regexCountGroups(args.pattern);
    var groupNames = regexNamedGroups(args.pattern);
    var selector = regexResolveGroup(args.group, groupCount, groupNames);
    if (selector.error) return selector;
    var collected = regexCollect(globalCopy.re, args.text, limit.value);
    var values = [];
    var missing = 0;
    for (var i = 0; i < collected.matches.length; i++) {
        var m = collected.matches[i];
        var value;
        if (selector.mode === "full") {
            value = m[0];
        } else if (selector.mode === "index") {
            value = m[selector.index] === undefined ? null : m[selector.index];
        } else {
            value = m.groups === undefined || m.groups === null || m.groups[selector.name] === undefined
                ? null : m.groups[selector.name];
        }
        if (value === null) missing++;
        values.push(value === undefined ? null : value);
    }
    var matchedCount = values.length;
    var unique = regexBool(args.unique, false);
    if (unique) {
        var seen = {};
        var deduped = [];
        for (var j = 0; j < values.length; j++) {
            var key = values[j] === null ? "\u0000null" : "\u0000" + values[j];
            if (Object.prototype.hasOwnProperty.call(seen, key)) continue;
            seen[key] = true;
            deduped.push(values[j]);
        }
        values = deduped;
    }
    return {
        result: values,
        count: values.length,
        matchedCount: matchedCount,
        group: selector.echo,
        groupCount: groupCount,
        groupNames: groupNames,
        unique: unique,
        missingGroups: missing,
        truncated: collected.truncated,
        limit: limit.value,
        flags: globalCopy.flags,
        note: "未匹配到的捕获组记为 null；truncated 为 true 表示达到 limit 上限；" +
            "文本上限 " + REGEX_MAX_TEXT + " 字符。"
    };
}

/** 工具 regex_replace：按模式替换，支持 $1..$9 反向引用与 global 开关。 */
function regexReplace(args) {
    args = regexArgsObject(args);
    var textCheck = regexCheckText(args.text);
    if (textCheck.error) return textCheck;
    var replacement = args.replacement;
    if (typeof replacement === "number" || typeof replacement === "boolean") replacement = String(replacement);
    if (typeof replacement !== "string") return { error: "replacement 必须是字符串" };
    if (replacement.length > REGEX_MAX_PATTERN * 10) {
        return { error: "replacement 过长（上限 " + (REGEX_MAX_PATTERN * 10) + " 字符）" };
    }
    var compiled = regexCompile(args.pattern, args.flags);
    if (compiled.error) return compiled;
    var global = regexBool(args.global, true);
    var flags = compiled.flags;
    if (global) {
        if (flags.indexOf("g") < 0) flags += "g";
    } else {
        flags = flags.replace(/g/g, "");
    }
    var re;
    try {
        re = new RegExp(args.pattern, flags);
    } catch (e) {
        return { error: "正则编译失败: " + regexMessage(e) };
    }
    var count = 0;
    var truncated = false;
    if (global) {
        var collected = regexCollect(re, args.text, REGEX_MAX_MATCHES);
        count = collected.matches.length;
        truncated = collected.truncated;
    } else {
        re.lastIndex = 0;
        count = re.exec(args.text) === null ? 0 : 1;
    }
    re.lastIndex = 0;
    var replaced;
    try {
        replaced = args.text.replace(re, replacement);
    } catch (e) {
        return { error: "替换失败: " + regexMessage(e) };
    }
    return {
        result: replaced,
        replacements: count,
        global: global,
        flags: flags,
        truncated: truncated,
        note: "replacement 支持 $1..$9 反向引用、$& 表示整个匹配、$$ 表示字面美元符；" +
            "全局模式下替换次数最多统计 " + REGEX_MAX_MATCHES + " 次。"
    };
}

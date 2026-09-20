/**
 * Muse 插件：URL 工具箱
 *
 * 运行契约（宿主 WebViewSkillEngine / JsSandbox）：
 *  - 宿主把本文件整体 eval 进 WebView 的 V8，再执行
 *    `JSON.stringify(<functionName>.apply(null, [参数对象]))`；
 *  - 每个工具 = 一个顶层函数，只接收一个参数对象，返回普通对象（不要自己 JSON.stringify）；
 *  - 失败返回 { error: "中文原因" }，任何情况下都不向外抛异常。
 *
 * 实现说明：
 *  - 不依赖 URL / URLSearchParams 构造函数，改为手写解析，行为可预测；
 *  - 只做纯本地字符串计算：不联网、不读写文件。
 */

var URL_MAX_LENGTH = 20000;
/** decodeURI 语义下的保留字符：整体解码时保持其百分号编码不还原。 */
var URL_RESERVED_CHARS = "!#$&'()*+,/:;=?@[]";
/** 路径允许直接出现的字符（RFC 3986 pchar + 斜杠）。 */
var URL_PATH_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:@\/%]/;
/** 查询串文本允许直接出现的字符（保留 & = + ? / 等分隔符）。 */
var URL_QUERY_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:@\/?%]/;
/** userinfo 允许直接出现的字符（@ 会被编码，避免破坏结构）。 */
var URL_USERINFO_KEEP = /[A-Za-z0-9\-._~!$&'()*+,;=:%]/;

/** 参数对象兜底。 */
function urlArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** 异常统一转文本。 */
function urlMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** 宽松布尔读取。 */
function urlBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** 校验 URL 文本输入（非空、长度、空白字符）。 */
function urlCheckInput(value, label) {
    if (typeof value !== "string") return { error: label + " 必须是字符串" };
    var text = value.trim();
    if (text === "") return { error: label + " 不能为空" };
    if (text.length > URL_MAX_LENGTH) {
        return { error: label + " 过长（上限 " + URL_MAX_LENGTH + " 字符，当前 " + text.length + "）" };
    }
    if (/\s/.test(text)) {
        return { error: label + " 不能包含空白字符（空格请写成 %20）" };
    }
    return { value: text };
}

/** 安全的百分号解码：失败返回 null。 */
function urlTryDecode(value) {
    try {
        return decodeURIComponent(value);
    } catch (e) {
        return null;
    }
}

/** 单字符 encodeURIComponent：孤立代理字符不会抛异常。 */
function urlEncodeChar(ch) {
    try {
        return encodeURIComponent(ch);
    } catch (e) {
        return "";
    }
}

/** 安全的整体 encodeURIComponent。 */
function urlEncodeComponent(value) {
    try {
        return encodeURIComponent(String(value));
    } catch (e) {
        return "";
    }
}

/**
 * 逐字符百分号编码：keepRe 命中的字符原样保留，已合法的 %XX 不二次编码，
 * 代理对（emoji）整体编码，非法孤立代理字符丢弃。
 */
function urlEncodeKeep(value, keepRe) {
    var text = String(value);
    var out = "";
    var i = 0;
    while (i < text.length) {
        var ch = text.charAt(i);
        if (ch === "%" && i + 2 <= text.length && /^[0-9A-Fa-f]{2}$/.test(text.substring(i + 1, i + 3))) {
            out += "%" + text.substring(i + 1, i + 3).toUpperCase();
            i += 3;
            continue;
        }
        var code = text.charCodeAt(i);
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < text.length) {
            var next = text.charCodeAt(i + 1);
            if (next >= 0xdc00 && next <= 0xdfff) {
                out += urlEncodeChar(text.substring(i, i + 2));
                i += 2;
                continue;
            }
        }
        out += keepRe.test(ch) ? ch : urlEncodeChar(ch);
        i++;
    }
    return out;
}

/** 解码并记录警告；解码失败时按原文返回。 */
function urlDecodeWithWarning(value, warnings, label) {
    var decoded = urlTryDecode(value);
    if (decoded === null) {
        if (warnings) warnings.push(label + " 的百分号编码非法，已按原文返回");
        return value;
    }
    return decoded;
}

/** 查询串里的单个片段解码：+ 视为空格（表单语义），非法编码按原文返回。 */
function urlDecodeQueryPart(value, warnings, label) {
    var text = String(value);
    var plus = text.replace(/\+/g, " ");
    var decoded = urlTryDecode(plus);
    if (decoded === null) {
        if (warnings) warnings.push(label + "的百分号编码非法，已按原文返回");
        return text;
    }
    return decoded;
}

/**
 * 整体查询串解码（component=false 语义）：还原百分号编码，
 * 但保留 ! # $ & ' ( ) * + , / : ; = ? @ [ ] 这些分隔字符的编码形态（同 decodeURI）。
 */
function urlDecodeWhole(text) {
    var input = String(text).replace(/\u0001/g, "");
    var placeholders = [];
    var masked = input.replace(/%[0-9A-Fa-f]{2}/g, function (seq) {
        var ch = String.fromCharCode(parseInt(seq.substring(1), 16));
        if (URL_RESERVED_CHARS.indexOf(ch) >= 0) {
            placeholders.push(ch);
            return "\u0001" + (placeholders.length - 1) + "\u0001";
        }
        return seq;
    });
    try {
        var decoded = decodeURIComponent(masked);
        return decoded.replace(/\u0001(\d+)\u0001/g, function (all, index) {
            return placeholders[parseInt(index, 10)];
        });
    } catch (e) {
        return null;
    }
}

/** URL 结构切分：protocol / authority / path / query / fragment。 */
function urlSplitParts(url) {
    var m = /^(?:([A-Za-z][A-Za-z0-9+.\-]*):)?(?:\/\/([^\/?#]*))?([^?#]*)(?:\?([^#]*))?(?:#([\s\S]*))?$/.exec(url);
    if (m === null) return { error: "URL 结构无法解析" };
    return {
        protocol: m[1] === undefined ? null : m[1].toLowerCase(),
        hasAuthority: m[2] !== undefined,
        authority: m[2] === undefined ? "" : m[2],
        path: m[3] === undefined ? "" : m[3],
        query: m[4] === undefined ? null : m[4],
        fragment: m[5] === undefined ? null : m[5]
    };
}

/** 主机名字符合法性（域名、IPv4、方括号 IPv6、IDN 均放行）。 */
function urlCheckHostname(hostname) {
    if (hostname.length === 0) return "URL 缺少主机名";
    if (hostname.length > 253) return "主机名过长";
    if (/[\s\/?#@\\<>"{}|^`]/.test(hostname)) return "主机名含非法字符: " + hostname;
    return null;
}

/** 端口合法性校验。 */
function urlCheckPort(port) {
    if (port === null || port === undefined || port === "") return null;
    if (!/^\d{1,5}$/.test(port)) return "端口必须是数字: " + port;
    var num = parseInt(port, 10);
    if (num < 1 || num > 65535) return "端口必须在 1-65535 之间: " + port;
    return null;
}

/** 拆分 authority 为 userinfo / hostname / port。 */
function urlSplitAuthority(authority) {
    var userinfo = null;
    var hostport = authority;
    var at = authority.lastIndexOf("@");
    if (at >= 0) {
        userinfo = authority.substring(0, at);
        hostport = authority.substring(at + 1);
        if (/[\s\/?#]/.test(userinfo)) return { error: "URL 的 userinfo 含非法字符" };
    }
    var hostname = hostport;
    var port = null;
    if (hostport.charAt(0) === "[") {
        var close = hostport.indexOf("]");
        if (close < 0) return { error: "IPv6 主机缺少 ]" };
        hostname = hostport.substring(0, close + 1);
        var rest = hostport.substring(close + 1);
        if (rest !== "") {
            if (rest.charAt(0) !== ":") return { error: "IPv6 主机后含非法字符: " + rest };
            port = rest.substring(1);
        }
    } else {
        var colon = hostport.indexOf(":");
        if (colon >= 0) {
            hostname = hostport.substring(0, colon);
            port = hostport.substring(colon + 1);
        }
    }
    var hostError = urlCheckHostname(hostname);
    if (hostError) return { error: hostError };
    var portError = urlCheckPort(port);
    if (portError) return { error: portError };
    return {
        userinfo: userinfo,
        hostname: hostname.toLowerCase(),
        port: port === null || port === "" ? null : parseInt(port, 10)
    };
}

/** 解析查询串：返回 params（同名参数为数组）、keys（首次出现顺序）与顺序保留的 pairs。 */
function urlParseQueryPairs(query) {
    var params = {};
    var keys = [];
    var pairs = [];
    var warnings = [];
    if (query === null || query === undefined || query === "") {
        return { params: params, keys: keys, pairs: pairs, warnings: warnings };
    }
    var segments = String(query).split("&");
    for (var i = 0; i < segments.length; i++) {
        var segment = segments[i];
        if (segment === "") continue;
        var eq = segment.indexOf("=");
        var rawKey = eq >= 0 ? segment.substring(0, eq) : segment;
        var rawValue = eq >= 0 ? segment.substring(eq + 1) : "";
        var key = urlDecodeQueryPart(rawKey, warnings, "参数名");
        var value = urlDecodeQueryPart(rawValue, warnings, "参数值");
        if (key === "") continue;
        if (key === "__proto__") {
            warnings.push("已跳过非法参数名 __proto__");
            continue;
        }
        pairs.push({ key: key, value: value });
        if (!Object.prototype.hasOwnProperty.call(params, key)) {
            keys.push(key);
            params[key] = value;
        } else if (Array.isArray(params[key])) {
            params[key].push(value);
        } else {
            params[key] = [params[key], value];
        }
    }
    return { params: params, keys: keys, pairs: pairs, warnings: warnings };
}

/** 参数值转文本（对象/数组按 JSON 文本，null/undefined 记空串）。 */
function urlParamValue(value, warnings) {
    if (value === undefined || value === null) return "";
    if (typeof value === "object") {
        if (warnings) warnings.push("参数值含对象或数组，已按 JSON 文本编码");
        try {
            return JSON.stringify(value);
        } catch (e) {
            return String(value);
        }
    }
    return String(value);
}

/** 参数对象序列化为查询串（键值均做百分号编码，同名多值用数组）。 */
function urlSerializeParams(params, warnings) {
    var keys = Object.keys(params);
    var parts = [];
    for (var i = 0; i < keys.length; i++) {
        var key = keys[i];
        if (key === "__proto__") {
            if (warnings) warnings.push("已跳过非法参数名 __proto__");
            continue;
        }
        var value = params[key];
        if (Array.isArray(value)) {
            if (value.length === 0) {
                parts.push(urlEncodeComponent(key) + "=");
                continue;
            }
            for (var j = 0; j < value.length; j++) {
                parts.push(urlEncodeComponent(key) + "=" + urlEncodeComponent(urlParamValue(value[j], warnings)));
            }
            continue;
        }
        parts.push(urlEncodeComponent(key) + "=" + urlEncodeComponent(urlParamValue(value, warnings)));
    }
    return parts.join("&");
}

/** pairs 序列化为查询串。 */
function urlSerializePairs(pairs) {
    var parts = [];
    for (var i = 0; i < pairs.length; i++) {
        parts.push(urlEncodeComponent(pairs[i].key) + "=" + urlEncodeComponent(pairs[i].value));
    }
    return parts.join("&");
}

/** pairs 还原成 params 形态（同名参数为数组）。 */
function urlPairsToParams(pairs) {
    var params = {};
    var keys = [];
    for (var i = 0; i < pairs.length; i++) {
        var key = pairs[i].key;
        var value = pairs[i].value;
        if (!Object.prototype.hasOwnProperty.call(params, key)) {
            keys.push(key);
            params[key] = value;
        } else if (Array.isArray(params[key])) {
            params[key].push(value);
        } else {
            params[key] = [params[key], value];
        }
    }
    return { params: params, keys: keys };
}

/** 用新的查询串重建完整 URL（保留原片段，去掉旧查询串）。 */
function urlRebuildQuery(base, query) {
    var hashIndex = base.indexOf("#");
    var fragment = hashIndex >= 0 ? base.substring(hashIndex) : "";
    var head = hashIndex >= 0 ? base.substring(0, hashIndex) : base;
    var qIndex = head.indexOf("?");
    if (qIndex >= 0) head = head.substring(0, qIndex);
    return head + (query !== "" ? "?" + query : "") + fragment;
}

/** 工具 url_parse：把 URL 拆成部件并展开查询参数。 */
function urlParse(args) {
    args = urlArgsObject(args);
    var inputCheck = urlCheckInput(args.url, "url");
    if (inputCheck.error) return inputCheck;
    var url = inputCheck.value;
    var parts = urlSplitParts(url);
    if (parts.error) return parts;
    if (parts.protocol === null && !parts.hasAuthority && url.indexOf("://") >= 0) {
        return { error: "URL 协议格式非法（协议需以字母开头，只能含字母、数字、+、-、.）" };
    }
    var warnings = [];
    var userinfo = null;
    var username = null;
    var password = null;
    var hostname = null;
    var host = null;
    var port = null;
    if (parts.hasAuthority) {
        var authority = urlSplitAuthority(parts.authority);
        if (authority.error) return { error: authority.error };
        hostname = authority.hostname;
        port = authority.port;
        host = port === null ? hostname : hostname + ":" + port;
        if (authority.userinfo !== null) {
            userinfo = authority.userinfo;
            var colon = userinfo.indexOf(":");
            var rawUser = colon >= 0 ? userinfo.substring(0, colon) : userinfo;
            var rawPass = colon >= 0 ? userinfo.substring(colon + 1) : null;
            username = urlDecodeWithWarning(rawUser, warnings, "userinfo");
            password = rawPass === null ? null : urlDecodeWithWarning(rawPass, warnings, "userinfo");
        }
    }
    var pathDecoded = urlDecodeWithWarning(parts.path, warnings, "path");
    var fragmentDecoded = parts.fragment === null ? null : urlDecodeWithWarning(parts.fragment, warnings, "fragment");
    var queryInfo = urlParseQueryPairs(parts.query);
    for (var i = 0; i < queryInfo.warnings.length; i++) warnings.push(queryInfo.warnings[i]);
    return {
        result: {
            protocol: parts.protocol,
            host: host,
            hostname: hostname,
            port: port,
            path: parts.path,
            pathDecoded: pathDecoded,
            query: parts.query,
            params: queryInfo.params,
            keys: queryInfo.keys,
            fragment: parts.fragment,
            fragmentDecoded: fragmentDecoded,
            userinfo: userinfo,
            username: username,
            password: password,
            hasAuthority: parts.hasAuthority,
            relative: parts.protocol === null
        },
        warnings: warnings,
        note: "protocol 不含冒号；host 为 hostname[:port]；pathDecoded 为解码后的路径；" +
            "query 不含 ?；fragment 不含 #；params 中同名参数为数组；" +
            "relative 为 true 表示输入没写协议（按相对引用解析）。"
    };
}

/** 工具 url_build：从部件对象拼装 URL，与 url_parse 输出兼容。 */
function urlBuild(args) {
    args = urlArgsObject(args);
    var warnings = [];
    var protocol = null;
    if (args.protocol !== undefined && args.protocol !== null && String(args.protocol).trim() !== "") {
        protocol = String(args.protocol).trim().replace(/:\/\/$/, "").replace(/:$/, "");
        if (!/^[A-Za-z][A-Za-z0-9+.\-]*$/.test(protocol)) {
            return { error: "protocol 非法: " + args.protocol };
        }
        protocol = protocol.toLowerCase();
    }
    var hostname = null;
    var port = null;
    if (args.hostname !== undefined && args.hostname !== null && String(args.hostname).trim() !== "") {
        hostname = String(args.hostname).trim();
        var hostnameError = urlCheckHostname(hostname);
        if (hostnameError) return { error: hostnameError };
    }
    if (args.host !== undefined && args.host !== null && String(args.host).trim() !== "") {
        var parsedHost = urlSplitAuthority(String(args.host).trim());
        if (parsedHost.error) return { error: "host 非法: " + parsedHost.error };
        if (hostname === null) hostname = parsedHost.hostname;
        if (parsedHost.port !== null) port = parsedHost.port;
    }
    if (args.port !== undefined && args.port !== null && String(args.port).trim() !== "") {
        var portText = String(args.port).trim().replace(/^:/, "");
        var portError = urlCheckPort(portText);
        if (portError) return { error: portError };
        port = parseInt(portText, 10);
    }
    var userinfo = null;
    if (args.userinfo !== undefined && args.userinfo !== null && String(args.userinfo) !== "") {
        if (hostname === null) return { error: "提供 userinfo 时必须同时提供 host 或 hostname" };
        userinfo = urlEncodeKeep(String(args.userinfo), URL_USERINFO_KEEP);
    } else if (args.username !== undefined && args.username !== null && String(args.username) !== "") {
        if (hostname === null) return { error: "提供 username 时必须同时提供 host 或 hostname" };
        var user = urlEncodeComponent(String(args.username));
        var pass = args.password === undefined || args.password === null ? null : String(args.password);
        userinfo = pass === null ? user : user + ":" + urlEncodeComponent(pass);
    } else if (args.password !== undefined && args.password !== null && String(args.password) !== "") {
        return { error: "只提供 password 无效，请同时提供 username" };
    }
    var rawPath = "";
    if (args.path !== undefined && args.path !== null) rawPath = String(args.path);
    else if (args.pathname !== undefined && args.pathname !== null) rawPath = String(args.pathname);
    var path = urlEncodeKeep(rawPath, URL_PATH_KEEP);
    if (hostname !== null && path !== "" && path.charAt(0) !== "/") path = "/" + path;
    var query = null;
    if (args.params !== undefined && args.params !== null) {
        if (typeof args.params !== "object" || Array.isArray(args.params)) {
            return { error: "params 必须是对象（值可以是字符串、数字、数组、true/false 或 null）" };
        }
        query = urlSerializeParams(args.params, warnings);
    } else if (args.query !== undefined && args.query !== null) {
        if (typeof args.query === "object") {
            if (Array.isArray(args.query)) return { error: "query 不能是数组；对象形式的参数请用 params" };
            query = urlSerializeParams(args.query, warnings);
        } else {
            var rawQuery = String(args.query).replace(/^\?/, "");
            query = urlEncodeKeep(rawQuery, URL_QUERY_KEEP);
        }
    }
    var fragment = null;
    if (args.fragment !== undefined && args.fragment !== null && String(args.fragment) !== "") {
        fragment = urlEncodeKeep(String(args.fragment).replace(/^#/, ""), URL_QUERY_KEEP);
    }
    if (protocol === null && hostname === null && path === "" && query === null && fragment === null) {
        return { error: "没有任何可拼装的部件（至少提供 protocol、host/hostname 或 path）" };
    }
    if (protocol !== null && hostname === null && path === "") {
        return { error: "只有 protocol 无法构成 URL，请同时提供 host/hostname 或 path" };
    }
    var prefix = "";
    if (hostname !== null) {
        prefix = (protocol === null ? "" : protocol + ":") + "//" +
            (userinfo === null ? "" : userinfo + "@") + hostname + (port === null ? "" : ":" + port);
    } else if (protocol !== null) {
        prefix = protocol + ":";
    }
    var url = prefix + path;
    if (query !== null && query !== "") url += "?" + query;
    if (fragment !== null) url += "#" + fragment;
    if (url.length > URL_MAX_LENGTH) {
        return { error: "拼装结果过长（上限 " + URL_MAX_LENGTH + " 字符）" };
    }
    return {
        result: url,
        query: query === null ? "" : query,
        warnings: warnings,
        note: "hostname 与 host 同时给出时以 hostname 为准；params 优先于 query；" +
            "path 中的空格等非法字符会自动百分号编码，已合法的 %XX 不会被二次编码。"
    };
}

/** encode / decode 模式实现：component=true 按单个值处理，false 按整体查询串处理。 */
function urlQueryCodec(args, mode, base, baseInfo, explicitQuery) {
    var component = urlBool(args.component, true);
    var input = null;
    var fromBase = false;
    if (typeof args.text === "string") {
        input = args.text;
    } else if (typeof args.value === "string") {
        input = args.value;
    } else if (typeof args.value === "number") {
        input = String(args.value);
    } else if (explicitQuery !== null) {
        input = explicitQuery;
    } else if (baseInfo !== null) {
        input = baseInfo.query === null ? "" : baseInfo.query;
        fromBase = true;
    }
    if (input === null) return { error: mode + " 需要提供 text（也接受 value、query 或 base 中的查询串）" };
    if (input.length > URL_MAX_LENGTH) {
        return { error: "text 过长（上限 " + URL_MAX_LENGTH + " 字符，当前 " + input.length + "）" };
    }
    var output;
    if (mode === "encode") {
        output = component ? urlEncodeComponent(input) : urlEncodeKeep(input, URL_QUERY_KEEP);
    } else if (component) {
        var decodedComponent = urlTryDecode(String(input).replace(/\+/g, " "));
        if (decodedComponent === null) return { error: "解码失败：文本包含非法的百分号编码" };
        output = decodedComponent;
    } else {
        var decodedWhole = urlDecodeWhole(input);
        if (decodedWhole === null) return { error: "解码失败：文本包含非法的百分号编码" };
        output = decodedWhole;
    }
    var response = {
        result: output,
        mode: mode,
        component: component,
        query: null,
        url: null,
        note: component
            ? "component=true 按单个值处理：编码用 encodeURIComponent，解码把 + 视为空格。"
            : "component=false 按整体查询串处理：保留 = & + ? 等分隔符，不把 + 当空格。"
    };
    if (fromBase || (explicitQuery !== null && base !== null)) {
        response.query = output;
        if (/\s/.test(output)) {
            response.warnings = ["结果含空白字符，未回写进 URL（请先 encode）"];
        } else {
            response.url = urlRebuildQuery(base, output);
        }
    }
    return response;
}

/** 工具 query_tool：查询参数解析、增改、删除与 URL 编解码。 */
function queryTool(args) {
    args = urlArgsObject(args);
    var mode = typeof args.mode === "string" ? args.mode.replace(/\s+/g, "").toLowerCase() : "";
    if (mode === "") return { error: "mode 不能为空（parse/set/remove/encode/decode）" };
    if (mode !== "parse" && mode !== "set" && mode !== "remove" && mode !== "encode" && mode !== "decode") {
        return { error: "不支持的 mode: " + args.mode + "（可选 parse/set/remove/encode/decode）" };
    }
    var base = null;
    var baseInfo = null;
    if (args.base !== undefined && args.base !== null) {
        if (typeof args.base !== "string") return { error: "base 必须是字符串（完整 URL）" };
        if (args.base.trim() !== "") {
            var baseCheck = urlCheckInput(args.base, "base");
            if (baseCheck.error) return baseCheck;
            base = baseCheck.value;
            var parsedBase = urlParse({ url: base });
            if (parsedBase.error) return { error: "base 解析失败: " + parsedBase.error };
            baseInfo = parsedBase.result;
        }
    }
    var explicitQuery = null;
    if (args.query !== undefined && args.query !== null) {
        if (typeof args.query === "string") explicitQuery = args.query.replace(/^\?/, "");
        else if (!(typeof args.query === "object" && !Array.isArray(args.query) && mode === "set")) {
            return { error: "query 必须是字符串（查询串），或 set 模式下的参数对象" };
        }
    }
    if (mode === "encode" || mode === "decode") {
        return urlQueryCodec(args, mode, base, baseInfo, explicitQuery);
    }
    var query;
    if (explicitQuery !== null) {
        query = explicitQuery;
    } else if (baseInfo !== null) {
        query = baseInfo.query === null ? "" : baseInfo.query;
    } else {
        return { error: mode + " 需要提供 query（查询字符串）或 base（完整 URL）" };
    }
    if (query.length > URL_MAX_LENGTH) {
        return { error: "query 过长（上限 " + URL_MAX_LENGTH + " 字符）" };
    }
    var parsedQuery = urlParseQueryPairs(query);
    if (mode === "parse") {
        var pairs = [];
        for (var i = 0; i < parsedQuery.pairs.length; i++) {
            pairs.push({ name: parsedQuery.pairs[i].key, value: parsedQuery.pairs[i].value });
        }
        return {
            result: parsedQuery.params,
            query: query,
            url: baseInfo === null ? null : base,
            pairs: pairs,
            keys: parsedQuery.keys,
            count: parsedQuery.keys.length,
            pairCount: pairs.length,
            warnings: parsedQuery.warnings,
            note: "result 为键值对象（同名参数为数组）；pairs 保留原始顺序与重复项。"
        };
    }
    var updates = args.params;
    if ((updates === undefined || updates === null) && args.query !== null && typeof args.query === "object" && !Array.isArray(args.query)) {
        updates = args.query;
    }
    var resultPairs = [];
    if (mode === "set") {
        if (updates === undefined || updates === null) {
            return { error: "set 需要提供 params 对象（键为参数名，值为新值；null 表示删除，数组表示多值）" };
        }
        if (typeof updates !== "object" || Array.isArray(updates)) return { error: "params 必须是对象" };
        var updateKeys = Object.keys(updates);
        if (updateKeys.length === 0) return { error: "params 不能为空对象" };
        var pending = {};
        var pendingOrder = [];
        for (var u = 0; u < updateKeys.length; u++) {
            var key = updateKeys[u];
            if (key === "__proto__") return { error: "参数名 __proto__ 不合法" };
            var value = updates[key];
            var list = [];
            if (value === null || value === undefined) {
                list = [];
            } else if (Array.isArray(value)) {
                for (var v = 0; v < value.length; v++) {
                    var item = value[v];
                    if (item !== null && typeof item === "object") {
                        return { error: "params." + key + " 的数组元素不能是对象" };
                    }
                    list.push(item === null || item === undefined ? "" : String(item));
                }
            } else if (typeof value === "object") {
                return { error: "params." + key + " 的值不能是对象，请先转成字符串" };
            } else {
                list.push(String(value));
            }
            pending[key] = list;
            pendingOrder.push(key);
        }
        var applied = {};
        for (var p = 0; p < parsedQuery.pairs.length; p++) {
            var pair = parsedQuery.pairs[p];
            if (!Object.prototype.hasOwnProperty.call(pending, pair.key)) {
                resultPairs.push({ key: pair.key, value: pair.value });
                continue;
            }
            if (Object.prototype.hasOwnProperty.call(applied, pair.key)) continue;
            applied[pair.key] = true;
            var newValues = pending[pair.key];
            for (var q = 0; q < newValues.length; q++) {
                resultPairs.push({ key: pair.key, value: newValues[q] });
            }
        }
        for (var w = 0; w < pendingOrder.length; w++) {
            var appendKey = pendingOrder[w];
            if (Object.prototype.hasOwnProperty.call(applied, appendKey)) continue;
            applied[appendKey] = true;
            var appendValues = pending[appendKey];
            for (var x = 0; x < appendValues.length; x++) {
                resultPairs.push({ key: appendKey, value: appendValues[x] });
            }
        }
    } else {
        var removeKeys = [];
        if (args.keys !== undefined && args.keys !== null) {
            if (typeof args.keys === "string") {
                if (args.keys !== "") removeKeys.push(args.keys);
            } else if (Array.isArray(args.keys)) {
                for (var k = 0; k < args.keys.length; k++) {
                    if (typeof args.keys[k] !== "string") return { error: "keys 必须是字符串数组" };
                    if (args.keys[k] !== "") removeKeys.push(args.keys[k]);
                }
            } else {
                return { error: "keys 必须是字符串或字符串数组" };
            }
        } else if (updates !== undefined && updates !== null && typeof updates === "object" && !Array.isArray(updates)) {
            var objectKeys = Object.keys(updates);
            for (var o = 0; o < objectKeys.length; o++) removeKeys.push(objectKeys[o]);
        } else {
            var single = args.key !== undefined ? args.key : args.name;
            if (typeof single === "string" && single !== "") {
                removeKeys.push(single);
            } else {
                return { error: "remove 需要提供 keys（要删除的参数名数组）或 params（取其键）或 key" };
            }
        }
        if (removeKeys.length === 0) return { error: "remove 没有给出任何要删除的参数名" };
        var removeMap = {};
        for (var r = 0; r < removeKeys.length; r++) removeMap[removeKeys[r]] = true;
        for (var s = 0; s < parsedQuery.pairs.length; s++) {
            if (Object.prototype.hasOwnProperty.call(removeMap, parsedQuery.pairs[s].key)) continue;
            resultPairs.push({ key: parsedQuery.pairs[s].key, value: parsedQuery.pairs[s].value });
        }
    }
    var serialized = urlSerializePairs(resultPairs);
    var before = urlSerializePairs(parsedQuery.pairs);
    var summary = urlPairsToParams(resultPairs);
    return {
        result: serialized,
        query: serialized,
        url: baseInfo === null ? null : urlRebuildQuery(base, serialized),
        params: summary.params,
        keys: summary.keys,
        pairCount: resultPairs.length,
        changed: serialized !== before,
        warnings: parsedQuery.warnings,
        note: "query 不含 ?；同名参数按原顺序保留；set 时 null 值表示删除该参数。"
    };
}

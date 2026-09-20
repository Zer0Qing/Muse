/**
 * Muse 插件：数据工具箱（CSV / JSON）
 *
 * 运行契约（宿主 WebViewSkillEngine / JsSandbox）：
 *  - 宿主把本文件整体 eval 进 WebView 的 V8，再执行
 *    `JSON.stringify(<functionName>.apply(null, [参数对象]))`；
 *  - 每个工具 = 一个顶层函数，只接收一个参数对象，返回普通对象（不要自己 JSON.stringify）；
 *  - 失败返回 { error: "中文原因" }，任何情况下都不向外抛异常。
 *
 * CSV 规则（RFC 4180 兼容，宽松解析）：
 *  - 双引号包裹的字段内可含分隔符、换行与转义的双引号（""）；
 *  - 有表头时首行作键名，无表头时键名为 col1..colN；
 *  - 完全空白的行会被忽略。
 *  - 上限：文本 500000 字符、列 200 列、单次最多输出 10000 行。
 * 纯本地计算：不联网、不读写文件。
 */

var CSV_MAX_TEXT = 500000;
var CSV_MAX_ROWS = 10000;
var CSV_ROWS_DEFAULT = 1000;
var CSV_MAX_COLUMNS = 200;
var CSV_NUMBER_RE = /^[+-]?(\d+(\.\d+)?|\.\d+)([eE][+-]?\d+)?$/;

/** 参数对象兜底。 */
function csvArgsObject(args) {
    return args !== null && typeof args === "object" && !Array.isArray(args) ? args : {};
}

/** 异常统一转文本。 */
function csvMessage(e) {
    if (e && e.message) return String(e.message);
    return String(e);
}

/** 宽松布尔读取。 */
function csvBool(value, def) {
    if (value === undefined || value === null) return def;
    if (typeof value === "string") {
        var text = value.replace(/\s+/g, "").toLowerCase();
        if (text === "true" || text === "1" || text === "yes") return true;
        if (text === "false" || text === "0" || text === "no" || text === "") return false;
    }
    return !!value;
}

/** 分隔符规范化：默认逗号，支持 ; | 制表符等单字符。 */
function csvNormalizeDelimiter(value) {
    if (value === undefined || value === null || value === "") return { delimiter: "," };
    if (typeof value !== "string") return { error: "delimiter 必须是字符串" };
    var text = value;
    if (text === "\\t" || text.toLowerCase() === "tab") text = "\t";
    if (text.length !== 1) return { error: "delimiter 必须是单个字符（如 , ; | 或制表符）" };
    if (text === "\"" || text === "\r" || text === "\n") return { error: "delimiter 不能是双引号或换行符" };
    return { delimiter: text };
}

/** 读取 CSV 文本参数（主参数 csv，兼容 text / data / input 别名）。 */
function csvReadText(args) {
    var candidates = [args.csv, args.text, args.data, args.input];
    var value = null;
    for (var i = 0; i < candidates.length; i++) {
        if (typeof candidates[i] === "string") {
            value = candidates[i];
            break;
        }
    }
    if (value === null) return { error: "缺少 csv 参数（需要 CSV 文本字符串）" };
    if (value.length > CSV_MAX_TEXT) {
        return { error: "CSV 文本过长（上限 " + CSV_MAX_TEXT + " 字符，当前 " + value.length + "）" };
    }
    var text = value.replace(/^\uFEFF/, "");
    if (text.replace(/\s+/g, "") === "") return { error: "CSV 文本为空" };
    return { text: text };
}

/**
 * 解析 CSV 为二维数组（宽松 RFC 4180）。
 * 返回 { rows, error }；未闭合的双引号视为格式错误。
 */
function csvParseRows(text, delimiter) {
    var rows = [];
    var quotedFlags = [];
    var row = [];
    var field = "";
    var rowQuoted = false;
    var inQuotes = false;
    var i = 0;
    var n = text.length;
    while (i < n) {
        var ch = text.charAt(i);
        if (inQuotes) {
            if (ch === "\"") {
                if (text.charAt(i + 1) === "\"") {
                    field += "\"";
                    i += 2;
                    continue;
                }
                inQuotes = false;
                i++;
                continue;
            }
            field += ch;
            i++;
            continue;
        }
        if (ch === "\"") {
            if (field.length === 0) {
                inQuotes = true;
                rowQuoted = true;
                i++;
                continue;
            }
            field += ch;
            i++;
            continue;
        }
        if (ch === delimiter) {
            row.push(field);
            field = "";
            i++;
            continue;
        }
        if (ch === "\r" || ch === "\n") {
            if (ch === "\r" && text.charAt(i + 1) === "\n") i++;
            row.push(field);
            rows.push(row);
            quotedFlags.push(rowQuoted);
            row = [];
            field = "";
            rowQuoted = false;
            i++;
            continue;
        }
        field += ch;
        i++;
    }
    if (inQuotes) return { error: "CSV 存在未闭合的双引号" };
    // rowQuoted 也要算：末尾是 "" 这种引号包裹的空字段时不能丢行
    if (field.length > 0 || row.length > 0 || rowQuoted) {
        row.push(field);
        rows.push(row);
        quotedFlags.push(rowQuoted);
    }
    var filtered = [];
    for (var r = 0; r < rows.length; r++) {
        if (!quotedFlags[r] && rows[r].length === 1 && rows[r][0] === "") continue;
        filtered.push(rows[r]);
    }
    return { rows: filtered };
}

/** 生成列名：空列名补 colN，重名加 _2/_3 后缀，__proto__ 换成 colN。 */
function csvUniqueNames(headerRow, width) {
    var names = [];
    var seen = {};
    for (var i = 0; i < width; i++) {
        var raw = headerRow !== null && i < headerRow.length && headerRow[i] !== undefined
            ? String(headerRow[i]) : "";
        var base = raw.replace(/^\uFEFF/, "").trim();
        if (base === "" || base === "__proto__") base = "col" + (i + 1);
        var name = base;
        var suffix = 1;
        while (Object.prototype.hasOwnProperty.call(seen, name)) {
            suffix++;
            name = base + "_" + suffix;
        }
        seen[name] = true;
        names.push(name);
    }
    return names;
}

/** 单元格取值转文本：null/undefined 为空串，对象/数组用 JSON 文本。 */
function csvFormatCell(value) {
    if (value === undefined || value === null) return "";
    if (typeof value === "string") return value;
    if (typeof value === "number" || typeof value === "boolean") return String(value);
    var text;
    try {
        text = JSON.stringify(value);
    } catch (e) {
        text = String(value);
    }
    return text === undefined ? "" : text;
}

/** RFC 4180 转义：含分隔符、引号或换行的值加引号并把 " 写成 ""。 */
function csvQuoteCell(text, delimiter) {
    if (text.indexOf("\"") >= 0 || text.indexOf(delimiter) >= 0 ||
        text.indexOf("\n") >= 0 || text.indexOf("\r") >= 0) {
        return "\"" + text.replace(/"/g, "\"\"") + "\"";
    }
    return text;
}

/** 判断字符串是否为纯数字（允许正负号、小数与科学计数法）。 */
function csvIsNumber(value) {
    var text = String(value).trim();
    if (text === "") return false;
    if (!CSV_NUMBER_RE.test(text)) return false;
    var num = parseFloat(text);
    if (isNaN(num) || num === Infinity || num === -Infinity) return false;
    return true;
}

/** 保留 6 位小数的数值整理（避免浮点误差写进结果）。 */
function csvRound(value) {
    if (value === null || value === undefined) return null;
    if (typeof value !== "number" || isNaN(value) || value === Infinity || value === -Infinity) return null;
    return Math.round(value * 1000000) / 1000000;
}

/** 取出现次数最多的前 limit 个取值（次数相同按首次出现顺序）。 */
function csvTopValues(freq, limit) {
    var keys = Object.keys(freq);
    var items = [];
    for (var i = 0; i < keys.length; i++) {
        items.push({ value: keys[i].substring(1), count: freq[keys[i]], order: i });
    }
    items.sort(function (a, b) {
        if (b.count !== a.count) return b.count - a.count;
        return a.order - b.order;
    });
    var out = [];
    for (var j = 0; j < items.length && j < limit; j++) {
        out.push({ value: items[j].value, count: items[j].count });
    }
    return out;
}

/** 读取 maxRows / top 之类的正整数参数。 */
function csvReadCount(value, def, max, label) {
    if (value === undefined || value === null || value === "") return { value: def };
    var num = typeof value === "number" ? value : parseInt(String(value), 10);
    if (isNaN(num) || num < 1) return { error: label + " 必须是 >=1 的整数" };
    return { value: num > max ? max : Math.floor(num) };
}

/** 工具 csv_to_json：CSV 文本转对象数组。 */
function csvToJson(args) {
    args = csvArgsObject(args);
    var input = csvReadText(args);
    if (input.error) return input;
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var trim = csvBool(args.trim, false);
    var maxRows = csvReadCount(args.maxRows, CSV_ROWS_DEFAULT, CSV_MAX_ROWS, "maxRows");
    if (maxRows.error) return maxRows;
    var parsed = csvParseRows(input.text, delim.delimiter);
    if (parsed.error) return parsed;
    var rows = parsed.rows;
    if (rows.length === 0) return { error: "CSV 没有可解析的内容" };
    var width = 0;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].length > width) width = rows[i].length;
    }
    if (width > CSV_MAX_COLUMNS) {
        return { error: "列数超过上限（最多 " + CSV_MAX_COLUMNS + " 列，当前 " + width + " 列）" };
    }
    var columns = csvUniqueNames(header ? rows[0] : null, width);
    var dataRows = header ? rows.slice(1) : rows.slice(0);
    var truncated = false;
    if (dataRows.length > maxRows.value) {
        dataRows = dataRows.slice(0, maxRows.value);
        truncated = true;
    }
    var records = [];
    for (var r = 0; r < dataRows.length; r++) {
        var cells = dataRows[r];
        var record = {};
        for (var c = 0; c < columns.length; c++) {
            var value = c < cells.length && cells[c] !== undefined ? cells[c] : "";
            record[columns[c]] = trim ? value.replace(/^\s+|\s+$/g, "") : value;
        }
        records.push(record);
    }
    return {
        result: records,
        columns: columns,
        rowCount: records.length,
        totalRows: header ? rows.length - 1 : rows.length,
        truncated: truncated,
        delimiter: delim.delimiter,
        header: header,
        note: "字段内的逗号与换行由双引号包裹处理，\"\" 还原为字面双引号；" +
            "truncated 为 true 表示只返回了前 " + maxRows.value + " 行。"
    };
}

/** 工具 json_to_csv：对象数组转 CSV（列顺序取并集的首次出现顺序）。 */
function jsonToCsv(args) {
    args = csvArgsObject(args);
    var raw = args.data;
    if (raw === undefined || raw === null) raw = args.json;
    if (raw === undefined || raw === null) raw = args.rows;
    if (raw === undefined || raw === null) return { error: "缺少 data 参数（JSON 数组，或它的字符串形式）" };
    var data = raw;
    if (typeof data === "string") {
        var trimmed = data.replace(/^\s+|\s+$/g, "");
        if (trimmed === "") return { error: "data 是空字符串，无法解析出 JSON 数组" };
        try {
            data = JSON.parse(trimmed);
        } catch (e) {
            return { error: "JSON 解析失败: " + csvMessage(e) };
        }
    }
    if (!Array.isArray(data)) return { error: "data 必须是 JSON 数组" };
    if (data.length > CSV_MAX_ROWS) {
        return { error: "数组元素过多（上限 " + CSV_MAX_ROWS + " 条，当前 " + data.length + " 条）" };
    }
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var crlf = csvBool(args.crlf, false);
    var eol = crlf ? "\r\n" : "\n";
    if (data.length === 0) {
        return {
            result: "",
            rows: 0,
            columns: [],
            delimiter: delim.delimiter,
            header: header,
            note: "数组为空，输出空文本。"
        };
    }
    var allObjects = true;
    var allArrays = true;
    for (var i = 0; i < data.length; i++) {
        var item = data[i];
        if (item === null || typeof item !== "object") {
            allObjects = false;
            allArrays = false;
        } else if (Array.isArray(item)) {
            allObjects = false;
        } else {
            allArrays = false;
        }
    }
    var columns = [];
    var table = [];
    var warnings = [];
    if (allObjects) {
        var seen = {};
        for (var o = 0; o < data.length; o++) {
            var keys = Object.keys(data[o]);
            for (var k = 0; k < keys.length; k++) {
                var key = keys[k];
                if (key === "__proto__") {
                    if (warnings.length === 0) warnings.push("已跳过非法字段名 __proto__");
                    continue;
                }
                if (!Object.prototype.hasOwnProperty.call(seen, key)) {
                    seen[key] = true;
                    columns.push(key);
                }
            }
        }
        for (var r = 0; r < data.length; r++) {
            var cells = [];
            for (var c = 0; c < columns.length; c++) {
                cells.push(csvFormatCell(data[r][columns[c]]));
            }
            table.push(cells);
        }
    } else if (allArrays) {
        var width = 0;
        for (var a = 0; a < data.length; a++) {
            if (data[a].length > width) width = data[a].length;
        }
        if (width > CSV_MAX_COLUMNS) {
            return { error: "列数超过上限（最多 " + CSV_MAX_COLUMNS + " 列，当前 " + width + " 列）" };
        }
        columns = csvUniqueNames(null, width);
        for (var b = 0; b < data.length; b++) {
            var rowCells = [];
            for (var d = 0; d < width; d++) {
                rowCells.push(csvFormatCell(data[b][d]));
            }
            table.push(rowCells);
        }
    } else {
        columns = ["value"];
        for (var p = 0; p < data.length; p++) {
            table.push([csvFormatCell(data[p])]);
        }
    }
    var lines = [];
    var useHeader = allObjects && header;
    if (useHeader) {
        var headerCells = [];
        for (var h = 0; h < columns.length; h++) {
            headerCells.push(csvQuoteCell(columns[h], delim.delimiter));
        }
        lines.push(headerCells.join(delim.delimiter));
    }
    for (var t = 0; t < table.length; t++) {
        var outCells = [];
        for (var q = 0; q < table[t].length; q++) {
            outCells.push(csvQuoteCell(table[t][q], delim.delimiter));
        }
        lines.push(outCells.join(delim.delimiter));
    }
    var response = {
        result: lines.join(eol),
        rows: table.length,
        columns: columns,
        delimiter: delim.delimiter,
        header: useHeader,
        note: "含分隔符、双引号或换行的值会自动加引号并按 RFC 4180 把 \" 转义为 \"\"；" +
            "缺失字段留空；只有对象数组且 header 为 true 时才输出表头行。"
    };
    if (warnings.length > 0) response.warnings = warnings;
    return response;
}

/** 工具 csv_stats：按列统计类型、非空计数、数值分布与高频取值。 */
function csvStats(args) {
    args = csvArgsObject(args);
    var input = csvReadText(args);
    if (input.error) return input;
    var delim = csvNormalizeDelimiter(args.delimiter);
    if (delim.error) return delim;
    var header = csvBool(args.header, true);
    var top = csvReadCount(args.top, 3, 10, "top");
    if (top.error) return top;
    var parsed = csvParseRows(input.text, delim.delimiter);
    if (parsed.error) return parsed;
    var rows = parsed.rows;
    if (rows.length === 0) return { error: "CSV 没有可解析的内容" };
    var width = 0;
    for (var i = 0; i < rows.length; i++) {
        if (rows[i].length > width) width = rows[i].length;
    }
    if (width > CSV_MAX_COLUMNS) {
        return { error: "列数超过上限（最多 " + CSV_MAX_COLUMNS + " 列，当前 " + width + " 列）" };
    }
    var columns = csvUniqueNames(header ? rows[0] : null, width);
    var dataRows = header ? rows.slice(1) : rows.slice(0);
    if (dataRows.length === 0) return { error: "CSV 只有表头行，没有数据行" };
    if (dataRows.length > CSV_MAX_ROWS * 10) {
        return { error: "数据行过多（上限 " + (CSV_MAX_ROWS * 10) + " 行，当前 " + dataRows.length + " 行）" };
    }
    var stats = [];
    for (var c = 0; c < width; c++) {
        var distinct = {};
        var freq = {};
        var distinctCount = 0;
        var nonEmpty = 0;
        var empty = 0;
        var numericCount = 0;
        var min = null;
        var max = null;
        var sum = 0;
        for (var r = 0; r < dataRows.length; r++) {
            var cells = dataRows[r];
            var raw = c < cells.length && cells[c] !== undefined ? cells[c] : "";
            if (raw === "") {
                empty++;
                continue;
            }
            nonEmpty++;
            var mapKey = "\u0000" + raw;
            if (!Object.prototype.hasOwnProperty.call(distinct, mapKey)) {
                distinct[mapKey] = true;
                distinctCount++;
            }
            if (Object.prototype.hasOwnProperty.call(freq, mapKey)) freq[mapKey]++;
            else freq[mapKey] = 1;
            if (csvIsNumber(raw)) {
                numericCount++;
                var num = parseFloat(raw.replace(/^\s+|\s+$/g, ""));
                if (min === null || num < min) min = num;
                if (max === null || num > max) max = num;
                sum += num;
            }
        }
        var entry = {
            name: columns[c],
            position: c + 1,
            type: nonEmpty === 0 ? "empty" : (numericCount === nonEmpty ? "number" : "text"),
            nonEmpty: nonEmpty,
            empty: empty,
            distinct: distinctCount
        };
        if (entry.type === "number") {
            entry.min = csvRound(min);
            entry.max = csvRound(max);
            entry.sum = csvRound(sum);
            entry.mean = csvRound(sum / nonEmpty);
            entry.topValues = csvTopValues(freq, top.value);
        } else if (entry.type === "text") {
            entry.numericValues = numericCount;
            entry.topValues = csvTopValues(freq, top.value);
        } else {
            entry.topValues = [];
        }
        stats.push(entry);
    }
    return {
        result: stats,
        rowCount: dataRows.length,
        columnCount: width,
        delimiter: delim.delimiter,
        header: header,
        note: "type 判定：非空值全为数字则 number，否则 text，全空则 empty；" +
            "number 列给出 min/max/sum/mean（保留 6 位小数），text 列给出 distinct 与出现最多的 top 个取值。"
    };
}

/**
 * Muse 插件：时间工具箱
 *
 * 运行契约（宿主 WebViewSkillEngine）：
 *  - 每个工具函数接收一个参数对象，如 function nowTime(args)；
 *  - 返回值可以是普通对象，宿主会自动 JSON 序列化后交给模型；
 *  - 出错时返回 { error: "原因" }，宿主会把这类结果标记为失败。
 *
 * 时区：除显式以 Z/UTC 标记的输入外，一律按设备本地时区解释与输出。
 */

var MS_PER_MINUTE = 60 * 1000;
var MS_PER_HOUR = 60 * MS_PER_MINUTE;
var MS_PER_DAY = 24 * MS_PER_HOUR;

var DATE_PATTERN = /^(\d{4})-(\d{1,2})-(\d{1,2})(?:[ T](\d{1,2}):(\d{2})(?::(\d{2}))?)?(Z)?$/;

/** 把 "2026-09-19 15:30:00" / ISO 字符串 / 纯数字时间戳解析为毫秒。 */
function parseInstant(raw, unit) {
    var text = String(raw).trim();
    if (/^\d+$/.test(text)) {
        var numeric = parseInt(text, 10);
        return String(unit).toLowerCase() === "s" ? numeric * 1000 : numeric;
    }
    var matched = DATE_PATTERN.exec(text);
    if (!matched) return NaN;
    var year = parseInt(matched[1], 10);
    var month = parseInt(matched[2], 10);
    var day = parseInt(matched[3], 10);
    var hour = matched[4] === undefined ? 0 : parseInt(matched[4], 10);
    var minute = matched[5] === undefined ? 0 : parseInt(matched[5], 10);
    var second = matched[6] === undefined ? 0 : parseInt(matched[6], 10);
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 23 || minute > 59 || second > 59) {
        return NaN;
    }
    if (matched[7]) return Date.UTC(year, month - 1, day, hour, minute, second);
    return new Date(year, month - 1, day, hour, minute, second).getTime();
}

function pad(value, width) {
    var text = String(value);
    while (text.length < width) text = "0" + text;
    return text;
}

/** 本地时区 ISO 风格字符串（带偏移，便于与 UTC 区分）。 */
function localIso(ms) {
    var date = new Date(ms);
    var offsetMinutes = -date.getTimezoneOffset();
    var sign = offsetMinutes >= 0 ? "+" : "-";
    var absolute = Math.abs(offsetMinutes);
    return pad(date.getFullYear(), 4) + "-" + pad(date.getMonth() + 1, 2) + "-" + pad(date.getDate(), 2) +
        "T" + pad(date.getHours(), 2) + ":" + pad(date.getMinutes(), 2) + ":" + pad(date.getSeconds(), 2) +
        sign + pad(Math.floor(absolute / 60), 2) + ":" + pad(absolute % 60, 2);
}

function isoWeekNumber(date) {
    var target = new Date(date.getFullYear(), date.getMonth(), date.getDate());
    var dayNumber = (target.getDay() + 6) % 7;
    target.setDate(target.getDate() - dayNumber + 3);
    var firstThursday = new Date(target.getFullYear(), 0, 4);
    var firstDayNumber = (firstThursday.getDay() + 6) % 7;
    firstThursday.setDate(firstThursday.getDate() - firstDayNumber + 3);
    return 1 + Math.round((target.getTime() - firstThursday.getTime()) / (7 * MS_PER_DAY));
}

function nowTime() {
    var now = new Date();
    var ms = now.getTime();
    var weekdays = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];
    return {
        result: {
            epochMillis: ms,
            epochSeconds: Math.floor(ms / 1000),
            localIso: localIso(ms),
            utcIso: now.toISOString(),
            localDate: pad(now.getFullYear(), 4) + "-" + pad(now.getMonth() + 1, 2) + "-" + pad(now.getDate(), 2),
            localTime: pad(now.getHours(), 2) + ":" + pad(now.getMinutes(), 2) + ":" + pad(now.getSeconds(), 2),
            weekday: weekdays[now.getDay()],
            isoWeek: isoWeekNumber(now)
        },
        timezone: "设备本地时区，UTC 偏移 " + (-now.getTimezoneOffset() / 60) + " 小时"
    };
}

function timestampConvert(args) {
    var raw = args.value;
    if (raw === undefined || raw === null || String(raw).trim().length === 0) {
        return { error: "value 不能为空" };
    }
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    var unit = typeof args.unit === "string" ? args.unit.trim().toLowerCase() : "ms";
    if (unit !== "ms" && unit !== "s") return { error: "unit 必须是 ms 或 s" };
    if (mode === "to_date") {
        var ms = parseInstant(raw, unit);
        if (isNaN(ms)) return { error: "无法解析时间戳: " + raw };
        var date = new Date(ms);
        return {
            result: {
                epochMillis: ms,
                epochSeconds: Math.floor(ms / 1000),
                localIso: localIso(ms),
                utcIso: date.toISOString()
            }
        };
    }
    if (mode !== "to_timestamp") return { error: "mode 必须是 to_date 或 to_timestamp" };
    var instant = parseInstant(raw, unit);
    if (isNaN(instant)) return { error: "无法解析日期: " + raw };
    return {
        result: {
            epochMillis: instant,
            epochSeconds: Math.floor(instant / 1000),
            localIso: localIso(instant),
            utcIso: new Date(instant).toISOString()
        }
    };
}

function durationFormat(args) {
    var value = typeof args.value === "number" ? args.value : parseFloat(args.value);
    if (isNaN(value)) return { error: "value 必须是数字" };
    var unit = typeof args.unit === "string" ? args.unit.trim().toLowerCase() : "ms";
    if (unit !== "ms" && unit !== "s") return { error: "unit 必须是 ms 或 s" };
    var totalMs = unit === "s" ? value * 1000 : value;
    var negative = totalMs < 0;
    var remaining = Math.abs(Math.round(totalMs));
    var days = Math.floor(remaining / MS_PER_DAY);
    remaining -= days * MS_PER_DAY;
    var hours = Math.floor(remaining / MS_PER_HOUR);
    remaining -= hours * MS_PER_HOUR;
    var minutes = Math.floor(remaining / MS_PER_MINUTE);
    remaining -= minutes * MS_PER_MINUTE;
    var seconds = Math.floor(remaining / 1000);
    var millis = remaining - seconds * 1000;
    var parts = [];
    if (days > 0) parts.push(days + "天");
    if (hours > 0) parts.push(hours + "小时");
    if (minutes > 0) parts.push(minutes + "分");
    if (seconds > 0) parts.push(seconds + "秒");
    if (parts.length === 0) parts.push(millis + "毫秒");
    return {
        result: (negative ? "-" : "") + parts.join(""),
        breakdown: { days: days, hours: hours, minutes: minutes, seconds: seconds, millis: millis }
    };
}

function dateDiff(args) {
    var fromMs = parseInstant(args.from, "ms");
    var toMs = parseInstant(args.to, "ms");
    if (isNaN(fromMs)) return { error: "无法解析起始日期: " + args.from };
    if (isNaN(toMs)) return { error: "无法解析结束日期: " + args.to };
    var deltaMs = toMs - fromMs;
    var absolute = Math.abs(deltaMs);
    var days = Math.floor(absolute / MS_PER_DAY);
    if (args.excludeEnd) days = days > 0 ? days - 1 : 0;
    var remainder = absolute - Math.floor(absolute / MS_PER_DAY) * MS_PER_DAY;
    var hours = Math.floor(remainder / MS_PER_HOUR);
    var minutes = Math.floor((remainder - hours * MS_PER_HOUR) / MS_PER_MINUTE);
    return {
        result: {
            direction: deltaMs === 0 ? "same" : (deltaMs > 0 ? "later" : "earlier"),
            totalDays: Math.floor(absolute / MS_PER_DAY),
            days: days,
            hours: hours,
            minutes: minutes,
            totalHours: Math.floor(absolute / MS_PER_HOUR),
            totalMinutes: Math.floor(absolute / MS_PER_MINUTE),
            totalSeconds: Math.floor(absolute / 1000),
            text: days + "天" + hours + "小时" + minutes + "分"
        },
        from: localIso(fromMs),
        to: localIso(toMs)
    };
}

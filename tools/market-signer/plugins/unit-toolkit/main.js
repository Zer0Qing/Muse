/**
 * Muse 插件：单位换算工具箱
 *
 * 运行契约（宿主 WebViewSkillEngine）：
 *  - 每个工具函数接收一个参数对象，如 function unitConvert(args)，args.value / args.from / args.to；
 *  - 返回值是普通对象，宿主会自动 JSON 序列化后交给模型；
 *  - 出错时返回 { error: "原因" }，宿主会把这类结果标记为失败。
 *
 * 纯本地计算：不联网、不读写文件。换算全部走「单位 → 类别基准单位 → 目标单位」，
 * 温度是仿射变换（°C/°F/K），因此单独处理，不走比例因子。
 */

/** 结果保留 12 位有效数字，抹掉浮点噪声（如 1 节 → 1.8519999999999999 km/h）。 */
var UNIT_SIGNIFICANT_DIGITS = 12;

/** 单位显示符号：未列出的单位直接用其规范键。 */
var UNIT_SYMBOLS = {
    m2: "m²", km2: "km²", cm2: "cm²", mm2: "mm²", mi2: "mi²", yd2: "yd²", ft2: "ft²", in2: "in²",
    m3: "m³", cm3: "cm³",
    c: "°C", f: "°F", k: "K",
    l: "L", ml: "mL",
    um: "µm", us: "µs",
    jin: "斤", liang: "两", qian: "钱", li: "里", chi: "尺", cun: "寸", mu: "亩",
    nmi: "nmi"
};

/**
 * 类别表。units 的数值因子是「1 个该单位 = factor 个基准单位」：
 *  - length 基准 m，mass 基准 g，area 基准 m²，volume 基准 L，speed 基准 m/s，
 *    data 基准 B（按 1024 进），time 基准 s，temperature 为仿射变换（见 unitToBase/unitFromBase）。
 */
var UNIT_CATEGORIES = {
    length: {
        label: "长度",
        base: "m",
        units: {
            m: 1, km: 1000, dm: 0.1, cm: 0.01, mm: 0.001, um: 0.000001, nm: 0.000000001,
            mi: 1609.344, yd: 0.9144, ft: 0.3048, in: 0.0254, nmi: 1852,
            li: 500, chi: 1 / 3, cun: 1 / 30
        },
        aliases: {
            "米": "m", meter: "m", metre: "m", meters: "m", metres: "m",
            "千米": "km", "公里": "km", kilometer: "km", kilometre: "km", kilometers: "km",
            "分米": "dm", decimeter: "dm", decimetre: "dm",
            "厘米": "cm", "公分": "cm", centimeter: "cm", centimetre: "cm", centimeters: "cm",
            "毫米": "mm", millimeter: "mm", millimetre: "mm", millimeters: "mm",
            "微米": "um", micrometer: "um", micrometre: "um",
            "纳米": "nm", nanometer: "nm", nanometre: "nm",
            "英里": "mi", mile: "mi", miles: "mi",
            "码": "yd", yard: "yd", yards: "yd",
            "英尺": "ft", foot: "ft", feet: "ft",
            "英寸": "in", inch: "in", inches: "in",
            "海里": "nmi", "浬": "nmi", nauticalmile: "nmi", nauticalmiles: "nmi",
            "里": "li", "市里": "li",
            "尺": "chi", "市尺": "chi",
            "寸": "cun", "市寸": "cun"
        }
    },
    mass: {
        label: "质量",
        base: "g",
        units: { g: 1, mg: 0.001, ug: 0.000001, kg: 1000, t: 1000000, lb: 453.59237, oz: 28.349523125, jin: 500, liang: 50, qian: 5 },
        aliases: {
            "克": "g", gram: "g", grams: "g",
            "毫克": "mg", milligram: "mg", milligrams: "mg",
            "微克": "ug", microgram: "ug", micrograms: "ug",
            "千克": "kg", "公斤": "kg", kilogram: "kg", kilograms: "kg", kilogramme: "kg",
            "吨": "t", "公吨": "t", ton: "t", tons: "t", tonne: "t", tonnes: "t",
            "磅": "lb", pound: "lb", pounds: "lb", lbs: "lb",
            "盎司": "oz", ounce: "oz", ounces: "oz",
            "斤": "jin", "市斤": "jin",
            "两": "liang", "市两": "liang",
            "钱": "qian", "市钱": "qian"
        }
    },
    temperature: {
        label: "温度",
        base: "c",
        units: { c: 1, f: 1, k: 1 },
        aliases: {
            "摄氏": "c", "摄氏度": "c", celsius: "c", centigrade: "c",
            "华氏": "f", "华氏度": "f", fahrenheit: "f",
            "开": "k", "开尔文": "k", "绝对温度": "k", kelvin: "k"
        }
    },
    area: {
        label: "面积",
        base: "m2",
        units: {
            m2: 1, km2: 1000000, cm2: 0.0001, mm2: 0.000001,
            ha: 10000, mu: 2000 / 3, acre: 4046.8564224,
            mi2: 2589988.110336, yd2: 0.83612736, ft2: 0.09290304, in2: 0.00064516
        },
        aliases: {
            "平方米": "m2", "平米": "m2", squaremeter: "m2", sqm: "m2",
            "平方公里": "km2", "平方千米": "km2", squarekilometer: "km2",
            "平方厘米": "cm2", squarecentimeter: "cm2",
            "平方毫米": "mm2", squaremillimeter: "mm2",
            "公顷": "ha", hectare: "ha", hectares: "ha",
            "亩": "mu", "市亩": "mu",
            "英亩": "acre", acre: "acre", acres: "acre",
            "平方英里": "mi2", squaremile: "mi2",
            "平方码": "yd2", squareyard: "yd2",
            "平方英尺": "ft2", squarefoot: "ft2", sqft: "ft2",
            "平方英寸": "in2", squareinch: "in2", sqin: "in2"
        }
    },
    volume: {
        label: "体积",
        base: "l",
        units: {
            l: 1, ml: 0.001, m3: 1000, cm3: 0.001,
            gal: 3.785411784, ukgal: 4.54609,
            qt: 0.946352946, pt: 0.473176473, cup: 0.2365882365,
            floz: 0.0295735295625, tbsp: 0.01478676478125, tsp: 0.00492892159375
        },
        aliases: {
            "升": "l", liter: "l", litre: "l", liters: "l", litres: "l",
            "毫升": "ml", milliliter: "ml", millilitre: "ml",
            "立方米": "m3", cubicmeter: "m3", cubicmetre: "m3",
            "立方厘米": "cm3", cubiccentimeter: "cm3", cc: "cm3",
            "加仑": "gal", "美制加仑": "gal", gallon: "gal", gallons: "gal", usgallon: "gal",
            "英制加仑": "ukgal", imperialgallon: "ukgal",
            "夸脱": "qt", quart: "qt", quarts: "qt",
            "品脱": "pt", pint: "pt", pints: "pt",
            "杯": "cup", cup: "cup", cups: "cup",
            "液盎司": "floz", fluidounce: "floz", floz: "floz",
            "汤匙": "tbsp", tablespoon: "tbsp",
            "茶匙": "tsp", teaspoon: "tsp"
        }
    },
    speed: {
        label: "速度",
        base: "mps",
        units: { mps: 1, kmh: 1 / 3.6, mph: 0.44704, knot: 1852 / 3600, fps: 0.3048 },
        aliases: {
            "米每秒": "mps", "米/秒": "mps", "m/s": "mps", meterpersecond: "mps", mps: "mps",
            "千米每小时": "kmh", "公里每小时": "kmh", "千米/时": "kmh", "千米/小时": "kmh", "km/h": "kmh", "kph": "kmh",
            kilometerperhour: "kmh", kmh: "kmh",
            "英里每小时": "mph", "英里/时": "mph", "英里/小时": "mph", "mi/h": "mph", milesperhour: "mph", mph: "mph",
            "节": "knot", "節": "knot", knot: "knot", knots: "knot", kn: "knot",
            "英尺每秒": "fps", "ft/s": "fps", footpersecond: "fps", fps: "fps"
        }
    },
    data: {
        label: "数据量",
        base: "b",
        units: {
            b: 1, kb: 1024, mb: 1048576, gb: 1073741824, tb: 1099511627776, pb: 1125899906842624,
            bit: 0.125, kbit: 128, mbit: 131072, gbit: 134217728
        },
        aliases: {
            "字节": "b", byte: "b", bytes: "b",
            "千字节": "kb", kilobyte: "kb", kilobytes: "kb",
            "兆字节": "mb", megabyte: "mb", megabytes: "mb",
            "吉字节": "gb", "千兆字节": "gb", gigabyte: "gb", gigabytes: "gb",
            "太字节": "tb", terabyte: "tb", terabytes: "tb",
            "拍字节": "pb", petabyte: "pb", petabytes: "pb",
            kib: "kb", kibibyte: "kb", mib: "mb", mebibyte: "mb",
            gib: "gb", gibibyte: "gb", tib: "tb", tebibyte: "tb", pib: "pb", pebibyte: "pb",
            "比特": "bit", "位": "bit", bit: "bit", bits: "bit",
            "千比特": "kbit", kbit: "kbit", "兆比特": "mbit", mbit: "mbit",
            "吉比特": "gbit", gbit: "gbit"
        }
    },
    time: {
        label: "时间",
        base: "s",
        units: { s: 1, ms: 0.001, us: 0.000001, ns: 0.000000001, min: 60, h: 3600, day: 86400, week: 604800 },
        aliases: {
            "秒": "s", second: "s", seconds: "s", sec: "s",
            "毫秒": "ms", millisecond: "ms", milliseconds: "ms",
            "微秒": "us", microsecond: "us", microseconds: "us",
            "纳秒": "ns", nanosecond: "ns", nanoseconds: "ns",
            "分钟": "min", minute: "min", minutes: "min",
            "小时": "h", "时": "h", hour: "h", hours: "h", hr: "h",
            "天": "day", "日": "day", day: "day", days: "day",
            "周": "week", "星期": "week", week: "week", weeks: "week", wk: "week"
        }
    }
};

/** 类别别名（含中文），键同样是归一化后的写法。 */
var UNIT_CATEGORY_ALIASES = {
    length: "length", "长度": "length", "距离": "length", distance: "length",
    mass: "mass", "质量": "mass", "重量": "mass", weight: "mass",
    temperature: "temperature", "温度": "temperature", temp: "temperature",
    area: "area", "面积": "area",
    volume: "volume", "体积": "volume", "容积": "volume", "容量": "volume", capacity: "volume",
    speed: "speed", "速度": "speed", velocity: "speed",
    data: "data", "数据": "data", "数据量": "data", "存储": "data", datasize: "data", storage: "data",
    time: "time", "时间": "time", duration: "time"
};

/* ────────────────────────────── 通用工具 ────────────────────────────── */

/** 单位/类别名归一：小写、去空格与下划线、全角转半角、上标与㎡/㎥ 展开。 */
function unitNormalizeKey(raw) {
    var text = String(raw).trim().toLowerCase();
    var out = "";
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch === " " || ch === "_" || ch === "\t") continue;
        if (ch >= "０" && ch <= "９") out += String.fromCharCode(ch.charCodeAt(0) - 0xfee0);
        else if (ch === "²") out += "2";
        else if (ch === "³") out += "3";
        else if (ch === "㎡") out += "m2";
        else if (ch === "㎥") out += "m3";
        else if (ch === "µ" || ch === "μ") out += "u";
        else if (ch === "°" || ch === "º") continue;
        else out += ch;
    }
    return out;
}

function unitHas(object, key) {
    return Object.prototype.hasOwnProperty.call(object, key);
}

function unitSymbol(categoryKey, unit) {
    if (unitHas(UNIT_SYMBOLS, unit)) return UNIT_SYMBOLS[unit];
    return unit;
}

/** 单位的中文名（取别名表里第一个中文别名），用于错误提示与友好显示。 */
function unitChineseName(categoryKey, unit) {
    var aliases = UNIT_CATEGORIES[categoryKey].aliases;
    for (var alias in aliases) {
        if (!unitHas(aliases, alias)) continue;
        if (aliases[alias] === unit && /[\u4e00-\u9fff]/.test(alias)) return alias;
    }
    return "";
}

/** 人类可读的「单位(中文名)」列表，未知单位时作为提示返回。 */
function unitListOf(categoryKey) {
    var category = UNIT_CATEGORIES[categoryKey];
    var items = [];
    for (var unit in category.units) {
        if (!unitHas(category.units, unit)) continue;
        var chinese = unitChineseName(categoryKey, unit);
        items.push(unitSymbol(categoryKey, unit) + (chinese ? "(" + chinese + ")" : ""));
    }
    return items;
}

/** 跨类别查找单位，返回所有命中的 { category, unit }。 */
function unitFindMatches(rawUnit) {
    var key = unitNormalizeKey(rawUnit);
    var matches = [];
    for (var categoryKey in UNIT_CATEGORIES) {
        if (!unitHas(UNIT_CATEGORIES, categoryKey)) continue;
        var category = UNIT_CATEGORIES[categoryKey];
        if (unitHas(category.aliases, key)) matches.push({ category: categoryKey, unit: category.aliases[key] });
        else if (unitHas(category.units, key)) matches.push({ category: categoryKey, unit: key });
    }
    return matches;
}

function unitResolveCategoryKey(rawCategory) {
    if (rawCategory === undefined || rawCategory === null) return null;
    var key = unitNormalizeKey(rawCategory);
    if (key.length === 0) return null;
    if (unitHas(UNIT_CATEGORY_ALIASES, key)) return UNIT_CATEGORY_ALIASES[key];
    if (unitHas(UNIT_CATEGORIES, key)) return key;
    return undefined;
}

function unitAllCategoryNames() {
    var names = [];
    for (var categoryKey in UNIT_CATEGORIES) {
        if (!unitHas(UNIT_CATEGORIES, categoryKey)) continue;
        names.push(categoryKey + "(" + UNIT_CATEGORIES[categoryKey].label + ")");
    }
    return names;
}

function unitParseNumber(raw, label) {
    if (typeof raw === "number") {
        if (isNaN(raw) || !isFinite(raw)) return { error: label + " 必须是有限数字" };
        return { value: raw };
    }
    if (typeof raw === "string") {
        var text = raw.trim();
        if (text.length === 0) return { error: label + " 不能为空" };
        if (!/^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/.test(text)) {
            return { error: label + " 不是合法数字: " + raw };
        }
        var parsed = parseFloat(text);
        if (isNaN(parsed) || !isFinite(parsed)) return { error: label + " 超出可解析范围" };
        return { value: parsed };
    }
    return { error: label + " 必须是数字" };
}

/** 抹掉浮点噪声：保留 12 位有效数字。 */
function unitRound(value) {
    if (value === 0) return 0;
    return parseFloat(value.toPrecision(UNIT_SIGNIFICANT_DIGITS));
}

/** 紧凑数字字符串（用于 formatted 字段）。 */
function unitFormatNumber(value) {
    if (value === 0) return "0";
    var abs = Math.abs(value);
    if (abs >= 1e15 || abs < 1e-6) return parseFloat(value.toPrecision(6)).toExponential(6).replace(/\.?0+e/, "e");
    var text = value.toPrecision(UNIT_SIGNIFICANT_DIGITS);
    return String(parseFloat(text));
}

/* ────────────────────────────── 温度（仿射） ────────────────────────────── */

/** 任意温度单位 → 摄氏度。 */
function unitTemperatureToCelsius(unit, value) {
    if (unit === "c") return value;
    if (unit === "f") return (value - 32) * 5 / 9;
    return value - 273.15;
}

/** 摄氏度 → 任意温度单位。 */
function unitTemperatureFromCelsius(unit, celsius) {
    if (unit === "c") return celsius;
    if (unit === "f") return celsius * 9 / 5 + 32;
    return celsius + 273.15;
}

/* ────────────────────────────── 换算 ────────────────────────────── */

function unitToBase(categoryKey, unit, value) {
    if (categoryKey === "temperature") return unitTemperatureToCelsius(unit, value);
    return value * UNIT_CATEGORIES[categoryKey].units[unit];
}

function unitFromBase(categoryKey, unit, value) {
    if (categoryKey === "temperature") return unitTemperatureFromCelsius(unit, value);
    return value / UNIT_CATEGORIES[categoryKey].units[unit];
}

function unitConvert(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var parsedValue = unitParseNumber(args.value, "value");
    if (parsedValue.error) return { error: parsedValue.error };
    if (typeof args.from !== "string" || args.from.trim().length === 0) return { error: "from 必须是单位字符串，如“m”或“千米”" };
    if (typeof args.to !== "string" || args.to.trim().length === 0) return { error: "to 必须是单位字符串，如“km”" };

    var explicitCategory = unitResolveCategoryKey(args.category);
    if (explicitCategory === undefined) {
        return {
            error: "未知类别: " + args.category + "。可用类别: " + unitAllCategoryNames().join("、"),
            availableCategories: unitAllCategoryNames()
        };
    }

    var fromMatch;
    var toMatch;
    if (explicitCategory) {
        var fromMatches = unitFindMatches(args.from).filter(function (item) { return item.category === explicitCategory; });
        if (fromMatches.length === 0) {
            return {
                error: "类别 " + explicitCategory + " 中没有单位 '" + args.from + "'。可用单位: " + unitListOf(explicitCategory).join("、"),
                availableUnits: unitListOf(explicitCategory)
            };
        }
        fromMatch = fromMatches[0];
        var toMatches = unitFindMatches(args.to).filter(function (item) { return item.category === explicitCategory; });
        if (toMatches.length === 0) {
            return {
                error: "类别 " + explicitCategory + " 中没有单位 '" + args.to + "'。可用单位: " + unitListOf(explicitCategory).join("、"),
                availableUnits: unitListOf(explicitCategory)
            };
        }
        toMatch = toMatches[0];
    } else {
        var fromAll = unitFindMatches(args.from);
        if (fromAll.length === 0) {
            return {
                error: "未知单位: " + args.from + "。已知单位示例: " + unitListOf("length").join("、") + " 等；也可用 category 指定类别。",
                availableUnits: unitListOf("length"),
                availableCategories: unitAllCategoryNames()
            };
        }
        if (fromAll.length > 1) {
            return { error: "单位 '" + args.from + "' 在多个类别中出现，请用 category 指定类别（" + unitAllCategoryNames().join("、") + "）" };
        }
        fromMatch = fromAll[0];
        var toAll = unitFindMatches(args.to);
        if (toAll.length === 0) {
            return {
                error: "未知单位: " + args.to + "。" + fromMatch.category + " 可用单位: " + unitListOf(fromMatch.category).join("、"),
                availableUnits: unitListOf(fromMatch.category)
            };
        }
        if (toAll.length > 1) {
            return { error: "单位 '" + args.to + "' 在多个类别中出现，请用 category 指定类别（" + unitAllCategoryNames().join("、") + "）" };
        }
        toMatch = toAll[0];
        if (toMatch.category !== fromMatch.category) {
            return {
                error: "from 与 to 不在同一类别: " + args.from + " 属于 " + fromMatch.category +
                    "（" + UNIT_CATEGORIES[fromMatch.category].label + "），" + args.to + " 属于 " + toMatch.category +
                    "（" + UNIT_CATEGORIES[toMatch.category].label + "）",
                availableUnits: unitListOf(fromMatch.category)
            };
        }
    }

    var categoryKey = fromMatch.category;
    var baseValue = unitToBase(categoryKey, fromMatch.unit, parsedValue.value);
    if (categoryKey === "temperature" && baseValue < -273.15) {
        return { error: "温度低于绝对零度（-273.15 °C），请检查输入" };
    }
    var resultValue = unitRound(unitFromBase(categoryKey, toMatch.unit, baseValue));

    var note = categoryKey === "temperature"
        ? "温度是仿射变换（°C/°F/K），已按绝对零度校正。"
        : "";
    if (categoryKey === "data") note = "数据量按 1024 进（1 KB = 1024 B），与 data_size 工具一致。";
    if (categoryKey === "length" && (fromMatch.unit === "in" || toMatch.unit === "in")) note = "in 为英寸（inch）。";
    if (categoryKey === "volume" && (fromMatch.unit === "gal" || toMatch.unit === "gal")) note = "gal 为美制加仑（3.785411784 L），英制请用 ukgal。";
    if (categoryKey === "area" && (fromMatch.unit === "mu" || toMatch.unit === "mu")) note = "亩为市亩（1 亩 = 666.6667 m²）。";

    return {
        result: resultValue,
        formatted: unitFormatNumber(resultValue) + " " + unitSymbol(categoryKey, toMatch.unit),
        category: categoryKey,
        categoryLabel: UNIT_CATEGORIES[categoryKey].label,
        from: { unit: fromMatch.unit, symbol: unitSymbol(categoryKey, fromMatch.unit), value: parsedValue.value },
        to: { unit: toMatch.unit, symbol: unitSymbol(categoryKey, toMatch.unit), value: resultValue },
        note: note
    };
}

/* ────────────────────────────── 数据量格式化 ────────────────────────────── */

var DATA_UNITS = ["B", "KB", "MB", "GB", "TB", "PB"];
var DATA_BASE = 1024;

function dataFormat(bytes, decimals) {
    var index = 0;
    var value = bytes;
    while (value >= DATA_BASE && index < DATA_UNITS.length - 1) {
        value = value / DATA_BASE;
        index++;
    }
    var text = value.toFixed(decimals);
    if (text.indexOf(".") >= 0) text = text.replace(/0+$/, "").replace(/\.$/, "");
    return { value: value, unit: DATA_UNITS[index], text: text + " " + DATA_UNITS[index] };
}

function dataSize(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode !== "format" && mode !== "parse") return { error: "mode 必须是 format（字节数→可读文本）或 parse（可读文本→字节数）" };

    var decimals = 2;
    if (args.decimals !== undefined && args.decimals !== null) {
        var parsedDecimals = typeof args.decimals === "string" ? parseInt(args.decimals, 10) : args.decimals;
        if (typeof parsedDecimals !== "number" || isNaN(parsedDecimals) || parsedDecimals % 1 !== 0 || parsedDecimals < 0 || parsedDecimals > 6) {
            return { error: "decimals 必须是 0..6 的整数" };
        }
        decimals = parsedDecimals;
    }

    if (mode === "format") {
        var parsedBytes = unitParseNumber(args.bytes, "bytes");
        if (parsedBytes.error) return { error: parsedBytes.error };
        if (parsedBytes.value < 0) return { error: "bytes 不能为负数" };
        if (parsedBytes.value > 9007199254740991) return { error: "bytes 超出安全整数范围（上限 2^53-1）" };
        var formatted = dataFormat(parsedBytes.value, decimals);
        return {
            result: formatted.text,
            bytes: parsedBytes.value,
            unit: formatted.unit,
            value: parseFloat(formatted.value.toFixed(decimals)),
            note: "按 1024 进位（1 KB = 1024 B）。"
        };
    }

    if (typeof args.text !== "string" || args.text.trim().length === 0) return { error: "parse 模式需要 text，如“1.5GB”" };
    var text = args.text.trim();
    var matched = /^([+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?)\s*([A-Za-z\u4e00-\u9fff]*)$/.exec(text);
    if (!matched) return { error: "无法解析数据量文本: " + args.text + "（示例: 1.5GB / 1024 / 512 MB）" };
    var amount = parseFloat(matched[1]);
    if (isNaN(amount) || !isFinite(amount)) return { error: "数据量数值无效: " + matched[1] };
    if (amount < 0) return { error: "数据量不能为负数" };

    var unitKey = matched[2].length === 0 ? "b" : unitNormalizeKey(matched[2]);
    var factors = {
        b: 1, "字节": 1, byte: 1, bytes: 1,
        kb: DATA_BASE, "千字节": DATA_BASE, kilobyte: DATA_BASE, kib: DATA_BASE,
        mb: DATA_BASE * DATA_BASE, "兆字节": DATA_BASE * DATA_BASE, megabyte: DATA_BASE * DATA_BASE, mib: DATA_BASE * DATA_BASE,
        gb: Math.pow(DATA_BASE, 3), "吉字节": Math.pow(DATA_BASE, 3), gigabyte: Math.pow(DATA_BASE, 3), gib: Math.pow(DATA_BASE, 3),
        tb: Math.pow(DATA_BASE, 4), "太字节": Math.pow(DATA_BASE, 4), terabyte: Math.pow(DATA_BASE, 4), tib: Math.pow(DATA_BASE, 4),
        pb: Math.pow(DATA_BASE, 5), "拍字节": Math.pow(DATA_BASE, 5), petabyte: Math.pow(DATA_BASE, 5), pib: Math.pow(DATA_BASE, 5),
        bit: 0.125, "比特": 0.125, bits: 0.125,
        kbit: 128, mbit: 131072, gbit: 134217728
    };
    if (!unitHas(factors, unitKey)) {
        return {
            error: "未知数据量单位: " + matched[2] + "。可用单位: " + DATA_UNITS.join("、") + "、bit（比特，1 bit = 1/8 B）",
            availableUnits: DATA_UNITS.concat(["bit"])
        };
    }
    var bytes = amount * factors[unitKey];
    if (!isFinite(bytes) || bytes > 9007199254740991) return { error: "换算结果超出安全整数范围" };
    var rounded = unitRound(bytes);
    return {
        result: rounded,
        bytes: rounded,
        text: args.text,
        unit: unitKey,
        human: dataFormat(rounded, 2).text,
        note: "按 1024 进位；b/B 均按字节，bit 才表示比特。"
    };
}

/* ────────────────────────────── 数字可读化 ────────────────────────────── */

var RMB_DIGITS = ["零", "壹", "贰", "叁", "肆", "伍", "陆", "柒", "捌", "玖"];
var RMB_UNITS = ["", "拾", "佰", "仟"];
var RMB_GROUPS = ["", "万", "亿", "万亿"];
var RMB_MAX_AMOUNT = 999999999999.99;

/** 四位一组的整数转中文大写（组内 0 折叠为「零」）。 */
function numberRmbGroup(group) {
    var text = "";
    var pendingZero = false;
    for (var i = 0; i < group.length; i++) {
        var digit = group.charCodeAt(i) - 48;
        var unit = RMB_UNITS[group.length - 1 - i];
        if (digit === 0) {
            if (text.length > 0) pendingZero = true;
            continue;
        }
        if (pendingZero) {
            text += "零";
            pendingZero = false;
        }
        text += RMB_DIGITS[digit] + unit;
    }
    return text;
}

/** 数字 → 中文大写金额（如 1234.56 → 壹仟贰佰叁拾肆元伍角陆分）。 */
function numberRmbUpper(value) {
    var negative = value < 0;
    var cents = Math.round(Math.abs(value) * 100);
    var integerPart = Math.floor(cents / 100);
    var fraction = cents - integerPart * 100;
    var jiao = Math.floor(fraction / 10);
    var fen = fraction % 10;

    var rest = String(integerPart);
    var groups = [];
    while (rest.length > 0) {
        var cut = rest.length - 4;
        if (cut < 0) cut = 0;
        groups.unshift(rest.substring(cut));
        rest = rest.substring(0, cut);
    }
    var yuanText = "";
    var pendingZero = false;
    for (var i = 0; i < groups.length; i++) {
        var groupText = numberRmbGroup(groups[i]);
        var groupUnit = RMB_GROUPS[groups.length - 1 - i];
        if (groupText.length === 0) {
            if (yuanText.length > 0) pendingZero = true;
            continue;
        }
        if (pendingZero) {
            yuanText += "零";
            pendingZero = false;
        }
        yuanText += groupText + groupUnit;
    }
    if (yuanText.length === 0) yuanText = "零";

    var text = yuanText + "元";
    if (jiao === 0 && fen === 0) text += "整";
    else if (jiao === 0) text += "零" + RMB_DIGITS[fen] + "分";
    else if (fen === 0) text += RMB_DIGITS[jiao] + "角";
    else text += RMB_DIGITS[jiao] + "角" + RMB_DIGITS[fen] + "分";
    return (negative ? "负" : "") + text;
}

/** 千分位：按原始小数位或指定小数位插入逗号。 */
function numberThousands(value, decimals, hasDecimals) {
    var text = hasDecimals ? value.toFixed(decimals) : String(value);
    if (text.indexOf("e") >= 0 || text.indexOf("E") >= 0) return text;
    var negative = text.charAt(0) === "-";
    if (negative) text = text.slice(1);
    var parts = text.split(".");
    var integer = parts[0];
    var grouped = "";
    for (var i = 0; i < integer.length; i++) {
        if (i > 0 && (integer.length - i) % 3 === 0) grouped += ",";
        grouped += integer.charAt(i);
    }
    var out = grouped + (parts.length > 1 ? "." + parts[1] : "");
    return (negative ? "-" : "") + out;
}

function numberFormat(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode.length === 0) return { error: "mode 不能为空，必须是 thousands/fixed/scientific/percent/rmb 之一" };
    var parsedValue = unitParseNumber(args.value, "value");
    if (parsedValue.error) return { error: parsedValue.error };
    var value = parsedValue.value;

    var decimals = null;
    if (args.decimals !== undefined && args.decimals !== null) {
        var parsedDecimals = typeof args.decimals === "string" ? parseInt(args.decimals, 10) : args.decimals;
        if (typeof parsedDecimals !== "number" || isNaN(parsedDecimals) || parsedDecimals % 1 !== 0 || parsedDecimals < 0 || parsedDecimals > 20) {
            return { error: "decimals 必须是 0..20 的整数" };
        }
        decimals = parsedDecimals;
    }

    if (mode === "thousands" || mode === "thousand" || mode === "group") {
        if (Math.abs(value) >= 1e21) return { error: "千分位不支持 ≥ 1e21 的数字，请改用 scientific 模式" };
        var hasDecimals = decimals !== null;
        var text = numberThousands(value, hasDecimals ? decimals : 0, hasDecimals);
        return {
            result: text,
            mode: "thousands",
            decimals: hasDecimals ? decimals : null,
            note: "decimals 省略时保留数字本身的小数位。"
        };
    }

    if (mode === "fixed" || mode === "decimal") {
        var fixedDecimals = decimals === null ? 2 : decimals;
        return { result: value.toFixed(fixedDecimals), mode: "fixed", decimals: fixedDecimals, number: parseFloat(value.toFixed(fixedDecimals)) };
    }

    if (mode === "scientific" || mode === "sci") {
        var digits = decimals === null ? 5 : decimals;
        var exponential = value.toExponential(digits);
        var matched = /^(-?)(\d)(?:\.(\d+))?e([+-]\d+)$/.exec(exponential);
        return {
            result: exponential,
            mode: "scientific",
            mantissa: matched ? parseFloat(matched[1] + matched[2] + (matched[3] ? "." + matched[3] : "")) : value,
            exponent: matched ? parseInt(matched[4], 10) : 0,
            note: "decimals 控制尾数小数位（默认 5）。"
        };
    }

    if (mode === "percent" || mode === "percentage") {
        var alreadyPercent = args.alreadyPercent === true;
        var percentValue = alreadyPercent ? value : value * 100;
        var percentDecimals = decimals === null ? 2 : decimals;
        var percentText = percentValue.toFixed(percentDecimals);
        if (percentDecimals > 0) percentText = percentText.replace(/0+$/, "").replace(/\.$/, "");
        return {
            result: percentText + "%",
            mode: "percent",
            number: parseFloat(percentValue.toFixed(percentDecimals)),
            ratio: alreadyPercent ? percentValue / 100 : value,
            note: alreadyPercent ? "已按 alreadyPercent=true 直接格式化。" : "默认把 value 视为比例并乘以 100（0.256 → 25.6%）。"
        };
    }

    if (mode === "rmb" || mode === "chinese" || mode === "capital") {
        if (Math.abs(value) > RMB_MAX_AMOUNT) {
            return { error: "金额超出支持范围（上限 " + RMB_MAX_AMOUNT + "）" };
        }
        var roundedCents = Math.round(Math.abs(value) * 100) / 100;
        var signedCents = value < 0 ? -roundedCents : roundedCents;
        return {
            result: numberRmbUpper(value),
            mode: "rmb",
            amount: signedCents,
            note: "四舍五入到分；输出为财务大写金额。"
        };
    }

    return { error: "不支持的 mode: " + args.mode + "（可用 thousands/fixed/scientific/percent/rmb）" };
}

/**
 * Muse 插件：数学工具箱
 *
 * 运行契约（宿主 WebViewSkillEngine）：
 *  - 每个工具函数接收一个参数对象，如 function mathEval(args)，args.expr；
 *  - 返回值是普通对象，宿主会自动 JSON 序列化后交给模型；
 *  - 出错时返回 { error: "原因" }，宿主会把这类结果标记为失败。
 *
 * 本插件是纯本地计算：不联网、不读写文件、不做动态代码求值。
 * 表达式求值由自带的词法分析 + 递归下降解析器完成，只允许白名单内的运算符、常量与函数。
 * 大整数进制转换用逐位字符串运算实现（不依赖 BigInt，兼容旧 WebView）。
 */

/** 输入上限：避免异常输入在 10s 的 JS 沙盒超时内拖垮 WebView。 */
var MATH_MAX_EXPRESSION_LENGTH = 2000;
var MATH_MAX_LIST_LENGTH = 1000;
var MATH_MAX_BASE_INPUT_LENGTH = 2048;
var MATH_MAX_FACTORIZE = 1000000000000; /* 1e12，试除法在此规模内可控 */
var MATH_SAFE_INTEGER = 9007199254740991;

var MATH_BASE_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz";
var MATH_PRECEDENCE = { "+": 1, "-": 1, "*": 2, "/": 2, "%": 2, "^": 4 };
var MATH_NUMERIC_PATTERN = /^[+-]?(?:\d+\.?\d*|\.\d+)(?:[eE][+-]?\d+)?$/;

/** 内置常量。 */
var MATH_CONSTANTS = {
    pi: Math.PI,
    e: Math.E,
    tau: Math.PI * 2,
    phi: (1 + Math.sqrt(5)) / 2
};

/** 内置函数（全部是纯函数；参数非法导致的 NaN 会在求值阶段被拦截）。 */
var MATH_FUNCTIONS = {
    abs: Math.abs,
    sqrt: Math.sqrt,
    cbrt: Math.cbrt,
    floor: Math.floor,
    ceil: Math.ceil,
    round: Math.round,
    trunc: Math.trunc,
    sign: Math.sign,
    ln: Math.log,
    log: Math.log10,
    log10: Math.log10,
    log2: Math.log2,
    exp: Math.exp,
    sin: Math.sin,
    cos: Math.cos,
    tan: Math.tan,
    pow: Math.pow,
    min: Math.min,
    max: Math.max,
    hypot: Math.hypot
};

/** 名称列表（错误提示里给模型可用清单）。 */
function mathNamesOf(table) {
    var names = [];
    for (var key in table) {
        if (Object.prototype.hasOwnProperty.call(table, key)) names.push(key);
    }
    return names.sort();
}

/* ────────────────────────────── 表达式求值 ────────────────────────────── */

/** 把中文/全角输入规范化成 ASCII 数学写法，顺手把 ** 视作幂运算符。 */
function mathNormalizeText(text) {
    var out = "";
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch >= "０" && ch <= "９") out += String.fromCharCode(ch.charCodeAt(0) - 0xfee0);
        else if (ch === "（") out += "(";
        else if (ch === "）") out += ")";
        else if (ch === "×" || ch === "·" || ch === "＊") out += "*";
        else if (ch === "÷" || ch === "／") out += "/";
        else if (ch === "－" || ch === "−" || ch === "—") out += "-";
        else if (ch === "＋") out += "+";
        else if (ch === "％") out += "%";
        else if (ch === "＾") out += "^";
        else if (ch === "，") out += ",";
        else if (ch === "．") out += ".";
        else if (ch === "\u00a0" || ch === "\u3000" || ch === "\u200b") out += " ";
        else out += ch;
    }
    return out;
}

/** 词法分析：数字（含小数、科学计数法）、名称、运算符、括号、逗号。 */
function mathTokenize(text) {
    var tokens = [];
    var index = 0;
    while (index < text.length) {
        var ch = text.charAt(index);
        if (/\s/.test(ch)) {
            index++;
            continue;
        }
        if (ch === "(" || ch === ")" || ch === "+" || ch === "-" || ch === "/" ||
            ch === "%" || ch === "^" || ch === ",") {
            tokens.push({ type: "op", value: ch, pos: index });
            index++;
            continue;
        }
        if (ch === "*") {
            if (text.charAt(index + 1) === "*") {
                tokens.push({ type: "op", value: "^", pos: index });
                index += 2;
            } else {
                tokens.push({ type: "op", value: "*", pos: index });
                index++;
            }
            continue;
        }
        if (ch >= "0" && ch <= "9" || ch === ".") {
            var start = index;
            var dots = 0;
            while (index < text.length) {
                var digitCh = text.charAt(index);
                if (digitCh >= "0" && digitCh <= "9") {
                    index++;
                    continue;
                }
                if (digitCh === ".") {
                    if (dots > 0) return { error: "数字里出现了多个小数点（位置 " + (index + 1) + "）" };
                    dots++;
                    index++;
                    continue;
                }
                break;
            }
            var literal = text.slice(start, index);
            if (literal === ".") return { error: "单独的小数点不是合法数字（位置 " + (start + 1) + "）" };
            var expCh = text.charAt(index);
            if (expCh === "e" || expCh === "E") {
                var probe = index + 1;
                var expSign = "";
                if (text.charAt(probe) === "+" || text.charAt(probe) === "-") {
                    expSign = text.charAt(probe);
                    probe++;
                }
                var expDigits = 0;
                while (probe < text.length && text.charAt(probe) >= "0" && text.charAt(probe) <= "9") {
                    probe++;
                    expDigits++;
                }
                if (expDigits > 0) {
                    literal = literal + "e" + expSign + text.slice(index + 1 + expSign.length, probe);
                    index = probe;
                }
            }
            var value = parseFloat(literal);
            if (isNaN(value)) return { error: "无法解析数字: " + literal };
            tokens.push({ type: "number", value: value, text: literal, pos: start });
            continue;
        }
        if (/[A-Za-z_]/.test(ch)) {
            var nameStart = index;
            while (index < text.length && /[A-Za-z0-9_]/.test(text.charAt(index))) index++;
            tokens.push({ type: "name", value: text.slice(nameStart, index), pos: nameStart });
            continue;
        }
        return { error: "非法字符 '" + ch + "'（位置 " + (index + 1) + "）" };
    }
    return { tokens: tokens };
}

/**
 * 递归下降解析（返回 AST）：
 *   expression := term (('+' | '-') term)*
 *   term       := unary (('*' | '/' | '%') unary)*
 *   unary      := ('+' | '-') unary | power
 *   power      := primary ('^' unary)?         // 右结合，且 -2^2 = -(2^2)
 *   primary    := number | constant | name '(' args ')' | '(' expression ')'
 */
function mathParse(tokens) {
    var pos = 0;
    var openParens = 0;

    function peek() {
        return pos < tokens.length ? tokens[pos] : null;
    }

    function parseExpression() {
        var left = parseTerm();
        if (left.error) return left;
        var node = left.node;
        while (true) {
            var token = peek();
            if (!token || token.type !== "op" || (token.value !== "+" && token.value !== "-")) break;
            pos++;
            var right = parseTerm();
            if (right.error) return right;
            node = { kind: "bin", op: token.value, left: node, right: right.node };
        }
        return { node: node };
    }

    function parseTerm() {
        var left = parseUnary();
        if (left.error) return left;
        var node = left.node;
        while (true) {
            var token = peek();
            if (!token || token.type !== "op" || (token.value !== "*" && token.value !== "/" && token.value !== "%")) break;
            pos++;
            var right = parseUnary();
            if (right.error) return right;
            node = { kind: "bin", op: token.value, left: node, right: right.node };
        }
        return { node: node };
    }

    function parseUnary() {
        var token = peek();
        if (token && token.type === "op" && (token.value === "-" || token.value === "+")) {
            pos++;
            var operand = parseUnary();
            if (operand.error) return operand;
            if (token.value === "-") return { node: { kind: "unary", op: "-", operand: operand.node } };
            return { node: operand.node };
        }
        return parsePower();
    }

    function parsePower() {
        var base = parsePrimary();
        if (base.error) return base;
        var token = peek();
        if (token && token.type === "op" && token.value === "^") {
            pos++;
            var exponent = parseUnary();
            if (exponent.error) return exponent;
            return { node: { kind: "bin", op: "^", left: base.node, right: exponent.node } };
        }
        return { node: base.node };
    }

    function parsePrimary() {
        var token = peek();
        if (!token) {
            if (openParens > 0) return { error: "括号不匹配：缺少 " + openParens + " 个右括号 ')'" };
            return { error: "表达式不完整：末尾缺少数字或括号" };
        }
        if (token.type === "number") {
            pos++;
            return { node: { kind: "num", value: token.value, text: token.text } };
        }
        if (token.type === "op" && token.value === "(") {
            pos++;
            openParens++;
            var inner = parseExpression();
            if (inner.error) return inner;
            var closing = peek();
            if (!closing || closing.type !== "op" || closing.value !== ")") {
                return { error: "括号不匹配：缺少右括号 ')'（对应位置 " + (token.pos + 1) + " 的左括号）" };
            }
            pos++;
            openParens--;
            return { node: inner.node };
        }
        if (token.type === "name") {
            pos++;
            var lower = token.value.toLowerCase();
            var after = peek();
            var isCall = after && after.type === "op" && after.value === "(";
            if (isCall) {
                if (!Object.prototype.hasOwnProperty.call(MATH_FUNCTIONS, lower)) {
                    return { error: "未知函数: " + token.value + "（可用函数: " + mathNamesOf(MATH_FUNCTIONS).join(", ") + "）" };
                }
                pos++;
                openParens++;
                var callArgs = [];
                var immediateClose = peek();
                if (immediateClose && immediateClose.type === "op" && immediateClose.value === ")") {
                    pos++;
                    openParens--;
                } else {
                    while (true) {
                        var arg = parseExpression();
                        if (arg.error) return arg;
                        callArgs.push(arg.node);
                        var separator = peek();
                        if (!separator) return { error: "括号不匹配：函数 " + lower + " 缺少右括号 ')'" };
                        if (separator.type === "op" && separator.value === ",") {
                            pos++;
                            continue;
                        }
                        if (separator.type === "op" && separator.value === ")") {
                            pos++;
                            openParens--;
                            break;
                        }
                        return { error: "函数 " + lower + " 的参数之间需要逗号分隔" };
                    }
                }
                return { node: { kind: "call", name: lower, args: callArgs } };
            }
            if (Object.prototype.hasOwnProperty.call(MATH_CONSTANTS, lower)) {
                return { node: { kind: "const", name: lower } };
            }
            return { error: "未知名称: " + token.value + "（可用常量: " + mathNamesOf(MATH_CONSTANTS).join(", ") + "）" };
        }
        return { error: "无法解析的内容: " + token.value + "（位置 " + (token.pos + 1) + "）" };
    }

    var parsed = parseExpression();
    if (parsed.error) return parsed;
    if (pos < tokens.length) {
        var extra = tokens[pos];
        if (extra.type === "op" && extra.value === ")") {
            return { error: "括号不匹配：多了一个右括号 ')'（位置 " + (extra.pos + 1) + "）" };
        }
        return { error: "表达式含多余内容: '" + (extra.text !== undefined ? extra.text : extra.value) + "'（位置 " + (extra.pos + 1) + "）" };
    }
    return parsed;
}

/** 求值：只接受有限数；除零、定义域外、溢出都转成明确错误。 */
function mathEvaluate(node) {
    if (node.kind === "num") return { value: node.value };
    if (node.kind === "const") return { value: MATH_CONSTANTS[node.name] };
    if (node.kind === "unary") {
        var operand = mathEvaluate(node.operand);
        if (operand.error) return operand;
        return mathCheckValue(-operand.value, "一元负号的结果");
    }
    if (node.kind === "call") {
        var values = [];
        for (var i = 0; i < node.args.length; i++) {
            var arg = mathEvaluate(node.args[i]);
            if (arg.error) return arg;
            values.push(arg.value);
        }
        var outcome;
        try {
            outcome = MATH_FUNCTIONS[node.name].apply(null, values);
        } catch (e) {
            return { error: "函数 " + node.name + " 调用失败: " + (e && e.message ? e.message : e) };
        }
        return mathCheckValue(outcome, "函数 " + node.name + " 的结果");
    }
    if (node.kind === "bin") {
        var left = mathEvaluate(node.left);
        if (left.error) return left;
        var right = mathEvaluate(node.right);
        if (right.error) return right;
        var a = left.value;
        var b = right.value;
        var value;
        if (node.op === "+") value = a + b;
        else if (node.op === "-") value = a - b;
        else if (node.op === "*") value = a * b;
        else if (node.op === "/") {
            if (b === 0) return { error: "除以零：除数不能为 0" };
            value = a / b;
        } else if (node.op === "%") {
            if (b === 0) return { error: "取模运算的除数不能为 0" };
            value = a % b;
        } else if (node.op === "^") {
            value = Math.pow(a, b);
        } else {
            return { error: "未知运算符: " + node.op };
        }
        return mathCheckValue(value, "运算符 " + node.op + " 的结果");
    }
    return { error: "表达式结构异常" };
}

function mathCheckValue(value, label) {
    if (typeof value !== "number" || isNaN(value)) return { error: label + "不是有效数字（可能超出定义域）" };
    if (!isFinite(value)) return { error: label + "溢出（超出数值范围）" };
    return { value: value };
}

/** 规范化输出：结构标准化 + 去掉多余括号，字面量保持原样。 */
function mathRender(node, minPrecedence) {
    var text;
    var precedence;
    if (node.kind === "num") {
        text = node.text;
        precedence = 5;
    } else if (node.kind === "const") {
        text = node.name;
        precedence = 5;
    } else if (node.kind === "unary") {
        text = "-" + mathRender(node.operand, 3);
        precedence = 3;
    } else if (node.kind === "call") {
        var rendered = [];
        for (var i = 0; i < node.args.length; i++) rendered.push(mathRender(node.args[i], 0));
        text = node.name + "(" + rendered.join(", ") + ")";
        precedence = 5;
    } else if (node.kind === "bin") {
        precedence = MATH_PRECEDENCE[node.op];
        var leftMin = node.op === "^" ? precedence + 1 : precedence;
        var rightMin = node.op === "^" ? precedence : precedence + 1;
        var leftText = mathRender(node.left, leftMin);
        var rightText = mathRender(node.right, rightMin);
        text = node.op === "^" ? leftText + "^" + rightText : leftText + " " + node.op + " " + rightText;
    } else {
        return "?";
    }
    if (precedence < minPrecedence) return "(" + text + ")";
    return text;
}

function mathEval(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var expr = args.expr;
    if (typeof expr !== "string") return { error: "expr 必须是字符串" };
    var trimmed = expr.trim();
    if (trimmed.length === 0) return { error: "expr 不能为空" };
    if (trimmed.length > MATH_MAX_EXPRESSION_LENGTH) {
        return { error: "expr 过长（上限 " + MATH_MAX_EXPRESSION_LENGTH + " 字符）" };
    }
    var lexed = mathTokenize(mathNormalizeText(trimmed));
    if (lexed.error) return { error: lexed.error };
    if (lexed.tokens.length === 0) return { error: "expr 中没有可解析的内容" };
    var parsed = mathParse(lexed.tokens);
    if (parsed.error) return { error: parsed.error };
    var evaluated = mathEvaluate(parsed.node);
    if (evaluated.error) return { error: evaluated.error };
    return {
        result: evaluated.value,
        expression: mathRender(parsed.node, 0),
        note: "支持 + - * / % ^（或 **）、括号、一元正负号、小数与科学计数法；常量 " +
            mathNamesOf(MATH_CONSTANTS).join("/") + "；函数 " + mathNamesOf(MATH_FUNCTIONS).join("/") + "。"
    };
}

/* ────────────────────────────── 进制转换 ────────────────────────────── */

/** 任意进制数字串 → 十进制数字串（逐位乘加，支持任意长度整数）。 */
function mathBaseToDecimal(digitValues, base) {
    var decimal = [0];
    for (var i = 0; i < digitValues.length; i++) {
        var carry = digitValues[i];
        for (var j = decimal.length - 1; j >= 0; j--) {
            var current = decimal[j] * base + carry;
            decimal[j] = current % 10;
            carry = (current - decimal[j]) / 10;
        }
        while (carry > 0) {
            decimal.unshift(carry % 10);
            carry = (carry - (carry % 10)) / 10;
        }
    }
    var text = decimal.join("");
    text = text.replace(/^0+(?=\d)/, "");
    return text;
}

/** 十进制数字串 → 目标进制数字串（逐位短除法）。 */
function mathDecimalToBase(decimalText, base) {
    if (decimalText === "0" || decimalText === "") return "0";
    var digits = [];
    for (var i = 0; i < decimalText.length; i++) digits.push(decimalText.charCodeAt(i) - 48);
    var out = [];
    var start = 0;
    while (start < digits.length) {
        var remainder = 0;
        for (var j = start; j < digits.length; j++) {
            var current = remainder * 10 + digits[j];
            digits[j] = Math.floor(current / base);
            remainder = current - digits[j] * base;
        }
        out.push(MATH_BASE_DIGITS.charAt(remainder));
        while (start < digits.length && digits[start] === 0) start++;
    }
    out.reverse();
    return out.join("");
}

function baseConvert(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var raw = args.value;
    if (raw === undefined || raw === null || raw === "") return { error: "value 不能为空" };
    if (typeof raw === "number") {
        if (isNaN(raw) || !isFinite(raw) || raw % 1 !== 0) {
            return { error: "value 为数字时必须是有限整数，超大整数请用字符串传入" };
        }
        raw = String(raw);
    }
    if (typeof raw !== "string") return { error: "value 必须是字符串或整数" };

    var fromParsed = mathParseBase(args.fromBase, "fromBase");
    if (fromParsed.error) return { error: fromParsed.error };
    var toParsed = mathParseBase(args.toBase, "toBase");
    if (toParsed.error) return { error: toParsed.error };

    var text = raw.trim().replace(/[\s_]/g, "");
    var negative = false;
    if (text.charAt(0) === "-") {
        negative = true;
        text = text.slice(1);
    } else if (text.charAt(0) === "+") {
        text = text.slice(1);
    }
    if (text.length === 0) return { error: "value 没有有效数字" };
    if (text.length > MATH_MAX_BASE_INPUT_LENGTH) {
        return { error: "value 过长（上限 " + MATH_MAX_BASE_INPUT_LENGTH + " 位）" };
    }
    var lower = text.toLowerCase();
    if (fromParsed.value === 16 && lower.indexOf("0x") === 0) lower = lower.slice(2);
    else if (fromParsed.value === 2 && lower.indexOf("0b") === 0) lower = lower.slice(2);
    else if (fromParsed.value === 8 && lower.indexOf("0o") === 0) lower = lower.slice(2);
    if (lower.length === 0) return { error: "value 没有有效数字" };
    if (lower.indexOf(".") >= 0) return { error: "暂不支持小数转换，请只传入整数" };

    var digitValues = [];
    for (var i = 0; i < lower.length; i++) {
        var value = MATH_BASE_DIGITS.indexOf(lower.charAt(i));
        if (value < 0) return { error: "非法字符 '" + text.charAt(i) + "'（" + fromParsed.value + " 进制只允许 0-9 与 a-z）" };
        if (value >= fromParsed.value) {
            return { error: "数字 '" + text.charAt(i) + "' 超出 " + fromParsed.value + " 进制的范围（最大数字 " + MATH_BASE_DIGITS.charAt(fromParsed.value - 1) + "）" };
        }
        digitValues.push(value);
    }

    var decimalText = mathBaseToDecimal(digitValues, fromParsed.value);
    var converted = mathDecimalToBase(decimalText, toParsed.value).toUpperCase();
    return {
        result: (negative ? "-" : "") + converted,
        decimal: (negative ? "-" : "") + decimalText,
        fromBase: fromParsed.value,
        toBase: toParsed.value,
        digits: converted.length,
        negative: negative,
        note: "输出用大写字母；输入不区分大小写，支持 0x/0b/0o 前缀与任意长度整数（不含小数）。"
    };
}

function mathParseBase(raw, label) {
    var value = raw;
    if (typeof value === "string") {
        if (!/^\s*[+-]?\d+\s*$/.test(value)) return { error: label + " 必须是整数" };
        value = parseInt(value, 10);
    }
    if (typeof value !== "number" || isNaN(value) || value % 1 !== 0) {
        return { error: label + " 必须是整数" };
    }
    if (value < 2 || value > 36) return { error: label + " 必须在 2..36 之间（当前 " + value + "）" };
    return { value: value };
}

/* ────────────────────────────── 数论 ────────────────────────────── */

/** 把「数字数组 / 逗号或空白分隔的字符串」解析为数字列表。 */
function mathParseNumberList(raw, label) {
    var items;
    if (raw === undefined || raw === null) return { error: label + " 不能为空" };
    if (typeof raw === "number") items = [raw];
    else if (Array.isArray(raw)) items = raw.slice();
    else if (typeof raw === "string") {
        var text = raw.trim();
        if (text.length === 0) return { error: label + " 不能为空字符串" };
        items = text.split(/[\s,，;；、]+/).filter(function (token) { return token.length > 0; });
    } else {
        return { error: label + " 必须是数字数组或分隔符字符串" };
    }
    if (items.length === 0) return { error: label + " 至少要有一个数字" };
    if (items.length > MATH_MAX_LIST_LENGTH) {
        return { error: label + " 元素过多（上限 " + MATH_MAX_LIST_LENGTH + " 个）" };
    }
    var values = [];
    for (var i = 0; i < items.length; i++) {
        var item = items[i];
        if (typeof item === "number") {
            if (isNaN(item) || !isFinite(item)) return { error: "第 " + (i + 1) + " 个数字不是有限数" };
            values.push(item);
            continue;
        }
        if (typeof item !== "string" || !MATH_NUMERIC_PATTERN.test(item.trim())) {
            return { error: "第 " + (i + 1) + " 项不是合法数字: " + String(item) };
        }
        var parsed = parseFloat(item);
        if (isNaN(parsed) || !isFinite(parsed)) return { error: "第 " + (i + 1) + " 个数字超出范围" };
        values.push(parsed);
    }
    return { values: values };
}

/** 把数字列表收紧为整数列表。 */
function mathParseIntegerList(raw, label) {
    var parsed = mathParseNumberList(raw, label);
    if (parsed.error) return parsed;
    for (var i = 0; i < parsed.values.length; i++) {
        if (parsed.values[i] % 1 !== 0) {
            return { error: "第 " + (i + 1) + " 个数字不是整数: " + parsed.values[i] };
        }
        if (Math.abs(parsed.values[i]) > MATH_SAFE_INTEGER) {
            return { error: "第 " + (i + 1) + " 个整数超出安全范围（|n| ≤ 2^53-1）" };
        }
    }
    return parsed;
}

function mathGcdPair(a, b) {
    a = Math.abs(a);
    b = Math.abs(b);
    while (b !== 0) {
        var rest = a % b;
        a = b;
        b = rest;
    }
    return a;
}

/** 最小公倍数；溢出安全整数范围时返回 NaN。 */
function mathLcmPair(a, b) {
    if (a === 0 || b === 0) return 0;
    var gcd = mathGcdPair(a, b);
    var result = Math.abs((a / gcd) * b);
    if (!isFinite(result) || result > MATH_SAFE_INTEGER) return NaN;
    return result;
}

function mathIsPrime(n) {
    if (n < 2) return false;
    if (n === 2 || n === 3) return true;
    if (n % 2 === 0 || n % 3 === 0) return false;
    var step = 4;
    for (var i = 5; i * i <= n; i += step) {
        if (n % i === 0) return false;
        step = 6 - step;
    }
    return true;
}

/** 质因数分解（试除法，返回 [{prime, exponent}]）。 */
function mathFactorize(n) {
    var factors = [];
    var remaining = n;
    var divisor = 2;
    while (divisor * divisor <= remaining) {
        if (remaining % divisor !== 0) {
            divisor = divisor === 2 ? 3 : divisor + 2;
            continue;
        }
        var exponent = 0;
        while (remaining % divisor === 0) {
            remaining = remaining / divisor;
            exponent++;
        }
        factors.push({ prime: divisor, exponent: exponent });
    }
    if (remaining > 1) factors.push({ prime: remaining, exponent: 1 });
    return factors;
}

function numberTheory(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var mode = typeof args.mode === "string" ? args.mode.trim().toLowerCase() : "";
    if (mode.length === 0) return { error: "mode 不能为空，必须是 gcd/lcm/factorize/is_prime 之一" };
    var parsed = mathParseIntegerList(args.numbers, "numbers");
    if (parsed.error) return { error: parsed.error };
    var numbers = parsed.values;

    if (mode === "gcd" || mode === "lcm") {
        var accumulator = Math.abs(numbers[0]);
        for (var i = 1; i < numbers.length; i++) {
            if (mode === "gcd") {
                accumulator = mathGcdPair(accumulator, numbers[i]);
            } else {
                accumulator = mathLcmPair(accumulator, numbers[i]);
                if (isNaN(accumulator)) {
                    return { error: "最小公倍数超出安全整数范围（2^53-1），请减少数量或改用更小的数字" };
                }
            }
        }
        return {
            result: accumulator,
            mode: mode,
            numbers: numbers,
            note: mode === "gcd" ? "结果为非负最大公约数；gcd(0, 0) = 0。" : "结果为非负最小公倍数；任一数为 0 时结果为 0。"
        };
    }

    if (mode === "factorize" || mode === "is_prime") {
        if (numbers.length !== 1) {
            return { error: mode + " 只接受一个整数（当前收到 " + numbers.length + " 个）" };
        }
        var target = numbers[0];
        if (Math.abs(target) > MATH_MAX_FACTORIZE) {
            return { error: "数值过大（上限 " + MATH_MAX_FACTORIZE + "），请换用更小的整数" };
        }
        if (target < 0) return { error: "只支持非负整数（当前 " + target + "）" };
        if (mode === "is_prime") {
            var prime = mathIsPrime(target);
            return {
                result: prime,
                mode: "is_prime",
                number: target,
                divisors: prime ? [1, target] : mathSmallDivisors(target),
                note: "判定使用试除法，结果确定。"
            };
        }
        if (target === 0 || target === 1) {
            return {
                result: [],
                factors: [],
                expression: String(target),
                isPrime: false,
                number: target,
                note: "0 与 1 没有质因数分解。"
            };
        }
        var factors = mathFactorize(target);
        var flat = [];
        var parts = [];
        for (var j = 0; j < factors.length; j++) {
            parts.push(factors[j].exponent === 1 ? String(factors[j].prime) : factors[j].prime + "^" + factors[j].exponent);
            for (var k = 0; k < factors[j].exponent; k++) flat.push(factors[j].prime);
        }
        return {
            result: factors,
            factors: flat,
            expression: parts.join(" × "),
            isPrime: factors.length === 1 && factors[0].exponent === 1,
            number: target
        };
    }

    return { error: "不支持的 mode: " + args.mode + "（可用 gcd/lcm/factorize/is_prime）" };
}

/** 小整数的全部正因数（仅用于 is_prime=false 时的提示，规模受 MATH_MAX_FACTORIZE 限制）。 */
function mathSmallDivisors(n) {
    var divisors = [];
    if (n < 1) return divisors;
    for (var i = 1; i * i <= n; i++) {
        if (n % i !== 0) continue;
        divisors.push(i);
        if (i !== n / i) divisors.push(n / i);
    }
    divisors.sort(function (a, b) { return a - b; });
    return divisors.length > 64 ? divisors.slice(0, 64) : divisors;
}

/* ────────────────────────────── 统计 ────────────────────────────── */

function stats(args) {
    if (!args || typeof args !== "object") return { error: "参数必须是对象" };
    var parsed = mathParseNumberList(args.numbers, "numbers");
    if (parsed.error) return { error: parsed.error };
    var values = parsed.values.slice();
    var count = values.length;

    var sum = 0;
    var min = values[0];
    var max = values[0];
    for (var i = 0; i < count; i++) {
        sum += values[i];
        if (values[i] < min) min = values[i];
        if (values[i] > max) max = values[i];
    }
    var mean = sum / count;

    var sorted = values.slice().sort(function (a, b) { return a - b; });
    var middle = Math.floor(count / 2);
    var median = count % 2 === 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;

    var squared = 0;
    for (var j = 0; j < count; j++) {
        var diff = values[j] - mean;
        squared += diff * diff;
    }
    var variance = squared / count;
    var sampleVariance = count > 1 ? squared / (count - 1) : 0;

    return {
        result: {
            count: count,
            sum: sum,
            mean: mean,
            median: median,
            min: min,
            max: max,
            range: max - min,
            stddev: Math.sqrt(variance),
            variance: variance,
            sampleStddev: Math.sqrt(sampleVariance)
        },
        note: "stddev/variance 为总体标准差与方差（除以 n）；sampleStddev 为样本标准差（除以 n-1）；median 为排序后中位数。"
    };
}

/**
 * 示例 skillpkg — 计算器工具
 *
 * 演示 .skillpkg 格式的主入口文件。
 * 不需要 METADATA 注释块（manifest.json 已声明元数据）。
 */

/**
 * 执行数学表达式计算。
 * @param {Object} args - 参数对象
 * @param {string} args.expr - 数学表达式
 * @returns {string} 计算结果的 JSON 字符串
 */
function calculate(args) {
    var expr = args.expr;
    // 安全检查：只允许数字、运算符、括号、小数点、Math 函数
    if (!/^[\d+\-*/%.()\s]|Math\./.test(expr)) {
        return JSON.stringify({ error: "表达式包含非法字符" });
    }
    try {
        var result = eval(expr);
        return JSON.stringify({ result: result });
    } catch (e) {
        return JSON.stringify({ error: e.message });
    }
}

/**
 * 格式化数字为指定小数位数。
 * @param {Object} args - 参数对象
 * @param {number} args.value - 要格式化的数字
 * @param {number} args.digits - 小数位数（默认 2）
 * @returns {string} 格式化结果的 JSON 字符串
 */
function formatNumber(args) {
    var value = args.value;
    var digits = args.digits !== undefined ? args.digits : 2;
    if (typeof value !== "number" || isNaN(value)) {
        return JSON.stringify({ error: "value 必须是有效数字" });
    }
    var formatted = Number(value).toFixed(digits);
    return JSON.stringify({ result: formatted });
}

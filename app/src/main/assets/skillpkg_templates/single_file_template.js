/**
 * @skillpkg
 * @id example_single_file
 * @name 单文件示例工具
 * @version 1.0.0
 * @author Muse
 * @description 演示单文件 JS Skill（通过 METADATA 注释块声明，无需打包成 .skillpkg）
 * @entry single_file_template.js
 * @tool text_stats 统计文本的字符数、单词数、行数 ["text"]
 * @tool reverse_text 反转文本 ["text"]
 */

/**
 * 统计文本的字符数、单词数、行数。
 * @param {Object} args - 参数对象
 * @param {string} args.text - 要统计的文本
 * @returns {string} 统计结果的 JSON 字符串
 */
function text_stats(args) {
    var text = args.text || "";
    var chars = text.length;
    var words = text.trim().split(/\s+/).filter(function(w) { return w.length > 0; }).length;
    var lines = text.split("\n").length;
    return JSON.stringify({ chars: chars, words: words, lines: lines });
}

/**
 * 反转文本。
 * @param {Object} args - 参数对象
 * @param {string} args.text - 要反转的文本
 * @returns {string} 反转结果的 JSON 字符串
 */
function reverse_text(args) {
    var text = args.text || "";
    var reversed = text.split("").reverse().join("");
    return JSON.stringify({ result: reversed });
}

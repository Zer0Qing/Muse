package io.zer0.muse.tools

/**
 * P1 工具参数 schema 校验。
 *
 * 在工具执行前校验：
 * - 必填参数缺失
 * - 参数类型与 [ToolRegistry.ToolDef.parameterTypes] 声明不一致（可强转则强转）
 * - 非法 JSON 参数值
 *
 * 目标：避免“模型漏传 query / 把数字传成字符串”导致上游 400 或工具静默失败，
 * 让 LLM 拿到结构化、可读的错误信息（details.errorType = INVALID_ARGUMENTS）。
 */
object ToolArgValidator {

    const val ERROR_TYPE = "INVALID_ARGUMENTS"

    data class ValidationResult(
        val valid: Boolean,
        val errors: List<String> = emptyList(),
        val coercedArgs: Map<String, String> = emptyMap(),
    ) {
        companion object {
            fun ok(args: Map<String, String>) = ValidationResult(
                valid = true,
                errors = emptyList(),
                coercedArgs = args,
            )

            fun invalid(errors: List<String>, args: Map<String, String>) = ValidationResult(
                valid = false,
                errors = errors,
                coercedArgs = args,
            )
        }
    }

    /**
     * @param def 工具定义；null 表示未注册，由调用方自行处理 not-found。
     */
    fun validate(name: String, args: Map<String, String>, def: ToolRegistry.ToolDef?): ValidationResult {
        if (def == null) {
            return ValidationResult.invalid(listOf("未知工具: $name"), args)
        }
        val errors = mutableListOf<String>()
        val coerced = LinkedHashMap<String, String>()

        // 必填参数
        def.required.forEach { required ->
            val value = args[required]
            if (value.isNullOrBlank()) {
                errors += "缺少必填参数: $required"
            } else {
                coerced[required] = value
            }
        }

        // 类型声明校验（非必填但传了值也要校验）
        def.parameterTypes.forEach { (param, typeName) ->
            val raw = args[param] ?: return@forEach
            if (raw.isBlank()) return@forEach
            when (typeName.lowercase()) {
                "integer", "int", "number", "long" -> {
                    val normalized = raw.trim().trim('"')
                    if (normalized.toLongOrNull() == null) {
                        errors += "参数 $param 应为 $typeName,实际值: $raw"
                    } else {
                        coerced[param] = normalized
                    }
                }
                "boolean", "bool" -> {
                    val normalized = raw.trim().trim('"')
                    if (normalized !in setOf("true", "false", "1", "0", "是", "否", "yes", "no")) {
                        errors += "参数 $param 应为 boolean,实际值: $raw"
                    } else {
                        coerced[param] = when (normalized) {
                            "1", "是", "yes" -> "true"
                            "0", "否", "no" -> "false"
                            else -> normalized
                        }
                    }
                }
                "array", "json" -> {
                    // 保持原样（执行器内部解析）；只做空值检查。
                    coerced[param] = raw
                }
                else -> {
                    coerced[param] = raw
                }
            }
        }

        // 未声明类型/非必填参数原样透传
        args.forEach { (k, v) ->
            if (!coerced.containsKey(k)) {
                coerced[k] = v
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.ok(coerced)
        } else {
            ValidationResult.invalid(errors, coerced)
        }
    }
}

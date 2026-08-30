package com.OKK.yes.core.hooks.plugins

object ScriptSyntaxCompat {
    /**
     * 将现代 Java 语法（泛型、Lambda 等）自动转译为 BeanShell 兼容的 AST 语法
     */
    fun transpile(code: String): String {
        var s = code

        // 1. 去除泛型声明和类型参数, e.g. HashMap<String, String>() -> HashMap(), Map<String, Object> -> Map, ArrayList<>() -> ArrayList()
        var prev: String
        do {
            prev = s
            s = s.replace(Regex("<[A-Za-z0-9_?,\\\\s\\[\\].*@]*>"), "")
        } while (s != prev)

        // 移除 BeanShell 不支持的 final 关键字
        s = s.replace(Regex("\\bfinal\\s+"), "")

        // 自动补充 var 变量定义行末尾遗漏的分号
        s = s.replace(Regex("""(\bvar\s+[A-Za-z0-9_$]+\\s*=[^\r\n;]+)""")) {
            "${it.groupValues[1]};"
        }

        // 2. 转换 Lambda 表达式为 BeanShell 接口匿名类对象 (跳过字符串常量内部)
        s = convertLambdas(s)

        return s
    }

    private fun convertLambdas(source: String): String {
        val sb = StringBuilder()
        var i = 0
        val len = source.length
        var inString = false
        var inChar = false

        while (i < len) {
            val c = source[i]
            if (c == '"' && (i == 0 || source[i - 1] != '\\')) {
                inString = !inString
                sb.append(c)
                i++
                continue
            }
            if (c == '\'' && (i == 0 || source[i - 1] != '\\')) {
                inChar = !inChar
                sb.append(c)
                i++
                continue
            }
            if (inString || inChar) {
                sb.append(c)
                i++
                continue
            }

            if (c == '-' && i + 1 < len && source[i + 1] == '>') {
                val arrowIdx = i

                // 寻找箭头前面的参数定义
                var paramStart = arrowIdx - 1
                while (paramStart >= 0 && source[paramStart].isWhitespace()) {
                    paramStart--
                }

                val paramEnd = paramStart + 1
                var paramName = "it"

                if (paramStart >= 0 && source[paramStart] == ')') {
                    var depth = 1
                    var scan = paramStart - 1
                    while (scan >= 0 && depth > 0) {
                        if (source[scan] == ')') depth++
                        else if (source[scan] == '(') depth--
                        scan--
                    }
                    paramStart = scan + 1
                    val rawParams = source.substring(paramStart + 1, paramEnd - 1).trim()
                    paramName = rawParams.substringAfterLast(' ').substringAfterLast(',').trim()
                    if (paramName.isBlank()) paramName = "it"
                } else {
                    var scan = paramStart
                    while (scan >= 0 && (source[scan].isJavaIdentifierPart() || source[scan] == '$')) {
                        scan--
                    }
                    paramStart = scan + 1
                    paramName = source.substring(paramStart, paramEnd).trim()
                }

                // 寻找箭头后面的主体
                var bodyStart = arrowIdx + 2
                while (bodyStart < len && source[bodyStart].isWhitespace()) {
                    bodyStart++
                }

                var bodyEnd = bodyStart
                if (bodyStart < len && source[bodyStart] == '{') {
                    var depth = 1
                    bodyEnd = bodyStart + 1
                    while (bodyEnd < len && depth > 0) {
                        if (source[bodyEnd] == '{') depth++
                        else if (source[bodyEnd] == '}') depth--
                        bodyEnd++
                    }
                } else {
                    while (bodyEnd < len && source[bodyEnd] != ';' && source[bodyEnd] != ')' && source[bodyEnd] != '\n' && source[bodyEnd] != ',') {
                        bodyEnd++
                    }
                }

                val lambdaBody = source.substring(bodyStart, bodyEnd).trim()
                // 回退 StringBuilder 中已经追加的 param 字符
                val prefixLength = sb.length - (arrowIdx - paramStart)
                if (prefixLength >= 0) {
                    sb.setLength(prefixLength)
                }

                if (lambdaBody.startsWith("{")) {
                    sb.append("new java.util.function.Consumer() { public void accept(Object $paramName) ").append(lambdaBody).append(" }")
                } else {
                    sb.append("new java.util.function.Consumer() { public void accept(Object $paramName) { ").append(lambdaBody).append("; } }")
                }

                i = bodyEnd
                continue
            }

            sb.append(c)
            i++
        }

        return sb.toString()
    }
}

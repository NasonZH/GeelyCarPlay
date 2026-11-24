package com.shilapi.xcertplay

/** Diagnostics describe state transitions; protocol payloads and credentials are never exported. */
internal object DiagnosticRedactor {
    /** 命中即整行丢弃 把原来两个正则合并成一次扫描 */
    private val dropped = Regex(
        "(?i)(pass(word|phrase)?|token|private.?key|certificate|pair.?record|ssid|" +
            "body=|payload=|hex=|(phone|device|peer|host)?name[=:])",
    )
    private val mac = Regex("(?i)(?<![0-9a-f])(?:[0-9a-f]{2}:){5}[0-9a-f]{2}(?![0-9a-f])")
    private val identifier = Regex("(?i)\\b[0-9a-f]{24,}\\b|\\b[0-9a-f]{8}-[0-9a-f-]{27,}\\b")
    private val address = Regex("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])")
    private val ipv6 = Regex("(?i)(?:[0-9a-f]{1,4}:)*[0-9a-f]{0,4}::[0-9a-f:]*(?:%[a-z0-9_.-]+)?|(?:[0-9a-f]{1,4}:){7}[0-9a-f]{1,4}")
    fun redact(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (dropped.containsMatchIn(line)) return null
        var redacted = line.replace(identifier, "[identifier]")
        // mac 地址 ipv4 和 ipv6 都必然含冒号或点 不含这些字符的行可跳过三次替换
        if (redacted.indexOf(':') >= 0 || redacted.indexOf('.') >= 0) {
            redacted = redacted.replace(mac, "[address]")
                .replace(address, "[ip]")
                .replace(ipv6, "[ip]")
        }
        return redacted.take(700)
    }
}

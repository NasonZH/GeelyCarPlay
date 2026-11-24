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

    /** 连接阶段同一行可能重复出现 缓存最近结果 上限很小 */
    private const val CACHE_LIMIT = 64
    private const val IDENTIFIER_MIN_LENGTH = 24
    private val cache = object : LinkedHashMap<String, String?>(CACHE_LIMIT, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean =
            size > CACHE_LIMIT
    }

    @Synchronized
    fun redact(line: String): String? {
        if (cache.containsKey(line)) return cache[line]
        val result = redactUncached(line)
        cache[line] = result
        return result
    }

    private fun redactUncached(line: String): String? {
        if (line.contains("TRACE ") || line.contains("PHONE ") || line.contains('\n') || line.contains('\r')) return null
        if (dropped.containsMatchIn(line)) return null
        var redacted = if (line.length >= IDENTIFIER_MIN_LENGTH) {
            line.replace(identifier, "[identifier]")
        } else {
            line
        }
        // 只有可能出现 MAC/IP 的行才跑三次替换 普通文本直接跳过
        if (needsAddressScan(redacted)) {
            redacted = redacted.replace(mac, "[address]")
                .replace(address, "[ip]")
                .replace(ipv6, "[ip]")
        }
        return redacted.take(700)
    }

    /** MAC 至少 5 个冒号 IPv4 至少 3 个点 压缩写法的 IPv6 必然含 :: */
    private fun needsAddressScan(text: String): Boolean {
        if (text.contains("::")) return true
        var colons = 0
        var dots = 0
        for (index in text.indices) {
            when (text[index]) {
                ':' -> colons++
                '.' -> dots++
            }
            if (colons >= 5 || dots >= 3) return true
        }
        return false
    }
}

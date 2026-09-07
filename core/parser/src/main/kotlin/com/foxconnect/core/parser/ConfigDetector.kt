package com.foxconnect.core.parser

import com.foxconnect.core.model.ProtocolType
import java.net.URI

object ConfigDetector {
    fun detect(raw: String): ParseResult<ProtocolType> {
        val text = raw.trim().removePrefix("\uFEFF")
        if (text.isEmpty()) return ParseResult.Error("empty", "متن کانفیگ خالی است")
        if (text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                ?.equals("[Interface]", ignoreCase = true) == true
        ) {
            return ParseResult.Success(ProtocolType.WIREGUARD)
        }
        val scheme = runCatching { URI(text).scheme }.getOrNull()
            ?: text.substringBefore("://", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        return ProtocolType.fromScheme(scheme)?.let { ParseResult.Success(it) }
            ?: ParseResult.Error(
                code = "unsupported_scheme",
                faMessage = "نوع کانفیگ پشتیبانی نمی‌شود",
                detail = scheme?.let { "scheme=$it" } ?: "scheme is missing",
            )
    }
}

package com.foxconnect.core.parser

sealed interface ParseResult<out T> {
    data class Success<T>(val value: T) : ParseResult<T>
    data class Error(val code: String, val faMessage: String, val detail: String? = null) : ParseResult<Nothing>
}

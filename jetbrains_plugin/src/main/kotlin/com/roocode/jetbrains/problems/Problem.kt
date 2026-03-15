package com.roocode.jetbrains.problems

import kotlinx.serialization.Serializable

@Serializable
data class Problem(
    val message: String,
    val severity: Int,
    val startLineNumber: Int,
    val startColumn: Int,
    val endLineNumber: Int,
    val endColumn: Int,
    val source: String? = null
)

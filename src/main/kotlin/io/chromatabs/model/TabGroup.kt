package io.chromatabs.model

data class TabGroup(
    val id: String,
    val name: String,
    val colorRgb: Int,
    val collapsed: Boolean = false,
    val order: Int = 0,
)

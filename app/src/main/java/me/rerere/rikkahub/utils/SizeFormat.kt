package me.rerere.rikkahub.utils

/** 把字节数格式化成 "3.2 MB" / "820 KB" / "512 B"，用于体积展示 */
fun Long.formatBytes(): String {
    if (this <= 0) return "0 B"
    if (this < 1024) return "$this B"
    val kb = this / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.1f GB".format(mb / 1024.0)
}

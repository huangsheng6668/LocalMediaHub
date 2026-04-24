package com.juziss.localmediahub.data

internal fun normalizeRoutePath(path: String): String {
    val normalized = path.trim().replace('\\', '/')
    val windowsAbsolutePath = Regex("^[A-Za-z]:/.*")
    return if (windowsAbsolutePath.matches(normalized)) {
        normalized
    } else {
        normalized.trimStart('/')
    }
}

package app.nya.password.core

/**
 * Idle auto-lock choices, the same as the other clients (`common/web/src/lib/autolock.ts`).
 * Minutes; 0 = never (only when the screen turns off, if that is on, or the app restarts).
 */
object AutoLock {
    const val DEFAULT = 10

    /** Longest custom value: 7 days. */
    const val MAX = 7 * 24 * 60
    val PRESETS = listOf(1, 5, 10, 15, 30, 60, 240, 0)

    fun valid(m: Int) = m in 0..MAX

    fun label(m: Int): String {
        if (m == 0) return "从不"
        if (m < 60) return "$m 分钟"
        val h = m / 60
        val r = m % 60
        if (r == 0 && h % 24 == 0) return "${h / 24} 天"
        return if (r == 0) "$h 小时" else "$h 小时 $r 分钟"
    }

    /** The presets plus the current value when it is a custom one, shortest first, "never" last. */
    fun options(current: Int): List<Int> =
        (PRESETS.filter { it > 0 } + listOfNotNull(current.takeIf { it > 0 })).distinct().sorted() + 0
}

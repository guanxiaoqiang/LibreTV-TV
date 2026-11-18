package com.libretv.tv.core

/**
 * 应用内统一的日志入口。
 *
 * 之前每个网络调用各自 `Log.d`，输出格式、标签与开关都不一致，出问题时无法
 * 拼接出一条完整的时间线。这里把「级别、标签、写入方式」收敛到一处：
 *
 * - [level] 由设置页控制，默认只留 INFO 及以上；
 * - [sink] 可替换，单元测试里换成空实现即可避免依赖 `android.util.Log`；
 * - 写入失败（单元测试中 `android.util.Log` 会抛 "not mocked"）不会影响业务。
 */
enum class LogLevel(val priority: Int) {
    OFF(0),
    ERROR(1),
    WARN(2),
    INFO(3),
    DEBUG(4);

    companion object {
        fun parse(raw: String?): LogLevel =
            values().firstOrNull { it.name == raw } ?: INFO
    }
}

/** 日志落地方式。 */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

object Logger {
    /** Logcat 标签长度上限（旧版本 Android 会截断超长标签）。 */
    private const val MAX_TAG_LENGTH = 23

    @Volatile
    var level: LogLevel = LogLevel.INFO

    @Volatile
    var sink: LogSink = LogcatSink

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message, null)

    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message, null)

    fun w(tag: String, message: String, error: Throwable? = null) =
        log(LogLevel.WARN, tag, message, error)

    fun e(tag: String, message: String, error: Throwable? = null) =
        log(LogLevel.ERROR, tag, message, error)

    fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        if (level == LogLevel.OFF) return
        if (level.priority > this.level.priority) return
        runCatching { sink.write(level, tag.take(MAX_TAG_LENGTH), message, error) }
    }
}

/** 默认落地到 Logcat。 */
private object LogcatSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.ERROR -> android.util.Log.e(tag, message, error)
            LogLevel.WARN -> android.util.Log.w(tag, message, error)
            LogLevel.INFO -> android.util.Log.i(tag, message)
            LogLevel.DEBUG -> android.util.Log.d(tag, message)
            LogLevel.OFF -> Unit
        }
    }
}

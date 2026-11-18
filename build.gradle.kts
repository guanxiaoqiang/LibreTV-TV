plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
    // KSP 版本前缀必须与 Kotlin 1.9.24 精确匹配（local-first-storage 1.1）。
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

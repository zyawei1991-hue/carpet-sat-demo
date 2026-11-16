plugins {
    base
    kotlin("jvm") version "1.9.24" apply false
    kotlin("android") version "1.9.24" apply false
    kotlin("plugin.serialization") version "1.9.24" apply false
    id("com.android.application") version "8.5.0" apply false
    // Room 的注解处理器走 KSP（对 Kotlin 1.9.24 的配套版本是 1.0.20）。版本对齐有讲究：
    // KSP 的前半段必须是 Kotlin 插件版本，错一位就是编译期找不到符号
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}

buildscript {
    dependencies {
        // AGP 9.4 内置 Kotlin 默认较旧；Compose 编译插件使用同版本 KGP。
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

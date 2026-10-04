plugins {
    id("com.android.application") version "9.4.1" apply false
    // Fija la versión de Kotlin que usa el Kotlin integrado de AGP 9.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
}

// Pinned versions (see README "Versions" for the sources they were checked against):
//   Gradle 9.7.0 (wrapper), AGP 9.3.1, Kotlin 2.4.21, kotlinx-serialization 1.13.0
// AGP 9 has built-in Kotlin, so the app module does not apply org.jetbrains.kotlin.android.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.21" apply false
}

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin { jvmToolchain(21) }

kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    implementation(libs.serialization)
    implementation(libs.commonmark)
    implementation(libs.snakeyaml)
    testImplementation(libs.junit)
}

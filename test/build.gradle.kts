plugins {
    id("java-library")
    alias(libs.plugins.kotlinJvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Gson for JSON parsing
    implementation(libs.gson)
    // Coroutines for async testing
    implementation(libs.kotlinx.coroutines.android)
}

// Compile the actual adapter mapping and actual enum, without Android classes or a copied implementation.
kotlin.sourceSets.named("test") {
    kotlin.srcDir("../app/src/main/java/net/ankio/auto/adapter/ezb")
    kotlin.srcDir("../server/src/main/java/org/ezbook/server/constant")
    kotlin.include("**/EzbMapping.kt", "**/BillType.kt", "**/EzbMappingTest.kt")
}

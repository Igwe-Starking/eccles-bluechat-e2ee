import org.gradle.api.tasks.Delete

plugins {
    alias(libs.plugins.android.application) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}

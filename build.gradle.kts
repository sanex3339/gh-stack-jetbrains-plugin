import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.sanex3339.ghstack"
version = "0.1.0"

kotlin {
    jvmToolchain(25)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val ideLocalPath = providers.gradleProperty("ideLocalPath").orNull
        if (ideLocalPath.isNullOrBlank()) {
            create(IntelliJPlatformType.WebStorm, "2026.2.1")
        } else {
            local(ideLocalPath)
        }
        bundledPlugin("Git4Idea")
        bundledPlugin("org.jetbrains.plugins.terminal")
        // Split platform modules Git4Idea's API exposes (GitRepository's supertypes, the diff viewer).
        bundledModule("intellij.platform.vcs.dvcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledModule("intellij.platform.vcs.impl")
        pluginVerifier()
        zipSigner()
    }
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            // CI verifies against JetBrains' recommended releases; locally, -PideLocalPath avoids the downloads.
            val ideLocalPath = providers.gradleProperty("ideLocalPath").orNull
            if (ideLocalPath.isNullOrBlank()) recommended() else local(ideLocalPath)
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=/path/to/repo opens that project in the sandbox IDE.
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}

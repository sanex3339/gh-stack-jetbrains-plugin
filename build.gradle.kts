import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    id("org.jetbrains.kotlin.jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.github.sanex3339.stackedprs"
version = providers.gradleProperty("pluginVersion").get()

kotlin {
    jvmToolchain(25)
    compilerOptions {
        // Rely on JVM default methods instead of generating "call super" bridges in classes that implement
        // platform interfaces; those bridges show up as deprecated-API usages in the Plugin Verifier.
        jvmDefault.set(JvmDefaultMode.NO_COMPATIBILITY)
    }
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
        bundledPlugin("com.intellij.mcpServer")
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
    // Secrets come from the environment (GitHub Actions secrets in release.yml); never commit them.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // -PpublishChannel=beta publishes to a pre-release channel instead of the default one.
        channels = providers.gradleProperty("publishChannel").map { listOf(it) }.orElse(listOf("default"))
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
    systemProperty("stackedprs.version", version.toString())
}

tasks.processResources {
    // The bundled agent skill records which plugin version wrote it (Settings reads it back to offer updates).
    val pluginVersion = version.toString()
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("agent-skill/SKILL.md") { filter { line -> line.replace("{{version}}", pluginVersion) } }
}

tasks.runIde {
    // ./gradlew runIde -PrunIdeProject=/path/to/repo opens that project in the sandbox IDE.
    providers.gradleProperty("runIdeProject").orNull?.let { args(it) }
}

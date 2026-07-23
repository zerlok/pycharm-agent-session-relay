import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.0.21"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        create(
            IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
            providers.gradleProperty("platformVersion").get(),
        )
        instrumentationTools()
        pluginVerifier()
        zipSigner()
        testFramework(TestFrameworkType.Platform)

        // The Agent Sessions tool window hosts each session's terminal via the platform Terminal plugin
        // (task 4.2, design D11/R7). Declaring the bundled plugin puts its API
        // (org.jetbrains.plugins.terminal.*) on the compile + test classpath and satisfies the matching
        // <depends>org.jetbrains.plugins.terminal</depends> in plugin.xml at (test) runtime.
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    // JUnit4 for plugin unit tests. The Platform test framework runs on the JUnit4 runner but does
    // not put the API on the test compile classpath, so declare it explicitly.
    testImplementation("junit:junit:4.13.2")

    // The IntelliJ Platform test base (UsefulTestCase) references org.opentest4j.AssertionFailedError
    // at class-load time, but the platform test-framework artifact does not pull opentest4j onto the
    // unit-test classpath. Declare it explicitly (from mavenCentral) so BasePlatformTestCase can initialize.
    testRuntimeOnly("org.opentest4j:opentest4j:1.3.0")
}

intellijPlatform {
    pluginConfiguration {
        // Marketplace change-notes for a release are the GitHub Release body, passed by release.yml
        // as CHANGE_NOTES. Empty for local/dev builds, which is fine.
        changeNotes = providers.environmentVariable("CHANGE_NOTES").orElse("")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Leave untilBuild unset so the plugin keeps working on newer IDE builds.
            untilBuild = provider { null }
        }
    }

    // Signing material and the Marketplace token are supplied by release.yml from GitHub secrets.
    // Only `signPlugin`/`publishPlugin` (release-only tasks) read these, so local builds need none.
    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }

    pluginVerification {
        ides {
            ide(
                IntelliJPlatformType.fromCode(providers.gradleProperty("platformType").get()),
                providers.gradleProperty("platformVersion").get(),
            )
        }
    }
}

kotlin {
    jvmToolchain(21)
}

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
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            // Leave untilBuild unset so the plugin keeps working on newer IDE builds.
            untilBuild = provider { null }
        }
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

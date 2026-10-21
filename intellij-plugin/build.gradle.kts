plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.bugtrace"
version = "0.2.0"
repositories { mavenCentral(); intellijPlatform { defaultRepositories() } }
dependencies {
	testRuntimeOnly(kotlin("stdlib"))
	testRuntimeOnly("com.google.code.gson:gson:2.13.2")
    intellijPlatform {
        val localIde = providers.gradleProperty("localIdePath")
        if (localIde.isPresent) local(localIde.get()) else intellijIdea("2026.2.3")
    }
}
kotlin { jvmToolchain(25) }
intellijPlatform {
    pluginConfiguration {
        ideaVersion { sinceBuild = "262.10968.63"; untilBuild = "262.*" }
    }
    buildSearchableOptions = false
}
tasks.register<JavaExec>("coreChecks") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.bugtrace.idea.CoreChecksKt"
}

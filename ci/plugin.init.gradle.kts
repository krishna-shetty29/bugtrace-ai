import org.gradle.api.tasks.JavaExec

// Both delivered plugin versions expect one writable temporary-directory argument.
// Keep this outside build.gradle.kts so CI does not replace local plugin versions.
gradle.projectsEvaluated {
    rootProject.allprojects {
        tasks.withType<JavaExec>().matching { it.name == "coreChecks" }.configureEach {
            doFirst {
                val work = project.layout.buildDirectory.dir("ci-core-checks").get().asFile
                check(work.mkdirs() || work.isDirectory) { "Cannot create core-check workspace" }
                setArgs(listOf(work.absolutePath))
            }
        }
    }
}

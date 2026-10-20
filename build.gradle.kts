plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.21" apply false
}

// Resolves artifacts without compiling; use on the review machine before --offline runs.
tasks.register("prefetchDependencies") {
    doLast {
        subprojects.forEach { project ->
            project.configurations.filter { it.isCanBeResolved }.forEach { config ->
                config.resolvedConfiguration.resolvedArtifacts.forEach { artifact ->
                    println("${project.path}:${config.name} ${artifact.moduleVersion.id}")
                }
            }
        }
    }
}

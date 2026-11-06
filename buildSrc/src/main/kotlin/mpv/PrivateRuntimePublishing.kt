package mpv

import getPropertyOrNull
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.tasks.AbstractPublishToMaven
import org.gradle.api.publish.maven.tasks.PublishToMavenLocal
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/** Matches lib/upload-7.x.gradle without storing credentials in version control. */
fun Project.configurePrivateRuntimeRepository() {
    val publishing = extensions.getByType<PublishingExtension>()
    val runtimeUploads = tasks.withType<PublishToMavenRepository>().matching {
        it.name.startsWith("publishMpvRuntime") && it.repository.name == "PrivateMaven"
    }
    val runtimeLocalUploads = tasks.withType<PublishToMavenLocal>().matching {
        it.name.startsWith("publishMpvRuntime")
    }
    // Kotlin creates publications and upload tasks even with the base plugin.
    // Wait for all project afterEvaluate callbacks: Kotlin can add more publications
    // (and lifecycle dependencies) after this project's own callback has run.
    gradle.projectsEvaluated {
        tasks.named("publish") { setDependsOn(listOf(runtimeUploads)) }
        tasks.named("publishAllPublicationsToPrivateMavenRepository") {
            setDependsOn(listOf(runtimeUploads))
        }
        tasks.named("publishToMavenLocal") {
            setDependsOn(listOf(runtimeLocalUploads))
        }
    }
    val mpvProject = this
    gradle.taskGraph.whenReady {
        // Reject explicit non-runtime uploads before any of their dependencies run.
        // enabled=false alone would still compile the Kotlin publication's artifacts.
        val unexpectedUploads = allTasks.filter { task ->
            task.project == mpvProject && task is AbstractPublishToMaven &&
                !task.name.startsWith("publishMpvRuntime")
        }
        check(unexpectedUploads.isEmpty()) {
            "Only MPV native runtimes may be published. Unexpected tasks: " +
                unexpectedUploads.joinToString { it.path } +
                ". Use :mediamp-mpv:publishMpvRuntimeToPrivateMaven."
        }
    }
    publishing.repositories.maven {
        name = "PrivateMaven"
        val urlProperty = if (version.toString().endsWith("SNAPSHOT")) {
            "snapshotRepositoryUrl"
        } else {
            "repositoryUrl"
        }
        url = uri(requireNotNull(getPropertyOrNull(urlProperty)) { "Missing $urlProperty" })
        // A file repository is useful for validating the complete publication locally.
        if (url.scheme != "file") {
            credentials {
                username = getPropertyOrNull("privateMavenUsername")
                password = getPropertyOrNull("privateMavenPassword")
            }
        }
    }

    tasks.register("publishMpvRuntimeToPrivateMaven") {
        group = "publishing"
        description = "Publish only MPV native runtimes to the lib private Maven repository"
        // Do not depend on Gradle's all-publications lifecycle task. Plugins may
        // attach Kotlin/Android uploads to it; this entry point has its own allowlist.
        dependsOn(runtimeUploads)
        doFirst {
            check(publishing.publications.any { it.name.startsWith("mpvRuntime") }) {
                "No MPV runtime is available. Enable the host buildvariant or supply prebuilt runtime JARs."
            }
        }
    }
}

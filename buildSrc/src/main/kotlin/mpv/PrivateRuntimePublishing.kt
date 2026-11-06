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
    // Kotlin creates publications and upload tasks even with the base plugin.
    // Rewire lifecycle tasks so unused Kotlin artifacts are not even built.
    afterEvaluate {
        val runtimeUploads = tasks.withType<PublishToMavenRepository>().matching {
            it.name.startsWith("publishMpvRuntime")
        }
        tasks.named("publish") { setDependsOn(listOf(runtimeUploads)) }
        tasks.named("publishAllPublicationsToPrivateMavenRepository") {
            setDependsOn(listOf(runtimeUploads))
        }
        tasks.named("publishToMavenLocal") {
            setDependsOn(listOf(tasks.withType<PublishToMavenLocal>().matching {
                it.name.startsWith("publishMpvRuntime")
            }))
        }
    }
    val mpvProject = this
    gradle.taskGraph.whenReady {
        // Reject explicit non-runtime uploads before any of their dependencies run.
        // enabled=false alone would still compile the Kotlin publication's artifacts.
        check(allTasks.none { task ->
            task.project == mpvProject && task is AbstractPublishToMaven &&
                !task.name.startsWith("publishMpvRuntime")
        }) {
            "Only MPV native runtimes may be published. Use :mediamp-mpv:publishMpvRuntimeToPrivateMaven."
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
        dependsOn("publishAllPublicationsToPrivateMavenRepository")
        doFirst {
            check(publishing.publications.any { it.name.startsWith("mpvRuntime") }) {
                "No MPV runtime is available. Enable the host buildvariant or supply prebuilt runtime JARs."
            }
        }
    }
}

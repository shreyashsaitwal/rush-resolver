package io.shreyash.rush.resolver

import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.FetchStatus
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.FETCH_ERROR
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.NOT_FOUND
import com.squareup.tools.maven.resolution.ResolvedArtifact
import org.apache.maven.model.Dependency
import java.util.regex.Pattern
import kotlin.system.exitProcess
import io.shreyash.rush.processor.model.DepScope

data class RushDependency(
    val artifact: ResolvedArtifact,
    val scope: DepScope,
)

val Dependency.coordinate: String
    get() {
        val version = this.version.replace(
            Pattern.compile("[\\[\\]]").toRegex(),
            ""
        )
        return "${this.groupId}:${this.artifactId}:$version"
    }

class Resolver(private val artifactResolver: ArtifactResolver) {

    fun resolveTransitively(
        coordinate: String,
        scope: DepScope,
        exclude: List<String>
    ): List<RushDependency> {
        println("Resolving -- $coordinate")

        val (status, artifact) = artifactResolver.resolve(artifactResolver.artifactFor(coordinate))
        handleFetchStatusErr(coordinate, status)
        if (artifact == null) return listOf()

        val allArtifacts = mutableListOf(RushDependency(artifact, scope))

        val deps = artifact.model.dependencies?.filter {
            !it.isOptional && if (scope == DepScope.COMPILE_ONLY) {
                it.scope == DepScope.COMPILE_ONLY.value
            } else {
                it.scope == DepScope.IMPLEMENT.value || it.scope == DepScope.COMPILE_ONLY.value
            }
        } ?: listOf()

        val resolvedDep = deps.map {
            if (!exclude.contains(it.coordinate)) {
                resolveTransitively(it.coordinate, DepScope.fromString(it.scope)!!, exclude)
            } else {
                listOf()
            }
        }.flatten()

        allArtifacts.addAll(resolvedDep)
        return allArtifacts
    }

    fun downloadArtifact(resolvedArtifact: ResolvedArtifact) {
        println("Downloading -- ${resolvedArtifact.coordinate}")
        val status = artifactResolver.downloadArtifact(resolvedArtifact)
        handleFetchStatusErr(resolvedArtifact.coordinate, status)
        println("Done -- ${resolvedArtifact.coordinate}")
    }

    private fun handleFetchStatusErr(coordinate: String, fetchStatus: FetchStatus) {
        when (fetchStatus) {
            NOT_FOUND -> {
                System.err.println(
                    """
                    No artifact found for the coordinate: $coordinate
                    Hint: Have you declared the correct repository providing the artifact?
                """.trimIndent()
                )
                exitProcess(1)
            }
            is FETCH_ERROR -> {
                System.err.println(
                    """
                    Could not fetch the coordinate: $coordinate
                    Repository: ${fetchStatus.repository}
                    Message: [${fetchStatus.responseCode}] ${fetchStatus.message}
                """.trimIndent()
                )
                exitProcess(1)
            }
            FetchStatus.INVALID_HASH -> {
                System.err.println("Hash validation failed for the coordinate: $coordinate")
                exitProcess(1)
            }
            is FetchStatus.ERROR -> {
                System.err.println(
                    """
                    Could not resolve the artifact for the coordinate: $coordinate
                    ${fetchStatus.errors.keys.map { it + "\n" }}
                """.trimIndent()
                )
                exitProcess(1)
            }
            else -> { /* Artifact resolved successfully! */
            }
        }
    }
}

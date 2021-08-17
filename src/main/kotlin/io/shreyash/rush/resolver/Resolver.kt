package io.shreyash.rush.resolver

import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.FetchStatus
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.FETCH_ERROR
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.NOT_FOUND
import com.squareup.tools.maven.resolution.ResolvedArtifact
import java.util.regex.Pattern
import io.shreyash.rush.processor.model.DepScope

class Resolver(private val artifactResolver: ArtifactResolver) {

    /**
     * Transitively resolves the artifact for the given [coordinate].
     *
     * @param mainScope scope as defined in the metadata file
     * @return          [ResolvedArtifact] for [coordinate] and all it's dependencies
     */
    fun resolveTransitively(coordinate: String, mainScope: DepScope): List<ResolvedArtifact?> {
        println("Resolving -- $coordinate")
        val artifact = artifactResolver.artifactFor(coordinate)
        val (status, resolvedArtifact) = artifactResolver.resolve(artifact)
        handleFetchStatusErr(coordinate, status)

        val resolvedArtifacts = mutableListOf(resolvedArtifact)
        val deps = resolvedArtifact?.model?.dependencies?.filter {
            !it.isOptional && if (mainScope == DepScope.COMPILE_ONLY) {
                it.scope == "compile"
            } else {
                it.scope == "runtime" || it.scope == "compile"
            }
        }

        val resolvedDeps = deps?.map {
            val version = it.version.replace(
                Pattern.compile("[\\[\\]]").toRegex(),
                ""
            )
            val depCoordinate = "${it.groupId}:${it.artifactId}:$version"
            resolveTransitively(depCoordinate, mainScope)
        }
        resolvedDeps?.flatten()?.let { resolvedArtifacts.addAll(it) }

        return resolvedArtifacts
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
            }
            is FETCH_ERROR -> {
                System.err.println(
                    """
                    Could not fetch the coordinate: $coordinate
                    Repository: ${fetchStatus.repository}
                    Message: [${fetchStatus.responseCode}] ${fetchStatus.message}
                """.trimIndent()
                )
            }
            FetchStatus.INVALID_HASH -> {
                System.err.println("Hash validation failed for the coordinate: $coordinate")
            }
            is FetchStatus.ERROR -> {
                System.err.println(
                    """
                    Could not resolve the artifact for the coordinate: $coordinate
                    ${fetchStatus.errors.keys.map { it + "\n" }}
                """.trimIndent()
                )
            }
            else -> { /* Artifact resolved successfully! */
            }
        }
    }
}

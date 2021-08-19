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
        val artifact = artifactResolver.artifactFor(coordinate)
        val (status, resolvedArtifact) = artifactResolver.resolve(artifact)
        handleFetchStatusErr(coordinate, status)

        if (resolvedArtifact == null) {
            System.err.println(
                """
                ERROR Could not resolve POM file for: $coordinate
                Fetch status: $status
                """.trimIndent()
            )
            exitProcess(1)
        }
        val allArtifacts = mutableListOf(RushDependency(resolvedArtifact, scope))

        val deps = resolvedArtifact.model.dependencies?.filter {
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
        val status = artifactResolver.downloadArtifact(resolvedArtifact)
        handleFetchStatusErr(resolvedArtifact.coordinate, status)
    }

    private fun handleFetchStatusErr(coordinate: String, fetchStatus: FetchStatus) {
        when (fetchStatus) {
            NOT_FOUND -> {
                System.err.println("ERROR No artifact found for: $coordinate")
                exitProcess(1)
            }
            is FETCH_ERROR -> {
                System.err.println(
                    """
                    ERROR Could not fetch: $coordinate
                    Repository: ${fetchStatus.repository}
                    Message: [${fetchStatus.responseCode}] ${fetchStatus.message}
                    """.trimIndent()
                )
                exitProcess(1)
            }
            FetchStatus.INVALID_HASH -> {
                System.err.println("ERROR Hash validation failed for: $coordinate")
                exitProcess(1)
            }
            is FetchStatus.ERROR -> {
                System.err.println(
                    """
                    ERROR Could not resolve the artifact for: $coordinate
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

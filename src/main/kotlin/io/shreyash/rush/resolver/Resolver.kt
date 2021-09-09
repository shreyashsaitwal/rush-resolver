package io.shreyash.rush.resolver

import com.squareup.tools.maven.resolution.Artifact
import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.FetchStatus
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.FETCH_ERROR
import com.squareup.tools.maven.resolution.FetchStatus.RepositoryFetchStatus.NOT_FOUND
import com.squareup.tools.maven.resolution.ResolvedArtifact
import io.shreyash.rush.processor.model.DepEntry
import io.shreyash.rush.processor.model.DepScope
import io.shreyash.rush.resolver.model.SkippedArtifacts
import org.apache.maven.model.Dependency
import java.util.regex.Pattern
import kotlin.system.exitProcess

data class RushDependency(
    val artifact: ResolvedArtifact,
    val scope: DepScope,
    val reqDeps: List<Dependency>,
)

val Dependency.coordinate: String
    get() {
        val version = this.version.replace(
            Pattern.compile("[\\[\\]]").toRegex(),
            ""
        )
        return "${this.groupId}:${this.artifactId}:$version"
    }

class Resolver(
    private val artifactResolver: ArtifactResolver,
    private val directDeps: List<DepEntry>
) {

    private val devDeps = listOf(
        // androidx
        artifactResolver.artifactFor("androidx.annotation:annotation:1.0.0"),
        artifactResolver.artifactFor("androidx.appcompat:appcompat:1.0.0"),
        artifactResolver.artifactFor("androidx.asynclayoutinflater:asynclayoutinflater:1.0.0"),
        artifactResolver.artifactFor("androidx.collection:collection:1.0.0"),
        artifactResolver.artifactFor("androidx.constraintlayout:constraintlayout:1.1.0"),
        artifactResolver.artifactFor("androidx.constraintlayout:constraintlayout-solver:1.1.0"),
        artifactResolver.artifactFor("androidx.coordinatorlayout:coordinatorlayout:1.0.0"),
        artifactResolver.artifactFor("androidx.core:core:1.0.0"),
        artifactResolver.artifactFor("androidx.arch.core:core-common:2.0.0"),
        artifactResolver.artifactFor("androidx.arch.core:core-runtime:2.0.0"),
        artifactResolver.artifactFor("androidx.cursoradapter:cursoradapter:1.0.0"),
        artifactResolver.artifactFor("androidx.customview:customview:1.0.0"),
        artifactResolver.artifactFor("androidx.drawerlayout:drawerlayout:1.0.0"),
        artifactResolver.artifactFor("androidx.fragment:fragment:1.0.0"),
        artifactResolver.artifactFor("androidx.interpolator:interpolator:1.0.0"),
        artifactResolver.artifactFor("androidx.legacy:legacy-support-core-ui:1.0.0"),
        artifactResolver.artifactFor("androidx.legacy:legacy-support-core-utils:1.0.0"),
        artifactResolver.artifactFor("androidx.lifecycle:lifecycle-common:2.0.0"),
        artifactResolver.artifactFor("androidx.lifecycle:lifecycle-livedata:2.0.0"),
        artifactResolver.artifactFor("androidx.lifecycle:lifecycle-runtime:2.0.0"),
        artifactResolver.artifactFor("androidx.lifecycle:lifecycle-viewmodel:2.0.0"),
        artifactResolver.artifactFor("androidx.loader:loader:1.0.0"),
        artifactResolver.artifactFor("androidx.localbroadcastmanager:localbroadcastmanager:1.0.0"),
        artifactResolver.artifactFor("androidx.print:print:1.0.0"),
        artifactResolver.artifactFor("androidx.slidingpanelayout:slidingpanelayout:1.0.0"),
        artifactResolver.artifactFor("androidx.swiperefreshlayout:swiperefreshlayout:1.0.0"),
        artifactResolver.artifactFor("androidx.vectordrawable:vectordrawable:1.0.0"),
        artifactResolver.artifactFor("androidx.vectordrawable:vectordrawable-animated:1.0.0"),
        artifactResolver.artifactFor("androidx.versionedparcelable:versionedparcelable:1.0.0"),
        artifactResolver.artifactFor("androidx.viewpager:viewpager:1.0.0"),
        // other
        artifactResolver.artifactFor("com.google.code.gson:gson:2.1"),
        artifactResolver.artifactFor("com.google.guava:guava:14.0.1"),
    )

    val skippedArtifacts = mutableListOf<SkippedArtifacts>()

    /**
     * @param coordinate Maven coordinate of the artifact that is to be resolved.
     * @param scope      [DepScope] of this artifact.
     * @param exclude    List of coordinates that should not be resolved.
     * @return           A list of [RushDependency] for the Maven artifacts for [coordinate] and it's transitive deps.
     */
    fun resolveTransitively(
        coordinate: String,
        scope: DepScope,
        exclude: List<String>
    ): List<RushDependency> {
        val artifact = artifactResolver.artifactFor(coordinate)

        // Check if this artifact is already available as a dev-dependency
        val devDep = devDeps.find {
            it.groupId == artifact.groupId && it.artifactId == artifact.artifactId
        }
        devDep?.run {
            // If the artifact exist as a dev dep, then ignore it if:
            // * its version is same
            // * its not explicitly declared in rush.yml
            if (this.version == artifact.version || !directDeps.any { it.value == coordinate }) {
                skippedArtifacts.add(
                    SkippedArtifacts(
                        artifact.coordinate,
                        this.version,
                        scope.value
                    )
                )
                return emptyList()
            }
        }

        println("INFO: Resolving:      ${artifact.coordinate}")
        val (status, resolvedArtifact) = artifactResolver.resolve(artifact)
        handleFetchStatusErr(coordinate, status)

        if (resolvedArtifact == null) {
            System.err.println(
                """
                ERROR: Could not locate POM file for: $coordinate
                Fetch status: $status
                """.trimIndent()
            )
            exitProcess(1)
        }

        val deps = resolvedArtifact.model.dependencies?.filter {
            !it.isOptional && if (scope == DepScope.COMPILE_ONLY) {
                it.scope == DepScope.COMPILE_ONLY.value
            } else {
                it.scope == DepScope.IMPLEMENT.value || it.scope == DepScope.COMPILE_ONLY.value
            }
        } ?: emptyList()

        val allArtifacts = mutableListOf(RushDependency(resolvedArtifact, scope, deps))

        val resolvedDep = deps.map {
            if (!exclude.contains(it.coordinate)) {
                resolveTransitively(it.coordinate, DepScope.fromString(it.scope)!!, exclude)
            } else {
                emptyList()
            }
        }.flatten()

        allArtifacts.addAll(resolvedDep)
        return allArtifacts
    }

    /**
     * Download the given [resolvedArtifact]
     */
    fun downloadArtifact(resolvedArtifact: ResolvedArtifact) {
        val status = artifactResolver.downloadArtifact(resolvedArtifact)
        artifactResolver.downloadSources(resolvedArtifact)
        handleFetchStatusErr(resolvedArtifact.coordinate, status)
    }

    /**
     * Handles the various fetch status errors by logging appropriate messages and exiting the process.
     */
    private fun handleFetchStatusErr(
        coordinate: String,
        fetchStatus: FetchStatus,
        exitOnErr: Boolean = true
    ) {
        when (fetchStatus) {
            NOT_FOUND -> {
                System.err.println("ERROR: No artifact found for: $coordinate")
                if (exitOnErr) exitProcess(1)
            }
            is FETCH_ERROR -> {
                System.err.println(
                    """
                    ERROR: Could not fetch: $coordinate
                    Repository: ${fetchStatus.repository}
                    Response code: ${fetchStatus.responseCode}
                    Message: ${fetchStatus.message}
                    """.trimIndent()
                )
                if (exitOnErr) exitProcess(1)
            }
            FetchStatus.INVALID_HASH -> {
                System.err.println("ERROR: Hash validation failed for: $coordinate")
                if (exitOnErr) exitProcess(1)
            }
            is FetchStatus.ERROR -> {
                fetchStatus.errors.entries.forEach {
                    handleFetchStatusErr(coordinate, it.value, false)
                }
                if (exitOnErr) exitProcess(1)
            }
            else -> { /* Artifact resolved successfully! */
            }
        }
    }
}

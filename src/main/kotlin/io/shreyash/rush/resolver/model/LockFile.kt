package io.shreyash.rush.resolver.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LockFile(
    @SerialName("skipped_artifacts") val skippedArtifacts: List<SkippedArtifacts>,
    @SerialName("resolved_artifacts") val resolvedArtifacts: List<ResolvedArtifacts>,
)

@Serializable
data class ResolvedArtifacts(
    @SerialName("coord") val coordinate: String,
    val scope: String,
    val type: String,
    val direct: Boolean,
    val path: String,
    val deps: List<String>,
)

@Serializable
data class SkippedArtifacts(
    @SerialName("coord") val coordinate: String,
    @SerialName("available_version") val availableVer: String,
    val scope: String,
)

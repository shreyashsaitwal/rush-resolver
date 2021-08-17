package io.shreyash.rush.resolver.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LockFile(
    @SerialName("resolved_deps") val resolvedDeps: List<ResolvedDep>
)

@Serializable
data class ResolvedDep(
    @SerialName("coord") val coordinate: String,
    val scope: String,
)

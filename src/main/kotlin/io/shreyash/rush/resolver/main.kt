package io.shreyash.rush.resolver

import com.charleskorn.kaml.Yaml
import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.MavenVersion
import com.squareup.tools.maven.resolution.Repositories
import java.io.FileInputStream
import java.nio.file.Paths
import java.util.regex.Pattern
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.streams.toList
import io.shreyash.rush.processor.model.RushYaml
import io.shreyash.rush.resolver.model.LockFile
import io.shreyash.rush.resolver.model.ResolvedDep

fun main(args: Array<String>) {
    val resolver = Resolver(
        ArtifactResolver(
            resolveGradleModule = false,
            suppressAddRepositoryWarnings = true,
            repositories = listOf(
                Repositories.MAVEN_CENTRAL,
                Repositories.GOOGLE_ANDROID,
                Repositories.GOOGLE_MAVEN_CENTRAL_ASIA
            )
        )
    )

    val metadataFile = metadataFile(args[0])
    // Filter out the local JAR files.
    val deps = metadataFile.deps.filter { it.value.contains(":") }
    println("INFO Fetching metadata...")

    // Download POM files of all the `deps` and their transitive deps
    val artifacts = deps.map {
        resolver.resolveTransitively(it.value, it.scope, it.exclude)
    }.flatten()

    val sorted = artifacts
        // Transitive resolution of deps results in duplicate artifacts, take only unique ones.
        .distinctBy { it.artifact.coordinate }
        // Group different versions of same artifact.
        .groupBy { it.artifact.groupId + it.artifact.artifactId }
        // If multiple versions of a same artifacts are found...
        .map { (_, artifactList) ->
            // ...first check if a specific version of this artifact was specifically defined in
            // the metadata file...
            val explicit = artifactList.find {
                it.artifact.coordinate.let {
                    deps.any { dep -> dep.value == it }
                }
            }

            // ...if it was, then take it, otherwise take the highest version.
            explicit ?: artifactList.sortedBy {
                val version =
                    it.artifact.version.replace(Pattern.compile("[\\[\\]]").toRegex(), "")
                MavenVersion.from(version)
            }[0]
        }

    val resolved = sorted.parallelStream()
        .map {
            // Download this artifact if it wasn't previously downloaded.
            if (!it.artifact.main.localFile.exists()) {
                println("INFO Downloading: ${it.artifact.coordinate}...")
                resolver.downloadArtifact(it.artifact)
            } else {
                println("INFO Found in cache: ${it.artifact.coordinate}")
            }

            // This will go in `rush.lock` for the Rush CLI to consume.
            ResolvedDep(
                coordinate = it.artifact.coordinate,
                scope = it.scope.value,
                type = it.artifact.suffix,
                localPath = it.artifact.main.localFile.toString().replace("\\", "/")
            )
        }.toList()

    // Create and write the `rush.lock` file.
    val lockYaml = Yaml.default.encodeToString(LockFile.serializer(), LockFile(resolved))
    val lockFile = Paths.get(args[0], ".rush", "rush.lock").apply {
        if (!this.exists()) this.createFile()
    }
    lockFile.writeText(lockYaml)
}

/**
 * @return The metadata file for the Rush project in [projectRoot].
 */
fun metadataFile(projectRoot: String): RushYaml {
    val file = if (Paths.get(projectRoot, "rush.yml").exists()) {
        Paths.get(projectRoot, "rush.yml").toFile()
    } else {
        Paths.get(projectRoot, "rush.yaml").toFile()
    }

    return Yaml.default.decodeFromStream(RushYaml.serializer(), FileInputStream(file))
}

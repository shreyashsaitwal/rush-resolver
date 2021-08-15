package io.shreyash.rush.resolver

import com.charleskorn.kaml.Yaml
import com.squareup.tools.maven.resolution.Artifact
import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.FetchStatus
import org.apache.maven.model.Dependency
import java.io.FileInputStream
import java.nio.file.Paths
import kotlin.io.path.exists
import io.shreyash.rush.model.RushYaml

fun main(args: Array<String>) {
    val metadataFile = metadataFile("./misc")
    val resolver = ArtifactResolver(resolveGradleModule = false, suppressAddRepositoryWarnings = true)

    metadataFile.deps.forEach {
        val art = resolver.artifactFor(it.mvnCoordinate)
        resolve(resolver, art)
    }
}

fun metadataFile(projectRoot: String): RushYaml {
    val file = if (Paths.get(projectRoot, "rush.yml").exists()) {
        Paths.get(projectRoot, "rush.yml").toFile()
    } else {
        Paths.get(projectRoot, "rush.yaml").toFile()
    }

    return Yaml.default.decodeFromStream(RushYaml.serializer(), FileInputStream(file))
}

var count = 0;

fun resolve(resolver: ArtifactResolver, artifact: Artifact) {
    val result = resolver.resolve(artifact)

    if (result.status !is FetchStatus.RepositoryFetchStatus.SUCCESSFUL) {
        println("${" ".repeat(count)}└─── Failed: ${artifact.artifactId}")
    }

    val deps = result.artifact?.model?.dependencies?.filter {
        !it.isOptional && (it.scope == "runtime" || it.scope == "compile")
    }

    if (count > 0) {
        println("    ${"│   ".repeat((count / 4) - 1)}└─── ${artifact.artifactId}")
    } else {
        println(artifact.artifactId)
    }

    count += 4
    deps?.forEach {
        resolve(resolver, resolver.artifactFor(it.coordinate()))
    }
    count -= 4
}

fun Dependency.coordinate() = "${this.groupId}:${this.artifactId}:${this.version}"

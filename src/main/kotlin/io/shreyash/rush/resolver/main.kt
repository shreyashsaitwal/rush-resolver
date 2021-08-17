package io.shreyash.rush.resolver

import com.charleskorn.kaml.Yaml
import com.squareup.tools.maven.resolution.ArtifactResolver
import com.squareup.tools.maven.resolution.Repositories
import java.io.FileInputStream
import java.nio.file.Paths
import kotlin.io.path.exists
import io.shreyash.rush.processor.model.RushYaml

fun main(args: Array<String>) {
    val metadataFile = metadataFile("./misc")
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

    val artifacts = metadataFile.deps.map {
        resolver.resolveTransitively(it.mvnCoordinate, it.scope, it.exclude)
    }.flatten()

    artifacts.distinctBy { it?.coordinate }.filterNotNull().parallelStream()
        .forEach { resolver.downloadArtifact(it) }
}

fun metadataFile(projectRoot: String): RushYaml {
    val file = if (Paths.get(projectRoot, "rush.yml").exists()) {
        Paths.get(projectRoot, "rush.yml").toFile()
    } else {
        Paths.get(projectRoot, "rush.yaml").toFile()
    }

    return Yaml.default.decodeFromStream(RushYaml.serializer(), FileInputStream(file))
}

package engineering.cute.cutekit.clion.toolwindow

import java.nio.file.Path

/**
 * Represents a flattened extern entry resolved from a CuteKit project.lock or project manifest.
 */
data class CutekitDependency(
    val id: String,
    val origin: Path,
    val git: String?,
    val tag: String?,
    val commit: String?,
    val version: String?,
    val names: List<String>,
)

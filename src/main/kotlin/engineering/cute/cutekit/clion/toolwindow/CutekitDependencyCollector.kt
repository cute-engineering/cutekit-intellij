package engineering.cute.cutekit.clion.toolwindow

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.reader

/**
 * Responsible for walking CuteKit metadata and collecting extern dependencies.
 */
class CutekitDependencyCollector(private val project: Project) {
    private val logger = Logger.getInstance(CutekitDependencyCollector::class.java)

    fun collect(): List<CutekitDependency> {
        val basePath = project.basePath ?: return emptyList()
        val queue = ArrayDeque<Path>()
        queue.add(Paths.get(basePath))

        val visitedRoots = mutableSetOf<Path>()
        val seenDependencies = mutableSetOf<String>()
        val result = mutableListOf<CutekitDependency>()

        while (queue.isNotEmpty()) {
            val root = queue.removeFirst().normalize()
            if (!visitedRoots.add(root)) {
                continue
            }

            val externs = readExterns(root)
            for (extern in externs) {
                val uniqueKey = listOf(
                    extern.id,
                    extern.git,
                    extern.tag,
                    extern.commit,
                    extern.version,
                    extern.origin.toString(),
                    extern.contentRoot?.toString()
                ).joinToString("|")
                if (seenDependencies.add(uniqueKey)) {
                    result.add(
                        CutekitDependency(
                            id = extern.id,
                            origin = extern.origin,
                            git = extern.git,
                            tag = extern.tag,
                            commit = extern.commit,
                            version = extern.version,
                            names = extern.names,
                            contentRoot = extern.contentRoot,
                        )
                    )
                }

                extern.localProjectPaths.forEach { candidate ->
                    if (candidate.exists() && candidate.isDirectory()) {
                        queue.add(candidate)
                    }
                }
            }
        }

        return result.sortedWith(compareBy({ it.id.lowercase() }, { it.origin.toString() }))
    }

    private fun readExterns(root: Path): List<ExternEntry> {
        val externs = readFromLockfile(root)
        if (externs.isNotEmpty()) {
            return externs
        }
        return readFromProjectManifest(root)
    }

    private fun readFromLockfile(root: Path): List<ExternEntry> {
        val lockPath = root.resolve("project.lock")
        if (!lockPath.exists()) {
            return emptyList()
        }
        return try {
            lockPath.reader(StandardCharsets.UTF_8).use { reader ->
                val raw = reader.readText()
                parseExterns(JSONObject(raw), root)
            }
        } catch (ioe: IOException) {
            logger.warn("Failed to read ${lockPath}", ioe)
            emptyList()
        } catch (t: Throwable) {
            logger.warn("Failed to parse ${lockPath}", t)
            emptyList()
        }
    }

    private fun readFromProjectManifest(root: Path): List<ExternEntry> {
        val manifestPath = root.resolve("project")
        if (!manifestPath.exists()) {
            return emptyList()
        }
        return try {
            manifestPath.reader(StandardCharsets.UTF_8).use { reader ->
                val raw = reader.readText()
                parseExterns(JSONObject(raw), root)
            }
        } catch (ioe: IOException) {
            logger.warn("Failed to read ${manifestPath}", ioe)
            emptyList()
        } catch (t: Throwable) {
            logger.warn("Failed to parse ${manifestPath}", t)
            emptyList()
        }
    }

    private fun parseExterns(json: JSONObject, root: Path): List<ExternEntry> {
        val externJson = json.optJSONObject("extern") ?: return emptyList()
        val externs = mutableListOf<ExternEntry>()
        for (key in externJson.keySet()) {
            val entryObject = externJson.optJSONObject(key) ?: continue
            externs.add(ExternEntry.fromJson(key, entryObject, root))
        }
        return externs
    }

    private data class ExternEntry(
        val id: String,
        val origin: Path,
        val git: String?,
        val tag: String?,
        val commit: String?,
        val version: String?,
        val names: List<String>,
        val localProjectPaths: List<Path>,
        val contentRoot: Path?,
    ) {
        companion object {
            fun fromJson(id: String, json: JSONObject, root: Path): ExternEntry {
                val git = json.optStringOrNull("git")
                val tag = json.optStringOrNull("tag")
                val commit = json.optStringOrNull("commit")
                val version = json.optStringOrNull("version")
                val names = json.optArrayStrings("names")

                val localCandidates = buildList {
                    add(root.resolve(".cutekit/extern").resolve(id))
                    val home = System.getProperty("user.home")
                    if (!home.isNullOrBlank()) {
                        add(Paths.get(home).resolve(".cutekit/extern").resolve(id))
                    }
                    git?.let {
                        // When externs are mounted directly inside the workspace
                        add(root.resolve(id))
                    }
                }

                val resolvedRoot = localCandidates.firstOrNull { it.exists() && it.isDirectory() }

                return ExternEntry(
                    id = id,
                    origin = root,
                    git = git,
                    tag = tag,
                    commit = commit,
                    version = version,
                    names = names,
                    localProjectPaths = localCandidates,
                    contentRoot = resolvedRoot
                )
            }
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? {
    val value = optString(key, null)
    return value?.takeIf { it.isNotBlank() }
}

private fun JSONObject.optArrayStrings(key: String): List<String> {
    val array = optJSONArray(key) ?: return emptyList()
    return array.toStringList()
}

private fun JSONArray.toStringList(): List<String> {
    val list = mutableListOf<String>()
    for (i in 0 until length()) {
        val value = optString(i, null)
        if (!value.isNullOrBlank()) {
            list.add(value)
        }
    }
    return list
}

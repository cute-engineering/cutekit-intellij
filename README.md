# CuteKit CLion Plugin

A CLion/IntelliJ Platform plugin that surfaces a flattened view of CuteKit extern dependencies inside a dedicated tool window. The plugin discovers CuteKit projects by looking for `project` and `project.lock` manifests and walks through nested extern directories to present a consolidated list of dependencies declared across the workspace.

## Features

- Tool window (**CuteKit Dependencies**) docked to the right side of the IDE.
- On-demand refresh plus automatic updates when CuteKit manifests change.
- Displays extern identifiers along with git metadata, resolved commit, version, and host package aliases.

## Building

```bash
./gradlew build
```

Gradle automatically downloads a compatible toolchain (JDK 21 for compilation) and produces the plugin artifact in `build/distributions`.

## Running in CLion

Use the Gradle run configuration provided by the IntelliJ Platform Gradle plugin:

```bash
./gradlew runIde
```

This launches a sandboxed CLion instance with the plugin preinstalled.

## Development Notes

- Kotlin sources live under `src/main/kotlin`.
- Plugin descriptors are under `src/main/resources/META-INF`.
- The dependency collector intentionally prefers lockfiles to avoid evaluating CuteKit macros at build time. If no lockfile is available, it falls back to the project manifest.

# Cutekit Integration

CLion plugin exposing the CuteKit Explorer tool window to browse project roots and extern checkouts in one tree.

## Features

- CuteKit Explorer tool window docked to the right.
- Combined project and extern tree with native CLion file actions.
- Manual refresh plus automatic updates on CuteKit manifest changes.

## Usage

```bash
./gradlew build      # assemble the plugin
./gradlew runIde     # launch a sandboxed CLion with the plugin
```

Sources live in `src/main/kotlin`, descriptors in `src/main/resources/META-INF`.

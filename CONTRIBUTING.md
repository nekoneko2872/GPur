# Developing GPur

Clone https://github.com/nekoneko2872/GPur, select the version branch, install JDK 25, and run `./gradlew applyAllPatches`.

GPur-owned classes/resources belong in `purpur-server/src/main`. Edit generated Minecraft/Paper sources after applying patches, then run fixup and rebuild in separate invocations:

```sh
./gradlew :purpur-server:fixupMinecraftSourcePatches :purpur-server:fixupPaperServerFilePatches
./gradlew rebuildAllServerPatches
```

Persist generated Gradle changes as single-file patches. Verify a fresh patch application before committing.

Run `./gradlew :purpur-server:test :purpur-server:createPaperclipJar`. Hardware tests are opt-in; ordinary tests must work without Vulkan. Preserve CPU event ordering, random consumption, and CPU fallbacks. Validate numeric parity and end-to-end performance separately.

Do not commit generated sources, jars, credentials, server configurations, logs, plugins, or worlds. Preserve upstream license/copyright notices.

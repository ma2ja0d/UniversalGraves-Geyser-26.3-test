# Changelog

## 1.1.0-beta.1+26.3 (untested port)

- Ported to Minecraft 26.3 (unobfuscated): Mojang names, Loom `net.fabricmc.fabric-loom`, Java 25.
- Targets Universal Graves 3.13.0+26.3 (`onBlockReplaced` hook is now `preRemoveSideEffects`).
- Merged the two packet mixins into one on `ServerCommonPacketListenerImpl`.
- Overlay armor stand metadata is now built directly from public data accessors, so the grave
  text no longer relies on reflection (the old reflective marker/small calls silently failed on
  obfuscated servers).
- Fox overlay uses name-based setters so a rename degrades the visual instead of breaking the mod.
- Logging switched to SLF4J.

# Changelog

## 1.0.1-beta.2 - 2026-04-05

- Add support for Minecraft 1.21.11 and remove the upper-bound loader version check that rejected newer 1.21.x servers.
- Update Fabric, Universal Graves, SGUI, and Polymer dependency lines for the 1.21.11 release line.
- Upgrade Fabric Loom to 1.13.4 and disable mod-provided javadocs to match the dependency toolchain used by the 1.21.11 stack.
- Pin the project to Java 21 with Gradle toolchains and automatic resolver support so local and CI builds do not depend on a manually set `JAVA_HOME`.

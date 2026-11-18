# Java4Shell (CVE-2026-114938): Android build environment

The Android app runs on **ART**, not the affected OpenJDK HotSpot JVM.
This repository does not configure an OpenJDK build in GitHub Actions: the
local Android Gradle Plugin, Gradle, SDK tooling and JVM tests depend on the
JDK installed on the developer's machine.

Install a **vendor-confirmed patched JDK** for local builds when using
affected Java versions (notably unpatched OpenJDK 17/21/25). Until then,
set `JAVA_TOOL_OPTIONS=-XX:-UseBASE64Intrinsics` before running Gradle
and restart any existing Gradle daemons. On PowerShell use
`$env:JAVA_TOOL_OPTIONS = '-XX:-UseBASE64Intrinsics'`. Verify with
`java -XX:+PrintFlagsFinal -version` (`UseBASE64Intrinsics = false`).

This mitigation affects build JVMs only, not the APK or ART runtime.
It does not automatically patch any locally installed JDK.

Sources:
- https://www.cert.europa.eu/publications/security-advisories/2026-021/
- https://adoptium.net/news/2026/11/temurin-november-2026-cspu-java4shell/

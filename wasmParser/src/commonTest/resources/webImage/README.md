# GraalVM Web Image fixtures

Third-party test inputs. They are kept in the repository only as parser and
analysis fixtures (`GraalWebImage.kt` in `wasmParser/src/commonMain/.../wasm/analysis/`
recognises this kind of module). Full licence texts are in [`licenses/`](licenses/).

Status legend: VERIFIED = checked against the upstream source named here;
UNVERIFIED = could not be established from repository evidence or the network.
Retrieval date of every upstream file below: 2026-11-12.

| File | Size | SHA-256 |
|---|---|---|
| `javac.js.wasm` | 34783442 | `e1a93c2381e78502b6e2789a9734274c95f6e9df39cfeac452e092a6c611bec5` |
| `wasm-spring-shell.js.wasm` | 25033370 | `5b87645d9259134ac5aa6fceed97b8513b7af91e4a8291477c6f2bb18602c3cd` |

Both were added in one commit, "[wasm] graalvm/wasm example files
added" (2026-03-28).

## What they are

Both modules are the output of the GraalVM Web Image backend of Native Image
(`native-image --tool:svm-wasm`, WasmGC / exception-handling / typed function
references), built from the Web Image demos in
<https://github.com/graalvm/graalvm-demos/tree/master/web-image> (VERIFIED by
project coordinates found inside the binaries):

- `javac.js.wasm`: demo `web-image/javac` (Maven artifact `javac`, `1.0-SNAPSHOT`;
  built with `./mvnw -Pnative package`, image name `javac`). It is OpenJDK's
  `javac` (`jdk.compiler`, `jdk.javadoc`, `jdk.jdeps`) compiled to Wasm together with
  the JDK class library and the GraalVM Native Image / Web Image runtime.
- `wasm-spring-shell.js.wasm`: demo `web-image/spring-shell` (Maven artifact
  `com.example:wasm-spring-shell:0.0.1-SNAPSHOT`, the `META-INF/native-image/com.example/wasm-spring-shell`
  resource is embedded in the module). A Spring Boot + Spring Shell application.
  The demo's `pom.xml` on `master` (retrieved 2026-11-12) pins Spring Boot 3.5.6 and
  Spring Shell 3.4.1; the module embeds Logback 1.5.18, which matches Boot 3.5.x.

Build toolchain recorded inside `javac.js.wasm`: `java.version` 25.0.1,
`java.runtime.name` "Java(TM) SE Runtime Environment", `java.runtime.version`
`25.0.1+8-LTS-jvmci-b01`, version date 2025-10-21. The Spring module reports
`LTS-jvmci-b01` as well. The Web Image backend requires Oracle GraalVM 25.1+
or an early-access build (demo README: <https://github.com/graalvm/graalvm-demos/blob/master/web-image/javac/README.md>).

Exact source revisions used for the build are UNVERIFIED: the demo repository
head at retrieval time is `5018449855bd2612c7addae87824083156eabcd0`
(2026-11-02), the fixtures were built earlier (March 2026 or before) and the
build revision is not recorded in this repository or in the binaries. Needed to
verify: the person who built them confirming
the `graalvm-demos` commit and the GraalVM build used.

## Licences

Decision recorded in HEXANA-33: `SPDX: GPL-2.0-only WITH Classpath-exception-2.0`
for the OpenJDK-derived class library and runtime parts. Evidence:

- The JDK class library is OpenJDK `jdk-25.0.1+8`
  (<https://github.com/openjdk/jdk25u/tree/jdk-25.0.1+8>). Its top-level `LICENSE`
  (GPL-2.0 plus the Classpath exception) is copied unchanged to
  [`licenses/OpenJDK-jdk25u-jdk-25.0.1-8-LICENSE.txt`](licenses/OpenJDK-jdk25u-jdk-25.0.1-8-LICENSE.txt)
  (VERIFIED, tag `jdk-25.0.1+8`, tag object `489509117ab1629cb82c7e4fe9b1dbfe8e0f878f`).
- The GraalVM Web Image sources in `oracle/graal` carry the same licence
  (<https://github.com/oracle/graal/blob/master/web-image/LICENSE>, copied to
  [`licenses/oracle-graal-web-image-LICENSE.txt`](licenses/oracle-graal-web-image-LICENSE.txt),
  retrieved from `master` on 2026-11-12; commit of `master` then:
  `13b1f0a42168b307f89641fc2f45bd60b661bf29`).
- Separate full texts: GPL-2.0 from <https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt>
  ([`licenses/GPL-2.0.txt`](licenses/GPL-2.0.txt)) and the SPDX text of the exception
  from <https://github.com/spdx/license-list-data/blob/main/text/Classpath-exception-2.0.txt>
  ([`licenses/Classpath-exception-2.0.txt`](licenses/Classpath-exception-2.0.txt)).
- The demo sources (application glue code in the modules) are UPL-1.0:
  <https://github.com/graalvm/graalvm-demos/blob/master/LICENSE.md>
  ([`licenses/graalvm-demos-UPL-1.0-LICENSE.md`](licenses/graalvm-demos-UPL-1.0-LICENSE.md)).

Other components observed inside the binaries (class names in the name
section / data strings). The licence of the first three rows was checked
against the upstream licence file at the stated tag; the rest was NOT checked
and must be reviewed before release:

| Component | Seen in | Licence | Status |
|---|---|---|---|
| Spring Boot / Spring Framework (`org.springframework.*`) | spring-shell | Apache-2.0, text: [`licenses/spring-boot-v3.5.6-Apache-2.0-LICENSE.txt`](licenses/spring-boot-v3.5.6-Apache-2.0-LICENSE.txt) (tag `v3.5.6`) | VERIFIED for Spring Boot; Spring Framework version not checked |
| Spring Shell (`org.springframework.shell.*`) | spring-shell | Apache-2.0, text: [`licenses/spring-shell-v3.4.1-Apache-2.0-LICENSE.txt`](licenses/spring-shell-v3.4.1-Apache-2.0-LICENSE.txt) (tag `v3.4.1`) | VERIFIED (version taken from the demo `pom.xml` on `master`) |
| Logback 1.5.18 (`ch.qos.logback.*`) | spring-shell | EPL-1.0 OR LGPL-2.1 (licensee's choice), text: [`licenses/logback-v1.5.18-LICENSE.txt`](licenses/logback-v1.5.18-LICENSE.txt); LGPL-2.1 full text: [`../licenses/LGPL-2.1.txt`](../licenses/LGPL-2.1.txt) | VERIFIED (tag `v_1.5.18`; EPL-1.0 text not stored) |
| Guava and Jimfs, shadowed as `org.graalvm.shadowed.com.google.common.*` | both | Apache-2.0 presumed | UNVERIFIED |
| commonmark-java (`org.commonmark.*`, bundled in `jdk.javadoc`) | javac | BSD-2-Clause presumed | UNVERIFIED |
| SLF4J, Jakarta, Hibernate Validator, JLine and other Spring Shell dependencies | spring-shell | not checked | UNVERIFIED |
| Xerces, JDK-internal ICU/CLDR data (inside the JDK class library) | both | part of OpenJDK, see its legal notices | UNVERIFIED (OpenJDK `THIRD_PARTY_README` not stored) |

## Open issue for the owner

`java.runtime.name` is "Java(TM) SE Runtime Environment" and the version carries
`-LTS-jvmci-`, which is how Oracle-branded JDK builds identify themselves
(OpenJDK builds say "OpenJDK Runtime Environment"). The GraalVM Web Image
backend itself ships in Oracle GraalVM. The class library inside the modules is
OpenJDK source code, but whether Oracle's binary distribution terms for the
toolchain (Oracle GraalVM / Oracle JDK) put any condition on redistributing the
generated modules is UNVERIFIED and needs a decision from JetBrains Legal.

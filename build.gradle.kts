plugins {
    application
}

group = "mcd"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    // JGit의 로그 출력처. 없으면 시작할 때마다 SLF4J 경고가 찍힌다. JGit이 쓰는 slf4j-api와 같은 버전.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.18")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "mcd.cli.Main"
}

// tailnet 보조 프로그램(Go, tsnet). 배포판에서는 mcd.jar 옆(lib)에 둔다. 빌드에 Go가 필요하다.
val netHelper by tasks.registering(Exec::class) {
    description = "mcd-net.exe(tailnet 보조 프로그램) 빌드"
    workingDir = file("net-helper")
    inputs.files(fileTree("net-helper") { exclude("build/**") })
    outputs.file("net-helper/build/mcd-net.exe")
    environment("GOOS", "windows")
    environment("GOARCH", "amd64")
    commandLine("go", "build", "-o", "build/mcd-net.exe", ".")
}

distributions {
    main {
        contents {
            from(netHelper) { into("lib") }
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("paper")
    }
}

val paperTest by tasks.registering(Test::class) {
    description = "실제 Paper 서버와 Mineflayer 봇을 띄우는 느린 테스트"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("paper")
    }
    // 실험 세션 이름(-Dmcd.session=...)을 테스트 JVM으로 넘긴다.
    providers.systemProperty("mcd.session").orNull?.let { systemProperty("mcd.session", it) }
    testLogging {
        showStandardStreams = true
    }
}

// 배포판: Java 25 런타임을 포함한 실행 폴더(app-image)를 zip으로 묶는다.
// jpackage는 기본적으로 런타임에서 java.exe를 빼지만 mcd는 그 런타임으로 Paper를 띄워야 하므로, 런타임은 jlink로 직접 만든다.
// 설치 파일(.exe/.msi)은 WiX Toolset이 있어야 만들 수 있어서 아직 만들지 않는다.
val jdkHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    .map { it.metadata.installationPath.asFile }
val packageDir = layout.buildDirectory.dir("jpackage")

val jlinkRuntime by tasks.registering(Exec::class) {
    description = "Paper까지 실행할 수 있는 Java 런타임(java.exe 포함)"
    val output = packageDir.map { it.dir("runtime").asFile }
    outputs.dir(output)
    doFirst { delete(output) }
    executable = jdkHome.get().resolve("bin/jlink").absolutePath
    // Paper가 어떤 모듈을 쓰는지 고정하기 어려워서 JDK의 모든 모듈을 넣는다. 크기 최적화는 나중에 한다.
    // 이 JDK에는 jmods가 없어서(JDK 24+의 런타임 이미지 링크) 모듈 목록을 java --list-modules로 받는다.
    val modules = providers.exec {
        commandLine(jdkHome.get().resolve("bin/java").absolutePath, "--list-modules")
    }.standardOutput.asText.map { out ->
        // 런타임 이미지에서 링크할 때는 jdk.jlink를 넣을 수 없다(JEP 493). jlink/jpackage는 mcd에 필요 없다.
        out.lines().filter { it.isNotBlank() }.map { it.substringBefore('@').trim() }
            .filter { it != "jdk.jlink" && it != "jdk.jpackage" }.joinToString(",")
    }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--add-modules", modules.get(), "--strip-debug", "--no-header-files", "--no-man-pages",
            "--output", output.get().absolutePath)
    })
}

val jpackageImage by tasks.registering(Exec::class) {
    description = "mcd 실행 폴더(build/jpackage/mcd)"
    dependsOn(tasks.installDist, jlinkRuntime)
    val output = packageDir.map { it.dir("mcd").asFile }
    outputs.dir(output)
    doFirst { delete(output) }
    executable = jdkHome.get().resolve("bin/jpackage").absolutePath
    args("--type", "app-image", "--name", "mcd",
        "--input", layout.buildDirectory.dir("install/mcd/lib").get().asFile.absolutePath,
        "--main-jar", "mcd.jar", "--main-class", "mcd.gui.App",
        "--runtime-image", packageDir.get().dir("runtime").asFile.absolutePath,
        // mcd.exe는 GUI, mcd-cli.exe는 콘솔 창이 있는 CLI다.
        "--add-launcher", "mcd-cli=" + file("src/jpackage/mcd-cli.properties").absolutePath,
        "--dest", packageDir.get().asFile.absolutePath)
}

val packageZip by tasks.registering(Zip::class) {
    description = "배포용 zip(build/distributions/mcd-windows.zip)"
    group = "distribution"
    dependsOn(jpackageImage)
    from(packageDir.map { it.dir("mcd") }) { into("mcd") }
    archiveFileName = "mcd-windows.zip"
}

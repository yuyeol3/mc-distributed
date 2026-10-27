import java.security.MessageDigest

plugins {
    application
}

group = "mcd"
// 배포 시 릴리스 태그와 같게 둔다. 앱은 이 값과 GitHub의 최신 릴리스를 비교해 업데이트를 안내한다.
version = "0.6.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // 플러그인 descriptor는 실행 없이 안전한 YAML 생성자로 읽는다.
    implementation("org.yaml:snakeyaml:2.5")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    // 월드 repo별 deploy key로 GitHub에 SSH 접속한다(GitHub 계정 없는 참가자).
    implementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.8.0.202609011348-r")
    // GUI 테마(Swing). Windows 기본 테마보다 현대적인 모양과 다크 모드.
    implementation("com.formdev:flatlaf:3.7.2")
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

// 도커 실험실(lab) 노드가 쓰는 Linux용 보조 프로그램.
val netHelperLinux by tasks.registering(Exec::class) {
    description = "mcd-net-linux 빌드"
    workingDir = file("net-helper")
    inputs.files(fileTree("net-helper") { exclude("build/**") })
    outputs.file("net-helper/build/mcd-net-linux")
    environment("GOOS", "linux")
    environment("GOARCH", "amd64")
    commandLine("go", "build", "-o", "build/mcd-net-linux", ".")
}

distributions {
    main {
        contents {
            from(netHelper) { into("lib") }
        }
    }
}

tasks.jar {
    // 버전을 붙이지 않는다: jpackage의 --main-jar와 업데이트 후 남는 파일 이름을 고정한다.
    archiveFileName = "mcd.jar"
    manifest {
        attributes("Implementation-Version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("paper", "lab")
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
    // Paper 호환성 테스트의 대상 빌드(-Dmcd.paperBuild=26.2-133).
    providers.systemProperty("mcd.paperBuild").orNull?.let { systemProperty("mcd.paperBuild", it) }
    testLogging {
        showStandardStreams = true
    }
}

// 도커 실험실: 컨테이너 하나를 PC 하나로 보고 tailnet, 같은 포트, 네트워크 장애까지 실제 배포 구성대로 시험한다.
// mcd.jar 대신 클래스 폴더를 넣는다. 버전이 없으면 업데이트 확인(GitHub API)을 건너뛰므로 실행마다 외부 호출이 생기지 않는다.
val labImageContext by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("lab-image"))
    from("lab/node.Dockerfile") { rename { "Dockerfile" } }
    from("lab/start.sh")
    from(sourceSets.main.get().output) { into("classes") }
    from(configurations.runtimeClasspath) { into("lib") }
    from(netHelperLinux) { rename { "mcd-net" } }
    // 봇(Mineflayer)이 접속할 수 있는 빌드. Bot.PAPER_BUILD와 같다.
    from("nodes/_cache") { include("paper-26.1.2-74.jar*"); into("cache") }
}

val labImage by tasks.registering(Exec::class) {
    description = "실험실 노드 이미지(mcd-lab-node)"
    dependsOn(labImageContext)
    commandLine("docker", "build", "-q", "-t", "mcd-lab-node", layout.buildDirectory.dir("lab-image").get().asFile.absolutePath)
}

val labGitImage by tasks.registering(Exec::class) {
    description = "실험실 월드 원격 이미지(mcd-lab-git)"
    commandLine("docker", "build", "-q", "-t", "mcd-lab-git", "-f", file("lab/git.Dockerfile").absolutePath, file("lab").absolutePath)
}

val labDerperImage by tasks.registering(Exec::class) {
    description = "실험실 DERP 중계 서버 이미지(mcd-lab-derper)"
    commandLine("docker", "build", "-q", "-t", "mcd-lab-derper", "-f", file("lab/derper.Dockerfile").absolutePath, file("lab").absolutePath)
}

val labBotImage by tasks.registering(Exec::class) {
    description = "실험실 플레이어 봇 이미지(mcd-lab-bot)"
    commandLine("docker", "build", "-q", "-t", "mcd-lab-bot", "-f", file("lab/bot.Dockerfile").absolutePath, file("e2e").absolutePath)
}

val labTest by tasks.registering(Test::class) {
    description = "도커 실험실 시나리오(Docker 필요)"
    group = "verification"
    dependsOn(labImage, labGitImage, labDerperImage, labBotImage)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("lab")
    }
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
    inputs.property("jdk", jdkHome.map { it.absolutePath })
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
    // 입력을 선언하지 않으면 Gradle이 출력만 보고 최신으로 판단해, jar가 바뀌어도 다시 패키징하지 않는다
    // (실제로 옛 jar가 든 배포판으로 시험한 적이 있다).
    inputs.dir(layout.buildDirectory.dir("install/mcd/lib"))
    inputs.dir(packageDir.map { it.dir("runtime") })
    inputs.file("src/jpackage/mcd-cli.properties")
    val output = packageDir.map { it.dir("mcd").asFile }
    outputs.dir(output)
    doFirst { delete(output) }
    executable = jdkHome.get().resolve("bin/jpackage").absolutePath
    args("--type", "app-image", "--name", "mcd",
        "--input", layout.buildDirectory.dir("install/mcd/lib").get().asFile.absolutePath,
        "--main-jar", "mcd.jar", "--main-class", "mcd.gui.App",
        "--app-version", project.version.toString(),
        // FlatLaf가 창 장식에 네이티브 라이브러리를 쓴다. 없으면 시작할 때마다 restricted method 경고가 찍힌다.
        "--java-options", "--enable-native-access=ALL-UNNAMED",
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
    // 자동 업데이트가 받은 zip을 검증할 SHA-256을 옆에 남긴다(릴리스에 함께 올린다).
    doLast {
        val zip = archiveFile.get().asFile
        val digest = MessageDigest.getInstance("SHA-256").digest(zip.readBytes())
        val hex = digest.joinToString("") { "%02x".format(it) }
        zip.resolveSibling(zip.name + ".sha256").writeText("$hex  ${zip.name}\n")
    }
}

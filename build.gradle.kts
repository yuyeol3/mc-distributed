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

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "mcd.cli.Main"
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

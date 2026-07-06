plugins {
    application
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("lib/sim4da.jar"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets {
    main {
        java.srcDirs("src")
    }
    test {
        java.srcDirs("test")
    }
}

application {
    mainClass = "bank.BankSnapshot"
}

tasks.named<JavaExec>("run") {
    // CLI-Argumente durchreichen, z. B. ./gradlew run --args="8"
    standardInput = System.`in`
}

tasks.named<Test>("test") {
    modularity.inferModulePath = false
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

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

tasks.named<Test>("test") {
    modularity.inferModulePath = false
    useJUnitPlatform()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}

application {
    //mainClass = "pingpong.PingPongSimulation"
    mainClass = "firework.FireworkSimulation"
    applicationDefaultJvmArgs = listOf("-Xmx8g")
}

tasks.named<JavaExec>("run") {
    // Forward CLI args: ./gradlew run --args="20"
    standardInput = System.`in`
}

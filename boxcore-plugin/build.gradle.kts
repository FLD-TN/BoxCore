plugins {
    `java-library`
}

val paperApi = "io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT"

dependencies {
    implementation(project(":boxcore-api"))
    compileOnly(paperApi)
    compileOnly("me.clip:placeholderapi:2.11.6")

    testImplementation(paperApi)
    // Paper provides the SQLite driver at runtime (never shaded); tests need their own copy
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
    // Update 05: event and player mocks for the bottle listener and the clan boost rules
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("BoxCore-${project.version}.jar")
    // Bundle the API classes unrelocated so consumers resolve the same classes at runtime.
    val apiJar = project(":boxcore-api").tasks.named<Jar>("jar")
    dependsOn(apiJar)
    from(apiJar.map { zipTree(it.archiveFile) }) {
        exclude("META-INF/MANIFEST.MF")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.test {
    useJUnitPlatform()
}

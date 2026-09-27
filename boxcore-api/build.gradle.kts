plugins {
    `java-library`
    `maven-publish`
}

// The API signatures did not change in BoxCore 1.1.0: the artifact stays com.dex:boxcore-api:1.0.0,
// so consumers built against it need no change.
version = "1.0.0"

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT")
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.dex"
            artifactId = "boxcore-api"
            version = project.version.toString()
            from(components["java"])
            pom {
                name.set("BoxCore API")
                description.set("Public API of the BoxCore plugin (boosts)")
            }
        }
    }
}

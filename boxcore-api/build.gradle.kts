plugins {
    `java-library`
    `maven-publish`
}

// 1.1.0 (BoxCore 1.3.0) only adds GemService and GemResult; BoostService and BoostTypes are unchanged,
// so consumers built against 1.0.0 need no change.
// 1.2.0 (BoxCore 1.4.0) adds BoostService#getMultiplierTenths / #getMultiplierExact (default methods) and
// Multipliers; existing signatures are unchanged. getMultiplier returns the whole part of a decimal multiplier.
version = "1.2.0"

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
                description.set("Public API of the BoxCore plugin (boosts, gems)")
            }
        }
    }
}

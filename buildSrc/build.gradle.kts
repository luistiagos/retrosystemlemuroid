repositories {
    google()
    mavenCentral()
}

plugins {
    `kotlin-dsl`
}

dependencies {
    // SQLite driver for the prebuilt-DB generator task. Pure JVM, no Android runtime needed.
    implementation("org.xerial:sqlite-jdbc:3.46.1.0")
    // JSON parser for reading Room's schemas/*.json (identityHash + entity DDL).
    implementation("org.json:json:20240303")
}

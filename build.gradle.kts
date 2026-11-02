plugins {
    application
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform("io.dropwizard:dropwizard-dependencies:${Deps.dropwizardVersion}"))
    implementation(kotlin("stdlib"))
    implementation("io.dropwizard:dropwizard-core:${Deps.dropwizardVersion}")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    testImplementation(platform("org.junit:junit-bom:${Deps.junitVersion}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.dropwizard:dropwizard-testing:${Deps.dropwizardVersion}")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

application {
    // Main class definition.
    mainClass.set("Bonakkie")
}

val configYML = "./config/config.yml"

tasks {

    test {
        useJUnitPlatform()
    }

    withType<JavaExec> {
            args("server", configYML)
    }

    wrapper {
        gradleVersion = "9.8.2"
    }
}

object Deps {
    const val junitVersion = "6.1.3"
    const val dropwizardVersion = "5.0.2"

}

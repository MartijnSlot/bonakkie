plugins {
    application
    kotlin("jvm") version "1.4.10"
}

repositories {
    // Use jcenter for resolving dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
}

dependencies {
    implementation("com.google.guava:guava:29.0-jre")
    implementation(kotlin("stdlib"))
    testImplementation("junit:junit:${Deps.junitVersion}")
}

application {
    // Main class definition.
    mainClassName = "Bonakkie"
}

tasks {

    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "1.8"
        }
    }

    wrapper {
        gradleVersion = "6.10"
    }
}

object Deps {
    const val junitVersion = "4.13"
}

plugins {
    application
    kotlin("jvm") version "1.4.10"
}

repositories {
    jcenter()
    mavenCentral()
}

dependencies {
    implementation("com.google.guava:guava:29.0-jre")
    implementation(kotlin("stdlib"))
    implementation("io.dropwizard:dropwizard-core:${Deps.dropwizardVersion}")

    testImplementation("org.junit.jupiter:junit-jupiter-api:${Deps.junitVersion}")
}

application {
    // Main class definition.
    mainClassName = "Bonakkie"
}

tasks {

    compileKotlin {
        kotlinOptions {
            jvmTarget = "1.14"
        }
    }

    compileTestKotlin {
        kotlinOptions {
            jvmTarget = "1.14"
        }
    }

    wrapper {
        gradleVersion = "6.10"
    }
}

object Deps {
    const val junitVersion = "5.7.0"
    const val dropwizardVersion = "2.0.13"

}

plugins {
    // Apply the java plugin to add support for Java
    java

    // Apply the application plugin to add support for building a CLI application.
    application
}

repositories {
    // Use jcenter for resolving dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
}

dependencies {
    // This dependency is used by the application.
    implementation("com.google.guava:guava:29.0-jre")

    // Use JUnit test framework
    testImplementation("junit:junit:4.13")
}

application {
    // Define the main class for the application.
    mainClassName = "bonakkie.App"
}

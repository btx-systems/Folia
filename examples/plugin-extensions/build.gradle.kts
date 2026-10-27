plugins {
    `java-library`
}

dependencies {
    compileOnly(project(":folia-api"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.0.3")
}

// A runnable Spring Boot service.
plugins {
    id("cutpay.java-conventions")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencyManagement {
    imports {
        mavenBom(versionCatalog.findLibrary("spring-cloud-bom").get().get().toString())
    }
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    // Run from the service directory so relative paths in config behave predictably.
    workingDir = projectDir
}

// Plain Java library whose dependency versions come from the Spring Boot BOM.
plugins {
    id("cutpay.java-conventions")
    `java-library`
}

val versionCatalog = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    val bom = platform(versionCatalog.findLibrary("spring-boot-bom").get())
    api(bom)
    annotationProcessor(bom)
    testImplementation(bom)
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

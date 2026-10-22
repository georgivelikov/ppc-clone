plugins {
    id("cutpay.spring-service")
}

dependencies {
    implementation(project(":libs:common-money"))
    implementation(project(":libs:common-web"))
    implementation(project(":libs:common-events"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("io.github.resilience4j:resilience4j-circuitbreaker:2.3.0")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation(project(":libs:common-test"))
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

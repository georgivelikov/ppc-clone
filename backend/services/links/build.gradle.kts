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
    runtimeOnly("org.postgresql:postgresql")

    // QR codes (ZXing draws the matrix; we render PNG and SVG ourselves) and the printable table card.
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.github.librepdf:openpdf:3.0.5")

    testImplementation(project(":libs:common-test"))
    testImplementation("com.google.zxing:javase:3.5.3")
    testImplementation("org.wiremock:wiremock-standalone:3.13.1")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
}

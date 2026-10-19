plugins {
    id("cutpay.spring-service")
}

dependencies {
    implementation(project(":libs:common-web"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

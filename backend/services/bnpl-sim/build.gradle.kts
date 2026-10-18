plugins {
    id("cutpay.spring-service")
}

dependencies {
    implementation(project(":libs:common-web"))

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-restclient")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
}

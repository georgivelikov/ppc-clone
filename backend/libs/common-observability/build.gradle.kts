plugins {
    id("cutpay.library-conventions")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
    api("org.springframework.boot:spring-boot-starter-actuator")
    testImplementation("org.springframework:spring-test")
}

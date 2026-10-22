plugins {
    id("cutpay.library-conventions")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-opentelemetry")
    api("org.springframework.boot:spring-boot-starter-actuator")
    // Only the servlet services with a JWT get the request-context logging; the gateway and anything else just skip it.
    compileOnly("org.springframework:spring-webmvc")
    compileOnly("jakarta.servlet:jakarta.servlet-api")
    compileOnly("org.springframework.security:spring-security-core")
    compileOnly("org.springframework.security:spring-security-oauth2-jose")
    testImplementation("org.springframework:spring-test")
    testImplementation("org.springframework:spring-webmvc")
    testImplementation("jakarta.servlet:jakarta.servlet-api")
    testImplementation("org.springframework.security:spring-security-core")
    testImplementation("org.springframework.security:spring-security-oauth2-jose")
}

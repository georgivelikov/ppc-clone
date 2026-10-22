plugins {
    id("cutpay.library-conventions")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-restclient")
    api("org.springframework.boot:spring-boot-starter-json")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-restclient-test")
}

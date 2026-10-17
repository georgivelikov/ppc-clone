rootProject.name = "cutpay"

pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "libs:common-money",
    "libs:common-web",
    "libs:common-events",
    "libs:common-test",
    "services:identity",
    "services:gateway",
)

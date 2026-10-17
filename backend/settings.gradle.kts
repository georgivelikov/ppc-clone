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
    "libs:webhook-signature",
    "services:identity",
    "services:gateway",
    "services:acquirer-sim",
    "services:payments",
    "services:ledger",
    "services:payouts",
    "services:webhooks",
)

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "nexa-operations-android"
include(
    ":app",
    ":core:auth",
    ":core:local",
    ":core:network",
    ":core:designsystem",
    ":core:device",
    ":contexts:tenantaccessgovernance:domain",
    ":contexts:tenantaccessgovernance:application",
    ":contexts:tenantaccessgovernance:infrastructure",
    ":contexts:tenantaccessgovernance:presentation",
    ":contexts:customerbuyerrelationships:domain",
    ":contexts:customerbuyerrelationships:application",
    ":contexts:customerbuyerrelationships:infrastructure",
    ":contexts:customerbuyerrelationships:presentation",
    ":contexts:catalogcommercialpolicy:domain",
    ":contexts:catalogcommercialpolicy:application",
    ":contexts:catalogcommercialpolicy:infrastructure",
    ":contexts:catalogcommercialpolicy:presentation",
    ":contexts:salescommitment:domain",
    ":contexts:salescommitment:application",
    ":contexts:salescommitment:infrastructure",
    ":contexts:salescommitment:presentation",
    ":contexts:inventoryavailability:domain",
    ":contexts:inventoryavailability:application",
    ":contexts:inventoryavailability:infrastructure",
    ":contexts:inventoryavailability:presentation",
    ":contexts:fulfillmentdelivery:domain",
    ":contexts:fulfillmentdelivery:application",
    ":contexts:fulfillmentdelivery:infrastructure",
    ":contexts:fulfillmentdelivery:presentation",
    ":contexts:creditreceivables:domain",
    ":contexts:creditreceivables:application",
    ":contexts:creditreceivables:infrastructure",
    ":contexts:businessdocuments:domain",
    ":contexts:businessdocuments:application",
    ":contexts:businessdocuments:infrastructure",
    ":contexts:businessdocuments:presentation"
)

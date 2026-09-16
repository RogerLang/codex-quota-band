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

rootProject.name = "CodexQuotaAndroid"
include(":app")
include(":xms-wearable-lib-cleanroom")
project(":xms-wearable-lib-cleanroom").projectDir =
  file("../third_party/xms_wearable_sdk_cleanroom/xms-wearable-lib")

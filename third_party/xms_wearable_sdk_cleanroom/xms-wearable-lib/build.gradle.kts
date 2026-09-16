plugins {
    id("com.android.library")
    id("maven-publish")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "org.zxor.oronbox"
                artifactId = "xms-wearable-lib"
                version = project.version.toString()
                pom {
                    name = "XMS Wearable Library Clean Room"
                    description = "Clean-room XMS Wearable SDK 1.4 compatible Android library"
                    licenses {
                        license {
                            name = "MIT License"
                            url = "https://opensource.org/license/mit"
                        }
                    }
                }
            }
        }
    }
}

group = rootProject.group
version = rootProject.version

base {
    archivesName = "xms-wearable-lib_${project.version}"
}

android {
    namespace = "com.xiaomi.xms.wearable"
    compileSdk = 35

    buildFeatures {
        aidl = true
    }

    defaultConfig {
        minSdk = 19
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.9.1")
    testImplementation("junit:junit:4.13.2")
}

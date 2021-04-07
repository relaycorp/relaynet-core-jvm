/*
 * This file was generated by the Gradle 'init' task.
 *
 * This generated file contains a sample Kotlin library project to get you started.
 */
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "tech.relaycorp"

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.3.72"

    // Apply the java-library plugin for API and implementation separation.
    `java-library`

    id("org.jetbrains.dokka") version "0.10.1"

    `maven-publish`

    id("com.diffplug.spotless") version "5.11.1"

    jacoco
}

repositories {
    jcenter()
}

dependencies {
    val kotlinCoroutinesVersion = "1.3.8"

    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

    // Bouncy Castle
    implementation("org.bouncycastle:bcpkix-jdk15on:1.66")

    // Libraries for ASN.1 serialization. We should eventually replace jASN1 with Bouncy Castle
    // https://github.com/relaycorp/relaynet-jvm/issues/25
    implementation("com.beanit:jasn1:1.11.3")
    implementation("org.bouncycastle:bcprov-jdk15on:1.66")

    testImplementation("org.jetbrains.kotlin:kotlin-test")
    testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.6.0")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:$kotlinCoroutinesVersion")
}

java {
    withJavadocJar()
    withSourcesJar()
}

jacoco {
    toolVersion = "0.8.5"
}

tasks.jacocoTestReport {
    reports {
        xml.isEnabled = true
        html.isEnabled = true
        html.destination = file("$buildDir/reports/coverage")
    }
}

tasks.jacocoTestCoverageVerification {
    violationRules {
        rule {
            limit {
                counter = "CLASS"
                value = "MISSEDCOUNT"
                maximum = "0".toBigDecimal()
            }
            limit {
                counter = "METHOD"
                value = "MISSEDCOUNT"
                maximum = "0".toBigDecimal()
            }

            limit {
                counter = "BRANCH"
                value = "MISSEDCOUNT"
                maximum = "0".toBigDecimal()
            }
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
    finalizedBy("jacocoTestReport")
    doLast {
        println("View code coverage at:")
        println("file://$buildDir/reports/coverage/index.html")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.dokka {
    outputFormat = "html"
    outputDirectory = "$buildDir/docs/api"
}

publishing {
    publications {
        create<MavenPublication>("default") {
            from(components["java"])

            pom {
                name.set(rootProject.name)
                description.set("Relaynet JVM library")
                url.set("https://github.com/relaycorp/relaynet-jvm")
                developers {
                    developer {
                        id.set("relaycorp")
                        name.set("Relaycorp, Inc.")
                        email.set("no-reply@relaycorp.tech")
                    }
                }
                licenses {
                    license {
                        name.set("Apache-2.0")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/relaycorp/relaynet-jvm.git")
                    developerConnection.set("scm:git:https://github.com/relaycorp/relaynet-jvm.git")
                    url.set("https://github.com/relaycorp/relaynet-jvm")
                }
            }
        }
    }
    repositories {
        maven {
            // publish=1 automatically publishes the version
            url = uri(
                "https://api.bintray.com/maven/relaycorp/maven/tech.relaycorp.relaynet/;publish=1"
            )
            credentials {
                username = System.getenv("BINTRAY_USERNAME")
                password = System.getenv("BINTRAY_KEY")
            }
        }
    }
}

spotless {
    val ktlintUserData = mapOf(
        "max_line_length" to "100",
        "disabled_rules" to "import-ordering"
    )

    kotlin {
        ktlint("0.36.0").userData(ktlintUserData)
    }
    kotlinGradle {
        ktlint().userData(ktlintUserData)
    }
}

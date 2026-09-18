import com.github.retrooper.compression.strategy.dir.JsonBase64DataDirStrategy
import com.github.retrooper.compression.strategy.dir.JsonRegistryCompressionDirStrategy
import com.github.retrooper.compression.strategy.dir.JsonToNbtDirStrategy
import com.github.retrooper.excludeAdventure
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    packetevents.`shadow-conventions`
    packetevents.`library-conventions`
    `mapping-compression`
    `pe-version`
}

// papermc repo + disableAutoTargetJvm needed for mockbukkit
repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

java {
    withJavadocJar()
}

dependencies {
    compileOnlyApi(libs.bundles.adventure)
    compileOnlyApi(libs.bundles.adventure.serializers)
    implementation(libs.adventure.api)
    api(project(":patch:adventure-text-serializer-gson", "shadow")) {
        excludeAdventure()
    }
    api(project(":patch:adventure-text-serializer-legacy", "shadow")) {
        excludeAdventure()
    }
    compileOnly(libs.gson)
    compileOnly(libs.adventure.text.logger.slf4j)
    compileOnly(libs.checkerqual)

    testRuntimeOnly(testlibs.bundles.adventure)
    testRuntimeOnly(testlibs.bundles.adventure.serializers)
    testImplementation(libs.bundles.adventure)
    testImplementation(project(":patch:adventure-text-serializer-gson"))
    testImplementation(project(":patch:adventure-text-serializer-legacy"))
    testImplementation(libs.adventure.text.serializer.legacy)
    testImplementation(libs.adventure.text.logger.slf4j)
    testImplementation(project(":netty-common"))
    testImplementation(testlibs.mockbukkit)
    testImplementation(testlibs.paper.api)
    testImplementation(testlibs.slf4j)
    testImplementation(testlibs.bundles.junit)
    testImplementation(libs.netty)
    testImplementation(libs.classgraph)
    testImplementation(project(":spigot"))
    testImplementation(project(":velocity"))
    testImplementation(libs.velocity)
    testImplementation(project(":bungeecord"))
    testImplementation(libs.bungeecord)
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.11.2")
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.2")
    testImplementation(testlibs.jmh.core)
    testAnnotationProcessor(testlibs.jmh.generator)
}

mappingCompression {
    mappingDirectory = rootDir.resolve("mappings")
    outDirectory = project.layout.buildDirectory.dir("mappings/generated/assets/mappings")

    with<JsonToNbtDirStrategy> {
        compress("data")
    }
    with<JsonBase64DataDirStrategy> {
        compress("item_base_components")
    }
    with<JsonRegistryCompressionDirStrategy> {
        compress("registries")
    }
}

tasks {
    assemble {
        setDependsOn(dependsOn.filterNot { it == "shadowNoAdventure" })
    }

    javadoc {
        val options = options as StandardJavadocDocletOptions
        options.use(true)
        options.tags("versions:A:Minecraft Versions:")
        mustRunAfter(generateVersionsFile)
    }

    sourcesJar {
        mustRunAfter(generateVersionsFile)
    }

    withType<JavaCompile> {
        dependsOn(generateVersionsFile)
    }

    processResources {
        dependsOn(compressMappings)
        from(project.layout.buildDirectory.dir("mappings/generated").get())
    }

    generateVersionsFile {
        packageName = "com.github.retrooper.packetevents.util"
    }

    test {
        useJUnitPlatform()
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    register<JavaExec>("jmh") {
        group = "verification"
        description = "Run the JMH benchmarks in src/test/java. Pass args with -PjmhArgs=\"...\""
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass = "org.openjdk.jmh.Main"
        args = ((project.findProperty("jmhArgs") as String?) ?: "")
            .split(" ").filter { it.isNotEmpty() }
    }

    shadowJar {
        exclude {
            val path = it.path
            path.startsWith("net/kyori") && !path.startsWith("net/kyori/adventure/text/serializer") && !path.startsWith(
                "net/kyori/option"
            )
        }
    }
}

publishing {
    publications {
        named<MavenPublication>("shadow") {
            artifact(tasks["javadocJar"])
        }
    }
}

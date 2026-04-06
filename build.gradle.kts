import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer
import com.expediagroup.graphql.plugin.gradle.tasks.GraphQLGenerateClientTask

plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.netflix.dgs.codegen") version "8.3.0"
    id("com.expediagroup.graphql") version "8.2.1"
}

group = "com.agentwork"
version = "0.0.1-SNAPSHOT"
description = "GraphMesh"

val koogVersion = "0.7.3"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
    maven("https://packages.confluent.io/maven/")

}

extra["springAiVersion"] = "2.0.0-M4"
extra["springModulithVersion"] = "2.0.5"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-cassandra")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-graphql")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.ai:spring-ai-starter-mcp-server-webmvc")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.ai:spring-ai-starter-vector-store-qdrant")
    implementation("org.springframework.ai:spring-ai-tika-document-reader")
    implementation("org.springframework.modulith:spring-modulith-starter-core")
    implementation("tools.jackson.module:jackson-module-kotlin")
    implementation("ai.koog:koog-agents-jvm:$koogVersion")
    implementation("ai.koog:koog-spring-boot-starter:$koogVersion")
    implementation("ai.koog:prompt-executor-openai-client:$koogVersion")
    implementation("ai.koog:prompt-executor-anthropic-client:$koogVersion")
    implementation("ai.koog:prompt-executor-ollama-client:$koogVersion")
    implementation("ai.koog:koog-spring-ai-starter-model-embedding:$koogVersion")
    implementation("org.apache.jena:apache-jena-libs:6.0.0")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.apache.avro:avro:1.12.0")
    implementation("io.confluent:kafka-avro-serializer:7.9.0")
    testImplementation("org.springframework.boot:spring-boot-starter-cassandra-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.springframework.modulith:spring-modulith-starter-test")
    testImplementation("io.mockk:mockk:1.13.16")
    implementation(platform("software.amazon.awssdk:bom:2.42.28"))
    implementation("software.amazon.awssdk:s3")
    implementation("com.github.ajalt.clikt:clikt:5.0.1")
    implementation("com.expediagroup:graphql-kotlin-ktor-client:8.2.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.modulith:spring-modulith-bom:${property("springModulithVersion")}")
        mavenBom("org.springframework.ai:spring-ai-bom:${property("springAiVersion")}")
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
    }
}

tasks.generateJava {
    schemaPaths.add("${projectDir}/src/main/resources/graphql-client")
    packageName = "com.agentwork.graphmesh.codegen"
    generateClient = true
}

tasks.withType<Test> {
    useJUnitPlatform()
    jvmArgs("-Xmx2g")
}

val assembleCliSchema by tasks.registering {
    group = "cli"
    description = "Merges all graphql/*.graphqls files into a flat SDL for CLI codegen (extend type → inline fields)."
    val inputDir = layout.projectDirectory.dir("src/main/resources/graphql")
    val outputFile = layout.buildDirectory.file("generated/cli-schema/schema.graphqls")
    inputs.dir(inputDir)
    outputs.file(outputFile)
    doLast {
        val out = outputFile.get().asFile
        out.parentFile.mkdirs()
        val files = inputDir.asFile.listFiles { f -> f.extension == "graphqls" }
            ?: error("No .graphqls files found in ${inputDir.asFile}")
        val sorted = files.sortedWith(
            compareByDescending<java.io.File> { it.name == "schema.graphqls" }
                .thenBy { it.name }
        )
        // Merge extend type blocks into their base types so graphql-kotlin codegen sees one flat schema.
        // Collect all lines from all files, then rewrite extend type X { ... } as plain content appended to type X.
        val combined = sorted.joinToString("\n\n") { it.readText() }
        // Extract fields from each "extend type Foo { ... }" block and append them to the "type Foo { ... }" block.
        val extendPattern = Regex("""extend\s+type\s+(\w+)\s*\{([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        val merged = extendPattern.replace(combined) { "" } // strip all extend blocks first
        val fieldsToAppend = mutableMapOf<String, MutableList<String>>()
        extendPattern.findAll(combined).forEach { match ->
            val typeName = match.groupValues[1]
            val body = match.groupValues[2].trim()
            fieldsToAppend.getOrPut(typeName) { mutableListOf() }.add(body)
        }
        // Now insert the extra fields into the base type blocks.
        val typePattern = Regex("""(type\s+(\w+)\s*\{)([^}]*)\}""", RegexOption.DOT_MATCHES_ALL)
        val result = typePattern.replace(merged) { m ->
            val typeName = m.groupValues[2]
            val extra = fieldsToAppend[typeName]?.joinToString("\n") ?: ""
            val existingBody = m.groupValues[3]
            val newBody = if (extra.isBlank()) existingBody else "$existingBody\n    $extra\n"
            "${m.groupValues[1]}$newBody}"
        }
        out.writeText(result)
    }
}

tasks.named<GraphQLGenerateClientTask>("graphqlGenerateClient") {
    dependsOn(assembleCliSchema)
    packageName.set("com.agentwork.graphmesh.cli.generated")
    schemaFile.set(layout.buildDirectory.file("generated/cli-schema/schema.graphqls"))
    queryFiles.from(
        fileTree("${projectDir}/src/main/kotlin/com/agentwork/graphmesh/cli/queries") {
            include("*.graphql")
        }
    )
    serializer.set(GraphQLSerializer.JACKSON)
}

// Wire the generated GraphQL client sources into the main Kotlin source set so that
// compileKotlin always sees the generated classes.
sourceSets {
    main {
        kotlin {
            srcDir(layout.buildDirectory.dir("generated/source/graphql/main"))
        }
    }
}
tasks.named("compileKotlin") {
    dependsOn(tasks.named("graphqlGenerateClient"))
}

tasks.register<JavaExec>("cliRun") {
    group = "cli"
    description = "Runs the GraphMesh CLI. Usage: ./gradlew cliRun --args=\"collection list\""
    dependsOn(tasks.named("classes"))
    mainClass.set("com.agentwork.graphmesh.cli.GraphMeshCliKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

tasks.register<Jar>("cliJar") {
    group = "cli"
    description = "Builds an executable fat-jar for the CLI."
    dependsOn(tasks.named("classes"))
    archiveBaseName.set("graphmesh-cli")
    archiveVersion.set("")
    archiveClassifier.set("")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    isZip64 = true
    manifest {
        attributes(
            "Main-Class" to "com.agentwork.graphmesh.cli.GraphMeshCliKt",
            "Implementation-Title" to "GraphMesh CLI",
            "Implementation-Version" to project.version
        )
    }
    from(sourceSets["main"].output)
    val runtimeClasspath = configurations.runtimeClasspath.get()
    from({ runtimeClasspath.filter { it.name.endsWith(".jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/INDEX.LIST", "module-info.class")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Copy>("cliInstall") {
    group = "cli"
    description = "Assembles a distributable CLI layout under build/cli with bin/ and lib/."
    dependsOn(tasks.named("cliJar"))
    val jarProvider = tasks.named<Jar>("cliJar").flatMap { it.archiveFile }
    from(jarProvider) {
        into("lib")
    }
    into(layout.buildDirectory.dir("cli"))
    doLast {
        val binDir = layout.buildDirectory.dir("cli/bin").get().asFile
        binDir.mkdirs()
        val shellScript = binDir.resolve("graphmesh")
        shellScript.writeText(
            """
            #!/usr/bin/env bash
            # GraphMesh CLI launcher
            SCRIPT_DIR="${'$'}(cd "${'$'}(dirname "${'$'}{BASH_SOURCE[0]}")" && pwd)"
            LIB_DIR="${'$'}SCRIPT_DIR/../lib"
            exec java -jar "${'$'}LIB_DIR/graphmesh-cli.jar" "${'$'}@"
            """.trimIndent() + "\n"
        )
        shellScript.setExecutable(true)

        val batScript = binDir.resolve("graphmesh.bat")
        batScript.writeText(
            """
            @echo off
            rem GraphMesh CLI launcher (Windows)
            set SCRIPT_DIR=%~dp0
            set LIB_DIR=%SCRIPT_DIR%..\lib
            java -jar "%LIB_DIR%\graphmesh-cli.jar" %*
            """.trimIndent() + "\r\n"
        )
    }
}

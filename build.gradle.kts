plugins {
    kotlin("jvm") version "2.2.21"
    kotlin("plugin.spring") version "2.2.21"
    id("org.springframework.boot") version "4.0.5"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.netflix.dgs.codegen") version "8.3.0"
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

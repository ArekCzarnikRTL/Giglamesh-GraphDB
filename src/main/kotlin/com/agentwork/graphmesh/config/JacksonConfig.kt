package com.agentwork.graphmesh.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Spring Boot 4 wechselt auf Jackson 3 (`tools.jackson.*`) und stellt nur dafuer
 * einen ObjectMapper-Bean bereit. Mehrere Services im Projekt importieren weiterhin
 * die klassische Jackson-2-Variante (`com.fasterxml.jackson.databind.ObjectMapper`).
 * Dieser Bean schliesst die Luecke.
 */
@Configuration
class JacksonConfig {
    @Bean
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()
}

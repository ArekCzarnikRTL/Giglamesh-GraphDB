package com.agentwork.graphmesh.api

import org.springframework.graphql.data.method.annotation.MutationMapping
import org.springframework.stereotype.Controller

@Controller
class PurgeController(
    private val purgeService: PurgeService,
) {

    @MutationMapping
    fun purgeAllData(): PurgeService.PurgeResult = purgeService.purgeAll()
}

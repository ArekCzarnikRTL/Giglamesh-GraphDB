package com.agentwork.graphmesh.llm

/**
 * Strips C0 control characters (except TAB, LF) and normalises line endings.
 *
 * Background: the Koog OpenAI client builds JSON request bodies that do not
 * escape all control characters correctly. When the input text contains
 * bytes like 0x00..0x1F (other than TAB, LF), OpenAI responds with HTTP 400
 * "We could not parse the JSON body of your request". This can happen with
 * Markdown pulled from tools that embed binary or unusual whitespace, with
 * PDFs that contained form feeds, or with imported documents of unknown
 * provenance.
 *
 * Call this on any text that is going to be sent to the LLM.
 */
fun sanitizeForLlm(text: String): String {
    val normalised = text.replace("\r\n", "\n").replace('\r', '\n')
    return buildString(normalised.length) {
        for (c in normalised) {
            if (c == '\t' || c == '\n' || c.code >= 0x20) append(c)
        }
    }
}

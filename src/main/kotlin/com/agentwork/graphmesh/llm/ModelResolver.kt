package com.agentwork.graphmesh.llm

import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.ollama.client.OllamaModels
import ai.koog.prompt.llm.LLModel

/**
 * Koog 0.7.3 verlangt fuer chat- bzw. embed-Aufrufe vorgefertigte LLModel-Instanzen
 * mit den richtigen Capabilities. Ein nacktes `LLModel(LLMProvider.OpenAI, "gpt-4o")`
 * traegt keine Capabilities und fuehrt zu Laufzeitfehlern wie
 * "Cannot determine proper LLM params for OpenAI model: gpt-4o" oder
 * "Model text-embedding-3-small does not support embed".
 *
 * Dieser Resolver ist provider-agnostisch: Er mappt bekannte OpenAI- und Ollama-Namen
 * auf die passenden Konstanten aus `OpenAIModels.*` bzw. `OllamaModels.*`. Unbekannte
 * Namen werden als dynamisches Ollama-Modell behandelt (das entspricht Koogs
 * `client.createDynamicModel("...")`-Verhalten) — so koennen beliebige Ollama-Tags
 * wie `llama3.2:3b`, `mxbai-embed-large:335m`, etc. verwendet werden, ohne den
 * Resolver anfassen zu muessen.
 */
fun resolveLlmModel(name: String): LLModel = when (name.lowercase()) {
    // --- OpenAI chat ---
    "gpt-4o" -> OpenAIModels.Chat.GPT4o
    "gpt-4o-mini" -> OpenAIModels.Chat.GPT4oMini
    "gpt-4.1" -> OpenAIModels.Chat.GPT4_1
    "gpt-4.1-mini" -> OpenAIModels.Chat.GPT4_1Mini
    "gpt-4.1-nano" -> OpenAIModels.Chat.GPT4_1Nano

    // --- OpenAI embeddings ---
    "text-embedding-3-small" -> OpenAIModels.Embeddings.TextEmbedding3Small
    "text-embedding-3-large" -> OpenAIModels.Embeddings.TextEmbedding3Large
    "text-embedding-ada-002" -> OpenAIModels.Embeddings.TextEmbeddingAda002

    // --- Ollama chat (Meta LLAMA) ---
    "llama3.2", "llama-3.2" -> OllamaModels.Meta.LLAMA_3_2
    "llama3.2:3b", "llama-3.2:3b", "llama3.2-3b" -> OllamaModels.Meta.LLAMA_3_2_3B

    // --- Ollama embeddings ---
    "nomic-embed-text" -> OllamaModels.Embeddings.NOMIC_EMBED_TEXT
    "all-minilm" -> OllamaModels.Embeddings.ALL_MINI_LM
    "bge-large" -> OllamaModels.Embeddings.BGE_LARGE
    "multilingual-e5" -> OllamaModels.Embeddings.MULTILINGUAL_E5
    "mxbai-embed-large" -> OllamaModels.Embeddings.MXBAI_EMBED_LARGE

    else -> error(
        "Unbekanntes LLM-Modell: '$name'. Bitte einen der unterstuetzten Namen " +
            "in graphmesh.embedding.model bzw. graphmesh.extraction.model verwenden " +
            "(OpenAI: gpt-4o, gpt-4o-mini, text-embedding-3-small; " +
            "Ollama: llama3.2, llama3.2:3b, nomic-embed-text, mxbai-embed-large, ...)."
    )
}

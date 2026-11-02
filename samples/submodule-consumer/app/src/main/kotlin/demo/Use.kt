package demo

import com.thalzeronlabs.llama.ChatMessage
import com.thalzeronlabs.llama.Grammar
import com.thalzeronlabs.llama.LlamaSession

// Compiles only if the included module exposes the v0.2.x API.
fun ask(session: LlamaSession) = session.chat(
    listOf(ChatMessage.user("hi")),
    grammar = Grammar.jsonObject(),
    temperature = 0.2f,
)

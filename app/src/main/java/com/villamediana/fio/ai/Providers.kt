package com.villamediana.fio.ai

/** How a provider is spoken to: the OpenAI way (most of them) or Anthropic's Messages API. */
enum class Wire { OPENAI, ANTHROPIC }

/**
 * Where her own intelligence lives. Most only need the key (the address is known); Azure AI
 * Foundry and "Outro" need the address too. [hears]: it turns speech into text as well — when it
 * doesn't, hearing stays on the phone.
 */
data class Provider(
    val id: String,
    val name: String,
    val base: String?,
    val wire: Wire,
    val hears: Boolean,
    /** What the address looks like, when she types it. */
    val baseHint: String = "https://…/v1",
)

object Providers {
    val OPENAI = Provider("openai", "OpenAI", "https://api.openai.com/v1", Wire.OPENAI, hears = true)
    val ANTHROPIC = Provider("anthropic", "Anthropic", "https://api.anthropic.com/v1", Wire.ANTHROPIC, hears = false)
    val KIMI = Provider("kimi", "Kimi", "https://api.moonshot.ai/v1", Wire.OPENAI, hears = false)
    val FOUNDRY = Provider("foundry", "Azure Foundry", null, Wire.OPENAI, hears = true, baseHint = "https://<recurso>.openai.azure.com/openai/v1")
    val OPENROUTER = Provider("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", Wire.OPENAI, hears = false)
    val OTHER = Provider("custom", "Outro", null, Wire.OPENAI, hears = true)

    val all = listOf(OPENAI, ANTHROPIC, KIMI, FOUNDRY, OPENROUTER, OTHER)

    /** The one saved; nothing saved (set up before providers existed) is "Outro", with the address she typed. */
    fun of(id: String?): Provider = all.firstOrNull { it.id == id } ?: OTHER
}

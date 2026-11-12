// Decisions with LFM3 d1 (Liquid AI's decision model), the way llama.cpp's /v1/systemone does them
// (tools/server/server-decision.cpp, type lfm3-d1): one prompt per question, read in one pass
// (the model is not causal: every token sees the whole prompt), and at its end the logits of each
// answer's label tokens. Nothing is generated. Only llama.h: the app's engine and the lab's check
// on the computer use this same file.
//
// What is left to the caller: rendering the prompt (the model's own "systemone" template) and the
// softmax with the model's temperature over the scores returned here.

#pragma once

#include "llama.h"

#include <algorithm>
#include <cstring>
#include <string>
#include <vector>

namespace fio {

/** [text]'s tokens as plain text: nothing added, special tokens not read as such. */
inline std::vector<llama_token> plain_tokens(const llama_vocab * vocab, const std::string & text) {
    std::vector<llama_token> toks(text.size() + 8);
    int n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), false, false);
    if (n < 0) {
        toks.resize((size_t) -n);
        n = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(), toks.data(), (int32_t) toks.size(), false, false);
    }
    toks.resize(n > 0 ? (size_t) n : 0);
    return toks;
}

/**
 * The tokens an answer can start with: each spelling in [forms] that is a single token, once
 * ("yes", "Yes", "YES"; "A", " A"). Its score is the best of their logits.
 */
inline std::vector<llama_token> label_group(const llama_vocab * vocab, const std::vector<std::string> & forms) {
    std::vector<llama_token> out;
    for (const auto & form : forms) {
        const auto toks = plain_tokens(vocab, form);
        if (toks.size() == 1 && std::find(out.begin(), out.end(), toks[0]) == out.end()) out.push_back(toks[0]);
    }
    return out;
}

/**
 * The texts of the control tokens (2 bytes or more), longest first: written in what she said they
 * would be read as the token itself, so the caller breaks them (a zero-width space after their first
 * character), as d1_break does.
 */
inline std::vector<std::string> control_spellings(const llama_vocab * vocab) {
    std::vector<std::string> out;
    const int n = llama_vocab_n_tokens(vocab);
    for (llama_token id = 0; id < n; id++) {
        if (llama_vocab_get_attr(vocab, id) & LLAMA_TOKEN_ATTR_CONTROL) {
            const char * text = llama_vocab_get_text(vocab, id);
            if (text && std::strlen(text) >= 2) out.emplace_back(text);
        }
    }
    std::stable_sort(out.begin(), out.end(), [](const std::string & a, const std::string & b) { return a.size() > b.size(); });
    return out;
}

/**
 * [prompt] (rendered, with its special tokens written out) read in one pass from an empty memory,
 * then each group's best logit at the last position. Empty when it can't be done (too long for one
 * pass, a failed decode, a group with no token).
 */
inline std::vector<float> decide(llama_context * ctx, const llama_vocab * vocab, const std::string & prompt, const std::vector<std::vector<llama_token>> & groups) {
    std::vector<llama_token> toks(prompt.size() + 16);
    int n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), false, true);
    if (n < 0) {
        toks.resize((size_t) -n);
        n = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(), toks.data(), (int32_t) toks.size(), false, true);
    }
    if (n <= 0) return {};
    toks.resize((size_t) n);
    // Not causal: the whole prompt has to be in one micro-batch.
    if ((uint32_t) n > llama_n_ubatch(ctx) || (uint32_t) n > llama_n_ctx(ctx)) return {};
    for (const auto & g : groups) if (g.empty()) return {};

    // A model like this has no memory (nothing to keep between passes): it is encoded, which is what
    // llama_decode would end up doing anyway (with a warning each time).
    llama_memory_t mem = llama_get_memory(ctx);
    if (mem) llama_memory_clear(mem, true);
    llama_batch b = llama_batch_get_one(toks.data(), n);
    if ((mem ? llama_decode(ctx, b) : llama_encode(ctx, b)) != 0) return {};
    const float * logits = llama_get_logits_ith(ctx, -1);
    if (!logits) return {};

    std::vector<float> scores;
    scores.reserve(groups.size());
    for (const auto & g : groups) {
        float best = logits[g[0]];
        for (const llama_token t : g) best = std::max(best, logits[t]);
        scores.push_back(best);
    }
    if (mem) llama_memory_clear(mem, true);
    return scores;
}

} // namespace fio

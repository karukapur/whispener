# Output Reliability Strategy Order

Ordered from cheapest to most expensive in LLM calls/token cost.

| Order | Strategy | Extra LLM Calls | Token Cost | Latency Cost | What It Fixes |
| ---: | --- | ---: | --- | --- | --- |
| 1 | Local eager validation: accumulate chunks and commit only after valid JSON parses | 0 | none | none | Prevents partial JSON from reaching UI |
| 2 | More tolerant local parser for fenced JSON, wrapped JSON, and simple heading/bullets | 0 | none | none | Recovers usable outputs that are not perfect JSON |
| 3 | Prompt/schema tightening with shorter required output | 0 | slightly lower or neutral | neutral | Reduces truncation risk and malformed output risk |
| 4 | Disable streaming for final commits, keep previous context until full response arrives | 0 | same | may feel less live | Avoids streamed partial JSON edge cases |
| 5 | Use a specific reliable structured-output model instead of `openrouter/free` | 0 | model-dependent, often paid | model-dependent | Avoids unstable free-router provider selection |
| 6 | Retry once only after `InvalidResponse` using the same request shape | +1 max | up to 2x for failed interval | higher | Recovers transient malformed output |
| 7 | Streaming first, then non-streaming retry if final streamed JSON is invalid | +1 max | up to 2x for failed interval | higher | Keeps live drafts but improves final commit reliability |
| 8 | Fallback from free router to a known reliable paid model | +1 max | paid fallback | higher | Recovers free-router failure while preserving context |
| 9 | LLM repair pass over invalid output | +1 max | extra repair prompt/completion | higher | Can repair malformed but non-empty JSON-like text |

Recommendation: try orders 1-4 before adding retries or paid fallback. Backoff is not listed here because it reduces call pressure but increases staleness, which is a separate product tradeoff.


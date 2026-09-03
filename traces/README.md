# Trace Handoffs

Use `summarize_trace_for_llm.py` before giving a trace to an LLM. Listener traces can become too large to read directly, so the script emits a short root-cause brief with the setup signals, compact counters, and the most useful evidence lines.

## Default Brief

```sh
python3 traces/summarize_trace_for_llm.py
```

By default the script:

- Selects the newest `listener-summary-trace-*.txt` file by modified time.
- Redacts OpenRouter- and Groq-style secrets defensively.
- Keeps output around a small character budget.
- Prioritizes causal signals over repeated runtime noise.

Runtime trace fields use `cadenceMillis` for the active adaptive summary
cadence. `configuredCadenceMillis` preserves the stored user preference for
comparison, and `adaptiveCadencePhase` reports `warmup`, `middle`, or
`sustained`.

## Useful Options

```sh
python3 traces/summarize_trace_for_llm.py --file traces/listener-summary-trace-4-1787833659379.txt
python3 traces/summarize_trace_for_llm.py --output /tmp/listener-trace-brief.md
python3 traces/summarize_trace_for_llm.py --full --max-events 24 --budget-chars 0
```

Use the brief output first. Use `--full` only when the brief points to a timing, parser, or UI-state ambiguity that needs more surrounding evidence.

## Rate Limit Traces

`RateLimited` failures include safe provider headers when Groq or OpenRouter returns them:

- `retryAfterSeconds`
- `rateLimitRemainingRequests` / `rateLimitResetRequests`
- `rateLimitRemainingTokens` / `rateLimitResetTokens`

If the app pauses retries after one of these failures, the runtime log will show `summary_rate_limit_cooldown_started`, followed by `summary_attempt_skipped reason=remote_rate_limit_cooldown` while local transcript capture continues.

`summary_attempt_skipped reason=groq_token_budget_cooldown` means the app did not call Groq because its local rolling token estimate says the next request would exceed the current Groq tokens-per-minute window. Finalized Chinese remains pending and should be sent in a bounded chunk after the token window clears.

## Delta Guard Traces

`summary_attempt_skipped reason=stable_transcript_delta_below_minimum` means remote summaries were otherwise ready, but fewer than three new finalized Chinese characters were pending. `lastSentTranscript` is not advanced on this skip, so the tiny delta accumulates into the next eligible request.

## Agent Note

Do not add a nested `AGENTS.md` here unless the trace workflow needs rules that differ from the repository root. The root `AGENTS.md` should remain the canonical place for agent operating instructions; this folder README is just an operator guide for log compression.

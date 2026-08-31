# Listener Cadence Experiment

Standalone cadence sweep for Listener's remote English-context operation.

The harness is intentionally separate from the Android app. It does not read or
write app preferences, app storage, databases, or exported traces. It only sends
a fixed Traditional Chinese transcript to OpenRouter or Groq using the same
high-level summary contract as the app and records aggregate evidence.

## Run

```sh
OPENROUTER_API_KEY=sk-or-v1-... python3 experiments/cadence_expts/cadence_experiment.py
GROQ_API_KEY=gsk_... python3 experiments/cadence_expts/cadence_experiment.py --provider groq
```

If `local.properties` contains `OPENROUTER_API_KEY` or `openrouter.apiKey`, you
can pass it without printing the value:

```sh
KEY=$(awk -F= '/^(OPENROUTER_API_KEY|openrouter.apiKey)=/ && length($2)>0 {print $2; exit}' local.properties)
OPENROUTER_API_KEY="$KEY" python3 experiments/cadence_expts/cadence_experiment.py
```

For Groq:

```sh
KEY=$(awk -F= '/^(GROQ_API_KEY|groq.apiKey)=/ && length($2)>0 {print $2; exit}' local.properties)
GROQ_API_KEY="$KEY" python3 experiments/cadence_expts/cadence_experiment.py --provider groq
```

Useful options:

```sh
python3 experiments/cadence_expts/cadence_experiment.py --cadences 1,2,3,5,8,10,15
python3 experiments/cadence_expts/cadence_experiment.py --provider groq --cadences 5,8,10,15 --chunk-size 300 --script-repeats 20 --rest-between-cadences 30
python3 experiments/cadence_expts/cadence_experiment.py --shuffle --rest-between-cadences 60
python3 experiments/cadence_expts/cadence_experiment.py --non-streaming
```

## Output

Each run writes local evidence files under `experiments/cadence_expts/results/`:

- `cadence_sweep_<timestamp>.jsonl`: per-attempt machine-readable records.
- `cadence_sweep_<timestamp>.md`: human-readable summary table and result notes.

`results/` is gitignored because timings and failures are environment-specific.

Groq rows include `retry_after` and `x-ratelimit-*` header values in the JSONL
when Groq returns them. These are the fields to inspect when deciding whether a
failure was minute-window pressure, daily request exhaustion, or token pressure.

## Pass Criteria

A cadence is considered seamless for the tested account/model/time window when:

- No `RateLimited` failures occur.
- No parse/shape failures occur.
- p90 success latency is below the cadence interval.
- `last_sent` advances on every successful response, keeping unsent delta small.

A passing cadence is evidence for a tested account/model/time window, not a
guarantee that cooldown can be removed. Daily token/request limits and shared
organization usage can still change outside the app.

## Current Groq Evidence

Runs on 2026-08-31 against Groq `openai/gpt-oss-20b` with a repeated
7,439-character transcript:

- `10s`, 600-character chunks: 13/13 successes, p90 813 ms.
- `15s`, 600-character chunks: 13/13 successes, p90 1,358 ms.
- `8s`, 600-character chunks: 11/13 successes, one JSON validation HTTP 400,
  one TPM `RateLimited` response. The 429 showed request quota remaining but
  token-per-minute pressure, with `retry_after=4`.

Recommendation from this evidence: use 10 seconds as the sustained Groq cadence,
and keep rate-limit cooldown as a defensive fallback for unusually dense speech,
shared organization usage, or daily/token limits outside this controlled sweep.

## Adaptive Groq Cadence

The app now skips remote summary attempts until at least three new finalized
Chinese characters are pending. This Delta Guard removes silence and tiny
finalization churn from the API call stream without discarding text, because the
pending delta accumulates until the next eligible request.

Use this decision process before changing Groq cadence phases:

- Measure first-context latency: time from recording start to first committed
  English context.
- Measure context freshness: p90 age of pending finalized Chinese at commit.
- Measure provider pressure: lowest `rateLimitRemainingTokens`, reset windows,
  and any `RateLimited` rows.
- Accept a phase only when p90 request latency is below that phase interval,
  there are no 429s or parse failures, and token headroom does not steadily
  decay across the phase.

Current candidate schedule applied in the app across the remote model suite:

- Warmup: first eligible summary after the startup delay, then 5 second ticks
  during the first recording minute.
- Middle: 8 second ticks during the second recording minute.
- Sustained: 10 second ticks afterward, with the existing header-driven cooldown
  as a defensive fallback.

This schedule is Groq-derived evidence being applied to every remote model for
the current trial. Re-run the sweep against OpenRouter models before claiming it
as an OpenRouter-specific recommendation.

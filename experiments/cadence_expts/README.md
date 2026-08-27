# Listener Cadence Experiment

Standalone cadence sweep for Listener's OpenRouter English-context operation.

The harness is intentionally separate from the Android app. It does not read or
write app preferences, app storage, databases, or exported traces. It only sends
a fixed Traditional Chinese transcript to OpenRouter using the same high-level
summary contract as the app and records aggregate evidence.

## Run

```sh
OPENROUTER_API_KEY=sk-or-v1-... python3 experiments/cadence_expts/cadence_experiment.py
```

If `local.properties` contains `OPENROUTER_API_KEY` or `openrouter.apiKey`, you
can pass it without printing the value:

```sh
KEY=$(awk -F= '/^(OPENROUTER_API_KEY|openrouter.apiKey)=/ && length($2)>0 {print $2; exit}' local.properties)
OPENROUTER_API_KEY="$KEY" python3 experiments/cadence_expts/cadence_experiment.py
```

Useful options:

```sh
python3 experiments/cadence_expts/cadence_experiment.py --cadences 1,2,3,5,8,10,15
python3 experiments/cadence_expts/cadence_experiment.py --shuffle --rest-between-cadences 60
python3 experiments/cadence_expts/cadence_experiment.py --non-streaming
```

## Output

Each run writes local evidence files under `experiments/cadence_expts/results/`:

- `cadence_sweep_<timestamp>.jsonl`: per-attempt machine-readable records.
- `cadence_sweep_<timestamp>.md`: human-readable summary table and result notes.

`results/` is gitignored because timings and failures are environment-specific.

## Pass Criteria

A cadence is considered seamless for the tested account/model/time window when:

- No `RateLimited` failures occur.
- No parse/shape failures occur.
- p90 success latency is below the cadence interval.
- `last_sent` advances on every successful response, keeping unsent delta small.

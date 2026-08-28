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

## Useful Options

```sh
python3 traces/summarize_trace_for_llm.py --file traces/listener-summary-trace-4-1787833659379.txt
python3 traces/summarize_trace_for_llm.py --output /tmp/listener-trace-brief.md
python3 traces/summarize_trace_for_llm.py --full --max-events 24 --budget-chars 0
```

Use the brief output first. Use `--full` only when the brief points to a timing, parser, or UI-state ambiguity that needs more surrounding evidence.

## Agent Note

Do not add a nested `AGENTS.md` here unless the trace workflow needs rules that differ from the repository root. The root `AGENTS.md` should remain the canonical place for agent operating instructions; this folder README is just an operator guide for log compression.

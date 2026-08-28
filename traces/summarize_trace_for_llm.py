#!/usr/bin/env python3
"""Create an LLM-ready summary from the newest Listener trace.

Usage:
    python3 traces/summarize_trace_for_llm.py
    python3 traces/summarize_trace_for_llm.py --file traces/listener-summary-trace-4-1787833659379.txt
    python3 traces/summarize_trace_for_llm.py --output /tmp/listener-trace-summary.md
    python3 traces/summarize_trace_for_llm.py --full --max-events 24

The script is intentionally stdlib-only so it can run in a fresh checkout.
It redacts OpenRouter-style keys defensively even though exported traces should
already avoid writing secrets.
"""

from __future__ import annotations

import argparse
import collections
import pathlib
import re
import sys
from dataclasses import dataclass
from typing import Iterable


TRACE_GLOB = "listener-summary-trace-*.txt"
TIMESTAMPED_LINE = re.compile(
    r"^(?P<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [+-]\d{4}) "
    r"(?P<label>[A-Za-z0-9_]+)(?: (?P<fields>.*))?$"
)
FIELD_START = re.compile(r"(?<!\S)([A-Za-z][A-Za-z0-9]*)=")
SECRET = re.compile(r"(?i)(bearer\s+|sk-or-v1-|gsk[_-])[A-Za-z0-9._-]+")
DEFAULT_BUDGET_CHARS = 4500
INTERESTING_FIELDS = [
    "reason",
    "engine",
    "backend",
    "activeModelId",
    "remoteEnabled",
    "apiKeyPresent",
    "selectedRemoteModel",
    "selectedModel",
    "requestedModel",
    "effectiveModel",
    "remoteStatus",
    "message",
    "stableTranscriptChars",
    "deltaChars",
    "fullDeltaChars",
    "firstTokenSeen",
    "fallbackAttempted",
    "fallbackResult",
    "lastSentTranscriptChars",
    "lastSentTranscriptAdvanced",
]


@dataclass(frozen=True)
class TraceEvent:
    line_number: int
    timestamp: str
    label: str
    fields: dict[str, str]
    raw: str


def redact(text: str) -> str:
    return SECRET.sub(lambda match: "Bearer [REDACTED]" if match.group(1).lower().startswith("bearer") else "[REDACTED]", text)


def latest_trace(trace_dir: pathlib.Path) -> pathlib.Path:
    candidates = [path for path in trace_dir.glob(TRACE_GLOB) if path.is_file()]
    if not candidates:
        raise SystemExit(f"No {TRACE_GLOB} files found in {trace_dir}")
    return max(candidates, key=lambda path: path.stat().st_mtime)


def parse_fields(raw_fields: str) -> dict[str, str]:
    matches = list(FIELD_START.finditer(raw_fields))
    fields: dict[str, str] = {}
    for index, match in enumerate(matches):
        start = match.end()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(raw_fields)
        fields[match.group(1)] = raw_fields[start:end].strip()
    return fields


def parse_events(lines: list[str]) -> list[TraceEvent]:
    events: list[TraceEvent] = []
    for line_number, line in enumerate(lines, start=1):
        match = TIMESTAMPED_LINE.match(line)
        if not match:
            continue
        fields = parse_fields(match.group("fields") or "")
        events.append(
            TraceEvent(
                line_number=line_number,
                timestamp=match.group("ts"),
                label=match.group("label"),
                fields=fields,
                raw=redact(line),
            )
        )
    return events


def key_value_section(lines: list[str], header: str) -> dict[str, str]:
    try:
        start = lines.index(header) + 1
    except ValueError:
        return {}
    values: dict[str, str] = {}
    for line in lines[start:]:
        if not line.strip():
            break
        if "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = redact(value.strip())
    return values


def text_between(lines: list[str], start_header: str, end_header: str | None = None) -> str:
    try:
        start = lines.index(start_header) + 1
    except ValueError:
        return ""
    end = len(lines)
    if end_header is not None:
        try:
            end = lines.index(end_header, start)
        except ValueError:
            pass
    return "\n".join(lines[start:end]).strip()


def count_field(events: Iterable[TraceEvent], label: str, field: str) -> collections.Counter[str]:
    return collections.Counter(
        event.fields.get(field, "missing")
        for event in events
        if event.label == label
    )


def first_field(events: Iterable[TraceEvent], *keys: str) -> str | None:
    for event in events:
        for key in keys:
            value = event.fields.get(key)
            if value and value != "none":
                return value
    return None


def truncate(value: str, limit: int = 120) -> str:
    if len(value) <= limit:
        return value
    return value[: max(0, limit - 3)].rstrip() + "..."


def counter_summary(counter: collections.Counter[str], limit: int = 5) -> str:
    if not counter:
        return "none"
    items = [f"{truncate(key, 80)}={value}" for key, value in counter.most_common(limit)]
    hidden = len(counter) - len(items)
    if hidden > 0:
        items.append(f"+{hidden} more")
    return ", ".join(items)


def first_event(events: Iterable[TraceEvent], label: str) -> TraceEvent | None:
    return next((event for event in events if event.label == label), None)


def first_non_empty_field(events: Iterable[TraceEvent], key: str) -> str | None:
    fallback: str | None = None
    for event in events:
        value = event.fields.get(key)
        if not value:
            continue
        if value != "none":
            return value
        fallback = fallback or value
    return fallback


def max_int_field(events: Iterable[TraceEvent], key: str) -> str | None:
    values: list[int] = []
    for event in events:
        value = event.fields.get(key)
        if value and value.isdigit():
            values.append(int(value))
    if not values:
        return None
    return str(max(values))


def last_event(events: Iterable[TraceEvent], label: str) -> TraceEvent | None:
    for event in reversed(list(events)):
        if event.label == label:
            return event
    return None


def root_cause(events: list[TraceEvent], persisted_text: str) -> tuple[str, list[str]]:
    skips = count_field(events, "summary_attempt_skipped", "reason")
    failures = [event for event in events if event.label == "summary_response_failed"]
    commits = [event for event in events if event.label == "summary_response_committed"]
    first_drafts = [event for event in events if event.label == "openrouter_first_streaming_draft"]
    no_persisted = "No English summaries recorded" in persisted_text

    failure_messages = " ".join(event.fields.get("message", "") for event in failures)
    failure_statuses = {event.fields.get("remoteStatus", "") for event in failures}
    advanced_on_failure = any(event.fields.get("lastSentTranscriptAdvanced") == "yes" for event in failures)
    likely_legacy_advance = not advanced_on_failure and any(
        event.fields.get("reason") == "stable_transcript_unchanged_since_last_sent"
        for event in events
    ) and failures and not commits

    evidence: list[str] = []
    if commits:
        summary = "English context was produced; inspect latency/staleness rather than total failure."
        evidence.append(f"{len(commits)} committed summary event(s) were found.")
        if first_drafts:
            evidence.append(f"{len(first_drafts)} first streaming draft event(s) were found.")
        return summary, evidence

    if skips.get("remote_summaries_disabled", 0):
        summary = "Remote English summaries were disabled."
        evidence.append(f"{skips['remote_summaries_disabled']} summary attempt(s) skipped with remote_summaries_disabled.")
        evidence.append("Fix setup/preferences before investigating OpenRouter or local speech.")
        return summary, evidence

    if skips.get("missing_openrouter_key", 0):
        summary = "OpenRouter key was missing when summaries attempted to run."
        evidence.append(f"{skips['missing_openrouter_key']} summary attempt(s) skipped with missing_openrouter_key.")
        return summary, evidence

    if skips.get("missing_openrouter_model", 0):
        summary = "No remote OpenRouter summary model was selected."
        evidence.append(f"{skips['missing_openrouter_model']} summary attempt(s) skipped with missing_openrouter_model.")
        return summary, evidence

    if failures and ("ModelUnavailable" in failure_statuses or "No endpoints found" in failure_messages):
        summary = "Selected OpenRouter model had no usable endpoint for the summary request."
        evidence.append(f"{len(failures)} remote failure event(s) were recorded.")
        evidence.append("The failure message includes 'No endpoints found' or status ModelUnavailable.")
        selected = first_field(events, "selectedRemoteModel", "selectedModel", "requestedModel")
        if selected:
            evidence.append(f"Selected/requested remote model: {selected}.")
        if likely_legacy_advance:
            evidence.append("Subsequent unchanged-transcript skips suggest failed requests consumed transcript text before a valid English context existed.")
        return summary, evidence

    if failures:
        status_counts = collections.Counter(event.fields.get("remoteStatus", "missing") for event in failures)
        summary = "Remote summary requests failed before English context could be committed."
        evidence.append("Failure statuses: " + ", ".join(f"{key}={value}" for key, value in status_counts.items()))
        if no_persisted:
            evidence.append("No persisted English summaries were recorded.")
        return summary, evidence

    if skips and set(skips) == {"stable_transcript_empty"}:
        summary = "No finalized Chinese transcript was available for summarization."
        evidence.append(f"{skips['stable_transcript_empty']} attempt(s) skipped with stable_transcript_empty.")
        return summary, evidence

    if no_persisted:
        summary = "No English context was persisted; evidence is inconclusive from high-level counters."
        evidence.append("Review the key runtime lines below, especially skipped reasons and OpenRouter events.")
        return summary, evidence

    return "No obvious English-context failure detected.", ["Review raw trace lines for timing and UX expectations."]


def compact_event_line(event: TraceEvent) -> str:
    fields = " ".join(
        f"{key}={truncate(event.fields[key])}"
        for key in INTERESTING_FIELDS
        if key in event.fields
    )
    return f"- L{event.line_number} {event.timestamp} `{event.label}` {fields}".rstrip()


def brief_key_events(events: list[TraceEvent], limit: int) -> list[TraceEvent]:
    selected: list[TraceEvent] = []

    for label in [
        "trace_started_for_recording",
        "start_recording_requested",
        "summary_scheduler_started",
        "summary_prompt_prepared",
        "openrouter_request_started",
        "openrouter_fallback_retry_started",
        "openrouter_first_streaming_draft",
        "summary_response_failed",
        "summary_response_committed",
        "summary_response_valid_unchanged",
    ]:
        event = first_event(events, label)
        if event:
            selected.append(event)

    seen_failures: set[tuple[str, str]] = set()
    for event in events:
        if event.label != "summary_response_failed":
            continue
        identity = (event.fields.get("remoteStatus", ""), event.fields.get("message", ""))
        if identity in seen_failures:
            continue
        seen_failures.add(identity)
        selected.append(event)

    for reason in ["remote_summaries_disabled", "missing_openrouter_key", "missing_openrouter_model", "stable_transcript_empty", "stable_transcript_unchanged_since_last_sent"]:
        event = next(
            (
                item
                for item in events
                if item.label == "summary_attempt_skipped" and item.fields.get("reason") == reason
            ),
            None,
        )
        if event:
            selected.append(event)

    latest_failure = last_event(events, "summary_response_failed")
    if latest_failure:
        selected.append(latest_failure)

    deduped: list[TraceEvent] = []
    seen_lines: set[int] = set()
    for event in sorted(selected, key=lambda item: item.line_number):
        if event.line_number in seen_lines:
            continue
        seen_lines.add(event.line_number)
        deduped.append(event)
    return deduped[:limit]


def full_key_events(events: list[TraceEvent], limit: int) -> list[TraceEvent]:
    labels = {
        "trace_started_for_recording",
        "start_recording_requested",
        "summary_scheduler_started",
        "summary_prompt_prepared",
        "openrouter_request_started",
        "openrouter_fallback_retry_started",
        "openrouter_first_streaming_draft",
        "summary_response_committed",
        "summary_response_valid_unchanged",
        "summary_response_failed",
    }
    selected = [event for event in events if event.label in labels]
    selected.extend(
        event
        for event in events
        if event.label == "summary_attempt_skipped"
        and event.fields.get("reason") in {"remote_summaries_disabled", "missing_openrouter_key", "missing_openrouter_model", "stable_transcript_empty"}
    )
    deduped: list[TraceEvent] = []
    seen: set[tuple[str, str, str]] = set()
    for event in sorted(selected, key=lambda item: item.line_number):
        identity = (event.label, event.fields.get("reason", ""), event.fields.get("stableTranscriptChars", ""))
        if identity in seen and event.label == "summary_attempt_skipped":
            continue
        seen.add(identity)
        deduped.append(event)
    if len(deduped) <= limit:
        return deduped
    head = max(1, limit // 2)
    tail = limit - head
    return deduped[:head] + deduped[-tail:]


def key_events(events: list[TraceEvent], limit: int, full: bool) -> list[TraceEvent]:
    if full:
        return full_key_events(events, limit)
    return brief_key_events(events, limit)


def trim_report(report: str, budget_chars: int) -> str:
    if budget_chars <= 0 or len(report) <= budget_chars:
        return report
    marker = "\n## Evidence Lines\n"
    if marker not in report:
        return report[: max(0, budget_chars - 40)].rstrip() + "\n\n[trimmed]\n"
    head, tail = report.split(marker, 1)
    trimmed = head[: max(0, budget_chars - 90)].rstrip()
    return trimmed + "\n\n[trimmed to budget before evidence lines; rerun with --full or --budget-chars 0]\n"


def make_report(path: pathlib.Path, max_events: int, full: bool, budget_chars: int) -> str:
    text = redact(path.read_text(errors="replace"))
    lines = text.splitlines()
    events = parse_events(lines)
    diagnostics = key_value_section(lines, "Current diagnostics snapshot")
    persisted = text_between(lines, "Persisted English summaries", "Runtime summary decision log")
    cause, evidence = root_cause(events, persisted)
    label_counts = collections.Counter(event.label for event in events)
    skip_counts = count_field(events, "summary_attempt_skipped", "reason")
    failure_status_counts = count_field(events, "summary_response_failed", "remoteStatus")
    failure_messages = collections.Counter(
        event.fields.get("message", "missing")
        for event in events
        if event.label == "summary_response_failed"
    )

    report: list[str] = []
    report.append("# Listener Trace Brief")
    report.append("")
    report.append(f"- Trace file: `{path}`")
    report.append(f"- Mode: `{'full' if full else 'brief'}`")
    if diagnostics.get("phase"):
        report.append(f"- Final diagnostics phase: `{diagnostics['phase']}`")
    if diagnostics.get("modelId"):
        report.append(f"- Final diagnostics model: `{diagnostics['modelId']}`")
    report.append(f"- Parsed runtime events: {len(events)}")
    report.append("")
    report.append("## One-Line Diagnosis")
    report.append("")
    report.append(cause)
    report.append("")
    report.append("## Minimal Evidence")
    report.append("")
    report.extend(f"- {item}" for item in evidence)
    report.append("")
    report.append("## Setup Signals")
    report.append("")
    setup_keys = [
        "engine",
        "backend",
        "activeModelId",
        "remoteEnabled",
        "apiKeyPresent",
        "selectedRemoteModel",
        "selectedModel",
        "cadenceMillis",
        "stableTranscriptChars",
        "provisionalTranscriptChars",
    ]
    setup_event = next((event for event in events if event.label in {"trace_started_for_recording", "start_recording_requested", "summary_scheduler_started"}), None)
    if setup_event:
        for key in setup_keys:
            if key in {"stableTranscriptChars", "provisionalTranscriptChars"}:
                value = max_int_field(events, key)
            else:
                value = first_non_empty_field(events, key)
            if value:
                report.append(f"- {key}: `{truncate(value)}`")
    else:
        report.append("- No setup event found.")
    report.append("")
    report.append("## Compact Counts")
    report.append("")
    report.append("- Labels: " + counter_summary(label_counts, 8 if full else 5))
    if skip_counts:
        report.append("- Skip reasons: " + counter_summary(skip_counts, 6 if full else 4))
    if failure_status_counts:
        report.append("- Failure statuses: " + counter_summary(failure_status_counts, 4))
    if failure_messages:
        report.append("- Failure messages: " + " | ".join(f"{value}x {truncate(key, 90)}" for key, value in failure_messages.most_common(2 if not full else 4)))
    report.append("")
    report.append("## Evidence Lines")
    report.append("")
    for event in key_events(events, max_events, full):
        report.append(compact_event_line(event))
    report.append("")
    report.append("## How To Use")
    report.append("")
    report.append("Treat this as a compressed index into the trace. Root-cause from these lines first; open the raw trace only when the evidence points to parser, timing, or UI ambiguity that needs exact surrounding lines.")
    report.append("")
    return trim_report("\n".join(report).rstrip() + "\n", budget_chars)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description="Summarize the newest Listener trace for LLM debugging.")
    parser.add_argument("--file", type=pathlib.Path, help="Specific trace file to summarize.")
    parser.add_argument("--trace-dir", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parent, help="Directory containing trace files.")
    parser.add_argument("--output", type=pathlib.Path, help="Write Markdown report to this path instead of stdout.")
    parser.add_argument("--max-events", type=int, default=10, help="Maximum evidence timeline events to include.")
    parser.add_argument("--budget-chars", type=int, default=DEFAULT_BUDGET_CHARS, help="Approximate max output characters; use 0 for no trim.")
    parser.add_argument("--full", action="store_true", help="Include a broader event timeline and less aggressive counter limits.")
    args = parser.parse_args(argv)

    path = args.file or latest_trace(args.trace_dir)
    if not path.is_file():
        raise SystemExit(f"Trace file not found: {path}")

    report = make_report(path, max(3, args.max_events), args.full, args.budget_chars)
    if args.output:
        args.output.write_text(report)
        print(f"Wrote {args.output}")
    else:
        print(report, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))

#!/usr/bin/env python3
"""Standalone OpenRouter cadence sweep for Listener English-context summaries."""

from __future__ import annotations

import argparse
import json
import os
import re
import random
import ssl
import statistics
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass
from datetime import datetime
from pathlib import Path
from typing import Any

try:
    import certifi
except Exception:
    certifi = None


DEFAULT_MODEL = "openrouter/free"
DEFAULT_CADENCES = "2,3,5,8,10,15"
OPENROUTER_URL = "https://openrouter.ai/api/v1/chat/completions"
OPENROUTER_MODELS_URL = "https://openrouter.ai/api/v1/models?sort=latency-low-to-high&supported_parameters=structured_outputs"
SSL_CONTEXT = ssl.create_default_context(cafile=certifi.where()) if certifi else ssl.create_default_context()

TRADITIONAL_CHINESE_SCRIPT = """
大家好，今天我們在台北圓山花博公園做街頭訪問，想了解大家對台灣生活的感受。
第一位受訪者說，他很喜歡台灣的人情味，尤其是便利商店、夜市和大眾運輸都很方便。
他也提到，台北的房租和物價讓年輕人壓力很大，所以很多朋友正在考慮搬到新北或桃園。
第二位受訪者是一位大學生，她覺得台灣最珍貴的是自由的討論空間，大家可以公開表達不同意見。
不過她也擔心薪資成長太慢，畢業後如果找不到合適工作，可能會先去日本或新加坡試試看。
主持人追問，如果只能用一句話形容台灣，她說台灣像一個很熱情但也很焦慮的家。
接著一位退休老師分享，他喜歡台灣的醫療制度和社區互助，但希望政府更重視交通安全。
他說行人過馬路常常需要很小心，機車和汽車有時不太禮讓，這讓外國朋友也覺得緊張。
最後主持人總結，今天大家大多喜歡台灣的便利、自由和人情味，但也反覆提到房價、薪資和交通問題。
""".strip()


@dataclass
class AttemptRecord:
    run_id: str
    model: str
    stream: bool
    cadence_seconds: float
    attempt_index: int
    ok: bool
    status: str
    latency_ms: int
    transcript_chars: int
    delta_chars: int
    last_sent_chars_before: int
    last_sent_chars_after: int
    details_count: int
    response_global_context: str
    error_detail: str
    mode: str = "cadence"
    strategy_order: int = 0
    strategy_name: str = ""


def compact_transcript(text: str) -> str:
    return " ".join(line.strip() for line in text.splitlines() if line.strip())


def transcript_chunks(text: str, chunk_size: int) -> list[str]:
    compact = compact_transcript(text)
    return [compact[index : index + chunk_size] for index in range(0, len(compact), chunk_size)]


@dataclass(frozen=True)
class Strategy:
    order: int
    name: str
    model: str
    stream: bool
    parser: str
    prompt: str


def build_prompt(previous_summary: dict[str, Any] | None, tail: str, delta: str, prompt_profile: str = "default") -> str:
    prior = json.dumps(previous_summary, ensure_ascii=False) if previous_summary else "None yet"
    if prompt_profile == "tight":
        return f"""
Update live English context from the Traditional Chinese delta.

Return valid JSON only:
{{"globalContext":"Short current topic","details":["Useful detail","Useful detail"]}}

Rules:
- globalContext must be short English text.
- details must contain 1 to 3 short English strings.
- Use only supported facts from the new delta.
- If nothing meaningful changed, return the previous JSON unchanged.

Previous JSON:
{prior}

Continuity tail:
{tail or "None"}

New Chinese delta:
{delta}
""".strip()
    return f"""
You are a live context updater for an English-speaking listener who cannot understand the Chinese conversation.

Return only this JSON object, with no markdown and no extra text:
{{"globalContext":"Stable English topic heading","details":["Current useful detail 1","Current useful detail 2","Current useful detail 3","Current useful detail 4"]}}

Your job is to maintain a stable live understanding panel, not produce meeting notes.
Use the new finalized Chinese delta as the main source of truth. The small Chinese tail is only for continuity.

Output rules:
1. globalContext: one short English heading for the current main topic.
2. details: 2 to 6 concise English bullet-style details.
3. If the new delta is too short, unclear, noisy, repetitive, or adds nothing useful, return the previous JSON unchanged.
4. If the conversation clearly changes topic, reset both globalContext and details for the new topic.
5. Do not translate line by line.
6. Do not invent names, facts, decisions, or action items that are not supported by the transcript.
7. Keep all output in English.

Previous English summary JSON:
{prior}

Small Chinese continuity tail:
{tail or "None"}

New finalized Chinese delta:
{delta}
""".strip()


def build_payload(model: str, stream: bool, previous_summary: dict[str, Any] | None, tail: str, delta: str, prompt_profile: str = "default") -> bytes:
    max_items = 3 if prompt_profile == "tight" else 6
    max_tokens = 220 if prompt_profile == "tight" else 360
    payload = {
        "model": model,
        "stream": stream,
        "messages": [
            {
                "role": "system",
                "content": "Return concise English conversation context only as a JSON object. No markdown. No extra text.",
            },
            {"role": "user", "content": build_prompt(previous_summary, tail, delta, prompt_profile)},
        ],
        "response_format": {
            "type": "json_schema",
            "json_schema": {
                "name": "listening_context",
                "strict": True,
                "schema": {
                    "type": "object",
                    "properties": {
                        "globalContext": {"type": "string"},
                        "details": {
                            "type": "array",
                            "items": {"type": "string"},
                            "minItems": 1,
                            "maxItems": max_items,
                        },
                    },
                    "required": ["globalContext", "details"],
                    "additionalProperties": False,
                },
            },
        },
        "temperature": 0,
        "max_tokens": max_tokens,
        "provider": {
            "require_parameters": True,
            "max_price": {"prompt": 0, "completion": 0},
        },
    }
    return json.dumps(payload, ensure_ascii=False).encode("utf-8")


def sanitized_error(error: BaseException) -> str:
    text = f"{type(error).__name__}: {error}"
    text = re.sub(r"sk-or-v1-[A-Za-z0-9._-]+", "[REDACTED]", text)
    return text[:240]


def request_context(
    api_key: str,
    model: str,
    stream: bool,
    previous_summary: dict[str, Any] | None,
    tail: str,
    delta: str,
    parser_profile: str = "strict",
    prompt_profile: str = "default",
) -> tuple[bool, str, int, dict[str, Any] | None, str]:
    request = urllib.request.Request(
        OPENROUTER_URL,
        data=build_payload(model, stream, previous_summary, tail, delta, prompt_profile),
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key.strip()}",
            "Content-Type": "application/json",
            "User-Agent": "listener-cadence-experiment/1.0",
            "HTTP-Referer": "https://listener.local",
            "X-Title": "Listener Cadence Experiment",
        },
    )
    start = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=30, context=SSL_CONTEXT) as response:
            body = response.read()
        latency_ms = int((time.monotonic() - start) * 1000)
        try:
            context = parse_streaming_context(body, parser_profile) if stream else parse_context(body, parser_profile)
            return True, "Success", latency_ms, context, ""
        except Exception as error:
            return False, "InvalidResponse", latency_ms, None, sanitized_error(error)
    except urllib.error.HTTPError as error:
        latency_ms = int((time.monotonic() - start) * 1000)
        return False, http_status(error.code), latency_ms, None, sanitized_error(error)
    except TimeoutError as error:
        return False, "TimedOut", int((time.monotonic() - start) * 1000), None, sanitized_error(error)
    except OSError as error:
        return False, "Offline", int((time.monotonic() - start) * 1000), None, sanitized_error(error)


def http_status(code: int) -> str:
    return {
        401: "InvalidKey",
        404: "ModelUnavailable",
        408: "TimedOut",
        429: "RateLimited",
    }.get(code, f"HTTP{code}")


def parse_context(body: bytes, parser_profile: str = "strict") -> dict[str, Any]:
    envelope = json.loads(body)
    content = envelope["choices"][0]["message"]["content"]
    return parse_model_content(content, parser_profile)


def parse_streaming_context(body: bytes, parser_profile: str = "strict") -> dict[str, Any]:
    pieces: list[str] = []
    for raw_line in body.decode("utf-8", errors="replace").splitlines():
        if not raw_line.startswith("data:"):
            continue
        data = raw_line.removeprefix("data:").strip()
        if not data or data == "[DONE]":
            continue
        parsed = json.loads(data)
        delta = parsed.get("choices", [{}])[0].get("delta", {}).get("content")
        if isinstance(delta, str):
            pieces.append(delta)
    return parse_model_content("".join(pieces), parser_profile)


def parse_model_content(content: str, parser_profile: str) -> dict[str, Any]:
    if parser_profile == "tolerant":
        return parse_tolerant_context(content)
    return validate_context(json.loads(content))


def parse_tolerant_context(content: str) -> dict[str, Any]:
    cleaned = content.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
    try:
        return validate_context(json.loads(cleaned))
    except Exception:
        pass
    start = cleaned.find("{")
    end = cleaned.rfind("}")
    if start >= 0 and end > start:
        try:
            return validate_context(json.loads(cleaned[start : end + 1]))
        except Exception:
            pass
    loose = parse_loose_context(cleaned)
    if loose is not None:
        return loose
    raise ValueError("no tolerant context found")


def parse_loose_context(content: str) -> dict[str, Any] | None:
    lines = [
        line.strip().strip("*")
        for line in content.replace("```json", "").replace("```", "").splitlines()
        if line.strip()
    ]
    if len(lines) < 2:
        return None
    heading = ""
    details: list[str] = []
    for line in lines:
        lower = line.lower()
        if not heading and any(lower.startswith(prefix) for prefix in ("globalcontext:", "global context:", "heading:", "context:")):
            heading = line.split(":", 1)[1].strip()
        elif re.match(r"^([-*•]|\d+[.)])\s+.+", line):
            details.append(re.sub(r"^([-*•]|\d+[.)])\s+", "", line).strip())
        elif lower.startswith("detail"):
            details.append(line.split(":", 1)[-1].strip())
    if not heading:
        heading = next((line for line in lines if not re.match(r"^([-*•]|\d+[.)])\s+.+", line) and not line.endswith(":")), "")
    details = [detail for detail in details if detail and detail != heading]
    if heading and details:
        return {"globalContext": heading, "details": details[:6]}
    return None


def validate_context(value: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(value.get("globalContext"), str) or not value["globalContext"].strip():
        raise ValueError("missing globalContext")
    details = value.get("details")
    if not isinstance(details, list) or not details or not all(isinstance(item, str) and item.strip() for item in details):
        raise ValueError("missing details")
    return value


def percentile(values: list[int], pct: float) -> int | None:
    if not values:
        return None
    ordered = sorted(values)
    return ordered[round((len(ordered) - 1) * pct)]


def run_cadence(api_key: str, run_id: str, model: str, stream: bool, cadence_seconds: float, chunk_size: int, output_jsonl: Path, mode: str = "cadence", parser_profile: str = "strict", prompt_profile: str = "default") -> list[AttemptRecord]:
    records: list[AttemptRecord] = []
    transcript = ""
    last_sent = ""
    previous_summary: dict[str, Any] | None = None
    pieces = transcript_chunks(TRADITIONAL_CHINESE_SCRIPT, chunk_size)
    for index, chunk in enumerate(pieces, start=1):
        transcript += chunk
        delta = transcript[len(last_sent) :] if transcript.startswith(last_sent) else transcript
        before = len(last_sent)
        ok, status, latency_ms, context, error_detail = request_context(api_key, model, stream, previous_summary, last_sent[-800:], delta, parser_profile, prompt_profile)
        if ok and context is not None:
            last_sent = transcript
            previous_summary = context
        record = AttemptRecord(
            run_id=run_id,
            model=model,
            stream=stream,
            cadence_seconds=cadence_seconds,
            attempt_index=index,
            ok=ok,
            status=status,
            latency_ms=latency_ms,
            transcript_chars=len(transcript),
            delta_chars=len(delta),
            last_sent_chars_before=before,
            last_sent_chars_after=len(last_sent),
            details_count=len(context.get("details", [])) if context else 0,
            response_global_context=context.get("globalContext", "") if context else "",
            error_detail=error_detail,
            mode=mode,
        )
        records.append(record)
        with output_jsonl.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")
        print(
                f"  #{index:02d} {status} latency={latency_ms}ms "
                f"delta={record.delta_chars} last_sent={record.last_sent_chars_after}",
                flush=True,
            )
        if index < len(pieces):
            time.sleep(cadence_seconds)
    return records


def run_reliability(api_key: str, run_id: str, model: str, attempts: int, chunk_size: int, pause_seconds: float, output_jsonl: Path) -> list[AttemptRecord]:
    records: list[AttemptRecord] = []
    for stream in (True, False):
        label = "streaming" if stream else "non_streaming"
        print(f"\nRunning reliability mode {label}...", flush=True)
        transcript = ""
        last_sent = ""
        previous_summary: dict[str, Any] | None = None
        pieces = transcript_chunks(TRADITIONAL_CHINESE_SCRIPT, chunk_size)
        for index in range(1, attempts + 1):
            transcript += pieces[(index - 1) % len(pieces)]
            delta = transcript[len(last_sent) :] if transcript.startswith(last_sent) else transcript
            before = len(last_sent)
            ok, status, latency_ms, context, error_detail = request_context(api_key, model, stream, previous_summary, last_sent[-800:], delta)
            if ok and context is not None:
                last_sent = transcript
                previous_summary = context
            record = AttemptRecord(
                run_id=run_id,
                model=model,
                stream=stream,
                cadence_seconds=pause_seconds,
                attempt_index=index,
                ok=ok,
                status=status,
                latency_ms=latency_ms,
                transcript_chars=len(transcript),
                delta_chars=len(delta),
                last_sent_chars_before=before,
                last_sent_chars_after=len(last_sent),
                details_count=len(context.get("details", [])) if context else 0,
                response_global_context=context.get("globalContext", "") if context else "",
                error_detail=error_detail,
                mode=label,
            )
            records.append(record)
            with output_jsonl.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")
            print(
                f"  #{index:02d} {status} latency={latency_ms}ms "
                f"delta={record.delta_chars} last_sent={record.last_sent_chars_after}",
                flush=True,
            )
            if index < attempts and pause_seconds > 0:
                time.sleep(pause_seconds)
    return records


def fetch_specific_free_model(api_key: str) -> str | None:
    request = urllib.request.Request(
        OPENROUTER_MODELS_URL,
        headers={
            "Authorization": f"Bearer {api_key.strip()}",
            "User-Agent": "listener-cadence-experiment/1.0",
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=15, context=SSL_CONTEXT) as response:
            body = json.loads(response.read())
    except Exception:
        return None
    for item in body.get("data", []):
        model_id = item.get("id")
        pricing = item.get("pricing", {})
        modalities = item.get("architecture", {}).get("output_modalities", [])
        parameters = item.get("supported_parameters", [])
        prompt_price = str(pricing.get("prompt", ""))
        completion_price = str(pricing.get("completion", ""))
        free = prompt_price in {"0", "0.0", "0.000000", "0.0000000"} and completion_price in {"0", "0.0", "0.000000", "0.0000000"}
        if model_id and model_id != DEFAULT_MODEL and free and "text" in modalities and "structured_outputs" in parameters:
            return model_id
    return None


def cheap_strategies(api_key: str, model: str) -> list[Strategy]:
    specific = fetch_specific_free_model(api_key)
    strategies = [
        Strategy(1, "eager_validation_baseline", model, True, "strict", "default"),
        Strategy(2, "tolerant_parser", model, True, "tolerant", "default"),
        Strategy(3, "tight_prompt_schema", model, True, "strict", "tight"),
        Strategy(4, "non_streaming_final", model, False, "strict", "default"),
    ]
    if specific is not None:
        strategies.append(Strategy(5, f"specific_free_model:{specific}", specific, True, "strict", "default"))
    else:
        strategies.append(Strategy(5, "specific_free_model:unavailable", model, True, "strict", "default"))
    return strategies


def run_cheap_strategies(api_key: str, run_id: str, model: str, attempts: int, chunk_size: int, pause_seconds: float, output_jsonl: Path) -> list[AttemptRecord]:
    all_records: list[AttemptRecord] = []
    for strategy in cheap_strategies(api_key, model):
        if strategy.name.endswith(":unavailable"):
            print(f"\nSkipping strategy {strategy.order}: {strategy.name}", flush=True)
            continue
        print(f"\nRunning strategy {strategy.order}: {strategy.name}", flush=True)
        transcript = ""
        last_sent = ""
        previous_summary: dict[str, Any] | None = None
        pieces = transcript_chunks(TRADITIONAL_CHINESE_SCRIPT, chunk_size)
        for index in range(1, attempts + 1):
            transcript += pieces[(index - 1) % len(pieces)]
            delta = transcript[len(last_sent) :] if transcript.startswith(last_sent) else transcript
            before = len(last_sent)
            ok, status, latency_ms, context, error_detail = request_context(
                api_key,
                strategy.model,
                strategy.stream,
                previous_summary,
                last_sent[-800:],
                delta,
                strategy.parser,
                strategy.prompt,
            )
            if ok and context is not None:
                last_sent = transcript
                previous_summary = context
            record = AttemptRecord(
                run_id=run_id,
                model=strategy.model,
                stream=strategy.stream,
                cadence_seconds=pause_seconds,
                attempt_index=index,
                ok=ok,
                status=status,
                latency_ms=latency_ms,
                transcript_chars=len(transcript),
                delta_chars=len(delta),
                last_sent_chars_before=before,
                last_sent_chars_after=len(last_sent),
                details_count=len(context.get("details", [])) if context else 0,
                response_global_context=context.get("globalContext", "") if context else "",
                error_detail=error_detail,
                mode=f"strategy_{strategy.order}",
                strategy_order=strategy.order,
                strategy_name=strategy.name,
            )
            all_records.append(record)
            with output_jsonl.open("a", encoding="utf-8") as handle:
                handle.write(json.dumps(asdict(record), ensure_ascii=False) + "\n")
            print(
                f"  #{index:02d} {status} latency={latency_ms}ms "
                f"delta={record.delta_chars} last_sent={record.last_sent_chars_after}",
                flush=True,
            )
            if index < attempts and pause_seconds > 0:
                time.sleep(pause_seconds)
    return all_records


def summarize_records(records: list[AttemptRecord]) -> dict[str, Any]:
    successes = [record for record in records if record.ok]
    failures = [record for record in records if not record.ok]
    success_latencies = [record.latency_ms for record in successes]
    statuses: dict[str, int] = {}
    for record in records:
        statuses[record.status] = statuses.get(record.status, 0) + 1
    p90 = percentile(success_latencies, 0.9)
    return {
        "cadence_seconds": records[0].cadence_seconds if records else None,
        "mode": records[0].mode if records else None,
        "stream": records[0].stream if records else None,
        "strategy_order": records[0].strategy_order if records else 0,
        "strategy_name": records[0].strategy_name if records else "",
        "model": records[0].model if records else "",
        "attempts": len(records),
        "successes": len(successes),
        "failures": len(failures),
        "statuses": statuses,
        "median_success_latency_ms": int(statistics.median(success_latencies)) if success_latencies else None,
        "p90_success_latency_ms": p90,
        "max_success_latency_ms": max(success_latencies) if success_latencies else None,
        "max_delta_chars": max((record.delta_chars for record in records), default=0),
        "pass": bool(failures == [] and p90 is not None and p90 <= records[0].cadence_seconds * 1000),
    }


def write_markdown(path: Path, run_id: str, model: str, stream: bool | None, summaries: list[dict[str, Any]], title: str = "Cadence Sweep Evidence") -> None:
    lines = [
        f"# {title}",
        "",
        f"- Run ID: `{run_id}`",
        f"- Model: `{model}`",
        f"- Stream: `{str(stream).lower() if stream is not None else 'mixed'}`",
        f"- Transcript chars: `{len(compact_transcript(TRADITIONAL_CHINESE_SCRIPT))}`",
        "",
        "| Mode | Strategy | Model | Cadence/Pause | Attempts | Success | Failures | Statuses | Median ms | P90 ms | Max delta | Pass |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | --- | ---: | ---: | ---: | :---: |",
    ]
    for item in summaries:
        statuses = ", ".join(f"{key}={value}" for key, value in item["statuses"].items())
        lines.append(
            f"| {item['mode'] or 'cadence'} | {item['strategy_name'] or '-'} | {item['model'] or model} | "
            f"{item['cadence_seconds']:g}s | {item['attempts']} | {item['successes']} | "
            f"{item['failures']} | {statuses} | {item['median_success_latency_ms'] or '-'} | "
            f"{item['p90_success_latency_ms'] or '-'} | {item['max_delta_chars']} | "
            f"{'yes' if item['pass'] else 'no'} |"
        )
    passing = [item for item in summaries if item["pass"]]
    lines.extend(["", "## Result", ""])
    if passing:
        best = min(passing, key=lambda item: item["cadence_seconds"])
        lines.append(f"Lowest passing cadence in this run: `{best['cadence_seconds']:g}s`.")
    else:
        lines.append("No cadence passed all criteria in this run.")
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_cadences(value: str) -> list[float]:
    cadences = [float(item.strip()) for item in value.split(",") if item.strip()]
    if not cadences:
        raise argparse.ArgumentTypeError("at least one cadence is required")
    return cadences


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cadences", type=parse_cadences, default=parse_cadences(DEFAULT_CADENCES))
    parser.add_argument("--chunk-size", type=int, default=72)
    parser.add_argument("--model", default=DEFAULT_MODEL)
    parser.add_argument("--non-streaming", action="store_true", help="Use non-streaming completions instead of app-like SSE streaming.")
    parser.add_argument("--reliability", action="store_true", help="Compare streaming and non-streaming output reliability before cadence tuning.")
    parser.add_argument("--cheap-strategies", action="store_true", help="Run strategy-order experiments 1-5 from strategy_cost_order.md.")
    parser.add_argument("--reliability-attempts", type=int, default=6)
    parser.add_argument("--pause-seconds", type=float, default=2.0, help="Pause between reliability attempts.")
    parser.add_argument("--rest-between-cadences", type=float, default=0.0, help="Seconds to pause between cadence buckets.")
    parser.add_argument("--shuffle", action="store_true", help="Shuffle cadence order to reduce sweep-order bias.")
    parser.add_argument("--output-dir", type=Path, default=Path(__file__).resolve().parent / "results")
    args = parser.parse_args()

    api_key = os.environ.get("OPENROUTER_API_KEY")
    if not api_key:
        print("OPENROUTER_API_KEY is not set; refusing to inspect app storage or keystores.")
        return 2
    if certifi is None:
        print("Python package certifi is not available; TLS may fail if the system certificate store is incomplete.")

    args.output_dir.mkdir(parents=True, exist_ok=True)
    run_id = datetime.now().strftime("%Y%m%d_%H%M%S")
    prefix = "cheap_strategy_sweep" if args.cheap_strategies else "reliability_sweep" if args.reliability else "cadence_sweep"
    output_jsonl = args.output_dir / f"{prefix}_{run_id}.jsonl"
    output_md = args.output_dir / f"{prefix}_{run_id}.md"
    stream = not args.non_streaming

    print(f"Run ID: {run_id}", flush=True)
    print(f"Model: {args.model}", flush=True)
    print(f"Evidence JSONL: {output_jsonl}", flush=True)

    summaries: list[dict[str, Any]]
    if args.cheap_strategies:
        print(f"Cheap strategy attempts per mode: {args.reliability_attempts}", flush=True)
        print(f"Pause between attempts: {args.pause_seconds:g}s", flush=True)
        records = run_cheap_strategies(api_key, run_id, args.model, args.reliability_attempts, args.chunk_size, args.pause_seconds, output_jsonl)
        summaries = []
        for order in sorted({record.strategy_order for record in records}):
            group = [record for record in records if record.strategy_order == order]
            summaries.append(summarize_records(group))
        write_markdown(output_md, run_id, args.model, None, summaries, title="Cheap Strategy Evidence")
    elif args.reliability:
        print(f"Reliability attempts per mode: {args.reliability_attempts}", flush=True)
        print(f"Pause between attempts: {args.pause_seconds:g}s", flush=True)
        records = run_reliability(api_key, run_id, args.model, args.reliability_attempts, args.chunk_size, args.pause_seconds, output_jsonl)
        grouped = [
            [record for record in records if record.mode == "streaming"],
            [record for record in records if record.mode == "non_streaming"],
        ]
        summaries = [summarize_records(group) for group in grouped if group]
        write_markdown(output_md, run_id, args.model, None, summaries, title="Output Reliability Evidence")
    else:
        print(f"Stream: {str(stream).lower()}", flush=True)
        cadences = list(args.cadences)
        if args.shuffle:
            random.shuffle(cadences)
        print(f"Cadences: {', '.join(f'{cadence:g}s' for cadence in cadences)}", flush=True)
        summaries = []
        for cadence_index, cadence in enumerate(cadences):
            print(f"\nRunning cadence {cadence:g}s...", flush=True)
            records = run_cadence(api_key, run_id, args.model, stream, cadence, args.chunk_size, output_jsonl)
            summaries.append(summarize_records(records))
            if cadence_index < len(cadences) - 1 and args.rest_between_cadences > 0:
                print(f"Resting {args.rest_between_cadences:g}s before next cadence...", flush=True)
                time.sleep(args.rest_between_cadences)
        write_markdown(output_md, run_id, args.model, stream, summaries)

    print(f"\nEvidence Markdown: {output_md}", flush=True)
    print(output_md.read_text(encoding="utf-8"), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

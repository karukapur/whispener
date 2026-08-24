# Local model benchmark — Galaxy Z Flip6

Use the same Mandarin recording, room, device position, and battery starting range for every row. Disable charging and let the phone return to ambient temperature between runs. Record three runs per model and report the median.

| Model | Backend | CER | Real-time factor | Peak app memory | Battery / 30 min | Peak battery temp | First provisional | Stable after pause | Notes |
|---|---|---:|---:|---:|---:|---:|---:|---:|---|
| Tiny | — | — | — | — | — | — | — | — | Pending device run |
| Base | — | — | — | — | — | — | — | — | Pending device run |
| Small Q5 | — | — | — | — | — | — | — | — | Pending device run |

## Acceptance run

- [ ] Base provisional text appears within 3 seconds of speech beginning.
- [ ] Small Q5 provisional text appears within 3 seconds of speech beginning.
- [ ] Stable text appears within 2 seconds of a pause.
- [ ] A 30-minute continuous recording has no missing or duplicated boundary text.
- [ ] Vulkan is reported on the supported path.
- [ ] Forced Vulkan initialization failure clearly reports CPU fallback.
- [ ] Capture stays responsive with bounded processing work.
- [ ] No audio file is created during or after recording.

Do not populate measurements from an emulator; latency, memory, battery, and thermal results must come from the Galaxy Z Flip6.

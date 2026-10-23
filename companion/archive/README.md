# Archive

Accurate when written, but describes hardware, code, or plans that are no longer current.
Don't use these as instructions — check `../firmware/S3_App_V2/docs/` first.

| File | Why it's here |
|---|---|
| `docs/c6/` | ESP32-C6 era (Feb–Apr 2026). That firmware is at git tags `companion/c6-*`. |
| `docs/S3_CHANGES_FROM_V1.md` | V1 → V2 firmware changelog; the protocol has changed since. |
| `docs/MIC_ROOT_CAUSE_ANALYSIS.md` | July 2026 mic-quality investigation; some of its fixes (e.g. even-length BLE payloads) have since landed and it references deleted app code. |
| `docs/RESEARCH_AND_ROADMAP.md` | July 2026 roadmap; status is stale and market figures are unsourced. |
| `docs/S3_Session3_TestPlan_never-run.md` | Test plan that was never executed; tests features since removed. |
| `TouchSensorInitial/` | TP223 touch-pad experiment. Its pins clash with `S3_App_V2` — don't flash it on the glasses PCB as-is. |

# Validation — 2026-10-20

## Passed locally

- Java 21 / NeoForge 21.1.169: `gradlew build`.
- Pure geometry suite: **73,984** coordinate rotation, bounds, bijection and inverse round-trip cases.
- Python standard-library importer: **6** unit tests (air/NBT removal, duplicate and out-of-bounds cells, missing palettes, invalid palette indices, compressed/raw/truncated NBT, size bounds).
- User-installed structure guide: **154** static NBT templates converted, **0** skipped, with source hashes. Output is intentionally **not** committed or bundled. All default to creative-only.
- Actual isolated NeoForge **GameTest server**: **5 / 5 required tests passed**:
  1. Survival builds consume exactly 2 stone items; repeating the same build charges nothing.
  2. Missing materials place no blocks and consume nothing.
  3. Obstructions are preserved and no partial layout is left behind.
  4. A cancelled NeoForge placement event restores tentative blocks and preserves inventory.
  5. Server data reload discovers the demo, empty selection is safe, unsupported door material mapping is rejected.
- The test server starts without client classes on the dedicated side and shuts down normally. Only its own project `run/` world is used.

## Not claimed

- No ATM10 production world was opened or changed.
- No client GUI/GPU or multiplayer latency acceptance test.
- No universal machine formation guarantee for the 154 imported templates; dynamic guide scenes are outside this importer.
- No full compatibility certification for third-party protection mods or callback-heavy blocks. Test cancellation uses a real NeoForge event listener, not every protection plugin.
- No AE2 network material integration in 0.1.0.

Run instructions are in README. GameTest code is opt-in and excluded from the normal release jar.

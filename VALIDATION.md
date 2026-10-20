# Validation — 2026-10-20

## Passed locally

- Java 21 / NeoForge **21.1.259**: `gradlew build` with AE2 **19.2.18** and GuideME **21.1.19**.
- Pure geometry suite: **73,984** coordinate rotation, bounds, bijection and inverse round-trip cases.
- Python standard-library importer: **6** unit tests retained for offline workflows.
- Java `GuideImport` tests: synthetic JAR conversion, Markdown title resolution, broken-entry skip/report, glob filtering, path normalization, no-clobber on failed/empty import.
- Real installed `atm-structure-library-1.0.0.jar`: **154 / 154** static NBT templates converted, **0** skipped; the known `ae_family/ae_plus_ultimate` blueprint has **1,280** cells and a non-empty title.
- Ultimate matrix layout compared with ExtendedAE Plus 1.6.3's `data/extendedae_plus/structures/ultimate_super_assembler_matrix.json`: **1,280 non-air positions and block IDs, zero differences**.
- The installed ExtendedAE Plus 1.6.3 exposes `public static boolean formUltimate(ServerLevel, BlockPos)`; the reflection adapter targets that exact signature.
- Actual isolated NeoForge **GameTest server** with AE2 and GuideME loaded: **6 / 6 required tests passed**:
  1. Survival builds consume exactly 2 stone items; repeating the same build charges nothing.
  2. Missing materials place no blocks and consume nothing.
  3. Obstructions are preserved and no partial layout is left behind.
  4. A cancelled NeoForge placement event restores tentative blocks and preserves inventory.
  5. Server data reload discovers the demo, empty selection is safe, unsupported door material mapping is rejected.
  6. ME source simulation/extract/refund is transactional; a second-item shortage restores both items.
- The test server starts without client classes on the dedicated side and shuts down normally. Only its own project `run/` world is used.
- Generated data pack uses `pack_format` 48, marker `.mbuilder-import`, and `import-report.json`; world datapack IDs use the expected `file/mbuilder_import` form.
- Runtime capture in the actual ATM10 client (GuideME + all 557 mods): **353** StructureViewer scenes, **286 exported with >=10 blocks**, **66 scenes with 1–9 blocks**, **1 scene with no display blocks**, 0 compile failures. All 286 pass `Blueprint.parse`; server reload reports **441 buildable blueprints** (155 existing + 286 captured), including three 5,832-block Mekanism matrices after raising the cap to 8,192.
- Capture report: `saves/ATM10/datapacks/mbuilder_import/capture-report.json`; `level.dat` backed up as `level.dat.bak-mbuilder` before the local datapack was enabled.

## Not claimed

- The capture test ran in the local ATM10 world and modified its `datapacks/mbuilder_import` and `level.dat` (backup kept). No server/production world was touched.
- No actual client GUI/GPU or multiplayer latency acceptance test; the capture command's result was validated through logs and `Blueprint.parse`, not by clicking every row.
- ME transaction logic is covered with a fake `MEStorage`; an in-world AE2 grid/interface binding was not automated in GameTest.
- The `formUltimate` adapter was not executed in GameTest because the development server does not load ExtendedAE Plus. It needs one in-pack test with the actual ultimate matrix.
- The `/mbuilder import` command's in-place pack enabling/reload was not run against a production world; the converter, pack layout and ID were validated separately.
- No universal machine formation guarantee for the 154 imported templates; dynamic guide scenes are outside this importer.
- No full compatibility certification for third-party protection mods or callback-heavy blocks. Test cancellation uses a real NeoForge event listener, not every protection plugin.

Run instructions are in README. GameTest code is opt-in and excluded from the normal release jar.

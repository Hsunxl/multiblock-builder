# Validation — 2026-10-20

## 0.4.0 bundled structure library (2026-10-24)

- The guide library now ships inside the mod: `data/guide_import/multiblock_blueprints`
  (150 deduplicated static templates) and `data/guide_capture/multiblock_blueprints`
  (235 deduplicated runtime-captured scenes) plus `data/multiblock_builder/multiblock_blueprints/demo_frame.json`.
  Total **386** buildable blueprints in the jar (`multiblock-builder-0.4.0.jar`, 480 zip entries).
- Source pack had 440 files; 55 were geometric duplicates across the two roots
  (sha1 over size + block ids + block states), leaving 385 unique layouts.
- 19 layouts that contain a block without a usable BlockItem stay `survival: false`
  (18 Occultism chalk-glyph scenes + `oritech:unstable_container`); the other 366 are survival-buildable.
- `ae_family/ae_plus_ultimate.json` keeps `formation: extendedae_plus:ultimate_super_assembler_matrix` (1,280 cells).
- `gradlew build --offline` passed: 73,984 geometry cases, 154 real template imports, importer regression checks.
- Isolated GameTest server: **15 / 15 required tests passed**. The new
  `bundledBlueprintsShipInsideTheJar` test reads the live `ResourceManager`
  (not the parsed catalog, because the isolated test server only has AE2 loaded):
  386 bundled files present, 19 creative-only, well-formed `size`/`blocks`,
  ultimate matrix ships with its formation field and 1,280 cells, demo frame loads.
- Installed `multiblock-builder-0.4.0.jar` in `mods/` (0.3.4 moved out) and moved
  the old world datapacks to `saves/ATM10/datapack-backups/` so the jar alone provides the library.
- Not claimed: no in-game ATM10 client pass over all 386 rows yet; parsing depends on the
  blocks of installed mods, so a blueprint whose blocks are absent is logged and skipped at load time.

## 0.3.4 catalog hover layer fix (2026-10-21)

- User screenshots showed footer button labels rendering through the hover card.
  The card was at z=0; render-call order alone does not override widget/item depth.
- The entire card (opaque background, preview and text) now renders at z=400,
  with a scoped pose and explicit buffer flushes before/after the overlay and
  before the voxel texture pass. The original layout and mouse hitboxes are unchanged.
- `gradlew build --offline --console=plain` passed on Java 21 / NeoForge 21.1.259:
  73,984 geometry cases, 154 real template imports and importer regression checks.
- Installed 0.3.4 in the ATM10 mods folder; 0.3.3 backed up outside mods.
- No live ATM10 GPU/hover acceptance is claimed; restart and hover the last row
  over the rotate/materials buttons to verify on the actual client.

## 0.3.3 follow-up (2026-10-20 21:47 Asia/Shanghai)

- Preview bug fixed: server shell extraction previously checked only +X/+Y/+Z.
  Negative-side-only exposed blocks were never sent to the rotating client.
  All six neighbors are now checked, without changing the packet format.
- Isolated NeoForge GameTest server: **14 / 14 required tests passed**.
  Added complete 3x3x3 shell membership (26 cells, center excluded), partial
  inventory builds, split inventory stacks, customized-item preservation,
  missing bound interface, and three real AE2 grid integration cases.
- Real ME fixture: placed Interface + Drive with a physical 1K item cell +
  Creative Energy Cell. Asserted interface active and drive in the same grid.
  Materials.resolve and Placement.build use the normal production path.
  Success: 5 stone -> 3, two world blocks placed, repeat -> still 3, backpack
  remains 8. Shortage: 1 stone remains 1, no world blocks, backpack remains 8.
  Protection cancellation: 5 remains 5, tentative world blocks roll back.
- Player is a GameTest mock server player, with instabuild disabled. This is
  real server/world/AE2 storage integration, not a client mouse-input test.
- This supersedes the old fake-only ME coverage limitation below. Full ATM10
  client hover/GPU acceptance and third-party mod compatibility remain untested.
- Only the project's isolated run/ test world was used; saves/ATM10 was untouched.

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
- Originally ME transaction logic was fake-only; superseded by the 0.3.3 real-grid tests above. Shift-right-click GUI binding is not automated (tests use Selection.bindInterface).
- The `formUltimate` adapter was not executed in GameTest because the development server does not load ExtendedAE Plus. It needs one in-pack test with the actual ultimate matrix.
- The `/mbuilder import` command's in-place pack enabling/reload was not run against a production world; the converter, pack layout and ID were validated separately.
- No universal machine formation guarantee for the 154 imported templates; dynamic guide scenes are outside this importer.
- No full compatibility certification for third-party protection mods or callback-heavy blocks. Test cancellation uses a real NeoForge event listener, not every protection plugin.

Run instructions are in README. GameTest code is opt-in and excluded from the normal release jar.

# Test Structure Cleanup Design

**Date:** 2026-04-09
**Scope:** Reorganize backend test placement, naming, and documentation without changing production behavior.

## Context

The repository already declares two backend test layers:

- `src/test/kotlin` for regular tests
- `src/integrationTest/kotlin` for integration tests compiled through a dedicated `integrationTest` source set

That contract is implemented in `build.gradle.kts`, documented in public docs, and partially followed in the tree. The current problem is consistency rather than missing infrastructure:

- `src/test/kotlin` still contains many IntelliJ-platform-backed tests using `BasePlatformTestCase`
- at least one class in `src/test/kotlin` is explicitly named `*IntegrationTest`
- `src/integrationTest/kotlin` currently contains only a small subset of the heavy tests
- a few low-value tests validate things already covered more directly elsewhere

This mismatch makes the repository harder to understand and weakens the meaning of `test` versus `integrationTest`.

## Goals

- Make test placement match actual execution semantics
- Enforce a clear naming contract between regular and integration tests
- Remove only low-value duplicate tests
- Keep behavioral coverage intact
- Align README and public docs with the cleaned structure

## Non-Goals

- No production code refactors
- No feature changes
- No broad test rewriting beyond what is required for relocation, naming, or duplicate removal
- No aggressive test suite reduction
- No build-graph redesign beyond what is needed to keep migrated tests runnable

## Approaches Considered

### 1. Minimal Correction

Move only the most obviously misplaced tests and leave the rest untouched.

Pros:

- Lowest churn
- Lowest short-term risk

Cons:

- The rule remains ambiguous
- Future tests will likely drift again
- Existing naming conflicts stay confusing

### 2. Rule-Driven Reclassification

Define a hard placement contract, migrate tests to match it, rename tests to match task filters, and remove only clearly redundant low-value checks.

Pros:

- Clear ongoing maintenance rule
- Matches Gradle task boundaries
- Preserves coverage while reducing confusion

Cons:

- Moderate file churn
- Requires doc updates and verification across multiple tasks

### 3. Aggressive Suite Reduction

Reclassify tests and also remove most heavy or overlapping tests to shrink execution time.

Pros:

- Smaller suite
- Lower long-term runtime cost

Cons:

- Violates the selected cleanup constraint to preserve functional coverage
- High risk of deleting useful edge-case protection

## Recommended Approach

Use approach 2.

The repository already has the correct build-level structure. The cleanup should make the file layout and naming obey that structure instead of redefining the structure again.

## Target Test Contract

### `src/test/kotlin`

Keep tests here only if they are regular tests under the `test` task contract:

- pure model, parsing, diffing, rendering, serialization, validation, or transformation tests
- fast component-slice tests that do not require full IntelliJ platform wiring
- architecture and documentation sanity tests
- backend logic tests whose setup remains local and self-contained

Naming rule:

- all classes here end with `Test`

### `src/integrationTest/kotlin`

Move tests here if they depend on IntelliJ platform or broader runtime composition:

- tests using `BasePlatformTestCase`
- tests relying on editor/project fixtures, registered services, tool windows, or IDE event dispatch
- tests validating end-to-end action -> service -> state -> UI workflows
- tests validating Gradle build wiring or sandbox preparation

Naming rule:

- all classes here end with `IT`

### Shared Test Support

This cleanup distinguishes executable tests, not every helper file.

The current Gradle wiring allows `integrationTest` to reuse the `test` runtime classpath. Some migrated integration tests already depend on shared helpers and fixture resources that still live under the `test` tree, especially:

- `src/test/kotlin/com/charmnight/linkgraph/testing/ProjectFixtureLoader.kt`
- `src/test/resources/fixtures/**`

For this cleanup, those support assets remain shared and are not treated as misplaced executable tests. The implementation must make that dependency explicit at compile time, not only at task runtime.

Recommended implementation choice for this cleanup:

- keep shared helpers under `src/test/kotlin/com/charmnight/linkgraph/testing`
- keep shared fixture resources under `src/test/resources/fixtures`
- update `integrationTest` source-set wiring so migrated tests can compile against `sourceSets["test"].output` in addition to using the existing test dependency configurations

This avoids a larger support-layer extraction during the same cleanup while still making the contract technically correct.

## Planned Reclassification

### Migrate from `src/test/kotlin` to `src/integrationTest/kotlin`

These tests already behave like integration tests and should be renamed to `*IT` as part of the move:

- `actions/EditorPopupContextActionTest`
- `extract/DubboResolverTest`
- `extract/GraphExtractorTest`
- `extract/HttpFeignResolverTest`
- `extract/JavaResolverTest`
- `extract/MqResolverTest`
- `extract/MyBatisResolverTest`
- `extract/SpringResolverTest`
- `extract/UncertainLinkResolverTest`
- `semantic/SemanticAnalyzerResourceAnchorIntegrationTest`
- `semantic/provider/code/JavaCodeSemanticProviderTest`
- `semantic/provider/code/KotlinCodeSemanticProviderTest`
- `semantic/provider/resource/ResourceSemanticProviderTest`
- `semantic/subject/CaretSubjectLocatorTest`
- `semantic/subject/ResourceSubjectDecoderTest`
- `services/DebugMethodSignatureLocatorTest`
- `services/LinkGraphProjectServiceAsyncLifecycleTest`
- `services/LinkGraphProjectServiceBeautificationTest`
- `services/LinkGraphProjectServiceSemanticAnalysisTest`
- `toolwindow/LinkGraphDebugStartupActivityTest`

Rationale:

- they already rely on IntelliJ platform-backed fixtures or runtime composition
- they are not regular unit tests in execution cost or isolation level
- keeping them in `src/test` weakens the meaning of the regular test task

### Keep in `src/test/kotlin`

Keep regular tests in place when they are primarily:

- model and algorithm tests such as `model`, `diff`, `sync`, and most `mermaid` tests
- pure gateway/protocol tests in `llm`
- architecture and documentation assertions
- bootstrap metadata assertions that do not validate Gradle build wiring
- UI state/rendering slice tests that do not require full platform composition
- shared test-support helpers and fixture resources consumed by multiple test layers

## Planned Deletions or Consolidations

Deletion is intentionally narrow.

### Keep `TestFixtureResourcesTest` For Now

Reason:

- it is currently the cheapest direct check that shared fixture resources are exposed on the classpath
- moved integration tests still rely on those shared resources indirectly
- removing it during the same cleanup would reduce diagnosability without eliminating the underlying dependency

This test can be reconsidered only after shared fixture support is extracted into a cleaner dedicated support layer with equivalent direct validation.

### Consolidate `PluginBootstrapTest`

Keep:

- plugin descriptor metadata assertions
- classpath/bootstrap signature assertions

Remove:

- the web-workspace build-hook assertions, but only after `FrontendBuildWiringIT` is expanded to cover the same workspace/path guarantees exactly

Reason:

- final ownership of Gradle build-wiring assertions belongs in integration tests, but the migration must preserve those exact assertions before removing them from `PluginBootstrapTest`

## Execution Order

1. Update `integrationTest` source-set wiring so migrated tests can compile against shared helpers under `sourceSets["test"].output`
2. Move and rename misplaced integration tests without changing assertions
3. Verify migrated tests still compile and run with the explicit shared test-support classpath
4. Create or expand the integration build-wiring test at `src/integrationTest/kotlin/com/charmnight/linkgraph/build/FrontendBuildWiringIT.kt` so it absorbs the current unique web-workspace build-hook assertions from `PluginBootstrapTest`
5. Update docs and repository descriptions to reflect the cleaned contract
6. Remove only those duplicate checks that remain redundant after the migration and assertion backfill
7. Run `test`, `integrationTest`, and `check` after each phase or at least before finalizing

## Risk Management

- Do not mix relocation with behavioral test rewrites
- Delete only tests with clear duplicate or low-signal characteristics
- Preserve unique coverage even when a test is heavy
- Preserve shared test-support helpers unless the replacement support path is implemented in the same change
- If migrated tests compile against helpers under `src/test/kotlin`, make that dependency explicit in `integrationTest` compile classpath instead of relying on task runtime classpath assembly
- Treat build failures after migration as classification or naming regressions first, not feature regressions
- Avoid touching unrelated dirty-worktree files

## Validation Strategy

Required verification after implementation:

- `./gradlew test`
- `./gradlew integrationTest`
- `./gradlew check`
- `rg -l ': BasePlatformTestCase\\(\\)' src/test/kotlin`
  Expected: no matches, exit code `1`
- `rg -n 'class .*IT' src/test/kotlin`
  Expected: no matches, exit code `1`
- `rg -n 'class .*Test' src/integrationTest/kotlin`
  Expected: no matches for executable test classes, exit code `1`
- run one representative migrated class through targeted discovery:
  - `./gradlew integrationTest --tests '<migrated IT class>'`
    Expected: the class is discovered and executed by `integrationTest`
  - `./gradlew test --tests '<migrated IT class>'`
    Expected: Gradle reports `No tests found for given includes`

Success means:

- `test` runs only regular tests
- `integrationTest` runs only integration tests named `*IT`
- no platform-backed executable tests remain in `src/test/kotlin`
- the migrated test list is fully renamed and discoverable by Gradle task filters
- at least one representative migrated `*IT` class is discoverable by `integrationTest`, while `test --tests '<same class>'` fails with `No tests found for given includes`
- shared fixture helpers and resources remain resolvable for migrated tests
- docs no longer describe mixed semantics for `src/test`
- removed tests do not reduce meaningful behavioral coverage

## Expected Outcome

After cleanup, a new contributor should be able to infer the test policy directly from the tree:

- `src/test` means regular tests
- `src/integrationTest` means platform/runtime integration tests
- file names match task routing
- duplicate low-value checks no longer distract from meaningful failures

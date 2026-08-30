# Run the removed-sublevel split regression

Branch: `bug/nested-sublevel-recovery`

The branch is intentionally expected to **fail one GameTest on unmodified Sable 2.0.5**. That failure proves the #1376 lifecycle bug is reproduced before we attempt a production fix.

## Get the branch

From an existing clone of your fork:

```powershell
git fetch origin
git switch bug/nested-sublevel-recovery
git pull --ff-only
```

For a fresh clone:

```powershell
git clone https://github.com/JamesXNelson/sable.git
cd sable
git switch bug/nested-sublevel-recovery
```

Use Java 21. The first Gradle run will download Minecraft/NeoForge development dependencies and may take a while.

## Run the GameTests on Windows

From the repository root:

```powershell
.\gradlew.bat neoforge:runGameTest --stacktrace
```

Linux/macOS equivalent:

```bash
./gradlew neoforge:runGameTest --stacktrace
```

The upstream workflow uses the same `neoforge:runGameTest` task.

## Expected result before the fix

The Gradle task should finish with a non-zero result because this required GameTest fails:

```text
sable:removedsublevelsplittest.removedsubleveldoesnotattemptsplitassembly
```

The useful failure message is:

```text
Regression #1376: removed sublevel attempted heat-map split assembly
```

The test catches the original runtime exception deliberately, so it should be reported as a normal GameTest failure instead of crashing the whole test server. The caught Sable exception is:

```text
Sub-level assembly attempted inside plot of already removed sub-level
```

## Expected result after a correct production fix

The new test should pass, and the full `neoforge:runGameTest` task should succeed unless an unrelated existing test fails.

A correct fix should prevent a sublevel already marked for removal from continuing into heat-map split assembly. Avoid merely deleting or weakening the exception in `SubLevelAssemblyHelper`; the exception is exposing a lifecycle invariant violation.

## If the test unexpectedly passes on the unmodified branch

Save the complete console output and run:

```powershell
.\gradlew.bat neoforge:runGameTest --info --stacktrace *> gametest-output.txt
```

Then check:

1. `RemovedSubLevelSplitTest` was discovered.
2. `SUB_LEVEL_SPLITTING` is enabled in the GameTest configuration.
3. The test did not fail because the shared `assemblytest.brittlebreak` structure template was not found.
4. No upstream change was pulled beyond the branch base `6966d2928340de7631abcecf8549904b877df0a8`.

Send `gametest-output.txt` back for adjustment. An unexpected pass does not disprove the real crash; it means this minimal synthetic setup did not drive the heat map into the same state.

## Optional interactive run

After starting a development client/server with GameTests enabled, the test identifier should be:

```text
sable:removedsublevelsplittest.removedsubleveldoesnotattemptsplitassembly
```

Run it in-game with:

```mcfunction
/test run sable:removedsublevelsplittest.removedsubleveldoesnotattemptsplitassembly
```

The automated Gradle GameTest task is preferred because it exits with a machine-readable success/failure status.

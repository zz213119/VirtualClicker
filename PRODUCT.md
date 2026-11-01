# VirtualClicker — Product context

## What it is
VirtualClicker is a native Android utility for running app automation in a Virtual Display. It supports click, long-press, swipe, wait, repeat, scripts, and app-specific automation. Shizuku is the preferred backend; Root is an alternative.

## Who uses it and why
Android users who want an app to keep running in a separate virtual display while they use their phone for other things, or who need repeatable touch automation without tying the tool to one game.

## Main jobs
1. Authorize the Shizuku or Root backend.
2. Select the target Android app.
3. Configure and launch the Virtual Display.
4. Create or select an automation script; run, pause/stop, and inspect it.
5. Understand backend/display/task status and recover from permission or runtime errors.

## Product surfaces
- Home workbench: backend connection, target app, display preview and controls, task actions, optional diagnostics.
- Settings: backend mode, automation parameters, notifications, background behavior, AI options, logs.
- App picker: search and select user/system apps.
- Script editor: create and order click, long-press, swipe, and wait actions.
- Floating preview and AI assistant.

## Technical constraints
- Native Android app using Kotlin, Android Views/XML, and AppCompat.
- minSdk 28, targetSdk 36, Java/Kotlin target 17.
- Chinese is the primary UI language.
- Preserve existing view IDs, click listeners, services, permission flows, stored preferences, automation logic, and AI request behavior during visual work.
- Do not invent runtime statistics or success states. Status labels must be driven by actual app state.
- Do not migrate to Compose or add a heavy UI dependency for a visual refresh.

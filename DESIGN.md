# VirtualClicker — Design system

## Design intent
A calm, precise Android automation workbench. This is an Operate interface: the visual system should make connection state, target selection, preview, and task control immediately understandable. Familiar Android behavior and clarity outrank decoration.

## Visual identity
- Preserve the existing violet identity as the sole brand accent.
- Use a cool neutral background, white work surfaces, and thin neutral outlines.
- Reserve green for confirmed running/success, amber for warnings, and red for errors.
- Use color to communicate action, selection, and state—not as decoration.
- No gradients, glass blur, glow, fake metrics, decorative icon tiles, or ornamental card grids.

## Tokens
- Background: `vc_bg`
- Surface: `vc_card`
- Brand action: `vc_primary` / `vc_on_primary`
- Tonal controls: `vc_tonal` / `vc_on_tonal`
- Main text: `vc_text`
- Supporting text: `vc_text2`
- Outlines: `vc_outline`
- Semantic values: `vc_success`, `vc_warning`, `vc_error`

## Layout and components
- Keep the Android Views + AppCompat stack.
- Use 16–18dp page gutters and a consistent 16dp surface radius.
- Interactive controls should expose at least a 48dp touch target; leave breathing room between adjacent actions.
- Buttons use one 12dp shape. Text fields have a clear surface and outline, with at least 52dp height.
- Use the system sans-serif family and `sp` units so text follows Android font scaling.
- Use native Android controls, system Back behavior, keyboard behavior, and window/system-bar conventions.

## Information hierarchy
1. Backend connection and authorization.
2. Target application and scripts.
3. Virtual Display preview and controls.
4. Task actions and current status.
5. Diagnostics, which remain secondary and respect the existing visibility setting.

## Scope guard
A visual refresh must not alter backend selection, authorization, display creation, script execution, task lifecycles, persistence, AI requests, or service contracts.

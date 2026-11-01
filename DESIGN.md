# VirtualClicker — Design system

## Design intent
A calm, precise Android automation workbench. This is an Operate interface: connection state, target selection, preview, and task control must be immediately understandable. Familiar Android behavior and clarity outrank decoration.

## Visual identity — Ocean / blue-slate
The palette has moved from violet to an oceanic blue-teal identity. It should feel technical and dependable without turning the screen into a neon gaming dashboard.

| Role | Token | Value |
|---|---|---|
| App background | `vc_bg` | `#F2F6F7` |
| Work surface | `vc_card` | `#FFFFFF` |
| Primary action / selection | `vc_primary` | `#12677A` |
| Primary text | `vc_text` | `#17272D` |
| Supporting text | `vc_text2` | `#536B72` |
| Outline | `vc_outline` | `#D5E2E5` |
| Tonal surface | `vc_tonal` | `#DCECEF` |
| Running / success | `vc_success` | `#25734F` |
| Warning | `vc_warning` | `#805600` |
| Error / stop | `vc_error` | `#B3261E` |

- Blue-teal is the single brand accent for primary actions, selection, and active controls.
- Green means confirmed running/success only; amber means warning; red means failure or destructive stop.
- Preview surfaces use a neutral blue-grey so target-app content remains the visual focus.
- Floating controls use a deep blue-green shell with a translucent light toolbar and dark, readable control labels.
- Use color to communicate action, selection, and state—not as decoration.
- No gradients, glass blur, glow, fake metrics, decorative icon tiles, or ornamental card grids.

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

# Changelog

## v1.0 (versionCode: 1)
### Added
- **Pure Zygisk Battery Monitor**: Injected directly into Xiaomi HyperOS status bar beside the clock without LSPosed or companion APK.
- **KernelSU WebUI**: Live configuration for layout style, content mode, padding, interval, and font size.
- **Auto Layout Presets**: Preset switching for Dual-Line (1.5dp top, 0dp left, 6.5sp font) and Single-Line (0.5dp top, 0dp left, 6sp font).
- **Exact Clock Font Matching**: Uses HyperOS authentic `MiSansVF` medium weight (500) directly from the clock view.
- **0-Wakeup Sleep Protection**: BroadcastReceiver halts timer on screen-off to eliminate battery drain.

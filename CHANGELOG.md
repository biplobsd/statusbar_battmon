# Changelog

## v1.1 (versionCode: 2)
### Changed — zero-timer, zero-I/O, event-driven core
- **Removed the 1 Hz polling loop entirely.** There is no `postDelayed` ticker in the
  steady state any more; the readout is repainted only when the OS delivers an event
  (`ACTION_BATTERY_CHANGED`, screen on/off, configuration change, clock tint change).
- **No more battery sysfs reads.** Temperature, voltage, charging status and the charge
  counter now come from the sticky `ACTION_BATTERY_CHANGED` intent instead of
  `/sys/class/power_supply/battery/*`. On Qualcomm `pmic_glink` hardware each of those
  reads performs a synchronous RPC to the ADSP: measured 3.5 ms (`current_now`) and
  3.8 ms (`voltage_now`) of blocked time and ~10 interrupts per read.
- **Wattage without I/O.** Power is derived from the charge counter (`dQ/dt × V`) by
  default. Optional `power_source` values `sysfs_event` and `sysfs_live` restore the old
  behaviour for users who want it; both run off the main thread.
- **No more layout passes.** The two `TextView`s (WRAP_CONTENT) were replaced by a single
  self-drawing view with a size pre-computed from the worst-case string, so a value update
  can no longer trigger `requestLayout()`, a measure pass, or a window relayout.
- **Config reload without `stat()` polling** via `FileObserver` (inotify).
- **Typefaces cached**; no `Typeface.create()` in the update path.
- **Bounded, exception-free view discovery**: depth/size capped, `View.NO_ID` guarded,
  reflection result cached, and the `CLOCK_DIAG` log dump removed.
- **Fixed a use-after-free in the native loader**: the direct `ByteBuffer` handed to
  `InMemoryDexClassLoader` is now backed by process-lifetime storage (ART keeps a raw
  pointer into it), instead of a stack-local `std::vector` that was freed on return.

## v1.0 (versionCode: 1)
### Added
- **Pure Zygisk Battery Monitor**: Injected directly into Xiaomi HyperOS status bar beside the clock without LSPosed or companion APK.
- **KernelSU WebUI**: Live configuration for layout style, content mode, padding, interval, and font size.
- **Auto Layout Presets**: Preset switching for Dual-Line (1.5dp top, 0dp left, 6.5sp font) and Single-Line (0.5dp top, 0dp left, 6sp font).
- **Exact Clock Font Matching**: Uses HyperOS authentic `MiSansVF` medium weight (500) directly from the clock view.
- **0-Wakeup Sleep Protection**: BroadcastReceiver halts timer on screen-off to eliminate battery drain.


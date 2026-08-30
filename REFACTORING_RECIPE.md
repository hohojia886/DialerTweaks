# Case Study: Silencing Google Dialer Announcements (Fermat / Call Notes)

This document chronicles the discovery process, technical identification, and
final implementation strategy used to successfully silence the "Call Notes"
(codename `Fermat`) recording announcement in the modern Google Dialer.

---

## 1. Discovery Process (Trial & Error)

### Phase 1 — The Initial Blind Spot

The first attempt targeted standard Java-level audio components (`AudioTrack`,
`TextToSpeech`) exclusively within the Dialer process.

- **Result**: Failure. No logs were produced, and the announcement remained
  audible.
- **Conclusion**: The announcement was either played by a different process or
  routed through a non-standard audio path.

### Phase 2 — Broadened Scope & Diagnostic Mode

To locate the true source, the module's scope was expanded to include
`com.google.android.as` (System Intelligence), `com.google.android.gms` (GMS),
`com.google.android.googlequicksearchbox` (Google App), and `system_server`.

- **Diagnostic tooling**: Forced `Log.e` on every constructor and playback
  method of `AudioTrack`, `MediaPlayer`, `SoundPool`, `ToneGenerator`, and
  `TextToSpeech`.
- **Stack trace dumping**: Every audio event triggered a 15-frame stack trace
  dump to Logcat, to be inspected after the fact.

### Phase 3 — Identifying the Culprit

The logs revealed two independent actors, plus one obstacle:

1. **`com.google.android.dialer`** — triggers `MediaPlayer.prepare()` and
   `start()` through obfuscated classes (e.g. `oea.c`, `hsk.b`).
2. **`com.android.systemui`** — triggers `NotificationPlayer` (backed by
   `MediaPlayer`) to play the "beep" tone.
3. **The obstacle**: architectural identifiers such as `Fermat` or
   `AudioInjector` are stripped by R8/Proguard obfuscation, making
   name-based class/method filtering unreliable.

---

## 2. Core Implementation Principles

### 2.1 "Execute but Silent" Strategy

Directly blocking audio methods, or returning `null` from them, frequently
causes the Dialer's internal state machine to crash or hang, since callers
expect a valid object or a completed lifecycle.

- **Logic**: allow `MediaPlayer` / `AudioTrack` to run its full lifecycle
  (`prepare` → `start` → `stop`) exactly as intended by the caller.
- **Action**: intercept at the *side-effect* layer instead — call
  `setVolume(0f, 0f)` immediately before sound is produced.
- **Result**: silent muting with no observable change to Dialer's internal
  state, and no stability risk.

### 2.2 Anti-Obfuscation via Stack Trace Shape

Since obfuscated class names change across app versions, "intent" is
identified through the *shape* of the call stack rather than literal names:

- **Heuristic 1**: if a `MediaPlayer` is started within the Dialer process and
  the stack contains the generic keyword `media` (common across obfuscated
  internal audio paths), it is flagged as the announcement.
- **Heuristic 2**: if the caller is `NotificationPlayer` inside the `system`
  process during an active call, it is flagged as the beep tone.

---

## 3. Final Implementation Summary

### Target Processes

Scope was narrowed back down to the minimum required for correctness and
efficiency:

- `com.google.android.dialer` — voice announcement
- `com.android.systemui` — beep tones
- `system` (system_server) — cross-process coordination

### Hook Layers

1. **`MediaPlayer`** — hooks `prepare`, `prepareAsync`, and `start`;
   force-calls `setVolume(0, 0)` whenever a match is detected.
2. **`AudioTrack`** — hooks constructors to zero the volume, and hooks
   `write()` to zero out PCM buffer contents as a safety net.
3. **`ToneGenerator` / `Ringtone`** — playback is blocked directly when
   triggered by Dialer-related call stacks.

---

## 4. Key Takeaways

- **Don't trust names.** In obfuscated apps, the shape of the call stack is
  far more reliable than class or method names.
- **Don't block — mute.** Modifying instance state (volume, buffer contents)
  is safer than modifying control flow (return values, execution branches).
- **Follow the context.** Multi-process interception is necessary to track
  audio that has been handed off to a system-level player such as
  `NotificationPlayer`.

---

## 5. Original Proposal (Appendix)

This section preserves the initial conceptual strategy that ultimately led to
the production implementation above.

### 5.1 Shift in Core Philosophy

Earlier attempts to block playback outright (hooking `play()`, `start()`, or
returning `null`) frequently left the calling `AudioInjector` in an
unexpected state, causing crashes or resource leaks.

**Golden rule**: interceptions should favor *"execute but remain silent"*
over *"prevent execution"*.

### 5.2 Identifying the Caller via Stack Trace

To avoid muting legitimate call audio, the source of each playback call must
be verified before acting on it:

```kotlin
private fun isFermatCaller(): Boolean {
    val stack = Thread.currentThread().stackTrace
    return stack.any {
        it.className.contains("AudioInjector", true) ||
        it.className.contains("Fermat", true) ||
        it.className.contains("tidepods", true) // SODA-related classes
    }
}
```

### 5.3 Approach A — Volume Zeroing

Hook the relevant constructor (or lifecycle method) and force the volume to
zero once a match is confirmed:

```kotlin
audioTrackClass.declaredConstructors.forEach { ctor ->
    module.hook(ctor).intercept { chain ->
        val result = chain.proceed()
        if (isFermatCaller()) {
            val instance = chain.thisObject as AudioTrack
            runCatching { instance.setVolume(0f) }
        }
        result
    }
}
```

### 5.4 Approach B — SoundPool Stream Muting

For short beep sounds, intercept `play()` and mute the resulting `streamId`:

```kotlin
module.hookAfter(soundPoolPlay) { chain, result ->
    val streamId = result as Int
    if (isFermatCaller() && streamId > 0) {
        (chain.thisObject as SoundPool).setVolume(streamId, 0f, 0f)
    }
}
```

### 5.5 Approach C — PCM Buffer Nuking (`AudioTrack.write`)

The ultimate fail-safe: allow the write to proceed, but zero out the buffer
contents beforehand:

```kotlin
module.hookBefore(audioTrackWrite) { chain ->
    if (isFermatCaller()) {
        val buffer = chain.args[0] as ByteArray
        java.util.Arrays.fill(buffer, 0.toByte())
    }
}
```

---

## 6. Conclusion: Evolution from Proposal to Production

The final implementation is an evolved hybrid of the original Approaches A
and C, significantly enhanced by a new identification engine.

### 6.1 Comparison: Original vs. Final

- **Approach A (Volume Zeroing)** — originally focused on constructors only.
  The production version expanded coverage to the full lifecycle
  (`prepare`, `prepareAsync`, and `start`) to correctly handle object reuse
  and state resets.
- **Approach C (PCM Zeroing)** — originally targeted `byte[]` only. The
  production version added a safety net covering all overloads, including
  `short[]` and `ByteBuffer`, ensuring total silence even if the target
  switches to a high-performance audio path.
- **Identification (the major shift)** — the original proposal relied on
  matching specific class names (`Fermat`, `AudioInjector`). Due to heavy
  R8/Proguard obfuscation, the production version switched to **stack trace
  shape analysis**: instead of searching for *names*, it infers *intent* from
  process context plus generic architectural keywords (e.g. `media` within
  Dialer, `NotificationPlayer` within SystemUI).

### 6.2 Summary

This project demonstrates that, in modern obfuscated Android environments,
**modifying side effects (state)** is more reliable than **blocking execution
(control flow)**, and **heuristic pattern matching** holds up better over
time than **literal string matching**.

# Demo video recording checklist

Tools and steps for recording the demo in [`demo-video-script.md`](demo-video-script.md) on Fedora
GNOME (Wayland). Run the steps in order. To capture on a MacBook instead, follow
[`demo-video-recording-checklist-macos.md`](demo-video-recording-checklist-macos.md) and resume at
"Voice-over".

## Tools

| Task | Tool | Install |
|---|---|---|
| Screen capture | OBS Studio | `flatpak install flathub com.obsproject.Studio` |
| Voice-over, zoom, captions, export | Kdenlive | `flatpak install flathub org.kde.kdenlive` |

Do not use Kazam or SimpleScreenRecorder: they capture X11 only. GNOME's built-in recorder
(`Ctrl+Shift+Alt+R`) has no audio or quality controls.

## Prepare

- [ ] Use a USB or dongle microphone. A Bluetooth headset in call mode records a narrow, muddy signal.
- [ ] Record 10 seconds of speech and listen back before the real take.
- [ ] Close notification sources: chat, mail, the IDE's own balloons.
- [ ] Open 8 to 12 tabs with realistic file names.
- [ ] Raise the IDE zoom (View | Appearance) until the tab strip is readable at phone size.
- [ ] Install PrismTabs in a second JetBrains IDE. Name only IDEs it has run in.

## Configure OBS

- [ ] Settings | Video: base and output resolution equal the monitor's native resolution, 30 fps.
- [ ] Settings | Output | Recording: MKV, x264, CRF 18. After recording, use File | Remux Recordings to get MP4.
- [ ] Add a **Screen Capture (PipeWire)** source and pick the IDE window or the whole screen in the portal dialog.
- [ ] Enable the cursor in the source. The recording is silent: leave the microphone out.

A 1080p display cannot capture more pixels than it has. Zoom-ins in the edit upscale, so keep them
modest. For a tighter view, raise the IDE zoom further and record that beat separately.

## Capture

- [ ] Record each script row as its own take. Pause a second before and after each action.
- [ ] Move the cursor slowly. Hold on every click for a moment.
- [ ] Record the IDE restart (1:15 to 1:20) as one take and cut the wait while the IDE reloads.
- [ ] Record a light-theme and dark-theme take for 0:25 to 0:35.

## Voice-over

- [ ] Import the takes into Kdenlive and arrange them to the script's timings.
- [ ] Record the narration with Kdenlive's voice-over recorder on a new audio track while the muted timeline plays.
- [ ] Re-record a whole line after a stumble. Do not splice words.
- [ ] Normalise the narration to about -14 LUFS, the level YouTube plays at.

## Edit

- [ ] Zoom to the tab strip and tool window with a crop and position effect.
- [ ] Add a ring overlay at each click. Match the ring to the cursor position and the click moment.
- [ ] Generate captions with Kdenlive's subtitle speech recognition. Correct every line against the script.
- [ ] Burn the captions into the video on render.
- [ ] End card: Marketplace listing and prismtabs.com, as in the script.

## Export and publish

- [ ] Render MP4 (H.264, AAC) at 1920×1080, about 8 to 12 Mbps.
- [ ] Watch the whole file once with the sound off and once with the sound on.
- [ ] Put the Marketplace link on the first line of the description and repeat it in a pinned comment.
- [ ] Do not state prices. Say "14-day free trial".

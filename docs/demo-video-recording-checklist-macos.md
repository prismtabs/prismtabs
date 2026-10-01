# Demo video capture on macOS

Capture the screen takes for [`demo-video-script.md`](demo-video-script.md) on a MacBook Air M1
(8 GB), then edit on Linux. The built-in recorder needs no install. After the capture, continue at
"Voice-over" in [`demo-video-recording-checklist.md`](demo-video-recording-checklist.md).

The Retina display records at 2560×1600, so zoom-ins into the tab strip stay sharp. Click
highlighting is built in. The 8 GB of memory fits one IDE at a time: record each IDE as its own take.

## Prepare

- [ ] Quit every other application. Open Activity Monitor and keep the Memory Pressure graph green.
- [ ] Turn on a Focus mode that silences notifications.
- [ ] Grant Screen Recording permission to the recorder in System Settings | Privacy & Security.
- [ ] Use an external mouse, or enable secondary click on the trackpad, so "right-click" matches the narration.
- [ ] Open 8 to 12 tabs with realistic file names.
- [ ] Raise the IDE zoom (View | Appearance) until the tab strip is readable at phone size.
- [ ] Install PrismTabs in a second JetBrains IDE. Name only IDEs it has run in.

## Configure the recorder

- [ ] Press `Shift+Cmd+5` and choose **Record Entire Screen**.
- [ ] Options | Microphone: **None**. The voice-over is recorded later.
- [ ] Options | Show Mouse Clicks: on.
- [ ] Options | Show Floating Thumbnail: off. The thumbnail would appear in the next frame.
- [ ] Options | Save to: a folder you can copy from.

## Capture

- [ ] Record each script row as its own take. Pause a second before and after each action.
- [ ] Move the cursor slowly. Hold on every click for a moment.
- [ ] Record the light-theme and dark-theme switch for 0:25 to 0:35 in the first IDE.
- [ ] Quit the first IDE, then record the second IDE's take for the same beat.
- [ ] Record the IDE restart (1:15 to 1:20) as one take and cut the wait while the IDE reloads.
- [ ] Stop each take with the stop button in the menu bar, or with `Shift+Cmd+5`. Trim the first and last second in the edit.

## Hand over

- [ ] Copy the `.mov` files to the Linux machine, for example with a USB drive or `rsync`.
- [ ] Open one file in Kdenlive and check that the clip plays and keeps its 2560×1600 size.
- [ ] Set the Kdenlive project to 1920×1080. The Mac screen is 16:10 and YouTube is 16:9: scale each take to 1920 wide and crop 120 px from the bottom, which holds only the status bar.
- [ ] Continue at "Voice-over" in the Linux checklist. Skip its "Configure OBS" and "Capture" sections.

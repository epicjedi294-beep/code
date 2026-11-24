# Key Sequence Studio 1.4.4

A simple Java desktop app for keyboard/mouse-triggered sequences, Hold binds, Scratch-style macros, and run notifications.

## Open in IntelliJ IDEA

1. Extract the project ZIP.
2. In IntelliJ IDEA, choose **File → Open** and select the `KeySequenceStudio_Simple` folder (the folder containing `build.gradle`).
3. Let IntelliJ load Gradle.
4. In **Settings → Build Tools → Gradle**, select your installed JDK (JDK 26 is fine). The project targets Java 17 bytecode.
5. Reload Gradle and run **Tasks → application → run**, or run `KeySequenceStudio.main()`.

The first Gradle sync needs internet access to download JNativeHook 2.2.2. Make sure Gradle Offline mode is disabled.

## Build a Windows installer

On Windows, run **Tasks → distribution → packageWindowsInstaller**. A full JDK and WiX Toolset 3.x or later are required. The installer is created in `build/installer/`. The project also includes `packageWindowsPortable` for a portable app folder.

## Tabs

The app has four tabs: **Sequence**, **Hold**, **Macros**, and **Settings**. A trigger can belong to only one Sequence, Hold, or Macro entry at a time. Sequence binds and Macros can each list up to eight alternative triggers; pressing any one of them starts that entry. Hold binds have one trigger each. (The old Potato Mode / Fortnite graphics preset tab has been removed.)

## Sequence tab

1. Click **Add bind**, then press a keyboard key or mouse button as the trigger. A Sequence bind can have up to eight trigger keys/buttons: select it and click **Add trigger to selected**. Any one of its triggers starts the same sequence.
2. Select the bind and set **Repeat #** to repeat the full sequence (1–1,000 times).
3. Click **Add action**, then press a key or click a mouse button. Use **Add wait** for a pause.
4. Select a step and choose **Edit ms**. Key/mouse hold times allow 1–5,000 ms; waits allow 1–10,000 ms.
5. **Set ms for all** applies one hold time to all key/mouse steps in the selected sequence. Wait steps are unchanged.
6. Use **Rename** to give the bind a custom label. The original trigger appears beside it in gray brackets (for example, `auto gg [H]`). Reordering, removal, enable/disable, and timing behavior are unchanged.

## Macros tab

Macros now use a Scratch-style visual block workspace and are separate from Sequence and Hold binds. The workspace has a colored block toolbox, an event-style yellow trigger block, interlocking blue/purple/orange/green action blocks, and drag-and-drop placement/reordering.

1. Click **Add macro**, then press its first keyboard or mouse trigger.
2. Select the macro and use **Add trigger to selected** to add up to eight alternative triggers. Any one of the listed triggers starts the macro.
3. Use the scrollable **Block Toolbox** (Scratch-style categories and colors) to click or drag blocks into the workspace. Available blocks are:
   - **Motion:** Move mouse to x/y (absolute screen position), Move mouse by dx/dy (relative), Scroll wheel.
   - **Looks:** Show notice.
   - **Sound:** Play beep.
   - **Events / Input:** Press key, Click mouse, Type text.
   - **Control:** Wait, Repeat, End repeat, Stop macro.
   - **Sensing / Other:** Copy text, If pixel found + End if (run the blocks in between only when a matching color is on screen), Comment.
   - **Operators:** Random number reporter (default range 1–100).
   For Press key and Click mouse, press the physical input when capture starts. Type text supports ASCII letters, numbers, common punctuation, Enter, Tab, and Space using the active keyboard layout. Scroll wheel uses signed notches (positive = up, negative = down). Move-mouse blocks accept coordinates (or random values) and can be edited by double-click or **Edit selected**. Double-click **If pixel found** to set the search region (X/Y/width/height), target RGB color (with a “Sample color under mouse” button), and per-channel tolerance. Place the actions you want (e.g. Press key / Click mouse) between **If pixel found** and **End if**.
4. Drag blocks within the workspace to reorder them. Drag the green **Random number** reporter onto a numbered `[ ]` input block (for example, `Wait [ ] ms` or a move-mouse coordinate) to plug a random value into that slot. Double-click a block or select it and choose **Edit selected** to change its values. **Move up**, **Move down**, and **Remove block** remain available as alternatives. Blocks use a more pronounced Scratch-style interlocking shape (top socket / bottom tab + rounded corners).
5. **Repeat** / **End repeat** form nested loops. **If pixel found** / **End if** form conditionals: the blocks placed between them run only when a matching pixel exists in the region. Both can nest (up to five levels) and are validated before a macro runs. Stop macro ends the current macro. Comment blocks are visual notes. Copy text writes the configured text to the system clipboard.
6. **Repeat macro** repeats the entire block stack. Macros can be renamed, enabled/disabled, and removed. Macro execution has a safety ceiling of 100,000 block executions per run to avoid accidental runaway nested loops.

## Run notifications

The Settings tab can enable or disable run notifications, set their duration (500–15,000 ms), and choose **Top left**, **Top right**, **Bottom left**, or **Bottom right**. When a Sequence bind, Hold bind, or Macro starts, a compact 250 x 60 px non-focusable always-on-top popup appears in the selected corner. A blue countdown bar is drawn directly into the popup and shrinks until the notification closes. Notifications stay above normal windows when you click elsewhere; exclusive full-screen applications may cover desktop overlays.

## Hold tab

A Hold bind repeats one selected keyboard key or mouse button while its trigger remains physically held.

1. Click **Add hold bind** and press the trigger.
2. Select the bind and click **Set action**. Press the key or click the mouse button to repeat.
3. The repeated action **may be the same as the trigger** (for example, hold E → repeat E, or hold Mouse 4 → repeat Mouse 4).
4. Set **Repeat every** (1–10,000 ms) and **Press duration** (1–5,000 ms).
5. Use **Rename** to give a Hold bind a custom label; its physical trigger remains shown in gray brackets. Releasing the physical trigger stops repeats. Disabling/removing a bind or closing the app also stops it and releases any active generated output.

The app filters short synthetic release events when the output matches its own trigger so they are not mistaken for the user's physical release. This uses JNativeHook global events and Java Robot; verify same-trigger behavior on your Windows mouse/keyboard because event delivery can vary by driver. Automated repeated inputs may be restricted by some games or applications; check their rules before use.

## Settings tab

The Settings tab contains Sequence Delay Mode, application preferences, run notifications, and a global enable/disable hotkey.

### Global enable / disable

1. Click **Set toggle key**, then press a keyboard key or mouse button (for example, `P`).
2. Choose which groups the hotkey controls: **Sequence binds**, **Hold binds**, and/or **Macros**. All three are selected by default. For example, uncheck Sequence to make `P` toggle only Hold binds and Macros.
3. Press the selected hotkey once to disable the checked groups, then press it again to re-enable them. The **Disable selected** and **Enable selected** buttons perform the same actions manually.
4. This global switch does not change each bind's individual Enabled state. Disabling Hold immediately stops its repeat tasks and releases active generated output. Disabling Sequence or Macros cancels a currently running action in that group and releases any key/mouse action it was holding.
5. The global hotkey is reserved: it cannot also be a trigger or generated action. This avoids one press accidentally starting a macro while toggling the app.
6. The global disabled state resets to enabled when the app starts; the hotkey and group choices are saved.

The delay selector applies to Sequence actions only:

- **Per-step** uses the saved timing for each key/mouse action.
- **1 ms**, **5 ms**, **10 ms**, **20 ms**, and **50 ms** force that hold duration.
- Explicit Wait steps are always respected.
- The former Null option was removed; older saves that selected it migrate to Per-step.

## Input and save notes

- Sequence key/mouse steps default to 20 ms. Wait steps default to 250 ms. No automatic inter-step delay is added.
- Mouse clicks occur at the cursor position.
- Mouse Middle and Mouse Right are translated separately. Mouse 4/5 are independent side buttons; the scroll wheel is ignored.
- Physical triggers are not suppressed or intercepted from the target app.
- Renamed Sequence and Hold binds are stored independently from their triggers. A custom name such as `auto gg` displays as `auto gg [H]`, with the trigger tag rendered in gray. Clearing the name returns to the original trigger-only label.
- Settings include confirmation before deleting binds/macros, an always-on-top preference, timed run notifications, and a configurable global pause/resume hotkey for selected automation groups.
- Binds are auto-saved to `%USERPROFILE%\.key-sequence-studio\binds.dat`.
- Save format version 14 remains backward-compatible with versions 1–13. Existing macros continue to load. New Motion blocks (Move mouse to / Move mouse by) use later enum ordinals and are fully supported going forward. Older Potato Mode preference data in a save file is still read for compatibility but the feature and its tab have been removed.


### Notification improvements (1.3.9)

- Notifications use a compact 250 x 60 px overlay card.
- A dedicated custom-painted countdown bar remains visible below the message.
- Choose Top left, Top right, Bottom left, or Bottom right in Settings.
- Position and duration are saved automatically. Existing 1.3.8 settings default to Top right.
- The notification timer bar is rendered directly by the overlay to keep it visible and independent of the Windows Swing theme.

### Global hotkey improvements (1.4.1)

- Add a keyboard or mouse-button hotkey to toggle the selected automation groups on/off.
- Choose Sequence, Hold, and Macros independently; all are selected by default.
- Manual **Disable selected** and **Enable selected** buttons are available beside the live status label.
- Group-level disabling leaves each entry's own Enabled flag untouched. Hold loops stop and release active outputs; active Sequence/Macro runners are interrupted and release any currently pressed output.
- The hotkey cannot be assigned as an entry trigger or generated output action to prevent self-toggling.
- The hotkey and group selection persist; application startup always restores the global runtime state to enabled.

### Expanded Scratch-style macros (1.4.2)

- Added Type text, Scroll wheel, Repeat, End repeat, Stop macro, Play beep, Copy text, and Comment blocks.
- Added a scrollable toolbox grouped into Input, Control, Looks & Sound, and Clipboard & Notes.
- Repeat/End repeat blocks support nesting up to five levels. Unpaired loop blocks are rejected before execution.
- Macro execution stops after 100,000 block executions per run as a guard against accidentally huge nested loops.
- Type text uses Java Robot key events for ASCII characters and common punctuation; unsupported characters on a keyboard layout report an error.
- Copy text sets the system clipboard to the block's configured text. Comment blocks are informational only.
- Existing 1.4.1 macro blocks remain loadable; new block type IDs are appended after the original four types.


## Version 1.4.3 – draggable random number values

- Removed Random wait from the toolbox and replaced it with a reusable green **Random number** reporter.
- The reporter defaults to an inclusive integer range of 1–100. Click it to change the minimum and maximum; the default range is saved between app launches.
- Drag the reporter onto a numbered block input, such as `Wait [ ] ms`. The whole target block accepts the drop because each supported block has one numeric slot.
- Random values are supported in Wait duration, key/mouse hold duration, Repeat count, Scroll amount, and Type Text character interval.
- Each execution of a plugged-in reporter chooses a fresh integer in its configured inclusive range. When placed in a Repeat count slot, the count is chosen once when that Repeat block starts.
- Select a block with a random value and choose **Edit selected** to change the range or turn the random operand off.
- Existing v1.4.2 Random wait blocks migrate to ordinary Wait blocks with the old minimum and maximum plugged into the Wait number slot.
- Save format version 14 remains backward-compatible with versions 1–13.

## Version 1.4.4 – If pixel found (conditional)

- New **If pixel found** + **End if** blocks (toolbox → Sensing / Other).
- Works like a Scratch `if`: put action blocks (Press key, Click mouse, Wait, etc.) between them. Those actions run **only** when a matching pixel exists in the configured region.
- Configure region (X/Y/width/height), target RGB, and per-channel tolerance (0–255). Edit dialog has **Sample color under mouse** and **Use mouse as top-left**.
- Does **not** move the mouse by itself — it only decides whether the inner blocks run.
- Nesting with other If / Repeat blocks is supported (max 5 levels). Pairing is validated before the macro runs.
- Save format version 15 (older saves still load).

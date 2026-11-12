# Vidsic Player

**Vidsic Player** is an Android media player designed for playing and organizing audio files and videos stored on your device.

It provides separate Audio and Video libraries, allowing users to browse their media, create playlists, manage favorites, control playback, and customize their listening and viewing experience.

## Features

### 1. Audio and Video Playback
- Play music and other supported audio files.
- Watch locally stored videos.
- Play video files in audio-only mode.
- Control playback with play, pause, previous, next, and seek controls.
- Flip the player between landscape and portrait with the rotate button in its top bar.
- Tapping the player hides the controls and the status / navigation bars together; tap again to bring them back.
- Continue audio playback when the app is in the background.
- Access playback controls through the Android media notification.

### 2. Media Library
- Browse audio and video files separately.
- Organize media by folders.
- Browse audio by artist and album, and video by its available author/group classification.
- Search for media by name.
- Sort media by name, date, duration, file size, or most played.
- Switch between list and grid layouts.
- Select multiple items to perform library actions.

### 3. Playlists and Favorites
- Create and manage playlists.
- Keep audio and video playlists separate.
- Add individual or multiple items to playlists.
- Mark media as favorites.
- View recently played items.
- Manage the current playback queue.

### 4. Playback Queue
- Add media to the playback queue.
- Play an item immediately or schedule it to play next.
- Duplicates: by default an item already in the queue is not added again ("Already in queue") and "Play next" moves it instead of listing it twice. Turn on Settings > Allow play queue duplication to allow copies.
- Remove items from the queue.
- Reorder queued items by dragging them.
- Automatically continue to the next item.
- Use shuffle and repeat modes, including repeat-one.

### 5. Audio Spectrum Visualization
- Display an animated audio spectrum in the player.
- Optionally synchronize spectrum bars with the audio currently being played.
- Show a visual representation of changing audio frequencies instead of the normal player thumbnail.

The spectrum feature uses Android's audio visualization facilities.
The source code states that the spectrum permission is used to analyze the audio being played and that audio is not recorded.

### 6. Library Management
- Rescan the media library manually.
- Optionally rescan when the app opens or media files change.
- Show hidden audio files, videos, and folders.
- Access media found in hidden folders or `.nomedia` directories when the necessary Android file-access permission is granted.

### 7. Backup and Restore
- Create a Vidsic backup ZIP file.
- Back up your settings, favorites and audio playlists.
- Back up video playlists separately, plus photo favorites and collections.
- Restoring asks whether to Merge (keep what you have, add what is missing) or Replace.

**Note:** The backup feature stores favorites and playlist information, not copies of the actual media files.

### 8. Customization
- Choose the app's built-in theme.
- Use Android device colors where supported.
- Customize the theme's base appearance and accent color.
- Configure playback behavior and headset-related options.

### 9. Image Viewer (optional)
- Off by default. Turn it on in Settings > Image Viewer to add Photos to the bottom bar.
- Photos | Albums | Collection | Favorite | GIF | SVG tabs (GIF and SVG list every .gif / .svg on the device); history of opened pictures; search; sort; grid or list; multi-select.
- Collections work like playlists, for pictures.
- Viewer: swipe left/right, pinch or double-tap to zoom, rename, delete, favorite, share, info.
- Slideshow (in order or shuffled) with adjustable time per picture and fade between pictures.

## Platform

- Application type: Android application
- Minimum Android version declared by the uploaded source: Android 11 (API 30)
- Package name: `com.chxd.vidsicplayer`
- Main language: Java

Actual playback support depends on the device's media decoders and the file format.

## License

Vidsic Player is distributed under the **GNU General Public License v3.0 (GPL-3.0)**.

The license permits users to:
- Use the software.
- Study and modify its source code.
- Copy and redistribute the software.
- Distribute modified versions under the applicable GPL-3.0 requirements.
- Charge money for copies or distribution.

When redistributing the application or distributing modified versions, you must follow the applicable license conditions. These include preserving required notices, providing access to the corresponding source code, and licensing covered modified versions under GPL-3.0.

The software is provided without warranty as specified by the license.

## Project Links

- Source code:
  https://github.com/cardhollow/vidsic

- License:
  https://github.com/cardhollow/vidsic/blob/main/LICENSE.md

- Android APK release:
  https://github.com/cardhollow/vidsic/releases/tag/App

## Summary

Vidsic Player is a local Android audio and video player with library organization, playlists, favorites, queue management, background audio playback, audio spectrum visualization, backup and restore, and customizable themes.

Its primary purpose is to make the media already stored on your device easier to browse, organize, and play.

## Advanced Editor, Info and more

- **Advanced Editor** (⋮ on any video or audio): video – title, author, thumbnail, description; audio – title, artist, cover art, album, genre. Edits are written for real: into the file's own tags (MP3 ID3, M4A / MP4 / M4V atoms, FLAC comments) and into Android's media database, so other apps and rescans see them. Only for formats that can't be written to (for example OGG, Opus, WAV, MKV) the edit is kept inside Vidsic instead, and you're told when that happens. On Android 11+ without "All files access" the system asks once to allow the change. Renaming the file still updates the title as before.
- **Editing several items at once**: select items in the library and tap **Edit**. It is the same editor, but the title shows `<Unwritable>`. A field whose value is the same on every selected item shows that value and can be edited right away; a field that differs shows `<Edit>` — press it first to write one value for all of them. Fields you don't touch are left alone on every item.
- **Info** (⋮ on any video or audio): file name, folder, size, length, date, format, plays, genre / description.
- **Genres** tab in Audio. Folders, authors, albums and genres have a ⋮ menu (play, shuffle, queue, playlist, rename album / genre). Photo albums have a ⋮ menu too (slideshow, collection, favorites, delete).
- **Photos**: `.svg` drawings open in the viewer; Sort by has **Most viewed**.
- **Open with / Share**: audio, video and pictures from other apps open in Vidsic Player.
- Backups now also hold the edited details and chosen cover pictures.

## Picture editing

- **Rotate in the viewer**: turn the picture with two fingers; it snaps to 0 / 90 / 180 / 270 when you let go.
- **Edit picture** (⋮ in the viewer or on a picture): crop (free or fixed shape), rotate, flip, brightness / contrast / saturation. Saving asks **Replace** or **Save as new**.
  - **Rotate** by any angle with a slider (the angle shows above it while you drag, fine steps of 0.1°); the canvas grows and the new corners are transparent. 
  - **Text** can be rotated, has a background you can recolour, and a font picker (built-in fonts, or upload your own .ttf / .otf, which stays in the list).
  - **Draw** has Pencil and Eraser; pencil types: Pen, Marker, Highlighter, Dotted, Neon. The eraser only removes strokes drawn since the last Apply, never the picture itself.
  - **Chroma** (colour key): pick a spot (a magnifier follows your finger and shows the exact pixel and colour), choose **Select**: **Color** (every pixel of that colour), **Alpha** (every pixel with that transparency) or **Area** (only the connected area around the spot, like a bucket). Set the tolerance and **Smooth** (softens rough / aliased edges), then make the selection transparent or replace it with a colour. A checkerboard is always shown behind the picture in the editor.
  - **GIF and SVG** can be edited too ("Edit as PNG"): the first frame / the drawing is opened with transparency and saved as a new PNG so the alpha is kept. Anything with transparent areas is saved as PNG, never JPEG.
- **Collage**: select 2 to 9 pictures in Photos and tap Collage (or ⋮ on an album / collection > Make a collage). Pick a layout, shape, background and spacing; tap two pictures to swap them. Saved to Pictures/Vidsic.
- `.svg` drawings now have real thumbnails in the lists.

## More picture tools

- Viewer: the pencil button opens the editor; Rename is in the ⋮ menu.
- Editor also has Filters (B&W, Sepia, Cool, Warm, Vivid, Faded, Vintage), Text (colour, size, background, drag to place) and Draw. Saving asks Replace or Save new.
- Saving new pictures asks for All files access first, and falls back to writing the file directly when the media library refuses.
- **Create GIF**: select 2+ pictures > Create > GIF (or ⋮ on an album / collection). Set the time each picture stays, the transition into each one (None, Fade, Slide, Slide up, Zoom, Wipe), transition time, size, shape, fill / fit and smoothness; preview plays live.

## Saving, permissions and battery

- **Saving pictures** (edit > Save new, collage, GIF) no longer asks for All files access first. It just saves; only if saving fails does it offer **Open settings** or **Try again**.
- **Audio spectrum**: the Microphone permission is now declared (it was missing, so Android always refused it). If Android has stopped showing the question, the app offers to open its settings instead of failing silently.
- **Grant media access** asks first and only opens the settings page when Android won't ask again. Notifications are asked once, not at every start.
- **Battery Saving Mode** (Settings > Battery; off by default, optionally follows Android's Battery Saver): turns off the real audio spectrum, the Vibe visual and the player spectrum, auto rescan, Bluetooth auto-play and keep-screen-on, and slows timers and animations. Your settings are not changed; they come back when it is off.
- Less background drain even without that mode: the audio spectrum stops reading as soon as the app is out of sight, the caption timer stops in the background, the library no longer rescans while the app is hidden (it catches up when opened), the notification is only rebuilt when something changed, and the sync / position timers slow down in the background.

## Editor fixes

- Text tool: the dashed frame and background box are now centred on the text.
- Crop: the picture zooms so the crop frame fills the screen after each adjustment, like gallery apps.
- Create GIF: controls are split into Picture / Transition / Output tabs, with a summary line (position, length, size) and numbered thumbnails.
- Viewer: the pencil renames SVG and GIF files (they can't be edited).
- Picture in picture works again (the activity declares `supportsPictureInPicture`).

## Home screen: shortcuts and widgets

- **Add to home screen** is in the menu of every group (folder, artist/author, album, genre, playlist, photo album, collection) and next to the Favorites, GIF and SVG headers. New kinds of group only need an entry in `Group.java` and a call to `HomeAdd.show(...)`.
- **Shortcut**: choose a name, a built-in icon and colour, or your own picture (up to 2 MB, cut to a square). Tapping it opens that group in the app.
- **Widgets** (Vidsic Player / Vidsic Picture / Vidsic Group): the player with previous / play / next (resize it and it scales: a small row, then a larger thumbnail with title and a heart / shuffle / previous / play / next / repeat row, then a big thumbnail on top for very large sizes; tapping it opens the player), one picture (⋮ on a picture > Add as widget, or pick one when adding), and a group shown as a list like in the app. Tapping an audio row plays it without opening the app; covers are made in the background and cached. They use the app's colours, have no timers of their own, and reload when you leave the app.
- Picture in picture: the spectrum, progress and captions keep moving (the app counts as out of sight only when it is stopped, not paused).

## Source layout (home screen features)

| File | Role |
| --- | --- |
| `Group.java` | Describes any group (folder, album, playlist, collection...) and resolves its items |
| `HomeAdd.java` | "Add to home screen": shortcut dialog with icon / picture picker, widget pinning |
| `Widgets.java` | Player, Group list and Picture widget providers and list adapter |
| `WidgetCovers.java` | Glyph rendering and the on-disk cover cache used by the list widget |
| `WidgetConfig.java` | Setup screen for the Group and Picture widgets |
| `WidgetPlay.java` | Invisible activity that plays a tapped list row without opening the app |

## v31

- **Volume auto pause** (Settings, off by default): playback pauses when the music volume reaches 0 and resumes when the volume is raised again. It only resumes playback it paused itself, so a pause you made yourself stays paused.
- **Cover art / thumbnail is now written into the file**: Change cover art / Change thumbnail in Advanced Editing embeds the picture in the media file itself (MP3 APIC, FLAC PICTURE, M4A/MP4/M4V `covr`), through the same permission, recovery and media-index path as the tag edits, then asks Android to rescan the file. Any previous front cover is replaced. Multi-select applies it to every selected file. Formats that cannot hold a picture keep it inside Vidsic only (and you are told). Video thumbnails shown by Android's gallery ignore embedded art, so Vidsic also keeps the picture as its own display cover; "Use original" only removes that Vidsic cover.
- **Vibe spectrum**: the visual is back to the original resize behaviour (it restarts cleanly when its size changes instead of stretching the old frame), so it draws exactly as in the first version.

## v33

- **Spectrum / Vibe permission**: the audio spectrum and Vibe need the Microphone permission to read the sound Vidsic itself plays. If they were switched on by a restored backup (or the permission was revoked later) nothing asked for it, so Vibe fell back to a simulated drawing that did not match the music. Vidsic now asks after a restore, when the app opens, and when the player shows the spectrum/Vibe without the permission (once per app run).

## v34

- **Double-tap skip**: seeks now land exactly on the target (`SEEK_CLOSEST`) instead of snapping back to the previous keyframe, which made some skips look like they replayed the same spot.
- **Previous**: always goes to the previous track instead of restarting the current one. With Shuffle on it steps back through the tracks that were actually played.
- **Notification / media controls**: Shuffle and Loop (off / all / one) buttons added next to Previous, Play/Pause and Next.
- **About** shows version 34.0.

## v35

- **Search Specified** (Settings > Library, off by default): when on, searching while a group is open (a folder, author, album, genre, playlist, Favorites, or in Photos an album, collection, Favorites, GIFs or SVG drawings) only looks inside that group, and the search box says "Search in <name>". With no group open, or with the setting off, search covers the whole list as before.
- The two items below were added in this version too.

- **Toggle state is visible in the notification and media controls**: Shuffle and Loop show full strength with a dot underneath when on, and dimmed with no dot when off (Loop one uses the "1" icon). The system media controls (Android 13+) get the same states through custom actions.
- **Override Drawable** (Settings > Drawables, off by default): turns on an **Advance Drawable** button. It opens a list of every `ic_*.xml` in the app's drawable folder with a preview, a **Restore** and a **Change** button per icon. Change asks for an `.xml` file and checks that it is a usable Android vector (`<vector>` with viewport size and `<path>` items with valid `pathData`; `<group>` is allowed, resource colors such as `@color/x` are not). Cancel discards pending changes, Apply saves them, and the bottom Restore puts every icon back to default.
- The list is built by reflection over `R.drawable` (`Ic.list`), and every icon in the app is drawn through `Ic.get` / `Ic.set`, so a new `ic_*.xml` added to the drawable folder appears in the list and can be overridden with no other change. Names starting with `ic_launcher` are left out because the home screen draws them. Icons inside widget layout XML files are not affected.

## v36

- **Notification / media controls**: the dot under Shuffle and Loop is gone. State is shown by brightness only: full strength when on, dimmed when off (Loop one still uses the "1" icon).
- **Advance Drawable > Code**: each icon has a **Code** button that saves its XML (your custom version if you set one, otherwise the built-in one, rebuilt from the app's compiled resources) to a place you choose, so you can edit it and load it back with **Change**.
- **Swipe between tabs**: in Audios, Videos and Photos, swipe left on the list for the next tab and right for the previous one; the tab bar scrolls to keep the selected tab visible.

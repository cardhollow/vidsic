# Vidsic Player

**Vidsic Player** is an Android media player designed for playing and organizing audio files and videos stored on your device.

It provides separate Audio and Video libraries, allowing users to browse their media, create playlists, manage favorites, control playback, and customize their listening and viewing experience.

## Features

### 1. Audio and Video Playback
- Play music and other supported audio files.
- Watch locally stored videos.
- Play video files in audio-only mode.
- Control playback with play, pause, previous, next, and seek controls.
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
- Back up favorites and audio playlists.
- Back up video playlists separately.
- Restore supported backups later.

**Note:** The backup feature stores favorites and playlist information, not copies of the actual media files.

### 8. Customization
- Choose the app's built-in theme.
- Use Android device colors where supported.
- Customize the theme's base appearance and accent color.
- Configure playback behavior and headset-related options.

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

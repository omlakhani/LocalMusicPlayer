# Local Music Player (Android)

## Overview

A fully offline Android music player built using Kotlin.
The app plays audio files stored locally on the device and does not rely on any cloud or streaming service.

This project is designed for personal use and focuses on clean playback, playlist management, and a smooth user experience.

---

## Features

### Core Playback

* Play, pause, next, previous
* Shuffle and repeat modes
* Background playback support
* Notification controls
* Lock screen controls

### Library Management

* Load songs from device storage
* Automatic metadata detection (title, artist)
* Manual metadata editing (optional)

### Playlists

* Create multiple playlists
* Add or remove songs
* Drag and drop to reorder songs
* Persistent storage (saved locally)

### Search

* Search songs by name or artist
* Partial and case-insensitive matching

### UI

* Dark theme
* Full screen player
* Mini player (bottom bar)
* Smooth scrolling song list

### Add Songs

* File picker to select audio files
* Paste YouTube URL to download audio (MP3)
* Auto-save to local storage

---

## Tech Stack

* Language: Kotlin
* IDE: Android Studio
* Media Player: ExoPlayer
* Database: Room (SQLite)
* Storage: Local device storage (Music/MyApp)

---

## Architecture

The app follows a simple layered architecture:

### 1. UI Layer

* Activities / Fragments
* RecyclerView for lists
* Player UI

### 2. Playback Layer

* ExoPlayer instance
* MediaSession for system controls

### 3. Data Layer

* Room database
* Entities:

    * Song
    * Playlist
    * PlaylistSong (mapping)

### 4. Storage Layer

* File picker integration
* File saving to local directory

---

## Storage Structure

All downloaded or added songs are stored in:

Internal Storage / Music / MyApp

---

## Permissions

* Media access (for audio files)
* Foreground service (for background playback)

---

## Future Improvements

* Album art extraction
* Better UI animations
* Equalizer support
* Lyrics integration (offline)

---

## Important Notes

* This app is for personal use only
* YouTube downloading is not officially supported and may break
* No backend or cloud services are used

---

## Getting Started

1. Install Android Studio
2. Clone or create project
3. Build and run on your Android device
4. Add songs and start playing

---

## Goal

Build a simple, fast, and reliable offline music player without unnecessary complexity.

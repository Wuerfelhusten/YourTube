# Changelog

Every release needs a section `## <version>` here (the release workflow fails without one).
The section becomes the GitHub release text and is shown in the app after the update.

## 0.2.3

Fixes.

- SponsorBlock strip, skip and tools buttons sit on the video again on wide screens (unfolded foldables, tablets) instead of below it
- Only the real Google sign-in pages stay in the app; look-alike addresses open in the browser
- Login cookies are no longer included in Android cloud backups or device transfers

## 0.2.1

Fullscreen gestures and more branding options.

- Brightness and volume only react at the outer left and right edges, no longer in the middle or over the seekbar
- New setting: Playback > Brightness and volume by swiping (on by default)
- Gestures pause while a menu is open (YouTube quality / speed menu, comments, YourTube tools)
- Back closes an open YouTube menu or the comments first instead of leaving fullscreen or minimizing the player
- Branding: two more top bar options, "YouTube logo and name" and "YouTube logo and Premium"
- App icon: new "YouTube" variant (red play button on white)

## 0.2.0

Notifications.

- Bell in the top bar with the number of unread YouTube notifications; opens the notification inbox (replies, mentions, channels with the bell on) as a native screen
- Android notifications for new YouTube notifications, checked in the background
- Optional notifications for every new video from your subscriptions (off by default)
- Replies and comment notifications open the comments and jump to the comment (replies are expanded)
- Settings > Notifications: check interval, only on Wi-Fi, check now
- "What is new" after every update and a changelog under Settings > About
- Fixed: the related-videos chips on the watch page floated below the player
- Fixed: opening a notification or shared link while the settings were open
- Fixed: vnd.youtube links from other apps, channel links shared as /%40handle

## 0.1.0

First release.

- YouTube without ads, using Brave's adblock engine and cookie-banner blocking
- Native app shell modelled on the YouTube app: tabs, search, mini player, fullscreen
- Background playback, picture-in-picture and media notification
- SponsorBlock with a mode per category (skip, skip button, mark only, ignore)
- Return YouTube Dislike, DeArrow titles, original audio instead of AI dubbing
- Feed filters: Shorts, posts, mixes, watched videos, minimum length, keywords
- Own branding in the top bar and six app icons to choose from
- Updates itself from GitHub releases

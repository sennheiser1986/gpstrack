# GPS Track — Privacy Policy

_Last updated: 2026-09-09_

GPS Track is an open-source app that records GPS tracks on your device and, only when you
choose, shares your live location with people you approve. This document describes what the app
does with your data. Host it at a public URL and use that URL in the Play Console.

## What is stored on your device

- **Recorded tracks** — timestamped GPS points, and figures derived from them (distance, time,
  speed, elevation gain). Stored in a private on-device database. Deleting a track deletes its
  points. Nothing is uploaded as part of recording.
- **Settings** — your display name, chosen sharing-server URL, the list of people you follow,
  and a randomly generated sharing ID for this install.
- **Offline map** (optional) — a map data file you explicitly download.

## What leaves your device, and only if you turn it on

Location sharing is **off by default**. While you switch **Broadcast my location** on, the app
sends to the sharing server you configured, about every 7 seconds:

- your current latitude/longitude and a timestamp,
- your chosen display name,
- your sharing ID,
- the sharing IDs of people you have chosen to follow (to receive their positions back).

The reference server keeps only your **most recent** position, in memory, and discards it about
two minutes after you stop broadcasting. It is never written to disk on the server. Turning
Broadcast off stops all of this immediately.

If an administrator of that server has issued someone a web login, that person can **request**
to follow your device. The request appears in the app and is shared **only** if you tap Allow.
You can revoke it at any time by tapping Deny/Stop or by turning Broadcast off.

## Permissions

| Permission | Why |
| --- | --- |
| Location (fine/coarse) | Record tracks and, when broadcasting, report your position. |
| Background location | Keep recording/broadcasting while the app is not in the foreground or the screen is off. |
| Foreground service (location / data sync) | Run the recording, sharing, and offline-map-download tasks with an ongoing notification. |
| Camera | Only to scan another install's sharing QR code. |
| Notifications | Show the ongoing recording/sharing notifications and alert you to follow requests. |
| Internet / network state | Load map tiles and reach the sharing server you configure. |

## Third parties

- **Map tiles** are requested from OpenStreetMap's tile servers (or your offline file). Your IP
  address is visible to the tile server as with any web request.
- **The sharing server** is chosen and, in the reference deployment, self-hosted by you or
  whoever you trust. This project does not operate a shared backend.

No advertising, no analytics, no third-party trackers.

## Contact

Raise an issue at <https://github.com/sennheiser1986/gpstrack>.

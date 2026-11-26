# WebBlock

Android app that blocks distracting websites and apps – and ads and trackers in every app.

- Closes the browser tab of a blocked site
- Blocks websites in Chrome, Firefox, Samsung Internet, Edge, Brave, Opera, DuckDuckGo and other browsers
- Whole sites (youtube.com) or specific sections (reddit.com/r/all)
- Add several sites at once
- Checks that a site exists and suggests a fix for typos
- Block list protected by fingerprint or PIN
- Two lists: "Always" (blocked 24/7, protected by Alpha mode) and "On schedule" (follows the schedule, always changeable)
- Block apps too: pick installed apps and put them on either list
- Ad blocker for all apps and browsers (optional, off by default)
- Schedule with times and days of the week
- Alpha mode: locks your settings until a chosen date and time; WebBlock can't be turned off or uninstalled meanwhile, and changing the clock doesn't end it early
- Green dot = site exists, red dot = site not found (can be removed even in Alpha mode)
- Runs in the background, also after a restart
- Two pages with a tab bar (swipe to switch): **Blocker** and **Ad blocker**, each with a big
  status ring and charts of what was blocked

## Statistics

Counted on your phone only, kept for 60 days:

- Blocker page: block screens shown today and per day for the last 7 days (sites and apps)
- Ad blocker page: ads & trackers blocked today, in the last 7 days and in total, the share of
  all lookups that were blocked, a chart per day (7 or 30 days), how long the ad blocker has been
  running, and the most blocked addresses today

## Ad blocker

The ad blocker is a small local VPN that only sees DNS lookups – the step where an app asks
"where is ads.example.com?". Lookups for ad and tracker domains are answered with "nowhere"
(0.0.0.0); everything else is passed on unchanged to your network's normal DNS server. Your
actual traffic never goes through it, and nothing is sent to any other server.

- Filter list: [StevenBlack/hosts](https://github.com/StevenBlack/hosts) (ads + trackers), downloaded
  once a week. Only the domain names are read from it – no code is downloaded.
- Only one VPN can run on Android at a time. If you start another VPN (e.g. NordVPN), Android turns
  the ad blocker off and WebBlock shows a notification. As soon as the other VPN is turned off,
  a second message lets you turn the ad blocker back on with one tap.
- Video ads on YouTube come from the same servers as the videos, so they can't be blocked this way.
- If "Private DNS" in the Android settings is set to a specific provider, lookups bypass the ad blocker.
- Safety net: important sites (Google, YouTube, WhatsApp, GitHub, Android's connection check, …) are never
  blocked, and a downloaded list that would block them is rejected – the old list stays active.
- The numbers on its page never leave the phone.
- Not affected by Alpha mode – it can always be switched on and off.
- Fast: blocked lookups are answered instantly, other answers are cached for as long as the DNS
  server allows, and a second DNS server is asked if the first one doesn't answer within 1 s.

## Privacy

WebBlock has no account, no analytics and no ads. Your lists stay on your phone and are excluded
from backups. The only network requests are DNS lookups to check that a new site exists and the
weekly download of the ad blocker's filter list.

# Loyverse Staff Tools

## Current screens
1. **Token setup (MainActivity)** - first launch only, asks for your
   Loyverse API access token, saved on-device via SharedPreferences.
2. **Home screen (HomeActivity)** - green header + tile grid, matching
   the Loyverse-style look. "Add Stock" is live; "Set Composite" and
   "Add Variant" are placeholders for now.
3. **Add Stock (AddStockActivity)**
   - Search box accepts normal typing AND barcode scanner input, even
     if the box isn't focused (handled via dispatchKeyEvent so Sunmi's
     built-in scanner and iMin's external scanners "just work").
   - Exact barcode matches are prioritized; otherwise it searches by
     item name (partial match).
   - Results list is hidden until there's a search/scan match.
   - Each result shows current stock + a field to enter the quantity
     to ADD. Quantities persist across multiple searches so staff can
     scan several different items before hitting Confirm.
   - "Confirm Changes" batches everything into one API call.
   - Full catalog pagination is handled (not capped at 250 items).
   - Items with "Track stock" OFF in Loyverse are automatically
     skipped (there's nothing valid to update for them).

## How to upload this to your GitHub repo
1. On your repo's GitHub page, click "Add file" -> "Upload files".
2. Open this extracted folder and select ALL items inside it (app,
   build.gradle.kts, settings.gradle.kts, gradle.properties, README.md,
   .github) and drag them in together - not the outer folder itself.
3. Commit the changes.

## How to get your APK
1. Go to the "Actions" tab on your repo.
2. Click "Build APK" -> "Run workflow" (or wait for it to auto-trigger).
3. Wait for the green checkmark (2-5 minutes).
4. Click into the run, scroll to "Artifacts", download "app-debug-apk".
5. Unzip it to get app-debug.apk.

## Installing on a device
1. Transfer app-debug.apk to the device.
2. Enable "Install from unknown sources" if prompted.
3. Tap the APK to install.
4. On first open, paste in your Loyverse API access token.

## Still to come
- Set Composite item screen
- Add Variant (multi-barcode) screen
- True hardware scanner SDK integration for Sunmi/iMin (current
  version relies on the scanner acting as a keyboard, which covers
  most common scanner models but not all)
- Camera-based scanning fallback for phones with no physical scanner

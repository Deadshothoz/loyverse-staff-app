# Loyverse Staff Tools

## What this version does
- On first launch, asks for your Loyverse API access token (saved on-device only, never in the code).
- "Load Items" pulls your items + current stock from Loyverse.
- Type a quantity to ADD next to any item(s) you received.
- "Sync All Changes" sends a single batch update to Loyverse — this is the bulk stock update feature.

## How to upload this to your GitHub repo
1. On your repo's GitHub page, click "Add file" -> "Upload files".
2. Drag this entire folder's contents in (keep the same folder structure -
   most browsers preserve folders when you drag a whole folder in).
3. Commit the changes (the box at the bottom, just click "Commit changes").

## How to get your APK
1. Go to the "Actions" tab on your repo.
2. You should see a "Build APK" workflow run automatically start (or click
   "Run workflow" to trigger it manually).
3. Wait for it to finish (green checkmark, usually 2-5 minutes).
4. Click into the completed run, scroll to "Artifacts", and download
   "app-debug-apk". Unzip it to get app-debug.apk.

## Installing on a device
1. Transfer app-debug.apk to the device (USB, Bluetooth, cloud link, etc).
2. On the device, enable "Install from unknown sources" if prompted.
3. Tap the APK file to install.
4. On first open, paste in your Loyverse API access token (from Back Office
   -> Access Tokens).

## Known limitations of this version (to be improved next)
- Only fetches the first 250 items (no pagination yet).
- Composite item editing and multi-barcode/variant creation are not in this
  version yet - those come in the next update.
- No device-specific (Sunmi/iMin) hardware scanner integration yet - this
  version has no scanning at all, it's list-based only.

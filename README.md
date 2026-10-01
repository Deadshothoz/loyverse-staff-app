# Loyverse Staff Tools

An Android app for shop staff to manage stock in Loyverse without
opening the Back Office. Built for Sunmi / iMin devices with a built-in
barcode scanner, but works on any Android phone or tablet.

## Screens

**Token setup** - shown on first launch only. Paste your Loyverse API
access token (Back Office -> Settings -> Access tokens). It's saved on
the device.

**Home** - six tiles. Pull down anywhere on Home to refresh the
catalog (and pools) from Loyverse.

| Tile | What it does |
| --- | --- |
| Add Stock | Scan or search items, type how many to ADD, confirm. All changes go to Loyverse in one batch. |
| Reduce Stock | Same, but takes stock away. Untracked items are skipped. |
| Check Stock | Read-only list with filters: All items, Low stock, Out of stock, plus a category filter. |
| Stock Count | Type the number you physically counted - it REPLACES the stock in Loyverse. Blank = not counted, 0 = counted none. Matching counts aren't re-sent. |
| Manage Pools | Group items whose stock should be judged together (e.g. several generations of the same product) with one shared minimum. |
| Composite Items | Make an item composite (e.g. a box = 24 bars) and choose its components and quantities. |

### Scanning
Every screen with a search box accepts barcode scanner input even when
the box isn't selected. An exact barcode match wins; otherwise it
searches item names. While a quantity box is selected, keystrokes go to
that box instead, so typing numbers works normally.

### Track stock
If you add stock to (or count) an item that has "Track stock" switched
off in Loyverse, the app switches tracking on for it first, then sets
the stock.

### Check Stock rules
- **All items** shows everything, tracked or not.
- **Low stock** / **Out of stock** only include items (or pools) that
  have a low-stock minimum set. Low stock = stock at or below the
  minimum.
- Items that belong to a pool are hidden and replaced by one row for
  the pool, showing the combined stock against the pool's minimum.

### Pools
Pools live only in a Google Sheet - they never change anything in
Loyverse, and items keep their own minimums in Loyverse.

Setup (once per device): the pools sheet has an Apps Script web app
attached (Extensions -> Apps Script, deployed as a web app, access
"Anyone"). On the Manage Pools screen tap LINK and paste the web app
URL. Renaming the Apps Script project doesn't change the URL; making a
**new deployment** does, so use "Manage deployments -> Edit -> New
version" when updating the script to keep the same link.

An item can only be in one pool, so its stock is never counted twice.

### Composite items
Loyverse handles the stock: once an item is composite, selling it
takes stock from its components automatically.
- Tap **+**, scan/search the item that becomes composite (the box),
  then scan/search each component and type how many go into ONE box.
- The list shows each composite's parts and how many could be made from
  the components' current stock ("Can make").
- Loyverse rules the app follows: items with variants can't be
  composite, a composite item doesn't track its own stock, and a
  composite can't be a component of another composite.
- Add Stock and Stock Count refuse composite items - change the stock of
  the components instead.
- Tap an existing composite to change its components, or TURN OFF
  COMPOSITE to make it a normal item again.

### Keeping in sync
Each screen asks Loyverse only for what changed since the last sync
(sales at the POS, edits in Back Office, deleted items), so refreshing
is fast. Items deleted in Loyverse disappear from the app on the next
refresh. Closing and reopening the app does a full reload.

### Crash reports
If the app ever crashes, the next time it opens Home shows the error
with a COPY button - paste it to whoever is fixing the app.

## Building the APK
The app is built by GitHub Actions - no Android Studio needed.
1. Commit your changes on GitHub. The "Build APK" workflow runs
   automatically (or Actions -> Build APK -> Run workflow).
2. Wait for the green check (2-5 minutes). A red X means the code
   didn't compile - open the run to see the error.
3. Open the run, scroll to **Artifacts**, download `app-debug-apk`.
4. Unzip it to get `app-debug.apk`.

## Installing on a device
1. Copy `app-debug.apk` to the device.
2. Allow "Install from unknown sources" if asked.
3. Tap the APK to install (installing over the old version keeps the
   saved token and pools link).
4. On first open, paste your Loyverse API access token.

## Ideas for later
- Automatic label printing (needs the printer model and whether it
  speaks ESC/POS or TSPL)
- A free-hosted web version for use on a computer
- Camera scanning for phones without a built-in scanner

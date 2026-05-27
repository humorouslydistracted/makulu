# 🦁 Makulu — Changelog & Bug Fixes

## Patch 3 — May 28, 2026 (Input Sanitization)

### Changes
- **Input Sanitization**: All text fields (category name, menu item name, spend item name, receipt fields, footer) now only accept safe characters: letters, digits, spaces, `-./()&@#:+₹%`. Blocks CSV-breaking characters (commas, quotes, newlines, pipes, semicolons).
- **Max Length Limits**: Category 30 chars, Item name 40, Spend name 50, Receipt fields 30/50, Footer 60.

### Files Modified
- `AdminScreen.kt`

---

## Patch 2 — May 28, 2026 (UI Polish + Receipt Fixes + Permissions)

### Issues Fixed

#### Receipts (Issues 1–6)
| # | Issue | Fix |
|---|-------|-----|
| 1 | Footer "Thank you visit again" printing at TOP of slip | Moved footer to bottom, before feed+cut |
| 2 | No spacing at top/bottom of receipts | Added 2 blank lines (CMD_FEED_2) at top and bottom |
| 3 | Kitchen order qty has no alignment, hard to read | Used `formatKitchenLine()` — 2-column aligned format (Item + Qty) |
| 4 | Item font too small in receipts | Kept normal (user confirmed Option A — no change) |
| 5 | Date/time in ISO format instead of Indian format | Changed to `dd-MM-yyyy hh:mm:ss.SS AM/PM` in both receipts |
| 6 | Table number not prominent enough | Table now printed in **bold + double-size** in both receipts |

#### Sidebar (Issues 7–11)
| # | Issue | Fix |
|---|-------|-----|
| 7 | Sidebar not highlighting active page | Added `selected = true` based on `currentRoute` + parsed nav arguments for admin sub-items |
| 8 | Today's Orders: no Print/Update buttons | Added "Order Receipt", "Kitchen Receipt", "Update" buttons in order detail dialog. Update reopens order on original table (PLACED status) |
| 9 | Spending add dialog pre-populates old values | Fields cleared when "Add" button is clicked |
| 10 | Date field is text input instead of calendar | Replaced with Material 3 `DatePickerDialog` — tap to open calendar |
| 11 | Settings section clutters sidebar, Reset PIN exposed | Removed Settings section. Printer moved to top-level. "Forgot PIN?" added to Admin PIN entry screen. Reset PIN removed from sidebar |

#### Admin Page (Issues 12–13)
| # | Issue | Fix |
|---|-------|-----|
| 12 | Duplicate headings in admin sub-pages | Removed the section title text (tabs already indicate section) |
| 13 | Menu items font too small, + button hard to click | Category name → 20sp, Item name → 20sp, replaced `+` IconButton with "ADD" TextButton |

#### Main Page / Order Screen (Issues 14–17)
| # | Issue | Fix |
|---|-------|-----|
| 14 | Category header font too small | Increased to 19sp |
| 15 | Menu item text too small, +/- buttons too close | Item text → 19sp, buttons → 40dp with 16dp count padding |
| 16 | Review order popup: items small, buttons cramped | Item text → 19sp, +/- have round colored backgrounds (CircleShape), better spacing |
| 17 | Qty increase not showing in "Changes" section | Fixed logic to detect increases (shows "Item: 2 → 3" in green) and mixed increase+decrease |

#### CSV Backup (Issues 18–19)
| # | Issue | Fix |
|---|-------|-----|
| 18 | CSV not auto-updating on order complete | Injected `CsvManager` into `OrderViewModel`, calls `csvManager.onOrderCompleted()` after every complete |
| 19 | No permission requests, CSV fails silently | Added `MANAGE_EXTERNAL_STORAGE` + `POST_NOTIFICATIONS` to manifest. Added `PermissionGate` composable at app startup requesting both permissions with skip option |

### Additional Fixes
- **CSV auto-save on spend add/update/delete**: `SpendingViewModel` now calls `csvManager.exportAll()` after every spend operation
- **Ledger date format**: Changed from ISO to Indian format (`dd-MM-yyyy hh:mm:ss.SS AM/PM`)
- **Today's Orders date format**: Same Indian format applied
- **Copy Logs button**: Added at bottom of Printer Log section — copies all logs to clipboard for troubleshooting
- **Review popup button sizing** (follow-up): -/+ reduced to 30dp, count padding 12dp, delete 42dp/22dp

### Files Modified
- `PrinterManager.kt` — Receipt formatting, kitchen alignment, date format, feed lines
- `MainActivity.kt` — Sidebar restructure, highlighting, permission gate, forgot_pin route, today's orders callbacks
- `AdminScreen.kt` — DatePicker, spending dialogs, duplicate headings, font sizes, ADD button, copy logs, TodayOrders buttons, Indian date format
- `OrderScreen.kt` — Font sizes, button sizes/spacing, change tracking (increases)
- `Repository.kt` — `reopenCompletedOrder()`, CSV auto-save injection in ViewModels
- `AndroidManifest.xml` — MANAGE_EXTERNAL_STORAGE, POST_NOTIFICATIONS permissions

---

## Patch 1 — May 27, 2026 (Initial Bug Fixes)

### Issues Fixed
| # | Issue | Fix |
|---|-------|-----|
| 1 | Category delete crashes app | Fixed dialog scope placement, added `deleteCategoryWithItems()` |
| 2 | Admin PIN timeout too aggressive | Changed to 10s grace period after leaving admin pages |
| 3 | Sidebar structure confusing | Restructured: Today's Orders, Spending, Printer, Admin |
| 4 | Print buttons don't do anything | Wired up `printReceipt`/`printKitchenOrder` with snackbar feedback |
| 5 | Printer logs not visible | Added `PrinterLog` data class, `_logs` StateFlow, displayed in Printer section |
| 6 | Analysis page crashes (Vico dependency) | Removed Vico, replaced with simple list-based analysis view |
| 7 | `maxHeight` compilation error | Fixed implicit receiver with `this@BoxWithConstraints` |

### Files Modified
- `Database.kt` — FK CASCADE, `deleteByCategoryId`
- `Repository.kt` — `deleteCategoryWithItems()`, `getOrderWithItems()`
- `AdminScreen.kt` — Category delete fix, Analysis rewrite, CSV page, Printer scrollable
- `MainActivity.kt` — Admin timeout, sidebar, print callbacks, SnackbarHost
- `PrinterManager.kt` — Logging, `printTestPage()`, return Boolean from print methods
- `CsvManager.kt` — `listBackupFiles()`
- `app/build.gradle.kts` — Removed Vico dependency

---

## Architecture

### Tech Stack
- Kotlin 2.0.0 + Jetpack Compose (Material 3)
- Room Database, Hilt DI, MVVM
- Min SDK 31, Target SDK 35
- Bluetooth Classic SPP (ESC/POS) for thermal printing

### Project Structure
```
app/src/main/java/com/makulu/app/
├── Database.kt         — Entities, DAOs, Room DB, Type Converters
├── Repository.kt       — Repositories, ViewModels, Hilt Module
├── MainActivity.kt     — Activity, Biometric Gate, Permission Gate, Setup Flow, Navigation
├── OrderScreen.kt      — Homepage order taking UI (tables, menu, cart, review)
├── AdminScreen.kt      — All admin sections, spending sidebar, today's orders, printer, date picker
├── PrinterManager.kt   — Bluetooth SPP connection, ESC/POS receipt formatting
├── CsvManager.kt       — CSV export, auto-save, dual folder backup
└── Theme.kt            — Colors, typography, theme
```

### Sidebar Structure (Final)
```
📋 Today's Orders
💰 Shop Spending
🖨️ Printer
─────────────────
🔒 Admin (+ sub-items when unlocked)
   ├── Tables
   ├── Menu Items
   ├── Ledger
   ├── Spending
   ├── Analysis
   ├── CSV Backup
   └── Receipt
─────────────────
ℹ️ Makulu v1.0.0
```

### Key Behaviors
- **Biometric/PIN gate** at app startup
- **Permission gate** after auth (storage + notifications)
- **First-time setup**: PIN creation → Printer connection
- **Admin auto-lock**: 10s grace period after leaving admin pages
- **Forgot PIN**: Security question on PIN entry screen
- **CSV auto-save**: Triggered on every order complete and spend add/update/delete
- **Printer disconnect notification**: POST_NOTIFICATIONS permission for critical alerts
- **Input sanitization**: All text fields restricted to safe characters (no CSV-breaking chars)

---

*Last Updated: May 28, 2026*

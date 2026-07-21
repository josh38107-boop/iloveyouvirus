# Coffee POS User Manual

This manual explains how staff should use the coffee shop POS system during daily operations. It is written for non-technical users.

## Roles

### Manager

Managers can use all cashier functions and also manage sensitive parts of the system.

Managers can:
- Sign in with a manager PIN.
- Open and close shifts.
- View reports.
- Export Excel/CSV reports.
- Manage inventory.
- Edit menu/settings.
- Change employee PINs.
- Change the Void / Refund Authorization PIN.
- Configure printers and sync settings.

Managers should use their access carefully because manager actions can affect reports, stock, sync, printers, and staff permissions.

### Cashier

Cashiers handle normal selling work.

Cashiers can:
- Sign in with a cashier PIN.
- Add items to cart.
- Checkout orders.
- Accept Cash, Online, or Split payments.
- Print receipts if the printer is configured.
- View paid orders.
- Void or refund orders only if they know the 4-digit Void / Refund Authorization PIN.
- Use the cash drawer screen if available to them.

Cashiers cannot edit manager-only settings such as inventory setup, reports, menu, device setup, employee records, or authorization PIN settings.

## Daily Shared-Shift Workflow

Use this workflow when multiple tablets share one cash drawer.

1. The manager opens one shift at the start of the day.
2. Cashiers sign in on their own devices.
3. Each device should sync so it sees the active shift and the same orders.
4. Cashiers sell normally from their own devices.
5. Cash sales, online sales, add cash, remove cash, voids, and refunds are included in the cash drawer summary.
6. At the end of the day, the manager counts the physical drawer and closes the shift.

Important rules:
- Do not open a separate shift on every tablet if the shop uses one shared cash drawer.
- Make sure all tablets use the correct date and time.
- Turn on automatic date/time and automatic time zone in Android settings.
- Sync all tablets before closing the shift.
- Only the manager should close the final shift.

If a tablet does not show the same active shift, go to Settings and use Sync Now. If it still does not match, ask the manager before selling.

## Login

### PIN Number Buttons

What the buttons do: Enter the staff PIN.

Who can click it: Managers and cashiers.

What happens after clicking: PIN dots appear. When the PIN matches an employee, the user is signed in.

When to use it: At the start of work, after the POS is locked, or when changing users.

Possible warning/error messages:
- `PIN not found. Try 1 for manager or 2 for cashier.`

Example scenario: A cashier starts a shift, enters their PIN, and the POS opens to the selling screen.

### Backspace

What the button does: Removes the last digit entered.

Who can click it: Managers and cashiers.

What happens after clicking: The last PIN dot disappears.

When to use it: When a staff member enters the wrong digit.

Possible warning/error messages: None.

Example scenario: The cashier accidentally taps `3` instead of `2`, then taps backspace and enters the correct digit.

### C / Clear

What the button does: Clears the whole PIN entry.

Who can click it: Managers and cashiers.

What happens after clicking: All PIN dots are removed.

When to use it: When the PIN was entered incorrectly from the start.

Possible warning/error messages: None.

Example scenario: A manager starts typing the wrong PIN and taps Clear before trying again.

## Dashboard / Main Screen

The main dashboard is the POS screen. It shows the menu, cart, cashier name, shift status, and navigation buttons.

### Navigation Buttons

What the buttons do: Move between POS, Orders, Inventory, Reports, Devices, Settings, Menu, Manager, and Drawer.

Who can click it:
- Cashiers can use normal selling screens.
- Managers can access manager-only screens.

What happens after clicking: The selected screen opens.

When to use it: Use navigation to move from selling to reports, drawer, settings, or orders.

Possible warning/error messages:
- `Manager PIN required for settings access.`
- `Manager PIN required for reports access.`
- `Manager PIN required for inventory access.`
- `Manager PIN required for devices access.`

Example scenario: A cashier taps Orders to find a receipt. A manager taps Reports to review daily sales.

### Lock

What the button does: Signs out the current user and returns to the PIN login screen.

Who can click it: Managers and cashiers.

What happens after clicking: The POS locks.

When to use it: When leaving the counter, changing staff, or taking a break.

Possible warning/error messages: None.

Example scenario: A cashier finishes their shift and taps Lock so the next staff member signs in with their own PIN.

## Open Shift

### Starting Cash Field

What the field does: Records the cash already inside the drawer before selling begins.

Who can use it: Manager or cashier, depending on store procedure. Best practice is manager.

What happens after entering an amount: The amount becomes the starting float for the shift.

When to use it: At the beginning of the business day or after a previous shift was closed.

Possible warning/error messages:
- `Sign in before opening shift.`

Example scenario: The manager counts ₱1,500 in the drawer and enters `1500.00`.

### Open Shift

What the button does: Starts a new cash drawer shift.

Who can click it: Signed-in staff. Best practice is manager only.

What happens after clicking:
- A new shift opens.
- The starting cash is saved.
- Sales can now be checked out.
- Opening inventory snapshots may be recorded.

When to use it: Before taking the first order of the day.

Possible warning/error messages:
- `Sign in before opening shift.`
- `No active shift. Please open a shift before checking out.`

Example scenario: Before opening the shop, the manager enters starting cash and taps Open Shift.

## Join Active Shift

The goal of joining an active shift is for all devices to share the same cash drawer summary.

### Sync to Join the Shared Shift

What the action does: Updates the device so it can see the active shared shift and latest transactions.

Who can click it: Manager in Settings. Cashiers should ask a manager if the device is not synced.

What happens after clicking:
- The device contacts Supabase.
- It downloads active shift, orders, payments, inventory, and settings.
- The sync status changes to `Sync successful` if it works.

When to use it:
- When a cashier starts using a second tablet.
- When the drawer summary is not matching another device.
- Before closing the shift.

Possible warning/error messages:
- `Not configured`
- `Syncing...`
- `Sync successful`
- `Sync failed: ...`

Example scenario: The manager opens the shift on the main tablet. The cashier signs in on Device 1 and the manager taps Sync Now so Device 1 sees the same sales and drawer information.

Important: If a device does not show the same active shift after syncing, do not open another shift without manager approval. Opening another shift can split the drawer totals.

## Close Shift

### Close Shift

What the button does: Opens the close-shift dialog.

Who can click it: Best practice is manager only.

What happens after clicking: The POS asks for the counted cash amount.

When to use it: At the end of the day or when the cash drawer is being settled.

Possible warning/error messages:
- `No active shift to close.`

Example scenario: At closing time, the manager taps Close Shift and counts the cash drawer.

### Cash Counted Field

What the field does: Records the actual physical cash counted in the drawer.

Who can use it: Manager.

What happens after entering cash:
- The POS compares counted cash against expected cash.
- It shows Balanced, Missing Cash, or Extra Cash.

When to use it: During end-of-day cash counting.

Possible warning/error messages:
- `Please enter a valid amount for counted cash.`

Example scenario: Expected cash is ₱5,200 but counted cash is ₱5,150, so the POS shows Missing Cash.

### Confirm Close Shift

What the button does: Finalizes the shift.

Who can click it: Manager.

What happens after clicking:
- The shift is closed.
- Ending cash is saved.
- The screen returns to the drawer/open-shift state.

When to use it: Only after all sales are finished and the cash has been counted.

Possible warning/error messages:
- `Please enter a valid amount for counted cash.`
- `No active shift to close.`

Example scenario: The manager confirms the counted cash and closes the shift for the day.

## Add to Cart

### Menu Item Buttons

What the buttons do: Add selected drinks/food to the cart.

Who can click it: Managers and cashiers.

What happens after clicking:
- If the item has options, the option dialog may open.
- If no options are needed, the item is added to the cart.

When to use it: Whenever a customer orders an item.

Possible warning/error messages:
- Low stock warnings may appear if ingredients are low.

Example scenario: A customer orders one latte. The cashier taps Latte and chooses the size.

### Quantity Buttons

What the buttons do: Increase, decrease, or remove item quantities in the cart.

Who can click it: Managers and cashiers.

What happens after clicking: The cart total updates.

When to use it: When a customer orders multiple items or changes their order.

Possible warning/error messages: None normally.

Example scenario: A customer orders two cookies, so the cashier increases the cookie quantity to 2.

### Hold Order

What the button does: Saves the current cart temporarily.

Who can click it: Managers and cashiers.

What happens after clicking:
- The cart is moved to held orders.
- The current cart clears.

When to use it: When a customer pauses ordering or needs to step aside.

Possible warning/error messages:
- `Cart is empty.`
- `Order held.`

Example scenario: A customer forgets their wallet. The cashier holds the order and serves the next customer.

### Resume Held Order

What the button does: Brings a held cart back.

Who can click it: Managers and cashiers.

What happens after clicking: The held items return to the active cart.

When to use it: When the customer is ready to continue or pay.

Possible warning/error messages:
- `Held order resumed.`

Example scenario: The customer returns with payment, and the cashier resumes the held order.

### Cancel Order

What the button does: Clears the current cart.

Who can click it: Managers and cashiers.

What happens after clicking: Items in the cart are removed.

When to use it: When the customer cancels before payment.

Possible warning/error messages:
- `Cart is empty.`
- `Order canceled.`

Example scenario: A customer changes their mind before paying, so the cashier cancels the cart.

## Checkout

### Checkout

What the button does: Opens the payment screen for the current cart.

Who can click it: Managers and cashiers.

What happens after clicking:
- The POS shows totals and payment options.
- The cashier chooses Cash, Online, or Split.

When to use it: When the customer is ready to pay.

Possible warning/error messages:
- `Sign in before checkout.`
- `No active shift. Please open a shift before checking out.`
- `Cart is empty.`

Example scenario: The cashier finishes entering the customer’s items and taps Checkout.

### Confirm Checkout / Pay

What the button does: Completes the order payment.

Who can click it: Managers and cashiers.

What happens after clicking:
- The order is saved as paid.
- Inventory is deducted.
- Receipt text is created.
- Receipt may print automatically if enabled.

When to use it: After confirming the customer’s payment method and amount.

Possible warning/error messages:
- Payment amount errors.
- Printer errors if auto-print is enabled and the printer is not ready.

Example scenario: A customer pays online. The cashier selects Online and confirms checkout.

## Cash Payment

### Cash Payment Method

What the button does: Marks the order as paid by cash.

Who can click it: Managers and cashiers.

What happens after clicking:
- The cash amount field is used.
- The POS calculates change if the amount tendered is higher than the total.
- Cash sales are added to the drawer summary.

When to use it: When the customer pays with physical cash.

Possible warning/error messages:
- Invalid or insufficient cash amount.

Example scenario: Total is ₱180. Customer gives ₱200. The POS records ₱200 tendered and ₱20 change.

## Online Payment

### Online Payment Method

What the button does: Marks the order as paid online.

Who can click it: Managers and cashiers.

What happens after clicking:
- The order is saved as paid by Online.
- The amount is included in Online Payment Today.
- It does not increase physical cash in drawer.

When to use it: When the customer pays through an online payment method.

Possible warning/error messages:
- Payment confirmation should be checked manually before completing the order.

Example scenario: Customer shows a successful online payment receipt. The cashier selects Online and confirms checkout.

### Split Payment

What the button does: Splits payment between Cash and Online.

Who can click it: Managers and cashiers.

What happens after clicking:
- Cash amount is added to the physical drawer summary.
- Online amount is added to online payments.
- The combined amounts must cover the total.

When to use it: When a customer pays part cash and part online.

Possible warning/error messages:
- `Enter valid positive amounts for both Cash and Online.`

Example scenario: Total is ₱500. Customer pays ₱300 cash and ₱200 online.

## Print Receipt

### Print Receipt

What the button does: Prints one receipt for the paid order.

Who can click it: Managers and cashiers.

What happens after clicking:
- The POS sends the receipt to the saved printer.
- Status updates when printing succeeds or fails.

When to use it: When the customer wants a receipt or the shop keeps a printed copy.

Possible warning/error messages:
- `Receipt printing is turned off in Devices.`
- Bluetooth permission warnings.
- Printer connection messages.

Example scenario: Customer asks for a receipt after paying cash. Cashier taps Print Receipt.

### Print 2 Receipts

What the button does: Prints two copies.

Who can click it: Managers and cashiers.

What happens after clicking: Two receipt print jobs are sent.

When to use it: When one copy is for the customer and one copy is for shop records.

Possible warning/error messages:
- `Receipt printing is turned off in Devices.`
- Printer disconnected or permission messages.

Example scenario: A large order needs a customer copy and a kitchen/store copy.

## Void Order

### Void

What the button does: Cancels a paid order and restocks ingredients where applicable.

Who can click it: Managers and cashiers with the 4-digit Void / Refund Authorization PIN.

What happens after clicking:
- The authorization dialog opens.
- Staff must enter a reason and the 4-digit PIN.
- If correct, the order status becomes void.

When to use it:
- Wrong order was paid.
- Order was entered by mistake.
- Payment should be fully canceled.

Possible warning/error messages:
- `Incorrect PIN. Please try again.`
- `Please enter a reason to void order.`

Example scenario: Cashier accidentally checks out an Americano instead of a Latte. The cashier taps Void, enters the reason, and gets authorization.

## Refund Order

### Refund

What the button does: Marks a paid order as refunded.

Who can click it: Managers and cashiers with the 4-digit Void / Refund Authorization PIN.

What happens after clicking:
- The authorization dialog opens.
- Staff must enter a reason and the 4-digit PIN.
- If correct, the order status becomes refunded.

When to use it:
- Customer returned an item.
- Customer was charged incorrectly.
- Payment needs to be returned after the sale.

Possible warning/error messages:
- `Incorrect PIN. Please try again.`
- `Please enter a reason to refund order.`

Example scenario: Customer was charged twice. Cashier taps Refund, enters the reason, and gets manager authorization PIN.

## Cash Drawer

### Cash Drawer Summary

What it does: Shows drawer money and payment totals.

Who can view it: Managers and cashiers, depending on store procedure.

What happens on screen:
- Starting Cash
- Cash Sales Today
- Cash Added
- Cash Removed
- Online Payment Today
- Should Be in Drawer
- Total Cash + Online Payment

When to use it: During shift monitoring and before closing shift.

Possible warning/error messages: None normally.

Example scenario: Manager checks if expected cash matches the drawer before closing.

Important: `Should Be in Drawer` is physical cash only. `Total Cash + Online Payment` includes physical cash plus online payments.

## Add Cash

### Add Cash

What the button does: Records extra physical cash added to the drawer.

Who can click it: Manager or trusted cashier, depending on store policy.

What happens after clicking:
- A dialog asks for amount and optional reason.
- The amount increases Cash Added and Should Be in Drawer.

When to use it:
- Adding change to the drawer.
- Adding small bills or coins for operations.

Possible warning/error messages:
- `No active shift.`
- `Please enter a valid positive amount.`

Example scenario: Manager adds ₱500 in coins and small bills for change.

## Remove Cash

### Remove Cash

What the button does: Records physical cash taken out of the drawer.

Who can click it: Manager or trusted cashier, depending on store policy.

What happens after clicking:
- A dialog asks for amount and optional reason.
- The amount increases Cash Removed and reduces Should Be in Drawer.

When to use it:
- Cash pickup.
- Payout.
- Deposit removal.

Possible warning/error messages:
- `No active shift.`
- `Please enter a valid positive amount.`

Example scenario: Manager removes ₱2,000 for safe deposit and enters the reason.

## Sales Report

### Reports Screen

What it does: Shows sales, orders, payment breakdown, inventory usage, and cash drawer report.

Who can click it: Manager.

What happens after clicking: The reports screen opens.

When to use it:
- End-of-day review.
- Checking sales performance.
- Reviewing Cash vs Online totals.

Possible warning/error messages:
- `Manager PIN required for reports access.`

Example scenario: Manager checks today’s total sales and payment breakdown before closing.

The Payment Breakdown keeps each transaction's recorded payment-method name, such as BPI, GCash, or Online.

### Date Range Buttons

What the buttons do: Change report period, such as Today, Month, All, or Custom.

Who can click it: Manager.

What happens after clicking: Report numbers refresh for the selected date range.

When to use it: When checking daily, monthly, or custom period performance.

Possible warning/error messages: None normally.

Example scenario: Manager selects Month to compare sales for the last 30 days.

### Print Sales

What the button does: Prints a sales report.

Who can click it: Manager.

What happens after clicking: Report is sent to the configured printer.

When to use it: End of shift or end of day.

Possible warning/error messages:
- Printer permission or connection messages.
- Printing may fail if printer is not configured.

Example scenario: Manager prints the day’s sales summary for records.

## Excel/CSV Export

### Export Excel/CSV

What the button does: Saves report data to a file that can be opened in spreadsheet software.

Who can click it: Manager.

What happens after clicking:
- The report file is created.
- A success toast appears.

When to use it:
- Accounting.
- Monthly summaries.
- Sending reports to the owner.

Possible warning/error messages:
- File save or permission issues.

Example scenario: Manager exports the monthly sales report and sends it to accounting.

### Inventory Export

What the button does: Exports inventory data.

Who can click it: Manager.

What happens after clicking: An inventory report file is saved.

When to use it: Stock count, purchasing, or auditing.

Possible warning/error messages:
- File save or permission issues.

Example scenario: Manager exports inventory before ordering supplies.

## Inventory

### Inventory Screen

What it does: Shows ingredient stock and low-stock information.

Who can click it: Manager.

What happens after clicking: Inventory list opens.

When to use it:
- Checking stock.
- Adjusting quantities.
- Restocking ingredients.

Possible warning/error messages:
- `Manager PIN required for inventory access.`

Example scenario: Manager checks if milk is low before ordering more.

### Add / Edit Ingredient

What the action does: Creates or updates ingredient details.

Who can click it: Manager.

What happens after clicking: Ingredient form opens or saves.

When to use it: When adding new ingredients or fixing stock settings.

Possible warning/error messages:
- `Enter an ingredient name.`
- `Enter a unit (e.g. oz, ea, ml).`

Example scenario: Manager adds “Oat Milk” as a new ingredient.

### Adjust / Restock

What the action does: Changes stock quantity.

Who can click it: Manager.

What happens after clicking:
- Quantity on hand updates.
- Sync may run to share inventory with other devices.

When to use it:
- After deliveries.
- After stock count.
- When correcting wrong stock.

Possible warning/error messages: Invalid quantity or sync warning messages.

Example scenario: A delivery adds 10 liters of milk, so manager restocks milk quantity.

## Sync

### Save & Sync

What the button does: Saves Supabase URL, key, and device name, then syncs.

Who can click it: Manager.

What happens after clicking:
- Cloud settings are saved.
- Sync starts.
- Status updates.

When to use it: During setup or when changing device names/cloud credentials.

Possible warning/error messages:
- `Not configured`
- `Sync failed: ...`

Example scenario: Manager names a tablet “Counter 1” and taps Save & Sync.

### Sync Now

What the button does: Manually starts cloud sync.

Who can click it: Manager.

What happens after clicking:
- The device uploads and downloads latest data.
- Last sync time updates if successful.

When to use it:
- Before closing shift.
- When multiple devices do not match.
- After changing settings, inventory, or PINs.

Possible warning/error messages:
- `Syncing...`
- `Sync successful`
- `Sync failed: ...`

Example scenario: Manager taps Sync Now after changing the Void / Refund PIN so other devices receive it.

## Printer Settings

### Save Printer

What the button does: Saves the selected printer profile.

Who can click it: Manager.

What happens after clicking: Printer name, model, interface, paper size, and toggles are saved.

When to use it: After choosing or changing a printer.

Possible warning/error messages:
- Bluetooth permission messages.
- Printer connection messages.

Example scenario: Manager selects a Bluetooth receipt printer and taps Save.

### Search

What the button does: Searches for nearby Bluetooth printers.

Who can click it: Manager.

What happens after clicking:
- Nearby paired/found printers appear.
- Permission prompts may appear.

When to use it: When connecting a printer for the first time.

Possible warning/error messages:
- `Bluetooth permission is needed to find nearby printers.`
- `Bluetooth scan permission is needed to find nearby printers.`

Example scenario: Manager pairs a new printer, opens Devices, and taps Search.

### Printer Device Row

What the row does: Selects a printer from the list.

Who can click it: Manager.

What happens after clicking: The printer is selected for the profile.

When to use it: After Search finds the correct printer.

Possible warning/error messages: Connection or permission messages.

Example scenario: Manager taps `POS-58` from the printer list.

### Print Test

What the button does: Sends a test receipt to the saved printer.

Who can click it: Manager.

What happens after clicking:
- Printer receives a test print.
- Status message confirms success or failure.

When to use it: After setup, before live selling, or after printer problems.

Possible warning/error messages:
- Printer disconnected.
- Permission required.
- Paper or power issue.

Example scenario: Manager prints a test before opening the shop.

### Delete Printer

What the button does: Removes the saved printer profile.

Who can click it: Manager.

What happens after clicking: Printer settings reset.

When to use it: When replacing a printer or fixing bad printer settings.

Possible warning/error messages:
- `Printer profile deleted.`

Example scenario: Old printer is replaced, so manager deletes the old profile and sets up the new one.

### Print Receipts Toggle

What the toggle does: Turns receipt printing on or off.

Who can click it: Manager.

What happens after clicking: Receipt printing is enabled or disabled.

When to use it: Turn off if printer is broken or receipts are not needed.

Possible warning/error messages:
- `Receipt printing is turned off in Devices.`

Example scenario: Printer is out of paper, so manager turns off receipt printing temporarily.

### Automatically Print Receipt Toggle

What the toggle does: Prints receipt automatically after checkout.

Who can click it: Manager.

What happens after clicking: Future paid orders auto-print if receipt printing is enabled.

When to use it: Use when every customer should receive a receipt.

Possible warning/error messages: Printer errors if the printer is not ready.

Example scenario: Shop wants every sale printed automatically.

### Open Cash Drawer Toggle

What the toggle does: Sends a drawer-open command through the printer when supported.

Who can click it: Manager.

What happens after clicking: Drawer kick behavior is enabled or disabled.

When to use it: Use only if the cash drawer is connected to a compatible printer.

Possible warning/error messages: Drawer may not open if hardware does not support it.

Example scenario: Manager enables drawer kick so the drawer opens after cash payments.

## Device Settings

### Device Name

What the field does: Labels the tablet for sync and troubleshooting.

Who can edit it: Manager.

What happens after editing: The name is saved when Save & Sync is clicked.

When to use it: During setup or when replacing tablets.

Possible warning/error messages: Sync errors if cloud settings are wrong.

Example scenario: Manager names tablets “Manager Tablet” and “Counter 1”.

### Supabase URL and Key

What the fields do: Connect the POS to cloud sync.

Who can edit it: Manager.

What happens after editing: The device can sync when credentials are correct.

When to use it: Initial setup or cloud troubleshooting.

Possible warning/error messages:
- `Not configured`
- `Sync failed: ...`

Example scenario: Manager enters Supabase URL/key and taps Save & Sync.

### Void / Refund Authorization PIN

What the field does: Sets the 4-digit PIN required for voids and refunds.

Who can edit it: Manager.

What happens after clicking Save PIN:
- The PIN is saved locally.
- It can sync to other tablets if Supabase has the `void_refund_pin` column.

When to use it: When the manager wants to change authorization access.

Possible warning/error messages:
- `PIN must be exactly 4 digits.`
- `Manager access required to change PIN.`
- Sync may fail if Supabase schema is missing `void_refund_pin`.

Example scenario: Manager changes the authorization PIN from `1234` to `9876`. Cashiers must now enter `9876` to void or refund.

## Common Problems and What to Do

### The POS says No active shift

Meaning: The device does not currently have an active shift.

What to do:
- If starting the day, manager opens a shift.
- If another device already opened a shared shift, sync first.
- Do not open a second shift unless manager confirms.

### Sync failed

Meaning: The device could not upload or download cloud data.

What to do:
- Check Wi-Fi.
- Check Supabase settings.
- Try Sync Now again.
- If the error mentions `void_refund_pin`, run the schema repair SQL in Supabase.

### Printer is not printing

Meaning: Printer is off, disconnected, not configured, or permission is missing.

What to do:
- Check printer power and paper.
- Check Bluetooth pairing.
- Go to Devices and tap Search.
- Select the printer, Save, then Print Test.

### Cash drawer total does not match

Meaning: Physical cash and POS expected cash are different.

What to do:
- Check unpaid orders.
- Check cash payments.
- Check Add Cash and Remove Cash entries.
- Check voids/refunds.
- Sync all devices.
- Count the drawer again.

### Tablet times do not match

Meaning: One device may have wrong system time or timezone.

What to do:
- Open Android Settings.
- Turn on automatic date/time.
- Turn on automatic timezone.
- Make sure all tablets show the same local time.

## End-of-Day Checklist

1. Finish all customer orders.
2. Sync all devices.
3. Check Orders for any wrong payments.
4. Process needed voids/refunds with authorization PIN.
5. Open Cash Drawer screen.
6. Count physical cash.
7. Compare Should Be in Drawer to counted cash.
8. Close Shift.
9. Print Sales if needed.
10. Export Excel/CSV if needed.
11. Lock the POS.

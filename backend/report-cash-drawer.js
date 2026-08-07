function toCents(value) {
  return parseInt(value || 0);
}

function computeCashDrawer({ shifts = [], closedShiftAdjustments = [], fallbackClosedShiftVoidsRefunds = 0, cashSales = 0, onlinePayments = 0 } = {}) {
  const closedShiftAdjustmentByShift = new Map(
    closedShiftAdjustments.map(row => [
      `${row.current_shift_device_id}:${row.current_shift_id}`,
      toCents(row.amount_cents)
    ])
  );
  let unassignedClosedShiftVoidsRefunds = Math.max(toCents(fallbackClosedShiftVoidsRefunds), 0);
  const cashDrawer = shifts.reduce((totals, shift) => {
    const starting = toCents(shift.starting_cash_cents);
    const added = toCents(shift.cash_added_cents);
    const removed = toCents(shift.cash_removed_cents);
    const assignedClosedShiftVoidsRefunds = Math.min(
      removed,
      closedShiftAdjustmentByShift.get(`${shift.device_id}:${shift.id}`) || 0
    );
    const unassignedForShift = Math.min(
      Math.max(removed - assignedClosedShiftVoidsRefunds, 0),
      unassignedClosedShiftVoidsRefunds
    );
    unassignedClosedShiftVoidsRefunds -= unassignedForShift;
    const closedShiftVoidsRefunds = assignedClosedShiftVoidsRefunds + unassignedForShift;
    const manualRemoved = Math.max(removed - closedShiftVoidsRefunds, 0);
    const shiftCashSales = toCents(shift.cash_sales);
    const hasCashSales = shiftCashSales > 0;
    if (!hasCashSales) {
      totals.latestNoCashStarting = starting;
      return totals;
    }
    totals.hasCashSales = true;
    const displayedStarting = starting + added - manualRemoved;
    const expected = displayedStarting + shiftCashSales - closedShiftVoidsRefunds;
    if (starting > 0 || hasCashSales) totals.hasActivity = true;
    totals.startingCash += displayedStarting;
    totals.expectedCashEnding += expected;
    totals.actualCashEnding += expected;
    totals.cashAdded += added;
    totals.cashRemoved += manualRemoved;
    totals.closedShiftVoidsRefunds += closedShiftVoidsRefunds;
    return totals;
  }, { hasActivity: false, hasCashSales: false, latestNoCashStarting: 0, startingCash: 0, expectedCashEnding: 0, actualCashEnding: 0, cashAdded: 0, cashRemoved: 0, closedShiftVoidsRefunds: 0 });

  if (!cashDrawer.hasCashSales && cashDrawer.latestNoCashStarting > 0) {
    cashDrawer.hasActivity = true;
    cashDrawer.startingCash = cashDrawer.latestNoCashStarting;
    cashDrawer.expectedCashEnding = cashDrawer.latestNoCashStarting;
    cashDrawer.actualCashEnding = cashDrawer.latestNoCashStarting;
  }

  cashDrawer.onlinePayments = onlinePayments;
  cashDrawer.totalCashAndOnline = cashDrawer.expectedCashEnding + onlinePayments;
  cashDrawer.difference = 0;
  cashDrawer.cashSales = cashSales;
  if (!cashDrawer.hasActivity) {
    cashDrawer.startingCash = 0;
    cashDrawer.expectedCashEnding = 0;
    cashDrawer.actualCashEnding = 0;
    cashDrawer.cashAdded = 0;
    cashDrawer.cashRemoved = 0;
    cashDrawer.closedShiftVoidsRefunds = 0;
    cashDrawer.onlinePayments = 0;
    cashDrawer.totalCashAndOnline = 0;
    cashDrawer.cashSales = 0;
  }
  delete cashDrawer.hasCashSales;
  delete cashDrawer.latestNoCashStarting;
  return cashDrawer;
}

module.exports = { computeCashDrawer };

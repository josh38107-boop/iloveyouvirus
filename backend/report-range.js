const MANILA_TIME_ZONE = 'Asia/Manila';
const DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES = 120;

function normalizeCutoffMinutes(value) {
  const minutes = Number(value);
  return Number.isInteger(minutes) && minutes >= 0 && minutes <= 1439
    ? minutes
    : DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES;
}

function manilaDateLabel(date) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: MANILA_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(date).reduce((acc, part) => {
    acc[part.type] = part.value;
    return acc;
  }, {});
  return `${parts.year}-${parts.month}-${parts.day}`;
}

function windowForBusinessDate(dateLabel, cutoffMinutes) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(dateLabel || ''))) return null;
  const cutoff = normalizeCutoffMinutes(cutoffMinutes);
  const hours = String(Math.floor(cutoff / 60)).padStart(2, '0');
  const minutes = String(cutoff % 60).padStart(2, '0');
  const start = new Date(`${dateLabel}T${hours}:${minutes}:00+08:00`);
  if (Number.isNaN(start.getTime())) return null;
  return { businessDate: dateLabel, startMs: start.getTime(), endMs: start.getTime() + 24 * 60 * 60 * 1000 };
}

function currentBusinessDayWindow(cutoffMinutes, nowMs = Date.now()) {
  const today = manilaDateLabel(new Date(nowMs));
  let window = windowForBusinessDate(today, cutoffMinutes);
  if (nowMs < window.startMs) {
    window = windowForBusinessDate(manilaDateLabel(new Date(window.startMs - 24 * 60 * 60 * 1000)), cutoffMinutes);
  }
  return window;
}

function reportWindowForRange({ daysParam, fromDate, toDate, cutoffMinutes, nowMs = Date.now() }) {
  const cutoff = normalizeCutoffMinutes(cutoffMinutes);
  if (fromDate && toDate) {
    const from = windowForBusinessDate(fromDate, cutoff);
    const to = windowForBusinessDate(toDate, cutoff);
    if (from && to && from.startMs <= to.startMs) {
      return {
        days: null,
        fromMs: from.startMs,
        toMs: to.endMs,
        businessDate: from.businessDate,
        businessEndDate: to.businessDate,
        cutoffMinutes: cutoff,
        timezone: MANILA_TIME_ZONE
      };
    }
  }

  const parsed = parseInt(daysParam, 10);
  const days = Math.min(Math.max(Number.isFinite(parsed) ? parsed : 1, 1), 365);
  const current = currentBusinessDayWindow(cutoff, nowMs);
  return {
    days,
    fromMs: current.startMs - (days - 1) * 24 * 60 * 60 * 1000,
    toMs: nowMs,
    businessDate: current.businessDate,
    businessEndDate: current.businessDate,
    businessDayStartMs: current.startMs,
    businessDayEndMs: current.endMs,
    cutoffMinutes: cutoff,
    timezone: MANILA_TIME_ZONE
  };
}

module.exports = {
  MANILA_TIME_ZONE,
  DEFAULT_BUSINESS_DAY_CUTOFF_MINUTES,
  normalizeCutoffMinutes,
  manilaDateLabel,
  windowForBusinessDate,
  currentBusinessDayWindow,
  reportWindowForRange
};

(function (global) {
  'use strict';

  const encoder = new TextEncoder();
  const crcTable = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let bit = 0; bit < 8; bit++) c = (c & 1) ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    crcTable[n] = c >>> 0;
  }

  function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  }

  function makeZip(files, modifiedAt) {
    const localParts = [], centralParts = [];
    const dosTime = (modifiedAt.getHours() << 11) | (modifiedAt.getMinutes() << 5) | (modifiedAt.getSeconds() >> 1);
    const dosDate = ((Math.max(modifiedAt.getFullYear(), 1980) - 1980) << 9) |
      ((modifiedAt.getMonth() + 1) << 5) | modifiedAt.getDate();
    let offset = 0;

    for (const file of files) {
      const name = encoder.encode(file.name);
      const data = typeof file.data === 'string' ? encoder.encode(file.data) : file.data;
      const crc = crc32(data);
      const local = new Uint8Array(30 + name.length), lv = new DataView(local.buffer);
      lv.setUint32(0, 0x04034b50, true); lv.setUint16(4, 20, true); lv.setUint16(6, 0x0800, true);
      lv.setUint16(10, dosTime, true); lv.setUint16(12, dosDate, true); lv.setUint32(14, crc, true);
      lv.setUint32(18, data.length, true); lv.setUint32(22, data.length, true); lv.setUint16(26, name.length, true);
      local.set(name, 30); localParts.push(local, data);

      const central = new Uint8Array(46 + name.length), cv = new DataView(central.buffer);
      cv.setUint32(0, 0x02014b50, true); cv.setUint16(4, 20, true); cv.setUint16(6, 20, true);
      cv.setUint16(8, 0x0800, true); cv.setUint16(12, dosTime, true); cv.setUint16(14, dosDate, true);
      cv.setUint32(16, crc, true); cv.setUint32(20, data.length, true); cv.setUint32(24, data.length, true);
      cv.setUint16(28, name.length, true); cv.setUint32(42, offset, true); central.set(name, 46);
      centralParts.push(central); offset += local.length + data.length;
    }

    const centralSize = centralParts.reduce((sum, part) => sum + part.length, 0);
    const end = new Uint8Array(22), ev = new DataView(end.buffer);
    ev.setUint32(0, 0x06054b50, true); ev.setUint16(8, files.length, true); ev.setUint16(10, files.length, true);
    ev.setUint32(12, centralSize, true); ev.setUint32(16, offset, true);
    return new Blob([...localParts, ...centralParts, end], {
      type: 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet'
    });
  }

  function escapeXml(value) {
    return String(value ?? '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '')
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
  }

  const textCell = (ref, value, style) =>
    `<c r="${ref}" s="${style}" t="inlineStr"><is><t>${escapeXml(value)}</t></is></c>`;
  const numberCell = (ref, value, style) => {
    const number = Number(value);
    return `<c r="${ref}" s="${style}"><v>${Number.isFinite(number) ? number : 0}</v></c>`;
  };
  const row = (number, cells) => `<row r="${number}">${cells.join('')}</row>`;
  const money = cents => Number(cents || 0) / 100;
  function formatReportDate(date) {
    return new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Manila',
      month: 'short',
      day: 'numeric',
      year: 'numeric'
    }).format(date);
  }
  function dateFromInput(value) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(String(value || ''))) return null;
    return new Date(`${value}T00:00:00+08:00`);
  }
  function reportDateRangeLabel(days, generatedAt, customRange) {
    const reportWindow = customRange?.reportWindow;
    if (reportWindow?.fromMs && reportWindow?.toMs) {
      const start = formatReportDate(new Date(Number(reportWindow.fromMs)));
      const end = formatReportDate(new Date(Number(reportWindow.toMs)));
      const prefix = Number(reportWindow.days) === 1 ? 'Business day' : 'Business dates';
      return start === end ? `${prefix} ${start}` : `${prefix} ${start} - ${end}`;
    }
    const customStart = dateFromInput(customRange?.fromDate);
    const customEnd = dateFromInput(customRange?.toDate);
    if (customStart && customEnd) {
      const start = formatReportDate(customStart);
      const end = formatReportDate(customEnd);
      return start === end ? start : `${start} - ${end}`;
    }
    const end = new Date(generatedAt);
    const start = new Date(end);
    start.setDate(start.getDate() - Math.max(Number(days) || 1, 1) + 1);
    const startText = formatReportDate(start);
    const endText = formatReportDate(end);
    return startText === endText ? startText : `${startText} - ${endText}`;
  }
  function formatDateTime(value) {
    const timestamp = Number(value);
    if (!Number.isFinite(timestamp) || timestamp <= 0) return '';
    const date = new Date(timestamp);
    const parts = new Intl.DateTimeFormat('en-US', {
      timeZone: 'Asia/Manila',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      hour12: false
    }).formatToParts(date).reduce((map, part) => {
      map[part.type] = part.value;
      return map;
    }, {});
    return `${parts.month}/${parts.day}/${parts.year} ${parts.hour}:${parts.minute}`;
  }

  function makeWorksheet(stats, days, generatedAt, customRange) {
    const rows = [], merges = [];
    const addSection = (rowNumber, title) => {
      rows.push(row(rowNumber, [textCell(`A${rowNumber}`, title, 2)]));
      merges.push(`A${rowNumber}:E${rowNumber}`);
    };
    const rangeContext = { ...(customRange || {}), reportWindow: stats?.reportWindow || customRange?.reportWindow };
    const dateLabel = reportDateRangeLabel(days, generatedAt, rangeContext);
    const dateText = `${generatedAt.getMonth() + 1}/${generatedAt.getDate()}/${generatedAt.getFullYear()} ` +
      `${String(generatedAt.getHours()).padStart(2, '0')}:${String(generatedAt.getMinutes()).padStart(2, '0')}`;

    rows.push(row(1, [textCell('A1', 'Kanlungan Coffee Garage POS - Daily Report', 1)]));
    rows.push(row(2, [textCell('A2', 'Clean export layout - Black & White only', 6)]));
    merges.push('A1:E1', 'A2:E2');
    addSection(4, 'REPORT DETAILS');
    rows.push(row(5, [textCell('A5', 'Report Field', 3), textCell('B5', 'Value', 3)]));
    rows.push(row(6, [textCell('A6', 'Report Date Range', 4), textCell('B6', dateLabel, 4)]));
    rows.push(row(7, [textCell('A7', 'Generated At', 4), textCell('B7', dateText, 4)]));
    addSection(9, 'SUMMARY METRICS');
    rows.push(row(10, [textCell('A10', 'Metric', 3), textCell('B10', 'Value', 3)]));
    rows.push(row(11, [textCell('A11', 'Total Orders', 4), numberCell('B11', stats.ordersToday, 7)]));
    rows.push(row(12, [textCell('A12', 'Gross Sales', 4), numberCell('B12', money(stats.grossSales), 5)]));
    rows.push(row(13, [textCell('A13', 'Net Sales', 4), numberCell('B13', money(stats.netSales ?? stats.revenueToday), 5)]));

    let currentRow = 15;
    addSection(currentRow, 'PAYMENT BREAKDOWN');
    currentRow++;
    rows.push(row(currentRow, [textCell(`A${currentRow}`, 'Payment Method', 3), textCell(`B${currentRow}`, 'Total Amount', 3)]));
    currentRow++;
    const payments = stats.paymentBreakdown?.length ? stats.paymentBreakdown : [{ method: 'No transactions', total: 0 }];
    for (const payment of payments) {
      rows.push(row(currentRow, [textCell(`A${currentRow}`, payment.method, 4), numberCell(`B${currentRow}`, money(payment.total), 5)]));
      currentRow++;
    }

    currentRow++;
    addSection(currentRow, 'ORDER SUMMARY');
    currentRow++;
    rows.push(row(currentRow, [textCell(`A${currentRow}`, 'Date/Time', 3), textCell(`B${currentRow}`, 'Cashier', 3), textCell(`C${currentRow}`, 'Payment Method', 3), textCell(`D${currentRow}`, 'Items', 3), textCell(`E${currentRow}`, 'Total', 3)]));
    currentRow++;
    const orderSummary = stats.orderSummary?.length ? stats.orderSummary : [{ id: 'No paid orders', created_at: '', employee_name: '-', customer_name: '-', payment_method: 'Unavailable', items: '-', total_cents: 0 }];
    for (const orderItem of orderSummary) {
      rows.push(row(currentRow, [textCell(`A${currentRow}`, formatDateTime(orderItem.created_at), 4), textCell(`B${currentRow}`, orderItem.employee_name || '-', 4), textCell(`C${currentRow}`, orderItem.payment_method || 'Unavailable', 4), textCell(`D${currentRow}`, orderItem.items || '-', 4), numberCell(`E${currentRow}`, money(orderItem.total_cents), 5)]));
      currentRow++;
    }

    const drawer = stats.cashDrawer || {};
    currentRow++;
    addSection(currentRow, 'CASH DRAWER SUMMARY');
    currentRow++;
    rows.push(row(currentRow, [textCell(`A${currentRow}`, 'Metric', 3), textCell(`B${currentRow}`, 'Value', 3)]));
    currentRow++;
    const drawerRows = [
      ['Starting Cash', drawer.startingCash], ['Expected Cash Ending', drawer.expectedCashEnding],
      ['Online Payments', drawer.onlinePayments], ['Total Cash + Online Payment', drawer.totalCashAndOnline],
      ['Actual Cash Ending', drawer.actualCashEnding], ['Difference', drawer.difference],
      ['Cash Sales', drawer.cashSales], ['Cash Added', drawer.cashAdded], ['Cash Removed', drawer.cashRemoved]
    ];
    for (const [label, cents] of drawerRows) {
      rows.push(row(currentRow, [textCell(`A${currentRow}`, label, 4), numberCell(`B${currentRow}`, money(cents), 5)]));
      currentRow++;
    }
    currentRow++;
    rows.push(row(currentRow, [textCell(`A${currentRow}`, 'End of Daily Report', 6)]));
    merges.push(`A${currentRow}:E${currentRow}`);

    return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><dimension ref="A1:E${currentRow}"/><sheetViews><sheetView workbookViewId="0"/></sheetViews><sheetFormatPr defaultRowHeight="15"/><cols><col min="1" max="1" width="20" customWidth="1"/><col min="2" max="2" width="18" customWidth="1"/><col min="3" max="3" width="18" customWidth="1"/><col min="4" max="4" width="45" customWidth="1"/><col min="5" max="5" width="14" customWidth="1"/></cols><sheetData>${rows.join('')}</sheetData><mergeCells count="${merges.length}">${merges.map(ref => `<mergeCell ref="${ref}"/>`).join('')}</mergeCells></worksheet>`;
  }

  const styles = `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00"/><numFmt numFmtId="165" formatCode="#,##0"/></numFmts><fonts count="3"><font><sz val="11"/><color theme="1"/><name val="Calibri"/><family val="2"/></font><font><b/><sz val="12"/><color rgb="FF000000"/><name val="Calibri"/><family val="2"/></font><font><i/><sz val="10"/><color rgb="FF333333"/><name val="Calibri"/><family val="2"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="2"><border><left/><right/><top/><bottom/><diagonal/></border><border><left style="thin"><color rgb="FFD9D9D9"/></left><right style="thin"><color rgb="FFD9D9D9"/></right><top style="thin"><color rgb="FFD9D9D9"/></top><bottom style="thin"><color rgb="FFD9D9D9"/></bottom><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="8"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="left" vertical="center"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="1" xfId="0" applyFont="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1"/><xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf><xf numFmtId="0" fontId="2" fillId="0" borderId="0" xfId="0" applyFont="1" applyAlignment="1"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" vertical="center"/></xf></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>`;

  function build(stats, days, generatedAt = new Date(), customRange = null) {
    const iso = generatedAt.toISOString();
    return makeZip([
      { name: '[Content_Types].xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>` },
      { name: '_rels/.rels', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>` },
      { name: 'docProps/app.xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties"><Application>Kanlungan Coffee Garage POS</Application></Properties>` },
      { name: 'docProps/core.xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:creator>Kanlungan Coffee Garage POS</dc:creator><dcterms:created xsi:type="dcterms:W3CDTF">${iso}</dcterms:created><dcterms:modified xsi:type="dcterms:W3CDTF">${iso}</dcterms:modified></cp:coreProperties>` },
      { name: 'xl/workbook.xml', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Daily Report" sheetId="1" r:id="rId1"/></sheets></workbook>` },
      { name: 'xl/_rels/workbook.xml.rels', data: `<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>` },
      { name: 'xl/styles.xml', data: styles }, { name: 'xl/worksheets/sheet1.xml', data: makeWorksheet(stats, days, generatedAt, customRange) }
    ], generatedAt);
  }

  function download(stats, days, customRange = null) {
    const now = new Date(), link = document.createElement('a');
    const date = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}`;
    const rangeContext = { ...(customRange || {}), reportWindow: stats?.reportWindow || customRange?.reportWindow };
    link.href = URL.createObjectURL(build(stats, days, now, rangeContext));
    link.download = `POS_Report_${Number(days) === 1 ? 'Today' : `Last_${days}_Days`}_${date}.xlsx`;
    document.body.appendChild(link); link.click(); link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 1000);
  }

  global.ReportWorkbook = { build, download };
})(typeof window !== 'undefined' ? window : globalThis);

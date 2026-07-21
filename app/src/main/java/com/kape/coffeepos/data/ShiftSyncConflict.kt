package com.kape.coffeepos.data

/**
 * Merges a downloaded shift with the local row without allowing an older
 * remote payload to erase cumulative cash movements made locally.
 *
 * Shift identity and lifecycle fields come from the remote payload. Cash
 * movement totals are monotonic in the local data model, so the greatest
 * value is retained for each total.
 */
fun mergeRemoteShiftTotals(local: Shift?, remote: Shift): Shift {
    if (local == null) return remote

    return remote.copy(
        id = local.id,
        cashAddedCents = maxOf(local.cashAddedCents, remote.cashAddedCents),
        cashRemovedCents = maxOf(local.cashRemovedCents, remote.cashRemovedCents)
    )
}

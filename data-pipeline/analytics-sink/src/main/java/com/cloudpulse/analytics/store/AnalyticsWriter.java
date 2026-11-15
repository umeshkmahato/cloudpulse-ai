package com.cloudpulse.analytics.store;

import com.cloudpulse.analytics.store.Rows.InventoryRow;
import com.cloudpulse.analytics.store.Rows.MetricRow;
import com.cloudpulse.analytics.store.Rows.OrderRow;

/** Storage port. Each method returns true if a new row was written, false if the eventId already existed. */
public interface AnalyticsWriter {
    boolean write(OrderRow row);

    boolean write(InventoryRow row);

    boolean write(MetricRow row);
}

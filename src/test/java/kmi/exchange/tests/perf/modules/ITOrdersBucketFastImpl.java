package kmi.exchange.tests.perf.modules;

import kmi.exchange.core.orderbook.IOrdersBucket;
import kmi.exchange.core.orderbook.OrdersBucketFastImpl;

public class ITOrdersBucketFastImpl extends ITOrdersBucketBase {

    @Override
    protected IOrdersBucket createNewOrdersBucket() {
        return new OrdersBucketFastImpl();
    }
}

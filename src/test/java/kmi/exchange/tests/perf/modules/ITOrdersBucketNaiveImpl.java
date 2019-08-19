package kmi.exchange.tests.perf.modules;

import kmi.exchange.core.orderbook.IOrdersBucket;
import kmi.exchange.core.orderbook.OrdersBucketNaiveImpl;

public class ITOrdersBucketNaiveImpl extends ITOrdersBucketBase {

    @Override
    protected IOrdersBucket createNewOrdersBucket() {
        return new OrdersBucketNaiveImpl();
    }
}

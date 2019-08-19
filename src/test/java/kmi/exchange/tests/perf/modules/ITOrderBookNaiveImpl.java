package kmi.exchange.tests.perf.modules;

import kmi.exchange.tests.util.TestConstants;
import kmi.exchange.core.orderbook.IOrderBook;
import kmi.exchange.core.orderbook.OrderBookNaiveImpl;

import static kmi.exchange.tests.util.TestConstants.SYMBOLSPEC_EUR_USD;

public class ITOrderBookNaiveImpl extends ITOrderBookBase {

    @Override
    protected IOrderBook createNewOrderBook() {
        return new OrderBookNaiveImpl(TestConstants.SYMBOLSPEC_EUR_USD);
    }
}

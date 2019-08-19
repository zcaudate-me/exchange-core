package kmi.exchange.tests.perf.modules;

import kmi.exchange.tests.util.TestConstants;
import kmi.exchange.core.orderbook.IOrderBook;
import kmi.exchange.core.orderbook.OrderBookFastImpl;

import static kmi.exchange.tests.util.TestConstants.SYMBOLSPEC_EUR_USD;

public class ITOrderBookFastImpl extends ITOrderBookBase {

    @Override
    protected IOrderBook createNewOrderBook() {
        return new OrderBookFastImpl(OrderBookFastImpl.DEFAULT_HOT_WIDTH, TestConstants.SYMBOLSPEC_EUR_USD);
    }
}

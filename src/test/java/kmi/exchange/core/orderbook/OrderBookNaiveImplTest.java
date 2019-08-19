package kmi.exchange.core.orderbook;

import kmi.exchange.tests.util.TestConstants;

import static kmi.exchange.tests.util.TestConstants.SYMBOLSPEC_ETH_XBT;

public class OrderBookNaiveImplTest extends OrderBookBaseTest {

    @Override
    protected IOrderBook createNewOrderBook() {
        return new OrderBookNaiveImpl(TestConstants.SYMBOLSPEC_ETH_XBT);
    }
}
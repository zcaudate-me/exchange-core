package kmi.exchange.core.orderbook;

import kmi.exchange.beans.CoreSymbolSpecification;
import kmi.exchange.beans.L2MarketData;
import kmi.exchange.beans.Order;
import kmi.exchange.beans.StateHash;
import kmi.exchange.beans.cmd.CommandResultCode;
import kmi.exchange.beans.cmd.OrderCommand;
import kmi.exchange.beans.cmd.OrderCommandType;
import kmi.exchange.core.Utils;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import kmi.exchange.beans.*;
import kmi.exchange.beans.cmd.CommandResultCode;
import kmi.exchange.beans.cmd.OrderCommand;
import kmi.exchange.beans.cmd.OrderCommandType;
import kmi.exchange.core.Utils;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

public interface IOrderBook extends WriteBytesMarshallable, StateHash {

    /**
     * Process new order.
     * Depending on price specified (whether the order is marketable),
     * order will be matched to existing opposite GTC orders from the order book.
     * In case of remaining volume (order was not matched completely):
     * IOC - reject it as partially filled.
     * GTC - place as a new limit order into th order book.
     *
     * @param cmd - order to match/place
     * @return command code (success, or rejection reason)
     */
    CommandResultCode newOrder(OrderCommand cmd);

    /**
     * Cancel order
     * <p>
     * orderId - order Id
     *
     * @return false if order was not found, otherwise always true
     */
    boolean cancelOrder(OrderCommand cmd);

    /**
     * Move an order
     * <p>
     * orderId  - order Id
     * newPrice - new price (if 0 or same - order will not moved)
     * newSize  - new size (if higher than current size or 0 - order will not downsized)
     *
     * @return false if order was not found, otherwise always true
     */
    CommandResultCode moveOrder(OrderCommand cmd);


    int getOrdersNum();

    Order getOrderById(long orderId);

    // testing only - validateInternalState without changing state
    void validateInternalState();

    /**
     * @return actual implementation
     */
    OrderBookImplType getImplementationType();


    /**
     * Search for all orders for specified user.<br/>
     * Slow, because order book do not maintain uid->order index.<br/>
     * Produces garbage.<br/>
     * Orders must be processed before doing any other mutable call.<br/>
     *
     * @param uid user id
     * @return list of orders
     */
    List<Order> findUserOrders(long uid);

    CoreSymbolSpecification getSymbolSpec();

    Stream<Order> askOrdersStream(boolean sorted);

    Stream<Order> bidOrdersStream(boolean sorted);

    /**
     * State hash for order books is implementation-agnostic
     * Look {@link kmi.exchange.core.orderbook.IOrderBook#validateInternalState} for complete state validation for de-serialized objects
     */
    @Override
    default int stateHash() {
        return hashCode();
    }

    static int hash(final IOrdersBucket[] askBuckets, final IOrdersBucket[] bidBuckets, final CoreSymbolSpecification symbolSpec) {
        final int a = Arrays.hashCode(askBuckets);
        final int b = Arrays.hashCode(bidBuckets);
        return Objects.hash(a, b, symbolSpec.stateHash());
    }

    static boolean equals(IOrderBook me, Object o) {
        if (o == me) return true;
        if (o == null) return false;
        if (!(o instanceof IOrderBook)) return false;
        IOrderBook other = (IOrderBook) o;
        return Utils.checkStreamsEqual(me.askOrdersStream(true), other.askOrdersStream(true)) &&
                Utils.checkStreamsEqual(me.bidOrdersStream(true), other.bidOrdersStream(true));
    }

    /**
     * @param size max size for each part (ask, bid)
     * @return
     */

    /**
     * Obtain current L2 Market Data snapshot
     *
     * @param size max size for each part (ask, bid), if negative - all records returned
     * @return L2 Market Data snapshot
     */
    default L2MarketData getL2MarketDataSnapshot(int size) {
        int asksSize = getTotalAskBuckets();
        int bidsSize = getTotalBidBuckets();
        if (size >= 0) {
            // limit size
            asksSize = Math.min(asksSize, size);
            bidsSize = Math.min(bidsSize, size);
        }
        L2MarketData data = new L2MarketData(asksSize, bidsSize);
        fillAsks(asksSize, data);
        fillBids(bidsSize, data);
        return data;
    }

    /**
     * Request to publish L2 market data into outgoing disruptor message
     *
     * @param data - pre-allocated object from ring buffer
     */
    default void publishL2MarketDataSnapshot(L2MarketData data) {
        int size = L2MarketData.L2_SIZE;
        fillAsks(size, data);
        fillBids(size, data);
    }

    void fillAsks(final int size, L2MarketData data);

    void fillBids(final int size, L2MarketData data);

    int getTotalAskBuckets();

    int getTotalBidBuckets();


    static CommandResultCode processCommand(final IOrderBook orderBook, final OrderCommand cmd) {

        final OrderCommandType commandType = cmd.command;

        if (commandType == OrderCommandType.MOVE_ORDER) {

            return orderBook.moveOrder(cmd);

        } else if (commandType == OrderCommandType.CANCEL_ORDER) {

            boolean isCancelled = orderBook.cancelOrder(cmd);
            return isCancelled ? CommandResultCode.SUCCESS : CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;

        } else if (commandType == OrderCommandType.PLACE_ORDER) {

            return (cmd.resultCode == CommandResultCode.VALID_FOR_MATCHING_ENGINE)
                    ? orderBook.newOrder(cmd)
                    : cmd.resultCode; // no change

        } else if (commandType == OrderCommandType.ORDER_BOOK_REQUEST) {

            //log.debug("ORDER_BOOK_REQUEST {}", cmd.size);
            cmd.marketData = orderBook.getL2MarketDataSnapshot((int) cmd.size);
            return CommandResultCode.SUCCESS;

        } else {
            //log.warn("unsupported command {}", cmd.command);
            return CommandResultCode.MATCHING_UNSUPPORTED_COMMAND;
        }

    }

    static IOrderBook create(BytesIn bytes) {
        switch (OrderBookImplType.of(bytes.readByte())) {
            case NAIVE:
                return new OrderBookNaiveImpl(bytes);
            case FAST:
                return new OrderBookFastImpl(bytes);
            default:
                throw new IllegalArgumentException();
        }
    }

    @Getter
    enum OrderBookImplType {
        NAIVE(0),
        FAST(1);

        private byte code;

        OrderBookImplType(int code) {
            this.code = (byte) code;
        }

        public static OrderBookImplType of(byte code) {
            switch (code) {
                case 0:
                    return NAIVE;
                case 1:
                    return FAST;
                default:
                    throw new IllegalArgumentException("unknown OrderBookImplType:" + code);
            }
        }
    }


}

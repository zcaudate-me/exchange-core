package kmi.exchange.core.orderbook;

import kmi.exchange.beans.*;
import kmi.exchange.beans.cmd.OrderCommand;
import kmi.exchange.core.Utils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.NativeBytes;
import net.openhft.chronicle.wire.Wire;
import kmi.exchange.beans.*;
import kmi.exchange.beans.cmd.OrderCommand;
import kmi.exchange.core.Utils;
import kmi.exchange.beans.*;

import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
public final class OrderBookEventsHelper {

    //private OrderCommand currentCmd;

    public static void sendTradeEvent(OrderCommand cmd, IOrder activeOrder, Order matchingOrder, boolean fm, boolean fma, long price, long v) {

//        log.debug("** sendTradeEvent: active id:{} matched id:{}", activeOrder.orderId, matchingOrder.orderId);
//        log.debug("** sendTradeEvent: price:{} v:{}", price, v);

        final MatcherTradeEvent event = newMatcherEvent();

        event.eventType = MatcherEventType.TRADE;

        event.activeOrderId = activeOrder.getOrderId();
        event.activeOrderUid = activeOrder.getUid();
        event.activeOrderCompleted = fma;
        event.activeOrderAction = activeOrder.getAction();
//        event.activeOrderSeq = activeOrder.seq;

        event.matchedOrderId = matchingOrder.orderId;
        event.matchedOrderUid = matchingOrder.uid;
        event.matchedOrderCompleted = fm;

        event.price = price;
        event.size = v;
        event.timestamp = activeOrder.getTimestamp();
        event.symbol = cmd.symbol;

        // set order reserved price for correct released EBids
        event.bidderHoldPrice = activeOrder.getAction() == OrderAction.BID ? activeOrder.getReserveBidPrice() : matchingOrder.reserveBidPrice;

        event.nextEvent = cmd.matcherEvent;
        cmd.matcherEvent = event;

//        log.debug(" currentCmd.matcherEvent={}", currentCmd.matcherEvent);
    }

    public static void sendCancelEvent(OrderCommand cmd, Order order) {
//        log.debug("Cancel ");
        final MatcherTradeEvent event = newMatcherEvent();
        event.eventType = MatcherEventType.CANCEL;
        event.activeOrderId = order.orderId;
        event.activeOrderUid = order.uid;
        event.activeOrderCompleted = false;
        event.activeOrderAction = order.action;
//        event.activeOrderSeq = order.seq;
        event.matchedOrderId = 0;
        event.matchedOrderCompleted = false;
        event.price = order.price;
        event.size = order.size - order.filled;
        event.timestamp = cmd.timestamp;
        event.symbol = cmd.symbol;

        event.bidderHoldPrice = order.reserveBidPrice; // set order reserved price for correct released EBids

        event.nextEvent = cmd.matcherEvent;
        cmd.matcherEvent = event;
    }


    public static void attachRejectEvent(OrderCommand cmd, long rejectedSize) {

//        log.debug("Rejected {}", cmd.orderId);
//        log.debug("\n{}", getL2MarketDataSnapshot(10).dumpOrderBook());

        final MatcherTradeEvent event = newMatcherEvent();

        event.eventType = MatcherEventType.REJECTION;

        event.activeOrderId = cmd.orderId;
        event.activeOrderUid = cmd.uid;
        event.activeOrderCompleted = false;
        event.activeOrderAction = cmd.action;
//        event.activeOrderSeq = cmd.seq;

        event.matchedOrderId = 0;
        event.matchedOrderCompleted = false;

        event.price = cmd.price;
        event.size = rejectedSize;
        event.timestamp = cmd.timestamp;
        event.symbol = cmd.symbol;

        event.bidderHoldPrice = cmd.reserveBidPrice; // set command reserved price for correct released EBids

        // insert event
        event.nextEvent = cmd.matcherEvent;
        cmd.matcherEvent = event;
    }

    public static MatcherTradeEvent createBinaryEventsChain(final long timestamp,
                                                            final int section,
                                                            final NativeBytes<Void> bytes) {

        long[] dataArray = Utils.bytesToLongArray(bytes, 7);

        MatcherTradeEvent firstEvent = null;
        MatcherTradeEvent lastEvent = null;
        for (int i = 0; i < dataArray.length; i += 7) {

            final MatcherTradeEvent event = newMatcherEvent();

            event.eventType = MatcherEventType.BINARY_EVENT;

            event.symbol = section;
            event.activeOrderId = dataArray[i];
            event.activeOrderUid = dataArray[i + 1];
            event.matchedOrderId = dataArray[i + 2];
            event.matchedOrderUid = dataArray[i + 3];
            event.price = dataArray[i + 4];
            event.size = dataArray[i + 5];
            event.bidderHoldPrice = dataArray[i + 6];

            event.timestamp = timestamp;
            event.nextEvent = null;

//            log.debug("BIN EVENT: {}", event);

            // attach in direct order
            if (firstEvent == null) {
                firstEvent = event;
            } else {
                lastEvent.nextEvent = event;
            }
            lastEvent = event;
        }

        return firstEvent;
    }


    public static NavigableMap<Long, Wire> deserializeEvents(final MatcherTradeEvent evtHead) {
        final Map<Long, List<MatcherTradeEvent>> sections = MatcherTradeEvent.asList(evtHead).stream()
                .collect(Collectors.groupingBy(evt -> evt.symbol, Collectors.toList()));

        NavigableMap<Long, Wire> result = new TreeMap<>();

        sections.forEach((section, events) -> {
            final long[] dataArray = events.stream()
                    .flatMap(evt -> Stream.of(
                            evt.activeOrderId,
                            evt.activeOrderUid,
                            evt.matchedOrderId,
                            evt.matchedOrderUid,
                            evt.price,
                            evt.size,
                            evt.bidderHoldPrice))
                    .mapToLong(s -> s)
                    .toArray();

            final Wire wire = Utils.longsToWire(dataArray);

            result.put(section, wire);
        });


        return result;
    }


    private static MatcherTradeEvent newMatcherEvent() {
        return new MatcherTradeEvent();
    }

}

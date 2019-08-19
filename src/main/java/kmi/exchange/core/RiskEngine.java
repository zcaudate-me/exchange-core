package kmi.exchange.core;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesMarshallable;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.IntLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.IntObjectHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import kmi.exchange.beans.*;
import kmi.exchange.beans.api.binary.BatchAddAccountsCommand;
import kmi.exchange.beans.api.binary.BatchAddSymbolsCommand;
import kmi.exchange.beans.api.reports.*;
import kmi.exchange.beans.cmd.CommandResultCode;
import kmi.exchange.beans.cmd.OrderCommand;
import kmi.exchange.beans.cmd.OrderCommandType;
import kmi.exchange.core.journalling.ISerializationProcessor;

import java.util.Objects;
import java.util.Optional;

import static kmi.exchange.beans.MatcherEventType.*;
import static kmi.exchange.beans.cmd.OrderCommandType.*;

/**
 * Stateful risk engine
 */
@Slf4j
public final class RiskEngine implements WriteBytesMarshallable, StateHash {

    // state
    private final SymbolSpecificationProvider symbolSpecificationProvider;
    private final UserProfileService userProfileService;
    private final BinaryCommandsProcessor binaryCommandsProcessor;
    private final LongObjectHashMap<LastPriceCacheRecord> lastPriceCache;
    private final LongLongHashMap fees;

    // configuration
    private final int shardId;
    private final long shardMask;

    private final ISerializationProcessor serializationProcessor;

    public RiskEngine(final int shardId, final long numShards, final ISerializationProcessor serializationProcessor, final Long loadStateId) {
        if (Long.bitCount(numShards) != 1) {
            throw new IllegalArgumentException("Invalid number of shards " + numShards + " - must be power of 2");
        }
        this.shardId = shardId;
        this.shardMask = numShards - 1;
        this.serializationProcessor = serializationProcessor;

        if (loadStateId == null) {
            this.symbolSpecificationProvider = new SymbolSpecificationProvider();
            this.userProfileService = new UserProfileService();
            this.binaryCommandsProcessor = new BinaryCommandsProcessor(this::handleBinaryMessage, shardId);
            this.lastPriceCache = new LongObjectHashMap<>();
            this.fees = new LongLongHashMap();

        } else {
            // TODO change to creator (simpler init)
            final State state = serializationProcessor.loadData(
                    loadStateId,
                    ISerializationProcessor.SerializedModuleType.RISK_ENGINE,
                    shardId,
                    bytesIn -> {
                        if (shardId != bytesIn.readInt()) {
                            throw new IllegalStateException("wrong shardId");
                        }
                        if (shardMask != bytesIn.readLong()) {
                            throw new IllegalStateException("wrong shardMask");
                        }
                        final SymbolSpecificationProvider symbolSpecificationProvider = new SymbolSpecificationProvider(bytesIn);
                        final UserProfileService userProfileService = new UserProfileService(bytesIn);
                        final BinaryCommandsProcessor binaryCommandsProcessor = new BinaryCommandsProcessor(this::handleBinaryMessage, bytesIn, shardId);
                        final LongObjectHashMap<LastPriceCacheRecord> lastPriceCache = Utils.readLongHashMap(bytesIn, LastPriceCacheRecord::new);
                        final LongLongHashMap fees = Utils.readLongLongHashMap(bytesIn);
                        return new State(symbolSpecificationProvider, userProfileService, binaryCommandsProcessor, lastPriceCache, fees);
                    });

            this.symbolSpecificationProvider = state.symbolSpecificationProvider;
            this.userProfileService = state.userProfileService;
            this.binaryCommandsProcessor = state.binaryCommandsProcessor;
            this.lastPriceCache = state.lastPriceCache;
            this.fees = state.fees;
        }
    }

    @ToString
    public static class LastPriceCacheRecord implements BytesMarshallable, StateHash {
        public long askPrice = Long.MAX_VALUE;
        public long bidPrice = 0L;

        public LastPriceCacheRecord() {
        }

        public LastPriceCacheRecord(long askPrice, long bidPrice) {
            this.askPrice = askPrice;
            this.bidPrice = bidPrice;
        }

        public LastPriceCacheRecord(BytesIn bytes) {
            this.askPrice = bytes.readLong();
            this.bidPrice = bytes.readLong();
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(askPrice);
            bytes.writeLong(bidPrice);
        }

        public LastPriceCacheRecord averagingRecord() {
            LastPriceCacheRecord average = new LastPriceCacheRecord();
            average.askPrice = (this.askPrice + this.bidPrice) >> 1;
            average.bidPrice = average.askPrice;
            return average;
        }

        public static LastPriceCacheRecord dummy = new LastPriceCacheRecord(42, 42);

        @Override
        public int stateHash() {
            return Objects.hash(askPrice, bidPrice);
        }
    }


    /**
     * Pre-process command handler
     * 1. MOVE/CANCEL commands ignored, for specific uid marked as valid for matching engine
     * 2. PLACE ORDER checked with risk ending for specific uid
     * 3. ADD USER, BALANCE_ADJUSTMENT processed for specific uid, not valid for matching engine
     * 4. BINARY_DATA commands processed for ANY uid and marked as valid for matching engine TODO which handler marks?
     * 5. RESET commands processed for any uid
     *
     * @param cmd - command
     */
    public boolean preProcessCommand(final OrderCommand cmd) {

        final OrderCommandType command = cmd.command;

        if (command == MOVE_ORDER || command == CANCEL_ORDER || command == ORDER_BOOK_REQUEST) {
            return false;

        } else if (command == PLACE_ORDER) {
            if (uidForThisHandler(cmd.uid)) {
                cmd.resultCode = placeOrderRiskCheck(cmd);
            }
        } else if (command == ADD_USER) {
            if (uidForThisHandler(cmd.uid)) {
                cmd.resultCode = userProfileService.addEmptyUserProfile(cmd.uid) ? CommandResultCode.SUCCESS : CommandResultCode.USER_MGMT_USER_ALREADY_EXISTS;
            }
        } else if (command == BALANCE_ADJUSTMENT) {
            if (uidForThisHandler(cmd.uid)) {
                cmd.resultCode = userProfileService.balanceAdjustment(cmd.uid, cmd.symbol, cmd.price, cmd.orderId);
            }
        } else if (command == BINARY_DATA) {
            binaryCommandsProcessor.acceptBinaryFrame(cmd);
            if (shardId == 0) {
                cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            }

        } else if (command == RESET) {
            reset();
            if (shardId == 0) {
                cmd.resultCode = CommandResultCode.SUCCESS;
            }
        } else if (command == NOP) {
            if (shardId == 0) {
                cmd.resultCode = CommandResultCode.SUCCESS;
            }
        } else if (command == PERSIST_STATE_MATCHING) {
            if (shardId == 0) {
                cmd.resultCode = CommandResultCode.VALID_FOR_MATCHING_ENGINE;
            }
            return true; // true = publish sequence before finishing processing whole batch

        } else if (command == PERSIST_STATE_RISK) {
            final boolean isSuccess = serializationProcessor.storeData(cmd.orderId, ISerializationProcessor.SerializedModuleType.RISK_ENGINE, shardId, this);
            Utils.setResultVolatile(cmd, isSuccess, CommandResultCode.SUCCESS, CommandResultCode.STATE_PERSIST_RISK_ENGINE_FAILED);
        }

        return false;
    }

    private Optional<? extends WriteBytesMarshallable> handleBinaryMessage(Object message) {

        if (message instanceof BatchAddSymbolsCommand) {
            // TODO return status object
            final LongObjectHashMap<CoreSymbolSpecification> symbols = ((BatchAddSymbolsCommand) message).getSymbols();
            symbols.forEach(symbolSpecificationProvider::addSymbol);
            return Optional.empty();
        } else if (message instanceof BatchAddAccountsCommand) {
            // TODO return status object
            ((BatchAddAccountsCommand) message).getUsers().forEachKeyValue((u, a) -> {
                if (userProfileService.addEmptyUserProfile(u)) {
                    a.forEachKeyValue((cur, bal) -> userProfileService.balanceAdjustment(u, cur, bal, 1_000_000_000 + cur));
                } else {
                    log.debug("User already exist: {}", u);
                }
            });
            return Optional.empty();
        } else if (message instanceof ReportQuery) {
            return processReport((ReportQuery) message);
        } else {
            return Optional.empty();
        }
    }


    // TODO use with common module accepting implementations?
    private Optional<? extends WriteBytesMarshallable> processReport(ReportQuery reportQuery) {

        switch (reportQuery.getReportType()) {

            case STATE_HASH:
                return reportStateHash();

            case SINGLE_USER_REPORT:
                return reportSingleUser((SingleUserReportQuery) reportQuery);

            /*case TOTAL_CURRENCY_BALANCE:
                return reportGlobalBalance();*/

            default:
                throw new IllegalStateException("Report not implemented");
        }
    }

    private Optional<StateHashReportResult> reportStateHash() {
        return Optional.of(new StateHashReportResult(stateHash()));
    }

    private Optional<SingleUserReportResult> reportSingleUser(final SingleUserReportQuery query) {
        if (uidForThisHandler(query.getUid())) {
            UserProfile userProfile = userProfileService.getUserProfile(query.getUid());
            return Optional.of(new SingleUserReportResult(
                    userProfile,
                    null,
                    userProfile == null ? SingleUserReportResult.ExecutionStatus.USER_NOT_FOUND : SingleUserReportResult.ExecutionStatus.OK));
        } else {
            return Optional.empty();
        }
    }
/*
    private Optional<TotalCurrencyBalanceReportResult> reportGlobalBalance() {

        // prepare fast price cache for profit estimation with some price (exact value is not important, except ask==bid condition)
        final LongObjectHashMap<LastPriceCacheRecord> dummyLastPriceCache = new LongObjectHashMap<>();
        lastPriceCache.forEachKeyValue((s, r) -> dummyLastPriceCache.put(s, r.averagingRecord()));

        final LongLongHashMap currencyBalance = new LongLongHashMap();

        final LongLongHashMap symbolOpenInterestLong = new LongLongHashMap();
        final LongLongHashMap symbolOpenInterestShort = new LongLongHashMap();

        userProfileService.getUserProfiles().forEach(userProfile -> {
            userProfile.accounts.forEachKeyValue(currencyBalance::addToValue);
            userProfile.portfolio.forEachKeyValue((symbolId, portfolioRecord) -> {
                final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbolId);
                final LastPriceCacheRecord avgPrice = dummyLastPriceCache.getIfAbsentPut(symbolId, LastPriceCacheRecord.dummy);
                currencyBalance.addToValue(portfolioRecord.currency, portfolioRecord.estimateProfit(spec, avgPrice));

                if (portfolioRecord.position == PortfolioPosition.LONG) {
                    symbolOpenInterestLong.addToValue(symbolId, portfolioRecord.openVolume);
                } else if (portfolioRecord.position == PortfolioPosition.SHORT) {
                    symbolOpenInterestShort.addToValue(symbolId, portfolioRecord.openVolume);
                }
            });
        });

        return Optional.of(new TotalCurrencyBalanceReportResult(currencyBalance, new LongLongHashMap(fees), null, symbolOpenInterestLong, symbolOpenInterestShort));
    }
*/
    /**
     * Post process command
     *
     * @param cmd
     */
    public boolean handlerRiskRelease(final OrderCommand cmd) {
        handlerRiskRelease(cmd.symbol, cmd.marketData, cmd.matcherEvent);
        return false;
    }

    private boolean uidForThisHandler(final long uid) {
        return (shardMask == 0) || ((uid & shardMask) == shardId);
    }

    private CommandResultCode placeOrderRiskCheck(final OrderCommand cmd) {

        final UserProfile userProfile = userProfileService.getUserProfile(cmd.uid);
        if (userProfile == null) {
            cmd.resultCode = CommandResultCode.AUTH_INVALID_USER;
            log.warn("User profile {} not found", cmd.uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(cmd.symbol);
        if (spec == null) {
            log.warn("Symbol {} not found", cmd.symbol);
            return CommandResultCode.INVALID_SYMBOL;
        }

        // check if account has enough funds
        if (!placeOrder(cmd, userProfile, spec)) {
            log.warn("{} NSF uid={}: Can not place {}", cmd.orderId, userProfile.uid, cmd);
            log.warn("{} accounts:{}", cmd.orderId, userProfile.accounts);
            return CommandResultCode.RISK_NSF;
        }

        userProfile.commandsCounter++; // TODO should also set for MOVE
        return CommandResultCode.VALID_FOR_MATCHING_ENGINE;
    }


    private boolean placeOrder(final OrderCommand cmd,
                               final UserProfile userProfile,
                               final CoreSymbolSpecification spec) {


        if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {

            return placeExchangeOrder(cmd, userProfile, spec);

        } else if (spec.type == SymbolType.FUTURES_CONTRACT) {

            final SymbolPortfolioRecord portfolio = userProfile.getOrCreatePortfolioRecord(spec);
            final boolean canPlaceOrder = canPlaceMarginOrder(cmd, userProfile, spec, portfolio);
            if (canPlaceOrder) {
                portfolio.pendingHold(cmd.action, cmd.size);
                return true;
            } else {
                // try to cleanup portfolio if refusing to place
                userProfile.removeRecordIfEmpty(portfolio);
                return false;
            }

        } else {
            log.error("Symbol {} - unsupported type: {}", cmd.symbol, spec.type);
            return false;
        }
    }

    private boolean placeExchangeOrder(final OrderCommand cmd,
                                       final UserProfile userProfile,
                                       final CoreSymbolSpecification spec) {

        final int currency = (cmd.action == OrderAction.BID) ? spec.quoteCurrency : spec.baseCurrency;

        // futures positions check for this currency
        long freeFuturesMargin = 0L;
        for (final SymbolPortfolioRecord portfolioRecord : userProfile.portfolio) {
            if (portfolioRecord.currency == currency) {
                final long recSymbol = portfolioRecord.symbol;
                final CoreSymbolSpecification spec2 = symbolSpecificationProvider.getSymbolSpecification(recSymbol);
                // add P&L subtract margin
                freeFuturesMargin += portfolioRecord.estimateProfit(spec2, lastPriceCache.get(recSymbol));
                freeFuturesMargin -= portfolioRecord.calculateRequiredMarginForFutures(spec2);
            }
        }

        if (cmd.action == OrderAction.BID && cmd.reserveBidPrice < cmd.price) {
            // TODO refactor
            log.warn("reserveBidPrice={} less than price={}", cmd.reserveBidPrice, cmd.price);
            return false;
        }

        final long orderAmount = Utils.calculateHoldAmount(cmd.action, cmd.size, cmd.action == OrderAction.BID ? cmd.reserveBidPrice : cmd.price, spec);

//        log.debug("--------- {} -----------", cmd.orderId);
//        log.debug("serProfile.accounts.get(currency)={}", userProfile.accounts.get(currency));
//        log.debug("freeFuturesMargin={}", freeFuturesMargin);
//        log.debug("orderAmount={}", orderAmount);

        // speculative change balance
        long newBalance = userProfile.accounts.addToValue(currency, -orderAmount);

        final boolean canPlace = newBalance + freeFuturesMargin >= 0;

        if (!canPlace) {
            // revert balance change
            userProfile.accounts.addToValue(currency, orderAmount);
//            log.warn("orderAmount={} > userProfile.accounts.get({})={}", orderAmount, currency, userProfile.accounts.get(currency));
        }

        return canPlace;
    }


    /**
     * Checks:
     * 1. Users account balance
     * 2. Margin
     * 3. Current limit orders
     * <p>
     * NOTE: Current implementation does not care about accounts and positions quoted in different currencies
     */
    private boolean canPlaceMarginOrder(final OrderCommand cmd,
                                        final UserProfile userProfile,
                                        final CoreSymbolSpecification spec,
                                        final SymbolPortfolioRecord portfolio) {

        final long newRequiredMarginForSymbol = portfolio.calculateRequiredMarginForOrder(spec, cmd.action, cmd.size);
        if (newRequiredMarginForSymbol == -1) {
            // always allow placing a new order if it would not increase exposure
            return true;
        }

        // extra margin is required

        final long symbol = cmd.symbol;
        // calculate free margin for all positions same currency
        long freeMargin = 0L;
        for (final SymbolPortfolioRecord portfolioRecord : userProfile.portfolio) {
            final long recSymbol = portfolioRecord.symbol;
            if (recSymbol != symbol) {
                if (portfolioRecord.currency == spec.quoteCurrency) {
                    final CoreSymbolSpecification spec2 = symbolSpecificationProvider.getSymbolSpecification(recSymbol);
                    // add P&L subtract margin
                    freeMargin += portfolioRecord.estimateProfit(spec2, lastPriceCache.get(recSymbol));
                    freeMargin -= portfolioRecord.calculateRequiredMarginForFutures(spec2);
                }
            } else {
                freeMargin = portfolio.estimateProfit(spec, lastPriceCache.get(spec.symbolId));
            }
        }

//        log.debug("newMargin={} <= account({})={} + free {}",
//                newRequiredMarginForSymbol, portfolio.currency, userProfile.accounts.get(portfolio.currency), freeMargin);

        // check if current balance and margin can cover new required margin for symbol position
        return newRequiredMarginForSymbol <= userProfile.accounts.get(portfolio.currency) + freeMargin;
    }

    public void handlerRiskRelease(final long symbol,
                                   final L2MarketData marketData,
                                   MatcherTradeEvent mte) {

        // skip events processing if no events (or if contains BINARY EVENT)
        if (marketData == null && (mte == null || mte.eventType == BINARY_EVENT)) {
            return;
        }

        final CoreSymbolSpecification spec = symbolSpecificationProvider.getSymbolSpecification(symbol);
        if (spec == null) {
            throw new IllegalStateException("Symbol not found: " + symbol);
        }

        if (mte != null && mte.eventType != BINARY_EVENT) {
            // TODO ?? check if processing order is not reversed
            do {
                if (spec.type == SymbolType.CURRENCY_EXCHANGE_PAIR) {
                    handleMatcherEventExchange(mte, spec);
                } else {
                    handleMatcherEventMargin(mte, spec);
                }
                mte = mte.nextEvent;
            } while (mte != null);
        }

        // Process marked data
        if (marketData != null) {
            final RiskEngine.LastPriceCacheRecord record = lastPriceCache.getIfAbsentPut(symbol, RiskEngine.LastPriceCacheRecord::new);
            record.askPrice = (marketData.askSize != 0) ? marketData.askPrices[0] : Long.MAX_VALUE;
            record.bidPrice = (marketData.bidSize != 0) ? marketData.bidPrices[0] : 0;
        }
    }

    private void handleMatcherEventMargin(final MatcherTradeEvent ev, final CoreSymbolSpecification spec) {

        final long size = ev.size;

        if (ev.eventType == TRADE) {
            // TODO group by user profile ??
            int quoteCurrency = spec.quoteCurrency;

            if (uidForThisHandler(ev.activeOrderUid)) {
                // update taker's portfolio
                final UserProfile taker = userProfileService.getUserProfileOrThrowEx(ev.activeOrderUid);
                final SymbolPortfolioRecord takerSpr = taker.getPortfolioRecordOrThrowEx(ev.symbol);
                long sizeOpen = takerSpr.updatePortfolioForMarginTrade(ev.activeOrderAction, size, ev.price);
                final long fee = spec.takerFee * sizeOpen;
                taker.accounts.addToValue(quoteCurrency, -fee);
                fees.addToValue(quoteCurrency, fee);
                taker.removeRecordIfEmpty(takerSpr);
            }

            if (uidForThisHandler(ev.matchedOrderUid)) {
                // update maker's portfolio
                final UserProfile maker = userProfileService.getUserProfileOrThrowEx(ev.matchedOrderUid);
                final SymbolPortfolioRecord makerSpr = maker.getPortfolioRecordOrThrowEx(ev.symbol);
                long sizeOpen = makerSpr.updatePortfolioForMarginTrade(ev.activeOrderAction.opposite(), size, ev.price);
                final long fee = spec.makerFee * sizeOpen;
                maker.accounts.addToValue(quoteCurrency, -fee);
                fees.addToValue(quoteCurrency, fee);
                maker.removeRecordIfEmpty(makerSpr);
            }

        } else if (ev.eventType == REJECTION || ev.eventType == CANCEL) {

            if (uidForThisHandler(ev.activeOrderUid)) {
                // for cancel/rejection only one party is involved
                final UserProfile up = userProfileService.getUserProfileOrThrowEx(ev.activeOrderUid);
                final SymbolPortfolioRecord spr = up.getPortfolioRecordOrThrowEx(ev.symbol);
                spr.pendingRelease(ev.activeOrderAction, size);
                up.removeRecordIfEmpty(spr);
            }

        } else {
            log.error("unsupported eventType: {}", ev.eventType);
        }
    }


    private void handleMatcherEventExchange(final MatcherTradeEvent ev, final CoreSymbolSpecification spec) {


        if (ev.eventType == TRADE) {
            // TODO group by user profile ??

            // perform account-to-account transfers
            if (uidForThisHandler(ev.activeOrderUid)) {
//                log.debug("Processing release for taker");
                processExchangeHoldRelease2(ev.activeOrderUid, ev.activeOrderAction == OrderAction.ASK, ev, spec, true);
            }

            if (uidForThisHandler(ev.matchedOrderUid)) {
//                log.debug("Processing release for maker");
                processExchangeHoldRelease2(ev.matchedOrderUid, ev.activeOrderAction != OrderAction.ASK, ev, spec, false);
            }

        } else if (ev.eventType == REJECTION || ev.eventType == CANCEL) {
            if (uidForThisHandler(ev.activeOrderUid)) {

//                log.debug("CANCEL/REJ uid: {}", ev.activeOrderUid);

                // for cancel/rejection only one party is involved
                final UserProfile up = userProfileService.getUserProfileOrThrowEx(ev.activeOrderUid);
                final int currency = (ev.activeOrderAction == OrderAction.ASK) ? spec.baseCurrency : spec.quoteCurrency;
                final long amountForRelease = Utils.calculateHoldAmount(ev.activeOrderAction, ev.size, ev.bidderHoldPrice, spec);
                up.accounts.addToValue(currency, amountForRelease);

//                log.debug("REJ/CAN ASK: uid={} amountToRelease = {}  ACC:{}",
//                        ev.activeOrderUid, amountForRelease, userProfileService.getUserProfile(ev.activeOrderUid).accounts);

            }
        } else {
            log.error("unsupported eventType: {}", ev.eventType);
        }
    }

    private void processExchangeHoldRelease2(long uid, boolean isSelling, MatcherTradeEvent ev, CoreSymbolSpecification spec, boolean isTaker) {
        final long size = ev.size;
        final UserProfile up = userProfileService.getUserProfileOrThrowEx(uid);

        long feeForSize = (isTaker ? spec.takerFee : spec.makerFee) * size;
        fees.addToValue(spec.quoteCurrency, feeForSize);

        if (isSelling) {

            // selling
            final long obtainedAmountInQuoteCurrency = Utils.calculateAmountBid(size, ev.price, spec);
            up.accounts.addToValue(spec.quoteCurrency, obtainedAmountInQuoteCurrency - feeForSize);
//            log.debug("{} sells - getting {} -fee:{} (in quote cur={}) size={} ACCOUNTS:{}", up.uid, obtainedAmountInQuoteCurrency, feeForSize, spec.quoteCurrency, size, userProfileService.getUserProfile(uid).accounts);
        } else {

            //final long makerFeeCorrection = isTaker ? 0 : (spec.takerFee - spec.makerFee) * size;
            //log.debug("makerFeeCorrection={} ....(   - makerFee {} ) * {}", makerFeeCorrection, spec.makerFee, size);

            // buying, use bidderHoldPrice to calculate released amount based on price difference
            final long amountDiffToReleaseInQuoteCurrency = Utils.calculateAmountBidReleaseCorr(size, ev.bidderHoldPrice - ev.price, spec, isTaker);
            up.accounts.addToValue(spec.quoteCurrency, amountDiffToReleaseInQuoteCurrency);

            final long obtainedAmountInBaseCurrency = Utils.calculateAmountAsk(size, spec);
            up.accounts.addToValue(spec.baseCurrency, obtainedAmountInBaseCurrency);

//            log.debug("{} buys - amountDiffToReleaseInQuoteCurrency={} ({}-{}) (in quote cur={})",
//                    up.uid, amountDiffToReleaseInQuoteCurrency, ev.bidderHoldPrice, ev.price, spec.quoteCurrency);
//            log.debug("{} buys - getting {} (in base cur={}) size={} ACCOUNTS:{}",
//                    up.uid, obtainedAmountInBaseCurrency, spec.baseCurrency, size, userProfileService.getUserProfile(uid).accounts);
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        bytes.writeInt(shardId).writeLong(shardMask);

        symbolSpecificationProvider.writeMarshallable(bytes);
        userProfileService.writeMarshallable(bytes);
        binaryCommandsProcessor.writeMarshallable(bytes);
        Utils.marshallLongHashMap(lastPriceCache, bytes);
        // Utils.marshallLongHashMap(fees, bytes);
    }

    public void reset() {
        userProfileService.reset();
        symbolSpecificationProvider.reset();
        binaryCommandsProcessor.reset();
        lastPriceCache.clear();
        fees.clear();
    }

    @Override
    public int stateHash() {

        return Objects.hash(
                shardId,
                shardMask,
                symbolSpecificationProvider.stateHash(),
                userProfileService.stateHash(),
                binaryCommandsProcessor.stateHash(),
                Utils.stateHash(lastPriceCache),
                fees.hashCode());

        //log.debug("HASH RE{}/{} hash={} -- ssp={} ups={} bcp={} lpc={}", shardId, shardMask, hash, symbolSpecificationProvider.stateHash(), userProfileService.stateHash(), binaryCommandsProcessor.stateHash(), lastPriceCache.hashCode());
    }

    @AllArgsConstructor
    @Getter
    public class State {
        private final SymbolSpecificationProvider symbolSpecificationProvider;
        private final UserProfileService userProfileService;
        private final BinaryCommandsProcessor binaryCommandsProcessor;
        private final LongObjectHashMap<LastPriceCacheRecord> lastPriceCache;
        private final LongLongHashMap fees;
    }
}

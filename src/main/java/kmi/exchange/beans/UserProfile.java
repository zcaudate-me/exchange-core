package kmi.exchange.beans;

import kmi.exchange.core.Utils;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import org.eclipse.collections.impl.set.mutable.primitive.LongHashSet;
import kmi.exchange.core.Utils;

import java.util.Objects;

@Slf4j
public final class UserProfile implements WriteBytesMarshallable, StateHash {

    public final long uid;

    // symbol -> portfolio records
    public final LongObjectHashMap<SymbolPortfolioRecord> portfolio;

    // set of applied transactionId
    public final LongHashSet externalTransactions;

    // collected from accounts

    // currency accounts
    // currency -> balance
    public final LongLongHashMap accounts;


    // collected from portfolio
    // TODO change to cached guaranteed available funds based on current position?
    // public long fastMargin = 0L;

    public long commandsCounter = 0L;

    public UserProfile(long uid) {
        //log.debug("New {}", uid);
        this.uid = uid;
        this.portfolio = new LongObjectHashMap<>();
        this.externalTransactions = new LongHashSet();
        this.accounts = new LongLongHashMap();
    }

    public UserProfile(BytesIn bytesIn) {

        this.uid = bytesIn.readLong();

        // positions
        this.portfolio = Utils.readLongHashMap(bytesIn, b -> new SymbolPortfolioRecord(uid, b));

        // externalTransactions
        this.externalTransactions = Utils.readLongHashSet(bytesIn);

        // account balances
        this.accounts = Utils.readLongLongHashMap(bytesIn);
    }

    public SymbolPortfolioRecord getOrCreatePortfolioRecord(CoreSymbolSpecification spec) {
        final long symbol = spec.symbolId;
        SymbolPortfolioRecord record = portfolio.get(symbol);
        if (record == null) {
            record = new SymbolPortfolioRecord(uid, symbol, spec.quoteCurrency);
            portfolio.put(symbol, record);
        }
        return record;
    }

    public SymbolPortfolioRecord getPortfolioRecordOrThrowEx(long symbol) {
        final SymbolPortfolioRecord record = portfolio.get(symbol);
        if (record == null) {
            throw new IllegalStateException("not found portfolio for symbol " + symbol);
        }
        return record;
    }

    public void removeRecordIfEmpty(SymbolPortfolioRecord record) {
        if (record.isEmpty()) {
            accounts.addToValue(record.currency, record.profit);
            portfolio.removeKey(record.symbol);
        }
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        bytes.writeLong(uid);

        // positions
        Utils.marshallLongHashMap(portfolio, bytes);

        // externalTransactions
        Utils.marshallLongHashSet(externalTransactions, bytes);

        // account balances
        Utils.marshallLongHashMap(accounts, bytes);
    }


    @Override
    public String toString() {
        return "UserProfile{" +
                "uid=" + uid +
                ", portfolios=" + portfolio.size() +
                ", accounts=" + accounts +
                ", commandsCounter=" + commandsCounter +
                '}';
    }

    @Override
    public int stateHash() {
        return Objects.hash(
                uid,
                Utils.stateHash(portfolio),
                externalTransactions.hashCode(),
                accounts.hashCode());
    }
}

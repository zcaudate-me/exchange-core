package kmi.exchange.beans.api.binary;

import kmi.exchange.core.Utils;
import lombok.AllArgsConstructor;
import lombok.Getter;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import kmi.exchange.beans.CoreSymbolSpecification;
import kmi.exchange.core.Utils;

import java.util.Collection;

@AllArgsConstructor
@Getter
public class BatchAddSymbolsCommand implements WriteBytesMarshallable {

    private final LongObjectHashMap<CoreSymbolSpecification> symbols;

    public BatchAddSymbolsCommand(final CoreSymbolSpecification symbol) {
        symbols = LongObjectHashMap.newWithKeysValues(symbol.symbolId, symbol);
    }

    public BatchAddSymbolsCommand(final Collection<CoreSymbolSpecification> collection) {
        symbols = new LongObjectHashMap<>(collection.size());
        collection.forEach(s -> symbols.put(s.symbolId, s));
    }


    public BatchAddSymbolsCommand(final BytesIn bytes) {
        symbols = Utils.readLongHashMap(bytes, CoreSymbolSpecification::new);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        Utils.marshallLongHashMap(symbols, bytes);
    }
}

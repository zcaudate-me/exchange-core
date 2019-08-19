package kmi.exchange.beans.api.reports;

import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import kmi.exchange.beans.ReportType;

import java.util.function.Function;
import java.util.stream.Stream;

public interface ReportQuery<T extends ReportResult> extends WriteBytesMarshallable {

    ReportType getReportType();

    Function<Stream<BytesIn>, T> getResultBuilder();
}

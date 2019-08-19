package kmi.exchange.tests.util;

import kmi.exchange.core.ExchangeApi;
import lombok.extern.slf4j.Slf4j;
import net.openhft.affinity.AffinityLock;
import org.eclipse.collections.impl.map.mutable.primitive.LongLongHashMap;
import kmi.exchange.beans.CoreSymbolSpecification;
import kmi.exchange.beans.api.ApiCommand;
import kmi.exchange.beans.api.ApiPersistState;
import kmi.exchange.beans.cmd.CommandResultCode;
import kmi.exchange.beans.cmd.OrderCommandType;
import kmi.exchange.core.ExchangeApi;
import kmi.exchange.tests.util.ExchangeTestContainer;
import kmi.exchange.tests.util.TestOrdersGenerator;
import kmi.exchange.tests.util.UserCurrencyAccountsGenerator;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

@Slf4j
public class ThroughputTestsModule {


    public static void throughputTestImpl(final ExchangeTestContainer container,
                                          final int totalTransactionsNumber,
                                          final int targetOrderBookOrdersTotal,
                                          final int numAccounts,
                                          final int iterations,
                                          final Set<Integer> currenciesAllowed,
                                          final int numSymbols,
                                          final ExchangeTestContainer.AllowedSymbolTypes allowedSymbolTypes) throws Exception {

        try (final AffinityLock cpuLock = AffinityLock.acquireLock()) {

            final ExchangeApi api = container.api;

            final List<CoreSymbolSpecification> coreSymbolSpecifications = ExchangeTestContainer.generateRandomSymbols(numSymbols, currenciesAllowed, allowedSymbolTypes);

            final List<BitSet> usersAccounts = UserCurrencyAccountsGenerator.generateUsers(numAccounts, currenciesAllowed);

            final TestOrdersGenerator.MultiSymbolGenResult genResult = TestOrdersGenerator.generateMultipleSymbols(
                    coreSymbolSpecifications,
                    totalTransactionsNumber,
                    usersAccounts,
                    targetOrderBookOrdersTotal);

            List<Float> perfResults = new ArrayList<>();
            for (int j = 0; j < iterations; j++) {

                container.addSymbols(coreSymbolSpecifications);
                final LongLongHashMap globalBalancesExpected = container.userAccountsInit(usersAccounts);

                assertThat(container.totalBalanceReport().getSum(), is(globalBalancesExpected));

                final CountDownLatch latchFill = new CountDownLatch(genResult.getApiCommandsFill().size());
                container.setConsumer(cmd -> latchFill.countDown());
                genResult.getApiCommandsFill().forEach(api::submitCommand);
                latchFill.await();

                final CountDownLatch latchBenchmark = new CountDownLatch(genResult.getApiCommandsBenchmark().size());
                container.setConsumer(cmd -> latchBenchmark.countDown());
                long t = System.currentTimeMillis();
                genResult.getApiCommandsBenchmark().forEach(api::submitCommand);
                latchBenchmark.await();
                t = System.currentTimeMillis() - t;
                float perfMt = (float) genResult.getApiCommandsBenchmark().size() / (float) t / 1000.0f;
                log.info("{}. {} MT/s", j, String.format("%.3f", perfMt));
                perfResults.add(perfMt);

                assertThat(container.totalBalanceReport().getSum(), is(globalBalancesExpected));

                // compare orderBook final state just to make sure all commands executed same way
                // TODO compare events, balances, portfolios
                coreSymbolSpecifications.forEach(
                        symbol -> assertEquals(genResult.getGenResults().get(symbol.symbolId).getFinalOrderBookSnapshot(), container.requestCurrentOrderBook(symbol.symbolId)));

                container.resetExchangeCore();

                System.gc();
                Thread.sleep(300);
            }

            float avg = (float) perfResults.stream().mapToDouble(x -> x).average().orElse(0);
            log.info("Average: {} MT/s", avg);
        }
    }

}

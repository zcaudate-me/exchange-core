package kmi.exchange.tests.integration;

import kmi.exchange.tests.util.TestConstants;
import lombok.extern.slf4j.Slf4j;
import org.junit.Test;
import kmi.exchange.beans.OrderAction;
import kmi.exchange.beans.OrderType;
import kmi.exchange.beans.PortfolioPosition;
import kmi.exchange.beans.api.ApiPlaceOrder;
import kmi.exchange.beans.api.reports.TotalCurrencyBalanceReportResult;
import kmi.exchange.beans.cmd.CommandResultCode;
import kmi.exchange.tests.util.ExchangeTestContainer;

import static org.hamcrest.core.Is.is;
import static org.junit.Assert.*;
import static kmi.exchange.beans.OrderType.GTC;
import static kmi.exchange.tests.util.TestConstants.*;

/**
 * TODO more place scenarios, IOC reject tests, GTC move matching, Cancel tests
 */

@Slf4j
public final class ITFeesMargin {

    private final long makerFee = TestConstants.SYMBOLSPECFEE_USD_JPY.makerFee;
    private final long takerFee = TestConstants.SYMBOLSPECFEE_USD_JPY.takerFee;
    private final int symbolId = TestConstants.SYMBOLSPECFEE_USD_JPY.symbolId;

    @Test(timeout = 10_000)
    public void shouldProcessFees_AskGtcMakerPartial_BidIocTaker() throws Exception {

        try (final ExchangeTestContainer container = new ExchangeTestContainer()) {
            container.addSymbol(TestConstants.SYMBOLSPECFEE_USD_JPY);

            final long jpyAmount1 = 240_000L;
            container.createUserWithMoney(TestConstants.UID_1, TestConstants.CURRENCY_JPY, jpyAmount1);

            final ApiPlaceOrder order101 = ApiPlaceOrder.builder()
                    .uid(TestConstants.UID_1)
                    .id(101L)
                    .price(10770L)
                    .reservePrice(0L)
                    .size(40L)
                    .action(OrderAction.ASK)
                    .orderType(GTC)
                    .symbol(symbolId)
                    .build();

            container.submitCommandSync(order101, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify order placed
            container.validateUserState(
                    TestConstants.UID_1,
                    userProfile -> assertThat(userProfile.accounts.get(TestConstants.CURRENCY_XBT), is(0L)),
                    orders -> assertThat(orders.get(101L).price, is(order101.price)));

            // create second user
            final long jpyAmount2 = 150_000L;
            container.createUserWithMoney(TestConstants.UID_2, TestConstants.CURRENCY_JPY, jpyAmount2);

            TotalCurrencyBalanceReportResult totalBal1 = container.totalBalanceReport();
            assertThat(totalBal1.getSum().get(TestConstants.CURRENCY_USD), is(0L));
            assertThat(totalBal1.getSum().get(TestConstants.CURRENCY_JPY), is(jpyAmount1 + jpyAmount2));
            assertThat(totalBal1.getFees().get(TestConstants.CURRENCY_USD), is(0L));
            assertThat(totalBal1.getFees().get(TestConstants.CURRENCY_JPY), is(0L));
            assertThat(totalBal1.getOpenInterestLong().get(symbolId), is(0L));

            final ApiPlaceOrder order102 = ApiPlaceOrder.builder()
                    .uid(TestConstants.UID_2)
                    .id(102)
                    .price(10770L)
                    .reservePrice(10770L)
                    .size(30L)
                    .action(OrderAction.BID)
                    .orderType(OrderType.IOC)
                    .symbol(symbolId)
                    .build();

            container.submitCommandSync(order102, cmd -> assertThat(cmd.resultCode, is(CommandResultCode.SUCCESS)));

            // verify seller maker balance
            container.validateUserState(
                    TestConstants.UID_1,
                    userProfile -> {
                        assertThat(userProfile.accounts.get(TestConstants.CURRENCY_JPY), is(240_000L - makerFee * 30));
                        assertThat(userProfile.accounts.get(TestConstants.CURRENCY_USD), is(0L));
                        assertThat(userProfile.portfolio.get(symbolId).position, is(PortfolioPosition.SHORT));
                        assertThat(userProfile.portfolio.get(symbolId).openVolume, is(30L));
                        assertThat(userProfile.portfolio.get(symbolId).pendingBuySize, is(0L));
                        assertThat(userProfile.portfolio.get(symbolId).pendingSellSize, is(10L));
                    },
                    orders -> assertFalse(orders.isEmpty()));

            // verify buyer taker balance
            container.validateUserState(
                    TestConstants.UID_2,
                    userProfile -> {
                        assertThat(userProfile.accounts.get(TestConstants.CURRENCY_JPY), is(150_000L - takerFee * 30));
                        assertThat(userProfile.accounts.get(TestConstants.CURRENCY_USD), is(0L));
                        assertThat(userProfile.portfolio.get(symbolId).position, is(PortfolioPosition.LONG));
                        assertThat(userProfile.portfolio.get(symbolId).openVolume, is(30L));
                        assertThat(userProfile.portfolio.get(symbolId).pendingBuySize, is(0L));
                        assertThat(userProfile.portfolio.get(symbolId).pendingSellSize, is(0L));
                    },
                    orders -> assertTrue(orders.isEmpty()));

            // total balance remains the same
            final TotalCurrencyBalanceReportResult totalBal2 = container.totalBalanceReport();
            assertThat(totalBal2.getSum().get(TestConstants.CURRENCY_USD), is(0L));
            assertThat(totalBal2.getSum().get(TestConstants.CURRENCY_JPY), is(jpyAmount1 + jpyAmount2));
            assertThat(totalBal2.getFees().get(TestConstants.CURRENCY_USD), is(0L));
            assertThat(totalBal2.getFees().get(TestConstants.CURRENCY_JPY), is((makerFee + takerFee) * 30));
            assertThat(totalBal2.getOpenInterestLong().get(symbolId), is(30L));
        }
    }

}

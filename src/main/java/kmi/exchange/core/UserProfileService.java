package kmi.exchange.core;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;
import kmi.exchange.beans.StateHash;
import kmi.exchange.beans.UserProfile;
import kmi.exchange.beans.cmd.CommandResultCode;

import java.util.Objects;

/**
 * Stateful (!) User profile service
 * <p>
 * TODO make multi instance
 */
@Slf4j
public final class UserProfileService implements WriteBytesMarshallable, StateHash {

    /**
     * State: uid -> user profile
     */
    @Getter
    private final LongObjectHashMap<UserProfile> userProfiles;

    public UserProfileService() {
        this.userProfiles = new LongObjectHashMap<>(1024);
    }

    public UserProfileService(BytesIn bytes) {
        this.userProfiles = Utils.readLongHashMap(bytes, UserProfile::new);
    }

    /**
     * Find user profile
     *
     * @param uid
     * @return
     */
    public UserProfile getUserProfile(long uid) {
        return userProfiles.get(uid);
    }

    public UserProfile getUserProfileOrThrowEx(long uid) {

        final UserProfile userProfile = userProfiles.get(uid);

        if (userProfile == null) {
            throw new IllegalStateException("User profile not found, uid=" + uid);
        }

        return userProfile;
    }


    /**
     * Perform balance adjustment for specific user
     *
     * @param uid
     * @param currency
     * @param amount
     * @param fundingTransactionId
     * @return result code
     */
    public CommandResultCode balanceAdjustment(final long uid, final long currency, final long amount, final long fundingTransactionId) {

        final UserProfile userProfile = getUserProfile(uid);
        if (userProfile == null) {
            log.warn("User profile {} not found", uid);
            return CommandResultCode.AUTH_INVALID_USER;
        }

        if (amount == 0) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ZERO;
        }

        // double settlement protection
        if (userProfile.externalTransactions.contains(fundingTransactionId)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_ALREADY_APPLIED;
        }

        // validate balance for withdrowals
        if (amount < 0 && (userProfile.accounts.get(currency) + amount < 0)) {
            return CommandResultCode.USER_MGMT_ACCOUNT_BALANCE_ADJUSTMENT_NSF;
        }

        userProfile.externalTransactions.add(fundingTransactionId);
        userProfile.accounts.addToValue(currency, amount);

        //log.debug("FUND: {}", userProfile);
        return CommandResultCode.SUCCESS;
    }

    /**
     * Create a new user profile with known unique uid
     *
     * @param uid
     * @return
     */
    public boolean addEmptyUserProfile(long uid) {
        if (userProfiles.get(uid) == null) {
            userProfiles.put(uid, new UserProfile(uid));
            return true;
        } else {
            log.debug("Can not add user, already exists: {}", uid);
            return false;
        }
    }

    public void reset() {
        userProfiles.clear();
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {

        // write symbolSpecs
        Utils.marshallLongHashMap(userProfiles, bytes);
    }

    @Override
    public int stateHash() {
        return Objects.hash(Utils.stateHash(userProfiles));
    }

}
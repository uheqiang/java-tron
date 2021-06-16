package org.tron.core.store;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.tron.core.capsule.AppAccountIndexCapsule;
import org.tron.core.db.TronStoreWithRevoking;

/**
 * @author Brian
 * @date 2021/6/16 16:29
 */
@Slf4j(topic = "DB")
@Component
public class AppAccountIndexStore extends TronStoreWithRevoking<AppAccountIndexCapsule> {

    @Autowired
    private AppAccountIndexStore(@Value("app-account-index") String dbName) {
        super(dbName);
    }

    @Override
    public AppAccountIndexCapsule get(byte[] key) {
        byte[] value = revokingDB.getUnchecked(key);
        return ArrayUtils.isEmpty(value) ? null : new AppAccountIndexCapsule(value);
    }

    @Override
    public void put(byte[] key, AppAccountIndexCapsule item) {
        super.put(key, item);
    }

    @Override
    public void close() {
        super.close();
    }


}

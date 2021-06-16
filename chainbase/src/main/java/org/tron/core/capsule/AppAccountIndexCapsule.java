package org.tron.core.capsule;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.PersonalInfo;
import org.tron.protos.Protocol.AppAcount;

/**
 * @author Brian
 * @date 2021/6/16 16:33
 */
@Slf4j(topic = "capsule")
public class AppAccountIndexCapsule implements ProtoCapsule<AppAcount> {

    private AppAcount appAcount;

    /**
     * get AppAccountIndex from bytes data.
     */
    public AppAccountIndexCapsule(byte[] data) {
        try {
            this.appAcount = AppAcount.parseFrom(data);
        } catch (InvalidProtocolBufferException e) {
            logger.debug(e.getMessage());
        }
    }

    public AppAccountIndexCapsule(PersonalInfo personalInfo, ByteString address, long createTime){
        this.appAcount = AppAcount.newBuilder()
                .setPersonalInfo(personalInfo)
                .setAddress(address)
                .setCreateTime(createTime)
                .build();
    }

    @Override
    public byte[] getData() {
        return this.appAcount.toByteArray();
    }

    @Override
    public AppAcount getInstance() {
        return this.appAcount;
    }
}

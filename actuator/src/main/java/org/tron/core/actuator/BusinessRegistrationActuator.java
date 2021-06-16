package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.AccountContract;

/**
 * @author Brian
 * @date 2021/6/16 11:47
 */
@Slf4j(topic = "actuator")
public class BusinessRegistrationActuator extends AbstractActuator {

    public BusinessRegistrationActuator() {
        super(ContractType.AccountCreateContract, AccountContract.AccountCreateContract.class);
    }

    @Override
    public boolean execute(Object result) throws ContractExeException {
        return false;
    }

    @Override
    public boolean validate() throws ContractValidateException {
        return false;
    }

    @Override
    public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
        return null;
    }

    @Override
    public long calcFee() {
        return 0;
    }
}

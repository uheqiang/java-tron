package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.contract.AccountContract;
import org.tron.protos.contract.BusinessContract;

import java.util.Objects;

/**
 * @author Brian
 * @date 2021/6/16 11:47
 */
@Slf4j(topic = "actuator")
public class BusinessRegistrationActuator extends AbstractActuator {

    public BusinessRegistrationActuator() {
        super(ContractType.BusinessCreateContract, BusinessContract.BusinessCreateContract.class);
    }

    @Override
    public boolean execute(Object result) throws ContractExeException {
        TransactionResultCapsule ret = (TransactionResultCapsule)result;
        if (Objects.isNull(ret)){
            throw new RuntimeException("TransactionResultCapsule is null");
        }

        long fee = calcFee(); //default 0
        DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
        AccountStore accountStore = chainBaseManager.getAccountStore();
        try {
            long timestamp = dynamicStore.getLatestBlockHeaderTimestamp();
            BusinessContract.BusinessCreateContract businessCreateContract = any.unpack(BusinessContract.BusinessCreateContract.class);
            Protocol.PersonalInfo personalInfo = businessCreateContract.getPersonalInfo();

            AccountCapsule accountCapsule = new AccountCapsule(businessCreateContract.getAccountAddress(),
                    businessCreateContract.getType(), timestamp,  personalInfo);

            String keyString = personalInfo.getAppID().trim();
            accountStore.put(keyString.getBytes(), accountCapsule);
            accountStore.put(businessCreateContract.getAccountAddress().toByteArray(), accountCapsule);
            ret.setStatus(fee, Protocol.Transaction.Result.code.SUCESS);
        } catch (InvalidProtocolBufferException e) {
            logger.debug(e.getMessage(), e);
            ret.setStatus(fee, Protocol.Transaction.Result.code.FAILED);
            throw new ContractExeException(e.getMessage());
        }

        return true;
    }

    @Override
    public boolean validate() throws ContractValidateException {
        if (this.any == null) {
            throw new ContractValidateException("No contract!");
        }
        if (chainBaseManager == null) {
            throw new ContractValidateException("No account store or contract store!");
        }
        AccountStore accountStore = chainBaseManager.getAccountStore();
        if (!any.is(AccountContract.AccountCreateContract.class)) {
            throw new ContractValidateException(
                    "contract type error,expected type [AccountCreateContract],real type[" + any.getClass() + "]");
        }
        final BusinessContract.BusinessCreateContract contract;
        try {
            contract = this.any.unpack(BusinessContract.BusinessCreateContract.class);
        } catch (InvalidProtocolBufferException e) {
            logger.debug(e.getMessage(), e);
            throw new ContractValidateException(e.getMessage());
        }

        byte[] accountAddress = contract.getAccountAddress().toByteArray();
        if (!Commons.addressValid(accountAddress)) {
            throw new ContractValidateException("Invalid account address");
        }

        Protocol.PersonalInfo personalInfo = contract.getPersonalInfo();
        String keyString = personalInfo.getAppID().trim();
        // 检查商家是否注册
        if (accountStore.has(keyString.getBytes()) && contract.getType().getNumber() == Protocol.AccountType.Normal_VALUE) {
            String readableOwnerAddress = StringUtil.createReadableString(accountAddress);
            throw new ContractValidateException("Business information[" + readableOwnerAddress + "] not exists");
        }
        // 验证商家地址已存在
        if (accountStore.has(accountAddress)) {
            String readableAddress = StringUtil.createReadableString(accountAddress);
            throw new ContractValidateException("Account[" + readableAddress + "] has existed");
        }

        return true;
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

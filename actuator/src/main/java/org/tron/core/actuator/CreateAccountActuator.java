package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.Commons;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AppAccountIndexCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.AppAccountIndexStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.PersonalInfo;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.AccountContract.AccountCreateContract;

@Slf4j(topic = "actuator")
public class CreateAccountActuator extends AbstractActuator {

  public CreateAccountActuator() {
    super(ContractType.AccountCreateContract, AccountCreateContract.class);
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

      AccountCreateContract accountCreateContract = any.unpack(AccountCreateContract.class);

      AccountCapsule accountCapsule = new AccountCapsule(
              accountCreateContract.getAccountAddress(),
              accountCreateContract.getType(),
              timestamp,
              accountCreateContract.getPersonalInfo());

      accountStore.put(accountCreateContract.getAccountAddress().toByteArray(), accountCapsule);

      // 只有商家中的用户注册才与商家关联起来
      if (accountCreateContract.getType().getNumber() == AccountType.AssetIssue_VALUE) {
        AppAccountIndexStore appAccountIndexStore = chainBaseManager.getAppAccountIndexStore();
        PersonalInfo personalInfo = accountCreateContract.getPersonalInfo();
        AppAccountIndexCapsule appAccountIndexCapsule =  new AppAccountIndexCapsule(
                personalInfo,  accountCreateContract.getAccountAddress(), timestamp);
        byte[] key = personalInfo.getAppID().concat(personalInfo.getIdentity()).trim().getBytes();
        appAccountIndexStore.put(key, appAccountIndexCapsule);
      }

      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
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
    if (!any.is(AccountCreateContract.class)) {
      throw new ContractValidateException(
              "contract type error,expected type [AccountCreateContract],real type[" + any.getClass() + "]");
    }
    final AccountCreateContract contract;
    try {
      contract = this.any.unpack(AccountCreateContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    // 可信节点地址 or 称为商家地址
    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    if (!Commons.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid ownerAddress");
    }

    AccountCapsule accountCapsule = accountStore.get(ownerAddress);
    // 检查商家是否已注册
    if (accountCapsule == null && contract.getType().getNumber() == AccountType.Normal_VALUE) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException("Business[" + readableOwnerAddress + "] not exists");
    }

    byte[] accountAddress = contract.getAccountAddress().toByteArray();
    if (!Commons.addressValid(accountAddress)) {
      throw new ContractValidateException("Invalid account address");
    }

    // 验证用户地址是否已被注册
    String readableAddress;
    if (accountStore.has(accountAddress)) {
      readableAddress = StringUtil.createReadableString(accountAddress);
      throw new ContractValidateException("Account[" + readableAddress + "] has existed");
    }

    // 验证用户身份已经在商家中已经注册
    if (contract.getType().getNumber() == AccountType.AssetIssue_VALUE) {
      // 检查该用户已在某一个商家中已注册
      PersonalInfo personalInfo = contract.getPersonalInfo();
      // 商家ID
      String appId = personalInfo.getAppID();
      // 验证商家ID是否注册
      AccountCapsule business = accountStore.get(appId.trim().getBytes());
      if (business == null) {
        throw new ContractValidateException("Business not exists, app[" + appId + "]");
      }

      // 用户身份
      String identity = personalInfo.getIdentity();
      String key = appId.concat(identity).trim();
      AppAccountIndexStore appAccountIndexStore = chainBaseManager.getAppAccountIndexStore();
      AppAccountIndexCapsule appAccountIndexCapsule = appAccountIndexStore.get(key.getBytes());
      if (appAccountIndexCapsule != null) {
        throw new ContractValidateException("User has existed on app[" + appId + "]");
      }
    }
    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return any.unpack(AccountCreateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    //return chainBaseManager.getDynamicPropertiesStore().getCreateNewAccountFeeInSystemContract();
    return 0;
  }
}

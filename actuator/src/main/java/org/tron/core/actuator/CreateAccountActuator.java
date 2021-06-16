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
import org.tron.protos.Protocol.PersonalInfo;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.AccountContract.AccountCreateContract;

@Slf4j(topic = "actuator")
public class CreateAccountActuator extends AbstractActuator {

  //AccountType.AssetIssue = 1
  private static final int ASSET_ISSUE = 1;

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
      boolean withDefaultPermission = dynamicStore.getAllowMultiSign() == 1;
      AccountCapsule accountCapsule = new AccountCapsule(accountCreateContract,
              timestamp, withDefaultPermission, dynamicStore);
      accountStore.put(accountCreateContract.getAccountAddress().toByteArray(), accountCapsule);

      AppAccountIndexStore appAccountIndexStore = chainBaseManager.getAppAccountIndexStore();
      PersonalInfo personalInfo = accountCreateContract.getPersonalInfo();
      AppAccountIndexCapsule appAccountIndexCapsule =  new AppAccountIndexCapsule(
              personalInfo,  accountCreateContract.getAccountAddress(), timestamp);
      appAccountIndexStore.put(personalInfo.getIdentity().concat(personalInfo.getAppID()).getBytes(), appAccountIndexCapsule);

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

    // owneraddress：trusted node address
    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    if (!Commons.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid ownerAddress");
    }

    AccountCapsule accountCapsule = accountStore.get(ownerAddress);
    if (accountCapsule == null) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException("Account[" + readableOwnerAddress + "] not exists");
    }

    byte[] accountAddress = contract.getAccountAddress().toByteArray();
    if (!Commons.addressValid(accountAddress)) {
      throw new ContractValidateException("Invalid account address");
    }

    // 验证地址已存在
    if (accountStore.has(accountAddress)) {
      throw new ContractValidateException("Account has existed");
    }

    //验证该身份已经在app上已经注册
    if (contract.getType().getNumber() == ASSET_ISSUE) {
      //检查该用户已在某一个app上已注册
      PersonalInfo personalInfo = contract.getPersonalInfo();
      String appId = personalInfo.getAppID();
      String identity = personalInfo.getIdentity();
      String key = identity.concat(appId);
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
    return chainBaseManager.getDynamicPropertiesStore().getCreateNewAccountFeeInSystemContract();
  }
}

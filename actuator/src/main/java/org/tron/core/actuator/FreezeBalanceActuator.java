package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ArrayUtils;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.Commons;
import org.tron.common.utils.DBConfig;
import org.tron.common.utils.StringUtil;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.DelegatedResourceAccountIndexCapsule;
import org.tron.core.capsule.DelegatedResourceCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.*;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.BalanceContract.FreezeBalanceContract;
import org.tron.protos.contract.Common;

@Slf4j(topic = "actuator")
public class FreezeBalanceActuator extends AbstractActuator {

  public FreezeBalanceActuator() {
    super(ContractType.FreezeBalanceContract, FreezeBalanceContract.class);
  }

  @Override
  public boolean execute(Object result) throws ContractExeException {
    TransactionResultCapsule ret = (TransactionResultCapsule)result;
    if (Objects.isNull(ret)){
      throw new RuntimeException("TransactionResultCapsule is null");
    }

    long fee = calcFee();
    final FreezeBalanceContract freezeBalanceContract;
    AccountStore accountStore = chainBaseManager.getAccountStore();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    try {
      freezeBalanceContract = any.unpack(FreezeBalanceContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }

    long now = dynamicStore.getLatestBlockHeaderTimestamp();
    //long duration = freezeBalanceContract.getFrozenDuration() * 86_400_000;

    long frozenBalance = freezeBalanceContract.getFrozenBalance();
    //long newBalance = accountCapsule.getBalance() - frozenBalance;

    //long expireTime = now + duration;
    long expireTime = now;
    byte[] ownerAddress = freezeBalanceContract.getOwnerAddress().toByteArray();
    byte[] receiverAddress = freezeBalanceContract.getReceiverAddress().toByteArray();

//    switch (freezeBalanceContract.getResource()) {
//      case BANDWIDTH:
//        if (!ArrayUtils.isEmpty(receiverAddress) && dynamicStore.supportDR()) {
//          delegateResource(ownerAddress, receiverAddress, true, frozenBalance, expireTime);
//          accountCapsule.addDelegatedFrozenBalanceForBandwidth(frozenBalance);
//        } else {
//          long newFrozenBalanceForBandwidth = frozenBalance + accountCapsule.getFrozenBalance();
//          accountCapsule.setFrozenForBandwidth(newFrozenBalanceForBandwidth, expireTime);
//        }
//        dynamicStore.addTotalNetWeight(frozenBalance / 1000_000L);
//        break;
//      case ENERGY:
//        if (!ArrayUtils.isEmpty(receiverAddress) && dynamicStore.supportDR()) {
//          delegateResource(ownerAddress, receiverAddress, false, frozenBalance, expireTime);
//          accountCapsule.addDelegatedFrozenBalanceForEnergy(frozenBalance);
//        } else {
//          long newFrozenBalanceForEnergy = frozenBalance + accountCapsule.getAccountResource()
//                  .getFrozenBalanceForEnergy()
//                  .getFrozenBalance();
//          accountCapsule.setFrozenForEnergy(newFrozenBalanceForEnergy, expireTime);
//        }
//        dynamicStore.addTotalEnergyWeight(frozenBalance / 1000_000L);
//        break;
//    }

    AccountCapsule accountCapsule = accountStore.get(freezeBalanceContract.getOwnerAddress().toByteArray());
    if (freezeBalanceContract.getResource().getNumber() == Common.ResourceCode.ENERGY_VALUE) {
      if (!ArrayUtils.isEmpty(receiverAddress) && dynamicStore.supportDR()) {
        delegateResource(ownerAddress, receiverAddress, false, frozenBalance, expireTime);
        accountCapsule.addDelegatedFrozenBalanceForEnergy(frozenBalance);
      } else {
        //trc10与能量之间的兑换汇率：dynamicStore.getFuelExchangeRate()
        long newFrozenBalanceForEnergy = frozenBalance * dynamicStore.getFuelExchangeRate() +
                accountCapsule.getAccountResource()
                .getFrozenBalanceForEnergy()
                .getFrozenBalance();
        accountCapsule.setFrozenForEnergy(newFrozenBalanceForEnergy, expireTime);
      }
      //dynamicStore.addTotalEnergyWeight(frozenBalance / 1000_000L);
    }

    //asset id
    byte[] key = freezeBalanceContract.getAssetId().toByteArray();
    accountCapsule.reduceAssetAmountV2(key,freezeBalanceContract.getFrozenBalance(),
            dynamicStore,chainBaseManager.getAssetIssueV2Store());
    accountStore.put(accountCapsule.createDbKey(), accountCapsule);

    ret.setStatus(fee, code.SUCESS);

    return true;
  }


  @Override
  public boolean validate() throws ContractValidateException {
    if (this.any == null) {
      throw new ContractValidateException("No contract!");
    }
    if (chainBaseManager == null) {
      throw new ContractValidateException("No account store or dynamic store!");
    }
    AccountStore accountStore = chainBaseManager.getAccountStore();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    AssetIssueV2Store assetIssueV2Store = chainBaseManager.getAssetIssueV2Store();
    if (!any.is(FreezeBalanceContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [FreezeBalanceContract],real type[" + any.getClass() + "]");
    }

    final FreezeBalanceContract freezeBalanceContract;
    try {
      freezeBalanceContract = this.any.unpack(FreezeBalanceContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }
    byte[] ownerAddress = freezeBalanceContract.getOwnerAddress().toByteArray();
    if (!Commons.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }

    AccountCapsule ownerAccount = accountStore.get(ownerAddress);
    if (ownerAccount == null) {
      String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);
      throw new ContractValidateException("Account[" + readableOwnerAddress + "] not exists");
    }

    //需要被兑换的trc10
    long frozenBalance = freezeBalanceContract.getFrozenBalance();
    if (frozenBalance <= 0) {
      throw new ContractValidateException("frozenBalance must be positive");
    }
    /*if (frozenBalance < 1_000_000L) {
      throw new ContractValidateException("frozenBalance must be more than 1TRX");
    }*/

    /*int frozenCount = accountCapsule.getFrozenCount();
    if (!(frozenCount == 0 || frozenCount == 1)) {
      throw new ContractValidateException("frozenCount must be 0 or 1");
    }*/
    // 验证系统是否有该资源
    byte[] assetId = freezeBalanceContract.getAssetId().toByteArray();
    if (!assetIssueV2Store.has(assetId)) {
      throw new ContractValidateException("No asset in this system!");
    }


    // 限制特定的Token才能兑换成能量
    if (!Arrays.equals(assetId,"1000001".getBytes())) {
      throw new ContractValidateException("Account has no asset that can be exchanged for energy!");
    }

    Map<String, Long> asset = ownerAccount.getAssetMapV2();
    /*if (dynamicStore.getAllowSameTokenName() == 0) {
      asset = ownerAccount.getAssetMap();
    } else {
      asset = ownerAccount.getAssetMapV2();
    }*/
    if (asset.isEmpty()) {
      throw new ContractValidateException("Owner has no asset!");
    }

    // 验证账户是否有可兑换的资源，以及对应资源的余额是否大于兑换的数额
    Long assetBalance = asset.get(ByteArray.toStr(assetId));
    if (null == assetBalance || assetBalance <= 0) {
      throw new ContractValidateException("Asset balance must be greater than 0.");
    }
    if (frozenBalance > assetBalance) {
      throw new ContractValidateException("Asset balance is not sufficient.");
    }

//    long maxFrozenNumber = dbManager.getDynamicPropertiesStore().getMaxFrozenNumber();
//    if (accountCapsule.getFrozenCount() >= maxFrozenNumber) {
//      throw new ContractValidateException("max frozen number is: " + maxFrozenNumber);
//    }

    //冻结天数
    /*long frozenDuration = freezeBalanceContract.getFrozenDuration();
    long minFrozenTime = dynamicStore.getMinFrozenTime();
    long maxFrozenTime = dynamicStore.getMaxFrozenTime();

    boolean needCheckFrozeTime = DBConfig.getCheckFrozenTime() == 1;//for test
    if (needCheckFrozeTime && !(frozenDuration >= minFrozenTime
        && frozenDuration <= maxFrozenTime)) {
      throw new ContractValidateException(
          "frozenDuration must be less than " + maxFrozenTime + " days "
              + "and more than " + minFrozenTime + " days");
    }*/

    /*case BANDWIDTH: break;*/
    //throw new ContractValidateException("ResourceCode error,valid ResourceCode[BANDWIDTH、ENERGY]");
    if (freezeBalanceContract.getResource() != Common.ResourceCode.ENERGY) {
      throw new ContractValidateException("ResourceCode error,valid ResourceCode[ENERGY]");
    }

    byte[] receiverAddress = freezeBalanceContract.getReceiverAddress().toByteArray();
    //If the receiver is included in the contract, the receiver will receive the resource.
    if (!ArrayUtils.isEmpty(receiverAddress) && dynamicStore.supportDR()) {
      if (Arrays.equals(receiverAddress, ownerAddress)) {
        throw new ContractValidateException("receiverAddress must not be the same as ownerAddress");
      }

      if (!Commons.addressValid(receiverAddress)) {
        throw new ContractValidateException("Invalid receiverAddress");
      }

      AccountCapsule receiverCapsule = accountStore.get(receiverAddress);
      if (receiverCapsule == null) {
        String readableOwnerAddress = StringUtil.createReadableString(receiverAddress);
        throw new ContractValidateException("Account[" + readableOwnerAddress + "] not exists");
      }

      if (dynamicStore.getAllowTvmConstantinople() == 1 && receiverCapsule.getType() == AccountType.Contract) {
        throw new ContractValidateException("Do not allow delegate resources to contract addresses");
      }
    }

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return any.unpack(FreezeBalanceContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

  private void delegateResource(byte[] ownerAddress, byte[] receiverAddress, boolean isBandwidth,
      long balance, long expireTime) {
    AccountStore accountStore = chainBaseManager.getAccountStore();
    DelegatedResourceStore delegatedResourceStore = chainBaseManager.getDelegatedResourceStore();
    DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore = chainBaseManager
        .getDelegatedResourceAccountIndexStore();
    byte[] key = DelegatedResourceCapsule.createDbKey(ownerAddress, receiverAddress);
    //modify DelegatedResourceStore
    DelegatedResourceCapsule delegatedResourceCapsule = delegatedResourceStore.get(key);
    if (delegatedResourceCapsule != null) {
      if (isBandwidth) {
        delegatedResourceCapsule.addFrozenBalanceForBandwidth(balance, expireTime);
      } else {
        delegatedResourceCapsule.addFrozenBalanceForEnergy(balance, expireTime);
      }
    } else {
      delegatedResourceCapsule = new DelegatedResourceCapsule(
          ByteString.copyFrom(ownerAddress),
          ByteString.copyFrom(receiverAddress));
      if (isBandwidth) {
        delegatedResourceCapsule.setFrozenBalanceForBandwidth(balance, expireTime);
      } else {
        delegatedResourceCapsule.setFrozenBalanceForEnergy(balance, expireTime);
      }

    }
    delegatedResourceStore.put(key, delegatedResourceCapsule);

    //modify DelegatedResourceAccountIndexStore
    {
      DelegatedResourceAccountIndexCapsule delegatedResourceAccountIndexCapsule = delegatedResourceAccountIndexStore
          .get(ownerAddress);
      if (delegatedResourceAccountIndexCapsule == null) {
        delegatedResourceAccountIndexCapsule = new DelegatedResourceAccountIndexCapsule(
            ByteString.copyFrom(ownerAddress));
      }
      List<ByteString> toAccountsList = delegatedResourceAccountIndexCapsule.getToAccountsList();
      if (!toAccountsList.contains(ByteString.copyFrom(receiverAddress))) {
        delegatedResourceAccountIndexCapsule.addToAccount(ByteString.copyFrom(receiverAddress));
      }
      delegatedResourceAccountIndexStore
          .put(ownerAddress, delegatedResourceAccountIndexCapsule);
    }

    {
      DelegatedResourceAccountIndexCapsule delegatedResourceAccountIndexCapsule = delegatedResourceAccountIndexStore
          .get(receiverAddress);
      if (delegatedResourceAccountIndexCapsule == null) {
        delegatedResourceAccountIndexCapsule = new DelegatedResourceAccountIndexCapsule(
            ByteString.copyFrom(receiverAddress));
      }
      List<ByteString> fromAccountsList = delegatedResourceAccountIndexCapsule
          .getFromAccountsList();
      if (!fromAccountsList.contains(ByteString.copyFrom(ownerAddress))) {
        delegatedResourceAccountIndexCapsule.addFromAccount(ByteString.copyFrom(ownerAddress));
      }
      delegatedResourceAccountIndexStore
          .put(receiverAddress, delegatedResourceAccountIndexCapsule);
    }

    //modify AccountStore
    AccountCapsule receiverCapsule = accountStore.get(receiverAddress);
    if (isBandwidth) {
      receiverCapsule.addAcquiredDelegatedFrozenBalanceForBandwidth(balance);
    } else {
      receiverCapsule.addAcquiredDelegatedFrozenBalanceForEnergy(balance);
    }

    accountStore.put(receiverCapsule.createDbKey(), receiverCapsule);
  }

}

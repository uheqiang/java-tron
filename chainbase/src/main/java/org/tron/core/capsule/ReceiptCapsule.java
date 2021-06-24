package org.tron.core.capsule;

import java.util.Objects;
import lombok.Getter;
import lombok.Setter;
import org.tron.common.utils.Commons;
import org.tron.common.utils.DBConfig;
import org.tron.common.utils.ForkUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.StringUtil;
import org.tron.core.Constant;
import org.tron.core.config.args.Parameter.ForkBlockVersionEnum;
import org.tron.core.db.EnergyProcessor;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.protos.Protocol.ResourceReceipt;
import org.tron.protos.Protocol.Transaction.Result.contractResult;

public class ReceiptCapsule {

  private ResourceReceipt receipt;
  @Getter
  @Setter
  private long multiSignFee;

  private Sha256Hash receiptAddress;

  public ReceiptCapsule(ResourceReceipt data, Sha256Hash receiptAddress) {
    this.receipt = data;
    this.receiptAddress = receiptAddress;
  }

  public ReceiptCapsule(Sha256Hash receiptAddress) {
    this.receipt = ResourceReceipt.newBuilder().build();
    this.receiptAddress = receiptAddress;
  }

  public static ResourceReceipt copyReceipt(ReceiptCapsule origin) {
    return origin.getReceipt().toBuilder().build();
  }

  private static boolean checkForEnergyLimit(DynamicPropertiesStore ds) {
    long blockNum = ds.getLatestBlockHeaderNumber();
    return blockNum >= DBConfig.getBlockNumForEneryLimit();
  }

  public ResourceReceipt getReceipt() {
    return this.receipt;
  }

  public void setReceipt(ResourceReceipt receipt) {
    this.receipt = receipt;
  }

  public Sha256Hash getReceiptAddress() {
    return this.receiptAddress;
  }

  public void addNetFee(long netFee) {
    this.receipt = this.receipt.toBuilder().setNetFee(getNetFee() + netFee).build();
  }

  public long getEnergyUsage() {
    return this.receipt.getEnergyUsage();
  }

  public void setEnergyUsage(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setEnergyUsage(energyUsage).build();
  }

  public long getEnergyFee() {
    return this.receipt.getEnergyFee();
  }

  public void setEnergyFee(long energyFee) {
    this.receipt = this.receipt.toBuilder().setEnergyFee(energyFee).build();
  }

  public long getOriginEnergyUsage() {
    return this.receipt.getOriginEnergyUsage();
  }

  public void setOriginEnergyUsage(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setOriginEnergyUsage(energyUsage).build();
  }

  public long getEnergyUsageTotal() {
    return this.receipt.getEnergyUsageTotal();
  }

  public void setEnergyUsageTotal(long energyUsage) {
    this.receipt = this.receipt.toBuilder().setEnergyUsageTotal(energyUsage).build();
  }

  public long getNetUsage() {
    return this.receipt.getNetUsage();
  }

  public void setNetUsage(long netUsage) {
    this.receipt = this.receipt.toBuilder().setNetUsage(netUsage).build();
  }

  public long getNetFee() {
    return this.receipt.getNetFee();
  }

  public void setNetFee(long netFee) {
    this.receipt = this.receipt.toBuilder().setNetFee(netFee).build();
  }

  /**
   * payEnergyBill pay receipt energy bill by energy processor.
   */
  public void payEnergyBill(DynamicPropertiesStore dynamicPropertiesStore,
                            AccountStore accountStore,
                            ForkUtils forkUtils,
                            AccountCapsule origin,
                            AccountCapsule caller,
                            long payments,
                            /*long originEnergyLimit,*/
                            EnergyProcessor energyProcessor,
                            long now) throws BalanceInsufficientException {
    if (receipt.getEnergyUsageTotal() <= 0) {
      return;
    }

    // AllowTvmConstantinople == 1
    /*if (Objects.isNull(origin) && dynamicPropertiesStore.getAllowTvmConstantinople() == 1) {
      payEnergyBill(dynamicPropertiesStore, accountStore, forkUtils, caller, receipt.getEnergyUsageTotal(), energyProcessor, now);
      return;
    }*/

    // 创建合约 或 调用合约时不支持委托支付
    if (caller.getAddress().equals(origin.getAddress())) {
      payEnergyBill(dynamicPropertiesStore, accountStore, forkUtils, caller, receipt.getEnergyUsageTotal(), energyProcessor, now);
    } else {
      /*long originUsage = Math.multiplyExact(receipt.getEnergyUsageTotal(), percent) / 100;
      originUsage = getOriginUsage(dynamicPropertiesStore, origin, originEnergyLimit, energyProcessor, originUsage);
      long callerUsage = receipt.getEnergyUsageTotal() - originUsage;
      energyProcessor.useEnergy(origin, originUsage, now);
      this.setOriginEnergyUsage(originUsage);
      payEnergyBill(dynamicPropertiesStore, accountStore, forkUtils, caller, callerUsage, energyProcessor, now);*/

      // 调用合约支持委托支付
      long callerUsage = receipt.getEnergyUsageTotal();
      long originEnergyLeft = energyProcessor.getAccountLeftEnergyFromFreeze(origin);
      // 大于或等于被委托者愿意支付的数量payments
      if (originEnergyLeft - payments >= 0){
        payEnergyBill(dynamicPropertiesStore, accountStore, forkUtils, origin, callerUsage, energyProcessor, now);
      } else {
        // 被委托者支付的数量即为当前余额值
        long mandatorPayments = originEnergyLeft;
        // 调用者需要自己支付的数量即为callerUsage - originEnergyLeft
        long callerPayments = callerUsage - mandatorPayments;
        long callerEnergyLeft = energyProcessor.getAccountLeftEnergyFromFreeze(caller);
        if (mandatorPayments >= 0 && callerEnergyLeft - callerPayments >= 0) {
          //被委托者支付
          payEnergyBill(dynamicPropertiesStore,
                  accountStore,
                  forkUtils,
                  origin,
                  mandatorPayments,
                  energyProcessor,
                  now);
          //调用者支付
          payEnergyBill(dynamicPropertiesStore,
                  accountStore,
                  forkUtils,
                  caller,
                  callerPayments,
                  energyProcessor,
                  now);
        } else {
          throw new BalanceInsufficientException(StringUtil.createReadableString(caller.createDbKey())
                  + " or " + StringUtil.createReadableString(origin.createDbKey())
                  + " insufficient balance");
        }
      }
    }
  }

  private long getOriginUsage(DynamicPropertiesStore dynamicPropertiesStore, AccountCapsule origin,
      long originEnergyLimit,
      EnergyProcessor energyProcessor, long originUsage) {

    if (checkForEnergyLimit(dynamicPropertiesStore)) {
      return Math.min(originUsage,
          Math.min(energyProcessor.getAccountLeftEnergyFromFreeze(origin), originEnergyLimit));
    }
    return Math.min(originUsage, energyProcessor.getAccountLeftEnergyFromFreeze(origin));
  }

  private void payEnergyBill(
      DynamicPropertiesStore dynamicPropertiesStore,
      AccountStore accountStore,
      ForkUtils forkUtils,
      AccountCapsule account,
      long usage,
      EnergyProcessor energyProcessor,
      long now) throws BalanceInsufficientException {

    long accountEnergyLeft = energyProcessor.getAccountLeftEnergyFromFreeze(account);
    if (accountEnergyLeft >= usage) {
      //energyProcessor.useEnergy(account, usage, now);
      //this.setEnergyUsage(usage);

      long energyUsage = account.getEnergyUsage();
      account.setEnergyUsage(energyUsage - usage);
      accountStore.put(account.getAddress().toByteArray(), account);
    } else {
      throw new BalanceInsufficientException(StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
    }
    /*else {
      energyProcessor.useEnergy(account, accountEnergyLeft, now);

      if (forkUtils.pass(ForkBlockVersionEnum.VERSION_3_6_5) && dynamicPropertiesStore.getAllowAdaptiveEnergy() == 1) {
        long blockEnergyUsage = dynamicPropertiesStore.getBlockEnergyUsage() + (usage - accountEnergyLeft);
        dynamicPropertiesStore.saveBlockEnergyUsage(blockEnergyUsage);
      }

      long sunPerEnergy = Constant.SUN_PER_ENERGY;
      long dynamicEnergyFee = dynamicPropertiesStore.getEnergyFee();
      if (dynamicEnergyFee > 0) {
        sunPerEnergy = dynamicEnergyFee;
      }
      long energyFee = (usage - accountEnergyLeft) * sunPerEnergy;
      this.setEnergyUsage(accountEnergyLeft);
      this.setEnergyFee(energyFee);
      long balance = account.getBalance();
      if (balance < energyFee) {
        throw new BalanceInsufficientException(StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
      }
      account.setBalance(balance - energyFee);

      //send to blackHole
      Commons.adjustBalance(accountStore, accountStore.getBlackhole().getAddress().toByteArray(), energyFee);
    }*/

    //accountStore.put(account.getAddress().toByteArray(), account);
  }

  public contractResult getResult() {
    return this.receipt.getResult();
  }

  public void setResult(contractResult success) {
    this.receipt = receipt.toBuilder().setResult(success).build();
  }
}

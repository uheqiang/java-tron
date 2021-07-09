package org.tron.core.db;

import com.google.common.primitives.Bytes;
import com.google.protobuf.ByteString;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.spongycastle.util.encoders.Hex;
import org.springframework.util.StringUtils;
import org.tron.common.crypto.SignUtils;
import org.tron.common.crypto.SignatureInterface;
import org.tron.common.runtime.InternalTransaction.TrxType;
import org.tron.common.runtime.ProgramResult;
import org.tron.common.runtime.Runtime;
import org.tron.common.runtime.vm.DataWord;
import org.tron.common.utils.DBConfig;
import org.tron.common.utils.ForkUtils;
import org.tron.common.utils.Sha256Hash;
import org.tron.common.utils.WalletUtil;
import org.tron.core.capsule.*;
import org.tron.core.exception.*;
import org.tron.core.store.*;
import org.tron.protos.Protocol.Transaction;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.contractResult;
import org.tron.protos.contract.SmartContractOuterClass.CreateSmartContract;
import org.tron.protos.contract.SmartContractOuterClass.DelegationPay;
import org.tron.protos.contract.SmartContractOuterClass.SmartContract.ABI;
import org.tron.protos.contract.SmartContractOuterClass.TriggerSmartContract;

import java.security.SignatureException;
import java.util.Arrays;
import java.util.Objects;

import static org.tron.common.runtime.InternalTransaction.TrxType.TRX_CONTRACT_CALL_TYPE;
import static org.tron.common.runtime.InternalTransaction.TrxType.TRX_CONTRACT_CREATION_TYPE;
import static org.tron.common.utils.DecodeUtil.addressPreFixByte;

@Slf4j(topic = "TransactionTrace")
public class TransactionTrace {

  private TransactionCapsule trx;

  private ReceiptCapsule receipt;

  private StoreFactory storeFactory;

  private DynamicPropertiesStore dynamicPropertiesStore;

  private ContractStore contractStore;

  private AccountStore accountStore;

  private CodeStore codeStore;

  private EnergyProcessor energyProcessor;

  private TrxType trxType;

  private long txStartTimeInMs;

  private Runtime runtime;

  private ForkUtils forkUtils;

  @Getter
  private TransactionContext transactionContext;
  @Getter
  @Setter
  private TimeResultType timeResultType = TimeResultType.NORMAL;

  public TransactionTrace(TransactionCapsule trx, StoreFactory storeFactory,
      Runtime runtime) {
    this.trx = trx;
    Transaction.Contract.ContractType contractType = this.trx.getInstance().getRawData()
        .getContract(0).getType();
    switch (contractType.getNumber()) {
      case ContractType.TriggerSmartContract_VALUE:
        trxType = TRX_CONTRACT_CALL_TYPE;
        break;
      case ContractType.CreateSmartContract_VALUE:
        trxType = TRX_CONTRACT_CREATION_TYPE;
        break;
      default:
        trxType = TrxType.TRX_PRECOMPILED_TYPE;
    }
    this.storeFactory = storeFactory;
    this.dynamicPropertiesStore = storeFactory.getChainBaseManager().getDynamicPropertiesStore();
    this.contractStore = storeFactory.getChainBaseManager().getContractStore();
    this.codeStore = storeFactory.getChainBaseManager().getCodeStore();
    this.accountStore = storeFactory.getChainBaseManager().getAccountStore();

    this.receipt = new ReceiptCapsule(Sha256Hash.ZERO_HASH);
    this.energyProcessor = new EnergyProcessor(dynamicPropertiesStore, accountStore);
    this.runtime = runtime;
    this.forkUtils = new ForkUtils();
    forkUtils.init(dynamicPropertiesStore);
  }

  public TransactionCapsule getTrx() {
    return trx;
  }

  private boolean needVM() {
    return this.trxType == TRX_CONTRACT_CALL_TYPE
        || this.trxType == TRX_CONTRACT_CREATION_TYPE;
  }

  public void init(BlockCapsule blockCap) {
    init(blockCap, false);
  }

  //pre transaction check
  public void init(BlockCapsule blockCap, boolean eventPluginLoaded) {
    txStartTimeInMs = System.currentTimeMillis();
    transactionContext = new TransactionContext(blockCap, trx, storeFactory, false,
        eventPluginLoaded);
  }

  public void checkIsConstant() throws ContractValidateException, VMIllegalException {
    if (dynamicPropertiesStore.getAllowTvmConstantinople() == 1) {
      return;
    }
    TriggerSmartContract triggerContractFromTransaction = ContractCapsule
        .getTriggerContractFromTransaction(this.getTrx().getInstance());
    if (TRX_CONTRACT_CALL_TYPE == this.trxType) {
      ContractCapsule contract = contractStore
          .get(triggerContractFromTransaction.getContractAddress().toByteArray());
      if (contract == null) {
        logger.info("contract: {} is not in contract store", WalletUtil
            .encode58Check(triggerContractFromTransaction.getContractAddress().toByteArray()));
        throw new ContractValidateException("contract: " + WalletUtil
            .encode58Check(triggerContractFromTransaction.getContractAddress().toByteArray())
            + " is not in contract store");
      }
      ABI abi = contract.getInstance().getAbi();
      if (WalletUtil.isConstant(abi, triggerContractFromTransaction)) {
        throw new VMIllegalException("cannot call constant method");
      }
    }
  }

  //set bill
  public void setBill(long energyUsage) {
    if (energyUsage < 0) {
      energyUsage = 0L;
    }
    receipt.setEnergyUsageTotal(energyUsage);
  }

  //set net bill
  public void setNetBill(long netUsage, long netFee) {
    receipt.setNetUsage(netUsage);
    receipt.setNetFee(netFee);
  }

  public void addNetBill(long netFee) {
    receipt.addNetFee(netFee);
  }

  public void exec() throws ContractExeException, ContractValidateException {
    /*  VM execute  */
    runtime.execute(transactionContext);
    //todo 在这里可以设置固定手续费额度
    setBill(transactionContext.getProgramResult().getEnergyUsed());

    if (TrxType.TRX_PRECOMPILED_TYPE != trxType) {
      if (contractResult.OUT_OF_TIME.equals(receipt.getResult())) {
        setTimeResultType(TimeResultType.OUT_OF_TIME);
      } else if (System.currentTimeMillis() - txStartTimeInMs > DBConfig.getLongRunningTime()) {
        setTimeResultType(TimeResultType.LONG_RUNNING);
      }
    }
  }

  public void finalization() throws ContractExeException {
    try {
      pay();
    } catch (BalanceInsufficientException | ValidateSignatureException e) {
      throw new ContractExeException(e.getMessage());
    }
    if (StringUtils.isEmpty(transactionContext.getProgramResult().getRuntimeError())) {
      for (DataWord contract : transactionContext.getProgramResult().getDeleteAccounts()) {
        deleteContract(convertToTronAddress((contract.getLast20Bytes())));
      }
    }
  }

  /**
   * pay actually bill
   */
  public void pay() throws BalanceInsufficientException, ValidateSignatureException {
    //被委托支付者
    byte[] delegationAccount;
    //调用合约者
    byte[] callerAccount;
    DelegationPay delegationPay;
    long limitPerTransaction;
    long energyPayLimit;
    //交易待支付的额度
    long payments;
    switch (trxType) {
      //创建合约，有委托支付机制
      case TRX_CONTRACT_CREATION_TYPE:
        CreateSmartContract contract = ContractCapsule.getSmartContractFromTransaction(trx.getInstance());
        //验证支付的能量是否不小于链设置的最小值
        payments = contract.getCallEnergyValue();
        energyPayLimit = dynamicPropertiesStore.getCreateContractFee();
        if (payments < energyPayLimit) {
          throw new BalanceInsufficientException("Payment energy is too low");
        }
        delegationPay = contract.getDelegationPay();
        if (delegationPay != null && delegationPay.getSupport()) {
          delegationAccount = delegationPay.getSponsor().toByteArray();
          ByteString signByDelegation = contract.getDelegationPaySignature();
          validateDelegationSignature(delegationAccount,signByDelegation,delegationPay);
        }
        callerAccount = TransactionCapsule.getOwner(trx.getInstance().getRawData().getContract(0));
        break;
      //调用合约，可以设置委托支付机制
      case TRX_CONTRACT_CALL_TYPE:
        TriggerSmartContract callContract = ContractCapsule.getTriggerContractFromTransaction(trx.getInstance());
        //验证支付的能量是否不小于链设置的最小值
        payments = callContract.getCallEnergyValue();
        energyPayLimit = dynamicPropertiesStore.getCreateContractFee();
        if (payments < energyPayLimit) {
          throw new BalanceInsufficientException("Payment energy is too low");
        }
        delegationPay = callContract.getDelegationPay();
        if (delegationPay != null && delegationPay.getSupport()) {
          delegationAccount = delegationPay.getSponsor().toByteArray();
          ByteString signByDelegation = callContract.getDelegationPaySignature();
          validateDelegationSignature(delegationAccount,signByDelegation,delegationPay);
        }
        callerAccount = callContract.getOwnerAddress().toByteArray();
        break;
      default:
        return;
    }

    if (delegationPay.getSupport()) {
      delegationAccount = delegationPay.getSponsor().toByteArray();
      limitPerTransaction = delegationPay.getSponsorlimitpertransaction();
      if (payments <= limitPerTransaction){
        //由delegation支付全部
        callerAccount = delegationAccount;
      }
      //由二人共同支付
    } else {
      //由caller支付全部
      delegationAccount = callerAccount;
    }

    // originAccount Percent = 30%
    AccountCapsule agent = accountStore.get(delegationAccount);
    AccountCapsule caller = accountStore.get(callerAccount);
    receipt.payEnergyBill(
            dynamicPropertiesStore,
            accountStore,
            forkUtils,
            agent,
            caller,
            payments,
            energyProcessor,
            EnergyProcessor.getHeadSlot(dynamicPropertiesStore));
  }

  public boolean checkNeedRetry() {
    if (!needVM()) {
      return false;
    }
    return trx.getContractRet() != contractResult.OUT_OF_TIME && receipt.getResult()
        == contractResult.OUT_OF_TIME;
  }

  public void check() throws ReceiptCheckErrException {
    if (!needVM()) {
      return;
    }
    if (Objects.isNull(trx.getContractRet())) {
      throw new ReceiptCheckErrException("null resultCode");
    }
    if (!trx.getContractRet().equals(receipt.getResult())) {
      logger.info(
          "this tx id: {}, the resultCode in received block: {}, the resultCode in self: {}",
          Hex.toHexString(trx.getTransactionId().getBytes()), trx.getContractRet(),
          receipt.getResult());
      throw new ReceiptCheckErrException("Different resultCode");
    }
  }

  public ReceiptCapsule getReceipt() {
    return receipt;
  }

  public void setResult() {
    if (!needVM()) {
      return;
    }
    receipt.setResult(transactionContext.getProgramResult().getResultCode());
  }

  public String getRuntimeError() {
    return transactionContext.getProgramResult().getRuntimeError();
  }

  public ProgramResult getRuntimeResult() {
    return transactionContext.getProgramResult();
  }

  public Runtime getRuntime() {
    return runtime;
  }

  public void deleteContract(byte[] address) {
    codeStore.delete(address);
    accountStore.delete(address);
    contractStore.delete(address);
  }

  private byte[] convertToTronAddress(byte[] address) {
    if (address.length == 20) {
      byte[] newAddress = new byte[21];
      byte[] temp = new byte[]{addressPreFixByte};
      System.arraycopy(temp, 0, newAddress, 0, temp.length);
      System.arraycopy(address, 0, newAddress, temp.length, address.length);
      address = newAddress;
    }
    return address;
  }


  public enum TimeResultType {
    NORMAL,
    LONG_RUNNING,
    OUT_OF_TIME
  }

  // 验证被委托支付者的签名
  private void validateDelegationSignature(byte[] delegationAccount, ByteString sign,
                                              DelegationPay delegationPay) throws ValidateSignatureException {
    try {
      Sha256Hash hash = Sha256Hash.of(DBConfig.isECKeyCryptoEngine(), delegationPay.toByteArray());
      byte[] address = SignUtils.signatureToAddress(hash.getBytes(),
              getBase64FromByteString(sign), DBConfig.isECKeyCryptoEngine());
      if (!Arrays.equals(delegationAccount, address)) {
        throw new ValidateSignatureException("delegation sig verify error");
      }
    } catch (SignatureException e) {
      throw new ValidateSignatureException(e.getMessage());
    }
  }

  private static String getBase64FromByteString(ByteString sign) {
    byte[] r = sign.substring(0, 32).toByteArray();
    byte[] s = sign.substring(32, 64).toByteArray();
    byte v = sign.byteAt(64);
    if (v < 27) {
      v += 27;
    }
    SignatureInterface signature = SignUtils.fromComponents(r, s, v, DBConfig.isECKeyCryptoEngine());
    return signature.toBase64();
  }
}

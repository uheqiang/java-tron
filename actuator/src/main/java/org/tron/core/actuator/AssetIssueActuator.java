/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.tron.core.actuator;

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.tron.common.utils.Commons;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.AssetIssueCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.core.store.AccountStore;
import org.tron.core.store.AssetIssueStore;
import org.tron.core.store.AssetIssueV2Store;
import org.tron.core.store.DynamicPropertiesStore;
import org.tron.core.utils.TransactionUtil;
import org.tron.protos.Protocol.Account.Frozen;
import org.tron.protos.Protocol.Transaction.Contract.ContractType;
import org.tron.protos.Protocol.Transaction.Result.code;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract;
import org.tron.protos.contract.AssetIssueContractOuterClass.AssetIssueContract.FrozenSupply;

@Slf4j(topic = "actuator")
public class AssetIssueActuator extends AbstractActuator {

  public AssetIssueActuator() {
    super(ContractType.AssetIssueContract, AssetIssueContract.class);
  }

  @Override
  public boolean execute(Object result) throws ContractExeException {
    TransactionResultCapsule ret = (TransactionResultCapsule)result;
    if (Objects.isNull(ret)){
      throw new RuntimeException("TransactionResultCapsule is null");
    }

    // default 0
    long fee = calcFee();
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    AssetIssueStore assetIssueStore = chainBaseManager.getAssetIssueStore();
    AssetIssueV2Store assetIssueV2Store = chainBaseManager.getAssetIssueV2Store();
    AccountStore accountStore = chainBaseManager.getAccountStore();
    try {
      AssetIssueContract assetIssueContract = any.unpack(AssetIssueContract.class);
      byte[] ownerAddress = assetIssueContract.getOwnerAddress().toByteArray();
      AssetIssueCapsule assetIssueCapsule = new AssetIssueCapsule(assetIssueContract);
      AssetIssueCapsule assetIssueCapsuleV2 = new AssetIssueCapsule(assetIssueContract);
      long tokenIdNum = dynamicStore.getTokenIdNum();
      tokenIdNum++;
      assetIssueCapsule.setId(Long.toString(tokenIdNum));
      assetIssueCapsuleV2.setId(Long.toString(tokenIdNum));
      dynamicStore.saveTokenIdNum(tokenIdNum);

      if (dynamicStore.getAllowSameTokenName() == 0) {
        assetIssueCapsuleV2.setPrecision(0);
        //key is asset name
        assetIssueStore.put(assetIssueCapsule.createDbKey(), assetIssueCapsule);
        //key is asset Id
        assetIssueV2Store.put(assetIssueCapsuleV2.createDbV2Key(), assetIssueCapsuleV2);
      } else {
        assetIssueV2Store.put(assetIssueCapsuleV2.createDbV2Key(), assetIssueCapsuleV2);
      }

      //Commons.adjustBalance(accountStore, ownerAddress, -fee);
      //send to blackhole
      //Commons.adjustBalance(accountStore, accountStore.getBlackhole().getAddress().toByteArray(), fee);

      AccountCapsule accountCapsule = accountStore.get(ownerAddress);
      long remainSupply = assetIssueContract.getTotalSupply();
      /*List<FrozenSupply> frozenSupplyList = assetIssueContract.getFrozenSupplyList();
      Iterator<FrozenSupply> iterator = frozenSupplyList.iterator();
      long remainSupply = assetIssueContract.getTotalSupply();
      List<Frozen> frozenList = new ArrayList<>();
      long startTime = assetIssueContract.getStartTime();

      while (iterator.hasNext()) {
        FrozenSupply next = iterator.next();
        long expireTime = startTime + next.getFrozenDays() * 86_400_000;
        Frozen newFrozen = Frozen.newBuilder()
            .setFrozenBalance(next.getFrozenAmount())
            .setExpireTime(expireTime)
            .build();
        frozenList.add(newFrozen);
        remainSupply -= next.getFrozenAmount();
      }*/

      //ALLOW_SAME_TOKEN_NAME == 0，表示不运行创建相同名称的Token
      if (dynamicStore.getAllowSameTokenName() == 0) {
        accountCapsule.addAsset(assetIssueCapsule.createDbKey(), remainSupply);
      }
      accountCapsule.setAssetIssuedName(assetIssueCapsule.createDbKey());
      accountCapsule.setAssetIssuedID(assetIssueCapsule.createDbV2Key());
      accountCapsule.addAssetV2(assetIssueCapsuleV2.createDbV2Key(), remainSupply);
      //accountCapsule.setInstance(accountCapsule.getInstance().toBuilder().addAllFrozenSupply(frozenList).build());
      accountCapsule.setInstance(accountCapsule.getInstance());

      accountStore.put(ownerAddress, accountCapsule);

      ret.setAssetIssueID(Long.toString(tokenIdNum));
      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException /*| BalanceInsufficientException*/ | ArithmeticException e) {
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
      throw new ContractValidateException("No account store or dynamic store!");
    }
    DynamicPropertiesStore dynamicStore = chainBaseManager.getDynamicPropertiesStore();
    AssetIssueStore assetIssueStore = chainBaseManager.getAssetIssueStore();
    AccountStore accountStore = chainBaseManager.getAccountStore();
    if (!this.any.is(AssetIssueContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [AssetIssueContract],real type[" + any.getClass() + "]");
    }

    final AssetIssueContract assetIssueContract;
    try {
      assetIssueContract = this.any.unpack(AssetIssueContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    byte[] ownerAddress = assetIssueContract.getOwnerAddress().toByteArray();
    if (!Commons.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid ownerAddress");
    }

    if (!TransactionUtil.validAssetName(assetIssueContract.getName().toByteArray())) {
      throw new ContractValidateException("Invalid assetName");
    }

    /*int precision = assetIssueContract.getPrecision();
    if (precision != 0 && dynamicStore.getAllowSameTokenName() != 0) {
      if (precision < 0 || precision > 6) {
        throw new ContractValidateException("precision cannot exceed 6");
      }
      if (precision != 1) {
        throw new ContractValidateException("precision should be 1");
      }
    }*/

    //abr是token的简称
    if ((!assetIssueContract.getAbbr().isEmpty()) && !TransactionUtil
        .validAssetName(assetIssueContract.getAbbr().toByteArray())) {
      throw new ContractValidateException("Invalid abbreviation for token");
    }

    //去掉发行官网和token说明
    /*if (!TransactionUtil.validUrl(assetIssueContract.getUrl().toByteArray())) {
      throw new ContractValidateException("Invalid url");
    }
    if (!TransactionUtil.validAssetDescription(assetIssueContract.getDescription().toByteArray())) {
      throw new ContractValidateException("Invalid description");
    }*/

    //去掉起止时间
    /*if (assetIssueContract.getStartTime() == 0) {
      throw new ContractValidateException("Start time should be not empty");
    }
    if (assetIssueContract.getEndTime() == 0) {
      throw new ContractValidateException("End time should be not empty");
    }
    if (assetIssueContract.getEndTime() <= assetIssueContract.getStartTime()) {
      throw new ContractValidateException("End time should be greater than start time");
    }
    if (assetIssueContract.getStartTime() <= dynamicStore.getLatestBlockHeaderTimestamp()) {
      throw new ContractValidateException("Start time should be greater than HeadBlockTime");
    }*/

    if (dynamicStore.getAllowSameTokenName() == 0
        && assetIssueStore.get(assetIssueContract.getName().toByteArray()) != null) {
      throw new ContractValidateException("Token exists");
    }

    if (assetIssueContract.getTotalSupply() <= 0) {
      throw new ContractValidateException("TotalSupply must greater than 0!");
    }

    //与原生币不支持兑换
    /*if (assetIssueContract.getTrxNum() <= 0) {
      throw new ContractValidateException("TrxNum must greater than 0!");
    }
    if (assetIssueContract.getNum() <= 0) {
      throw new ContractValidateException("Num must greater than 0!");
    }*/

    //去掉带宽
    /*if (assetIssueContract.getPublicFreeAssetNetUsage() != 0) {
      throw new ContractValidateException("PublicFreeAssetNetUsage must be 0!");
    }
    if (assetIssueContract.getFrozenSupplyCount()
        > dynamicStore.getMaxFrozenSupplyNumber()) {
      throw new ContractValidateException("Frozen supply list length is too long");
    }
    if (assetIssueContract.getFreeAssetNetLimit() < 0
        || assetIssueContract.getFreeAssetNetLimit() >=
        dynamicStore.getOneDayNetLimit()) {
      throw new ContractValidateException("Invalid FreeAssetNetLimit");
    }
    if (assetIssueContract.getPublicFreeAssetNetLimit() < 0
        || assetIssueContract.getPublicFreeAssetNetLimit() >=
        dynamicStore.getOneDayNetLimit()) {
      throw new ContractValidateException("Invalid PublicFreeAssetNetLimit");
    }*/

    //不冻结
    /*long remainSupply = assetIssueContract.getTotalSupply();
    long minFrozenSupplyTime = dynamicStore.getMinFrozenSupplyTime();
    long maxFrozenSupplyTime = dynamicStore.getMaxFrozenSupplyTime();
    List<FrozenSupply> frozenList = assetIssueContract.getFrozenSupplyList();
    Iterator<FrozenSupply> iterator = frozenList.iterator();

    while (iterator.hasNext()) {
      FrozenSupply next = iterator.next();
      if (next.getFrozenAmount() <= 0) {
        throw new ContractValidateException("Frozen supply must be greater than 0!");
      }
      if (next.getFrozenAmount() > remainSupply) {
        throw new ContractValidateException("Frozen supply cannot exceed total supply");
      }
      if (!(next.getFrozenDays() >= minFrozenSupplyTime
          && next.getFrozenDays() <= maxFrozenSupplyTime)) {
        throw new ContractValidateException(
            "frozenDuration must be less than " + maxFrozenSupplyTime + " days "
                + "and more than " + minFrozenSupplyTime + " days");
      }
      remainSupply -= next.getFrozenAmount();
    }*/

    AccountCapsule accountCapsule = accountStore.get(ownerAddress);
    if (accountCapsule == null) {
      throw new ContractValidateException("Account not exists");
    }

    if (!accountCapsule.getAssetIssuedName().isEmpty()) {
      throw new ContractValidateException("An account can only issue one asset");
    }

    //去掉free
    /*if (accountCapsule.getBalance() < calcFee()) {
      throw new ContractValidateException("No enough balance for fee!");
    }*/

    return true;
  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return any.unpack(AssetIssueContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;//chainBaseManager.getDynamicPropertiesStore().getAssetIssueFee();
  }

  public long calcUsage() {
    return 0;
  }
}

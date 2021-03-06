package org.tron.core.actuator;

import static junit.framework.TestCase.fail;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import java.io.File;
import lombok.extern.slf4j.Slf4j;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.tron.common.utils.ByteArray;
import org.tron.common.utils.FileUtil;
import org.tron.core.Constant;
import org.tron.core.Wallet;
import org.tron.core.capsule.AccountCapsule;
import org.tron.core.capsule.TransactionResultCapsule;
import org.tron.core.capsule.WitnessCapsule;
import org.tron.core.config.DefaultConfig;
import org.tron.core.config.args.Args;
import org.tron.core.db.Manager;
import org.tron.core.exception.BalanceInsufficientException;
import org.tron.core.exception.ContractExeException;
import org.tron.core.exception.ContractValidateException;
import org.tron.protos.Contract;
import org.tron.protos.Protocol.AccountType;
import org.tron.protos.Protocol.Transaction.Result.code;

@Slf4j
public class WithdrawBalanceActuatorTest {

  private static Manager dbManager;
  private static final String dbPath = "output_withdraw_balance_test";
  private static AnnotationConfigApplicationContext context;
  private static final String OWNER_ADDRESS;
  private static final String OWNER_ADDRESS_INVALIATE = "aaaa";
  private static final String OWNER_ACCOUNT_INVALIATE;
  private static final long initBalance = 10_000_000_000L;
  private static final long allowance = 32_000_000L;

  static {
    Args.setParam(new String[]{"--output-directory", dbPath}, Constant.TEST_CONF);
    context = new AnnotationConfigApplicationContext(DefaultConfig.class);
    OWNER_ADDRESS = Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a1abc";
    OWNER_ACCOUNT_INVALIATE =
        Wallet.getAddressPreFixString() + "548794500882809695a8a687866e76d4271a3456";
  }

  /**
   * Init data.
   */
  @BeforeClass
  public static void init() {
    dbManager = context.getBean(Manager.class);
    //    Args.setParam(new String[]{"--output-directory", dbPath},
    //        "config-junit.conf");
    //    dbManager = new Manager();
    //    dbManager.init();
  }

  /**
   * Release resources.
   */
  @AfterClass
  public static void destroy() {
    Args.clearParam();
    if (FileUtil.deleteDir(new File(dbPath))) {
      logger.info("Release resources successful.");
    } else {
      logger.info("Release resources failure.");
    }
    context.destroy();
  }

  /**
   * create temp Capsule test need.
   */
  @Before
  public void createAccountCapsule() {
    AccountCapsule ownerCapsule =
        new AccountCapsule(
            ByteString.copyFromUtf8("owner"),
            ByteString.copyFrom(ByteArray.fromHexString(OWNER_ADDRESS)),
            AccountType.Normal,
            initBalance);
    dbManager.getAccountStore().put(ownerCapsule.createDbKey(), ownerCapsule);
  }

  private Any getContract(String ownerAddress) {
    return Any.pack(
        Contract.WithdrawBalanceContract.newBuilder()
            .setOwnerAddress(ByteString.copyFrom(ByteArray.fromHexString(ownerAddress)))
            .build());
  }

  @Test
  public void testWithdrawBalance() {
    long now = System.currentTimeMillis();
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(now);
    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    try {
      dbManager.adjustAllowance(address, allowance);
    } catch (BalanceInsufficientException e) {
      fail("BalanceInsufficientException");
    }
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
    Assert.assertEquals(accountCapsule.getAllowance(), allowance);
    Assert.assertEquals(accountCapsule.getLatestWithdrawTime(), 0);

    WitnessCapsule witnessCapsule = new WitnessCapsule(ByteString.copyFrom(address),
        100, "http://baidu.com");
    dbManager.getWitnessStore().put(address, witnessCapsule);

    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ADDRESS), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      Assert.assertEquals(ret.getInstance().getRet(), code.SUCESS);
      AccountCapsule owner =
          dbManager.getAccountStore().get(ByteArray.fromHexString(OWNER_ADDRESS));

      Assert.assertEquals(owner.getBalance(), initBalance + allowance);
      Assert.assertEquals(owner.getAllowance(), 0);
      Assert.assertNotEquals(owner.getLatestWithdrawTime(), 0);
    } catch (ContractValidateException e) {
      Assert.assertFalse(e instanceof ContractValidateException);
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }


  @Test
  public void invalidOwnerAddress() {
    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ADDRESS_INVALIATE), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      fail("cannot run here.");

    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);

      Assert.assertEquals("Invalidate address", e.getMessage());

    } catch (ContractExeException e) {
      Assert.assertTrue(e instanceof ContractExeException);
    }

  }

  @Test
  public void invalidOwnerAccount() {
    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ACCOUNT_INVALIATE), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      fail("cannot run here.");
    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);
      Assert.assertEquals("Account[" + OWNER_ACCOUNT_INVALIATE + "] not exists",
          e.getMessage());
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }

  @Test
  public void notWitness() {
//    long now = System.currentTimeMillis();
//    AccountCapsule accountCapsule = dbManager.getAccountStore()
//        .get(ByteArray.fromHexString(OWNER_ADDRESS));
//    accountCapsule.setFrozen(1_000_000_000L, now);
//    dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ADDRESS), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      fail("cannot run here.");

    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);
      Assert.assertEquals("Account[" + OWNER_ADDRESS + "] is not a witnessAccount",
          e.getMessage());
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }

  @Test
  public void noAllowance() {
    long now = System.currentTimeMillis();
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(now);

    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);

    AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
    Assert.assertEquals(accountCapsule.getAllowance(), 0);

    WitnessCapsule witnessCapsule = new WitnessCapsule(ByteString.copyFrom(address),
        100, "http://baidu.com");
    dbManager.getWitnessStore().put(address, witnessCapsule);

    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ADDRESS), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      fail("cannot run here.");

    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);
      Assert.assertEquals("witnessAccount does not have any allowance", e.getMessage());
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }

  @Test
  public void notTimeToWithdraw() {
    long now = System.currentTimeMillis();
    dbManager.getDynamicPropertiesStore().saveLatestBlockHeaderTimestamp(now);

    byte[] address = ByteArray.fromHexString(OWNER_ADDRESS);
    try {
      dbManager.adjustAllowance(address, allowance);
    } catch (BalanceInsufficientException e) {
      fail("BalanceInsufficientException");
    }
    AccountCapsule accountCapsule = dbManager.getAccountStore().get(address);
    accountCapsule.setLatestWithdrawTime(now);
    Assert.assertEquals(accountCapsule.getAllowance(), allowance);
    Assert.assertEquals(accountCapsule.getLatestWithdrawTime(), now);

    WitnessCapsule witnessCapsule = new WitnessCapsule(ByteString.copyFrom(address),
        100, "http://baidu.com");

    dbManager.getAccountStore().put(address, accountCapsule);
    dbManager.getWitnessStore().put(address, witnessCapsule);

    WithdrawBalanceActuator actuator = new WithdrawBalanceActuator(
        getContract(OWNER_ADDRESS), dbManager);
    TransactionResultCapsule ret = new TransactionResultCapsule();

    try {
      actuator.validate();
      actuator.execute(ret);
      fail("cannot run here.");

    } catch (ContractValidateException e) {
      Assert.assertTrue(e instanceof ContractValidateException);
      Assert.assertEquals("The last withdraw time is "
          + now + ",less than 24 hours", e.getMessage());
    } catch (ContractExeException e) {
      Assert.assertFalse(e instanceof ContractExeException);
    }
  }

}


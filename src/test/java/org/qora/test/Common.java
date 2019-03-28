package org.qora.test;

import static org.junit.Assert.*;

import java.net.URL;
import java.security.Security;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.bitcoinj.core.Base58;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.jsse.provider.BouncyCastleJsseProvider;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.qora.account.PrivateKeyAccount;
import org.qora.api.resource.TransactionsResource.ConfirmationStatus;
import org.qora.block.BlockChain;
import org.qora.block.BlockGenerator;
import org.qora.data.transaction.TransactionData;
import org.qora.repository.DataException;
import org.qora.repository.Repository;
import org.qora.repository.RepositoryFactory;
import org.qora.repository.RepositoryManager;
import org.qora.repository.hsqldb.HSQLDBRepositoryFactory;
import org.qora.settings.Settings;
import org.qora.test.common.TestAccount;
import org.qora.transaction.Transaction;
import org.qora.transaction.Transaction.ValidationResult;

public class Common {

	public static final String testConnectionUrl = "jdbc:hsqldb:mem:testdb";
	// public static final String testConnectionUrl = "jdbc:hsqldb:file:testdb/blockchain;create=true";

	public static final String testSettingsFilename = "test-settings.json";

	public static final byte[] v2testPrivateKey = Base58.decode("A9MNsATgQgruBUjxy2rjWY36Yf19uRioKZbiLFT2P7c6");
	public static final byte[] v2testPublicKey = Base58.decode("2tiMr5LTpaWCgbRvkPK8TFd7k63DyHJMMFFsz9uBf1ZP");
	public static final String v2testAddress = "QgV4s3xnzLhVBEJxcYui4u4q11yhUHsd9v";

	static {
		// This must go before any calls to LogManager/Logger
		System.setProperty("java.util.logging.manager", "org.apache.logging.log4j.jul.LogManager");

		Security.insertProviderAt(new BouncyCastleProvider(), 0);
		Security.insertProviderAt(new BouncyCastleJsseProvider(), 1);

		// Load/check settings, which potentially sets up blockchain config, etc.
		URL testSettingsUrl = Common.class.getClassLoader().getResource(testSettingsFilename);
		assertNotNull("Test settings JSON file not found", testSettingsUrl);
		Settings.fileInstance(testSettingsUrl.getPath());
	}

	public static Map<String, TransactionData> lastTransactionByAddress;
	public static Map<String, TestAccount> testAccountsByName = new HashMap<>();
	static {
		testAccountsByName.put("main", new TestAccount("A9MNsATgQgruBUjxy2rjWY36Yf19uRioKZbiLFT2P7c6"));
		testAccountsByName.put("dummy", new TestAccount("AdTd9SUEYSdTW8mgK3Gu72K97bCHGdUwi2VvLNjUohot"));
	}

	public static PrivateKeyAccount getTestAccount(Repository repository, String name) {
		return new PrivateKeyAccount(repository, testAccountsByName.get(name).getSeed());
	}

	public static void resetBlockchain() throws DataException {
		BlockChain.validate();
		lastTransactionByAddress = new HashMap<>();

		try (Repository repository = RepositoryManager.getRepository()) {
			for (TestAccount account : testAccountsByName.values()) {
				List<byte[]> signatures = repository.getTransactionRepository().getSignaturesMatchingCriteria(null, null, null, account.getAddress(), ConfirmationStatus.BOTH, 1, null, true);
				assertFalse("Test account should have existing transaction", signatures.isEmpty());

				TransactionData transactionData = repository.getTransactionRepository().fromSignature(signatures.get(0));
				lastTransactionByAddress.put(account.getAddress(), transactionData);
			}
		}
	}

	public static void signAndForge(Repository repository, TransactionData transactionData, PrivateKeyAccount signingAccount) throws DataException {
		Transaction transaction = Transaction.fromData(repository, transactionData);
		transaction.sign(signingAccount);

		// Add to unconfirmed
		assertTrue("Transaction's signature should be valid", transaction.isSignatureValid());

		ValidationResult result = transaction.isValidUnconfirmed();
		assertEquals("Transaction invalid", ValidationResult.OK, result);

		repository.getTransactionRepository().save(transactionData);
		repository.getTransactionRepository().unconfirmTransaction(transactionData);
		repository.saveChanges();

		// Generate block
		BlockGenerator.generateTestingBlock(repository, signingAccount);
	}

	@BeforeClass
	public static void setRepository() throws DataException {
		RepositoryFactory repositoryFactory = new HSQLDBRepositoryFactory(testConnectionUrl);
		RepositoryManager.setRepositoryFactory(repositoryFactory);
	}

	@AfterClass
	public static void closeRepository() throws DataException {
		RepositoryManager.closeRepositoryFactory();
	}

	public static void assertEmptyBlockchain(Repository repository) throws DataException {
		assertEquals("Blockchain should be empty for this test", 0, repository.getBlockRepository().getBlockchainHeight());
	}

}

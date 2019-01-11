package org.qora.transaction;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.qora.account.Account;
import org.qora.account.PublicKeyAccount;
import org.qora.asset.Asset;
import org.qora.data.transaction.LeaveGroupTransactionData;
import org.qora.data.group.GroupData;
import org.qora.data.transaction.TransactionData;
import org.qora.group.Group;
import org.qora.repository.DataException;
import org.qora.repository.Repository;

import com.google.common.base.Utf8;

public class LeaveGroupTransaction extends Transaction {

	// Properties
	private LeaveGroupTransactionData leaveGroupTransactionData;

	// Constructors

	public LeaveGroupTransaction(Repository repository, TransactionData transactionData) {
		super(repository, transactionData);

		this.leaveGroupTransactionData = (LeaveGroupTransactionData) this.transactionData;
	}

	// More information

	@Override
	public List<Account> getRecipientAccounts() throws DataException {
		return Collections.emptyList();
	}

	@Override
	public boolean isInvolved(Account account) throws DataException {
		String address = account.getAddress();

		if (address.equals(this.getLeaver().getAddress()))
			return true;

		return false;
	}

	@Override
	public BigDecimal getAmount(Account account) throws DataException {
		String address = account.getAddress();
		BigDecimal amount = BigDecimal.ZERO.setScale(8);

		if (address.equals(this.getLeaver().getAddress()))
			amount = amount.subtract(this.transactionData.getFee());

		return amount;
	}

	// Navigation

	public Account getLeaver() throws DataException {
		return new PublicKeyAccount(this.repository, this.leaveGroupTransactionData.getLeaverPublicKey());
	}

	// Processing

	@Override
	public ValidationResult isValid() throws DataException {
		// Check group name size bounds
		int groupNameLength = Utf8.encodedLength(leaveGroupTransactionData.getGroupName());
		if (groupNameLength < 1 || groupNameLength > Group.MAX_NAME_SIZE)
			return ValidationResult.INVALID_NAME_LENGTH;

		// Check group name is lowercase
		if (!leaveGroupTransactionData.getGroupName().equals(leaveGroupTransactionData.getGroupName().toLowerCase()))
			return ValidationResult.NAME_NOT_LOWER_CASE;

		GroupData groupData = this.repository.getGroupRepository().fromGroupName(leaveGroupTransactionData.getGroupName());

		// Check group exists
		if (groupData == null)
			return ValidationResult.GROUP_DOES_NOT_EXIST;

		Account leaver = getLeaver();

		// Can't leave if group owner
		if (leaver.getAddress().equals(groupData.getOwner()))
			return ValidationResult.GROUP_OWNER_CANNOT_LEAVE;

		if (!this.repository.getGroupRepository().memberExists(leaveGroupTransactionData.getGroupName(), leaver.getAddress()))
			return ValidationResult.NOT_GROUP_MEMBER;

		// Check fee is positive
		if (leaveGroupTransactionData.getFee().compareTo(BigDecimal.ZERO) <= 0)
			return ValidationResult.NEGATIVE_FEE;

		if (!Arrays.equals(leaver.getLastReference(), leaveGroupTransactionData.getReference()))
			return ValidationResult.INVALID_REFERENCE;

		// Check creator has enough funds
		if (leaver.getConfirmedBalance(Asset.QORA).compareTo(leaveGroupTransactionData.getFee()) < 0)
			return ValidationResult.NO_BALANCE;

		return ValidationResult.OK;
	}

	@Override
	public void process() throws DataException {
		// Update Group Membership
		Group group = new Group(this.repository, leaveGroupTransactionData.getGroupName());
		group.leave(leaveGroupTransactionData);

		// Save this transaction with updated member/admin references to transactions that can help restore state
		this.repository.getTransactionRepository().save(leaveGroupTransactionData);

		// Update leaver's balance
		Account leaver = getLeaver();
		leaver.setConfirmedBalance(Asset.QORA, leaver.getConfirmedBalance(Asset.QORA).subtract(leaveGroupTransactionData.getFee()));

		// Update leaver's reference
		leaver.setLastReference(leaveGroupTransactionData.getSignature());
	}

	@Override
	public void orphan() throws DataException {
		// Revert group membership
		Group group = new Group(this.repository, leaveGroupTransactionData.getGroupName());
		group.unleave(leaveGroupTransactionData);

		// Delete this transaction itself
		this.repository.getTransactionRepository().delete(leaveGroupTransactionData);

		// Update leaver's balance
		Account leaver = getLeaver();
		leaver.setConfirmedBalance(Asset.QORA, leaver.getConfirmedBalance(Asset.QORA).add(leaveGroupTransactionData.getFee()));

		// Update leaver's reference
		leaver.setLastReference(leaveGroupTransactionData.getReference());
	}

}
package org.totschnig.myexpenses.sync;

/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import android.accounts.Account;
import android.accounts.AccountManager;
import android.annotation.TargetApi;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SyncResult;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.support.annotation.VisibleForTesting;
import android.support.v4.provider.DocumentFile;
import android.support.v4.util.Pair;
import android.util.Log;

import com.annimon.stream.Collectors;
import com.annimon.stream.Stream;

import org.totschnig.myexpenses.export.CategoryInfo;
import org.totschnig.myexpenses.model.AccountType;
import org.totschnig.myexpenses.model.Payee;
import org.totschnig.myexpenses.model.PaymentMethod;
import org.totschnig.myexpenses.model.SplitTransaction;
import org.totschnig.myexpenses.model.Transaction;
import org.totschnig.myexpenses.model.Transfer;
import org.totschnig.myexpenses.provider.DatabaseConstants;
import org.totschnig.myexpenses.provider.TransactionProvider;
import org.totschnig.myexpenses.sync.json.ChangeSet;
import org.totschnig.myexpenses.sync.json.TransactionChange;
import org.totschnig.myexpenses.util.AcraHelper;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static android.content.Context.ACCOUNT_SERVICE;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_AMOUNT;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_CATID;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_COMMENT;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_CR_STATUS;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_DATE;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_METHODID;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_PAYEEID;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_REFERENCE_NUMBER;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_ROWID;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_SYNC_FROM_ADAPTER;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_SYNC_SEQUENCE_LOCAL;
import static org.totschnig.myexpenses.provider.DatabaseConstants.KEY_UUID;

public class SyncAdapter extends AbstractThreadedSyncAdapter {
  public static final String TAG = "SyncAdapter";
  private static final String LAST_SYNCED_REMOTE = "last_synced_remote";
  private static final String LAST_SYNCED_LOCAL = "last_synced_local";
  private static final String IS_INITIALIZED = "is_initialized";

  private Map<String, Long> categoryToId;
  private Map<String, Long> payeeToId;
  private Map<String, Long> methodToId;
  private Map<String, Long> accountUuidToId;

  private static final ThreadLocal<org.totschnig.myexpenses.model.Account>
      dbAccount = new ThreadLocal<>();

  public SyncAdapter(Context context, boolean autoInitialize) {
    super(context, autoInitialize);
  }

  @TargetApi(Build.VERSION_CODES.HONEYCOMB)
  public SyncAdapter(Context context, boolean autoInitialize, boolean allowParallelSyncs) {
    super(context, autoInitialize, allowParallelSyncs);
  }

  private String getUserDataWithDefault(AccountManager accountManager, Account account,
                                        String key, String defaultValue) {
    String value = accountManager.getUserData(account, key);
    return value == null ? defaultValue : value;
  }

  @Override
  public void onPerformSync(Account account, Bundle extras, String authority,
                            ContentProviderClient provider, SyncResult syncResult) {
    categoryToId = new HashMap<>();
    payeeToId = new HashMap<>();
    methodToId = new HashMap<>();
    accountUuidToId = new HashMap<>();
    Log.i(TAG, "onPerformSync");
    String accountId = account.name.substring(1);
    AccountManager accountManager = (AccountManager) getContext().getSystemService(ACCOUNT_SERVICE);
    if (!"1".equals(accountManager.getUserData(account, IS_INITIALIZED))) {
      try {
        provider.update(buildInitializationUri(accountId), new ContentValues(0), null, null);
        accountManager.setUserData(account, IS_INITIALIZED, "1");
      } catch (RemoteException e) {
        AcraHelper.report(e);
        return;
      }
    }

    long lastSyncedLocal = Long.parseLong(getUserDataWithDefault(accountManager, account,
        LAST_SYNCED_LOCAL, "0"));
    long lastSyncedRemote = Long.parseLong(getUserDataWithDefault(accountManager, account,
        LAST_SYNCED_REMOTE, "0"));
    dbAccount.set(org.totschnig.myexpenses.model.Account.getInstanceFromDb(Long.valueOf(accountId)));
    SyncBackend backend = getBackendForAccount(accountId);
    if (backend == null) {
      //TODO report
      return;
    } else if (!backend.isAvailable()) {
      return;
    }

    ChangeSet changeSetSince = backend.getChangeSetSince(lastSyncedRemote);

    List<TransactionChange> remoteChanges;
    if (changeSetSince != null) {
      lastSyncedRemote = changeSetSince.sequenceNumber;
      remoteChanges = changeSetSince.changes;
    } else {
      remoteChanges = new ArrayList<>();
    }

    List<TransactionChange> localChanges = new ArrayList<>();
    long sequenceToTest = lastSyncedLocal + 1;
    while(true) {
      List<TransactionChange> nextChanges = getLocalChanges(provider, accountId, sequenceToTest);
      if (nextChanges.size() > 0) {
        localChanges.addAll(nextChanges);
        lastSyncedLocal = sequenceToTest;
        sequenceToTest++;
      } else {
        break;
      }
    }

    if (localChanges.size() == 0 && remoteChanges.size() == 0) {
      return;
    }

    if (localChanges.size() > 0) {
      localChanges = collectSplits(localChanges);
    }

    Pair<List<TransactionChange>, List<TransactionChange>> mergeResult =
        mergeChangeSets(localChanges, remoteChanges);
    localChanges = mergeResult.first;
    remoteChanges = mergeResult.second;

    if (remoteChanges.size() > 0) {
      try {
        ContentProviderResult[] contentProviderResults = writeRemoteChangesToDb(provider, remoteChanges, accountId);
        if (contentProviderResults.length == 0) {
          throw new OperationApplicationException("write to db yielded no results");
        }
        accountManager.setUserData(account, LAST_SYNCED_REMOTE, String.valueOf(lastSyncedRemote));
      } catch (RemoteException | OperationApplicationException | SQLiteException e) {
        AcraHelper.report(e);
        return;
      }
    }

    if (localChanges.size() > 0 && backend.lock()) {
      try {
        lastSyncedRemote = backend.writeChangeSet(localChanges);
      } finally {
        backend.unlock();
      }
      if (lastSyncedRemote != ChangeSet.FAILED) {
        accountManager.setUserData(account, LAST_SYNCED_LOCAL, String.valueOf(lastSyncedLocal));
        accountManager.setUserData(account, LAST_SYNCED_REMOTE, String.valueOf(lastSyncedRemote));
      }
    }
  }

  private List<TransactionChange> getLocalChanges(ContentProviderClient provider, String accountId, long sequenceNumber) {
    List<TransactionChange> result = new ArrayList<>();
    try {
      Uri changesUri = buildChangesUri(sequenceNumber, accountId);
      boolean hasLocalChanges = hasLocalChanges(provider, changesUri);
      if (hasLocalChanges) {
        ContentValues currentSyncIncrease = new ContentValues(1);
        long nextSequence = sequenceNumber + 1;
        currentSyncIncrease.put(KEY_SYNC_SEQUENCE_LOCAL, nextSequence);
        //in case of failed synces due to non-available backends, sequence number might already be higher than nextSequence
        //we must take care to not decrease it here
        provider.update(TransactionProvider.ACCOUNTS_URI, currentSyncIncrease, KEY_ROWID + " = ? AND " + KEY_SYNC_SEQUENCE_LOCAL + " < ?",
            new String[]{accountId, String.valueOf(nextSequence)});
      }
      if (hasLocalChanges) {
        Cursor c = provider.query(changesUri, null, null, null, null);
        if (c != null) {
          if (c.moveToFirst()) {
            do {
              result.add(TransactionChange.create(c));
            } while (c.moveToNext());
          }
          c.close();
        }
      }
    } catch (RemoteException e) {
      e.printStackTrace();
    }
    return result;
  }

  /**
   * @param changeList
   * @return the same list with split parts moved as parts to their parents. If there are multiple parents
   * for the same uuid, the splits will appear under each of them
   */
  private List<TransactionChange> collectSplits(List<TransactionChange> changeList) {
    HashMap<String, List<TransactionChange>> splitsPerUuid = new HashMap<>();
    for (Iterator<TransactionChange> i = changeList.iterator(); i.hasNext(); ) {
      TransactionChange change = i.next();
      if ((change.parentUuid() != null)) {
        ensureList(splitsPerUuid, change.parentUuid()).add(change);
        i.remove();
      }
    }
    //When a split transaction is changed, we do not necessarily have an entry for the parent, so we
    //create one here
    Stream.of(splitsPerUuid.keySet()).forEach(uuid -> {
      if (!Stream.of(changeList).anyMatch(change -> change.uuid().equals(uuid))) {
        changeList.add(TransactionChange.builder().setType(TransactionChange.Type.updated).setTimeStamp(splitsPerUuid.get(uuid).get(0).timeStamp()).setUuid(uuid).build());
        splitsPerUuid.put(uuid, filterDeleted(
            splitsPerUuid.get(uuid), findDeletedUuids(Stream.of(splitsPerUuid.get(uuid)))));
      }
    });

    return Stream.of(changeList).map(change -> splitsPerUuid.containsKey(change.uuid()) ?
        change.toBuilder().setSplitPartsAndValidate(splitsPerUuid.get(change.uuid())).build() : change)
        .collect(Collectors.toList());
  }

  private ContentProviderResult[] writeRemoteChangesToDb(ContentProviderClient provider, List<TransactionChange> remoteChanges, String accountId)
      throws RemoteException, OperationApplicationException {
    ArrayList<ContentProviderOperation> ops = new ArrayList<>();
    Uri accountUri = TransactionProvider.ACCOUNTS_URI.buildUpon().appendPath(accountId).build();
    ops.add(ContentProviderOperation.newUpdate(accountUri).withValue(KEY_SYNC_FROM_ADAPTER, true).build());
    Stream.of(remoteChanges).filter(change -> !(change.isCreate() && uuidExists(change.uuid())))
        .forEach(change -> collectOperations(change, ops, -1));
    ops.add(ContentProviderOperation.newUpdate(accountUri).withValue(KEY_SYNC_FROM_ADAPTER, false).build());
    return provider.applyBatch(ops);
  }

  private boolean uuidExists(String uuid) {
    return Transaction.countPerUuid(uuid) > 0;
  }

  @VisibleForTesting
  public void collectOperations(@NonNull TransactionChange change, ArrayList<ContentProviderOperation> ops, int parentOffset) {
    switch (change.type()) {
      case created:
        ops.addAll(getContentProviderOperationsForCreate(change, ops.size(), parentOffset));
        break;
      case updated:
        ContentValues values = toContentValues(change);
        if (values.size() > 0) {
          ops.add(ContentProviderOperation.newUpdate(TransactionProvider.TRANSACTIONS_URI)
              .withSelection(KEY_UUID + " = ?", new String[]{change.uuid()})
              .withValues(values)
              .build());
        }
        break;
      case deleted:
        ops.add(ContentProviderOperation.newDelete(TransactionProvider.TRANSACTIONS_URI)
            .withSelection(KEY_UUID + " = ?", new String[]{change.uuid()})
            .build());
        break;
    }
    if (change.splitParts() != null) {
      final int newParentOffset = ops.size() - 1;
      List<TransactionChange> splitPartsFiltered = filterDeleted(change.splitParts(),
          findDeletedUuids(Stream.of(change.splitParts())));
      Stream.of(splitPartsFiltered).forEach(splitChange -> collectOperations(splitChange, ops,
          change.isCreate() ? newParentOffset : -1)); //back reference is only used when we insert a new split, for updating an existing split we search for its _id via its uuid
    }
  }


  private ArrayList<ContentProviderOperation> getContentProviderOperationsForCreate(
      TransactionChange change, int offset, int parentOffset) {
    if (!change.isCreate()) throw new AssertionError();
    Long amount;
    if (change.amount() != null) {
      amount = change.amount();
    } else {
      amount = 0L;
    }
    Transaction t;
    long transferAccount;
    if (change.splitParts() != null) {
      t = new SplitTransaction(getAccount().getId(), amount);
    } else if (change.transferAccount() != null &&
        (transferAccount = extractTransferAccount(change.transferAccount(), change.label())) != -1) {
      t = new Transfer(getAccount().getId(), amount);
      t.transfer_account = transferAccount;
    } else {
      t = new Transaction(getAccount().getId(), amount);
      if (change.label() != null) {
        t.setCatId(extractCatId(change.label()));
      }
    }
    t.uuid = change.uuid();
    if (change.comment() != null) {
      t.comment = change.comment();
    }
    if (change.date() != null) {
      Long date = change.date();
      assert date != null;
      t.setDate(new Date(date * 1000));
    }

    if (change.payeeName() != null) {
      long id = Payee.extractPayeeId(change.payeeName(), payeeToId);
      if (id != -1) {
        t.payeeId = id;
      }
    }
    if (change.methodLabel() != null) {
      long id = extractMethodId(change.methodLabel());
      if (id != -1) {
        t.methodId = id;
      }
    }
    if (change.crStatus() != null) {
      t.crStatus = Transaction.CrStatus.valueOf(change.crStatus());
    }
    t.referenceNumber = change.referenceNumber();
    if (parentOffset == -1 && change.parentUuid() != null) {
      long parentId = Transaction.findByUuid(change.parentUuid());
      if (parentId == -1) {
        return new ArrayList<>(); //if we fail to link a split part to a parent, we need to ignore it
      }
      t.parentId = parentId;
    }
    return t.buildSaveOperations(offset, parentOffset);
  }

  private ContentValues toContentValues(TransactionChange change) {
    if (!change.isUpdate()) throw new AssertionError();
    ContentValues values = new ContentValues();
    //values.put("parent_uuid", parentUuid());
    if (change.comment() != null) {
      values.put(KEY_COMMENT, change.comment());
    }
    if (change.date() != null) {
      values.put(KEY_DATE, change.date());
    }
    if (change.amount() != null) {
      values.put(KEY_AMOUNT, change.amount());
    }
    if (change.label() != null) {
      values.put(KEY_CATID, extractCatId(change.label()));
    }
    if (change.payeeName() != null) {
      long id = Payee.extractPayeeId(change.payeeName(), payeeToId);
      if (id != -1) {
        values.put(KEY_PAYEEID, id);
      }
    }
    if (change.methodLabel() != null) {
      long id = extractMethodId(change.methodLabel());
      if (id != -1) {
        values.put(KEY_METHODID, id);
      }
    }
    if (change.crStatus() != null) {
      values.put(KEY_CR_STATUS, change.crStatus());
    }
    if (change.referenceNumber() != null) {
      values.put(KEY_REFERENCE_NUMBER, change.referenceNumber());
    }
    //values.put("picture_id", pictureUri());
    return values;
  }

  private long extractTransferAccount(String uuid, String label) {
    Long id = accountUuidToId.get(uuid);
    if (id == null) {
      id = org.totschnig.myexpenses.model.Account.findByUuid(uuid);
      if (id == -1 && label != null) {
        org.totschnig.myexpenses.model.Account transferAccount =
            new org.totschnig.myexpenses.model.Account(label, getAccount().currency, 0L, "",
                AccountType.CASH, org.totschnig.myexpenses.model.Account.DEFAULT_COLOR);
        transferAccount.uuid = uuid;
        transferAccount.save();
        id = transferAccount.getId();
      }
      if (id != -1) { //should always be the case
        accountUuidToId.put(uuid, id);
      }
    }
    return id;
  }

  private long extractCatId(String label) {
    new CategoryInfo(label).insert(categoryToId);
    return categoryToId.get(label);
  }

  private long extractMethodId(String methodLabel) {
    Long id = methodToId.get(methodLabel);
    if (id == null) {
      id = PaymentMethod.find(methodLabel);
      if (id == -1) {
        id = PaymentMethod.maybeWrite(methodLabel, getAccount().type);
      }
      if (id != -1) { //should always be the case
        methodToId.put(methodLabel, id);
      }
    }
    return id;
  }

  @VisibleForTesting
  public Pair<List<TransactionChange>, List<TransactionChange>> mergeChangeSets(
      List<TransactionChange> first, List<TransactionChange> second) {

    //filter out changes made obsolete by later delete
    List<String> deletedUuids = findDeletedUuids(Stream.concat(Stream.of(first), Stream.of(second)));

    List<TransactionChange> firstResult = filterDeleted(first, deletedUuids);
    List<TransactionChange> secondResult = filterDeleted(second, deletedUuids);

    //merge update changes
    HashMap<String, List<TransactionChange>> updatesPerUuid = new HashMap<>();
    HashMap<String, TransactionChange> mergesPerUuid = new HashMap<>();
    Stream.concat(Stream.of(firstResult), Stream.of(secondResult))
        .filter(TransactionChange::isCreateOrUpdate)
        .forEach(change -> ensureList(updatesPerUuid, change.uuid()).add(change));
    List<String> uuidsRequiringMerge = Stream.of(updatesPerUuid.keySet())
        .filter(uuid -> updatesPerUuid.get(uuid).size() > 1).collect(Collectors.toList());
    Stream.of(uuidsRequiringMerge)
        .forEach(uuid -> mergesPerUuid.put(uuid, mergeUpdates(updatesPerUuid.get(uuid))));
    firstResult = replaceByMerged(firstResult, mergesPerUuid);
    secondResult = replaceByMerged(secondResult, mergesPerUuid);

    return Pair.create(firstResult, secondResult);
  }

  private List<String> findDeletedUuids(Stream<TransactionChange> stream) {
    return stream.filter(TransactionChange::isDelete)
        .map(TransactionChange::uuid)
        .collect(Collectors.toList());
  }

  private List<TransactionChange> filterDeleted(List<TransactionChange> input, List<String> deletedUuids) {
    return Stream.of(input).filter(change ->
        change.isDelete() || !deletedUuids.contains(change.uuid()))
        .collect(Collectors.toList());
  }

  private List<TransactionChange> replaceByMerged(List<TransactionChange> input, HashMap<String, TransactionChange> mergedMap) {
    return Stream.of(input).map(change -> change.isCreateOrUpdate()
        && mergedMap.containsKey(change.uuid()) ? mergedMap.get(change.uuid()) : change)
        .distinct().collect(Collectors.toList());
  }

  @VisibleForTesting
  public TransactionChange mergeUpdates(List<TransactionChange> changeList) {
    if (changeList.size() < 2) {
      throw new IllegalStateException("nothing to merge");
    }
    return Stream.of(changeList).sortBy(TransactionChange::timeStamp).reduce(this::mergeUpdate).get();
  }

  private TransactionChange mergeUpdate(TransactionChange initial, TransactionChange change) {
    if (!(change.isCreateOrUpdate() && initial.isCreateOrUpdate())) {
      throw new IllegalStateException("Can only merge creates and updates");
    }
    if (!initial.uuid().equals(change.uuid())) {
      throw new IllegalStateException("Can only merge changes with same uuid");
    }
    TransactionChange.Builder builder = initial.toBuilder();
    if (change.parentUuid() != null) {
      builder.setParentUuid(change.parentUuid());
    }
    if (change.comment() != null) {
      builder.setComment(change.comment());
    }
    if (change.date() != null) {
      builder.setDate(change.date());
    }
    if (change.amount() != null) {
      builder.setAmount(change.amount());
    }
    if (change.label() != null) {
      builder.setLabel(change.label());
    }
    if (change.payeeName() != null) {
      builder.setPayeeName(change.payeeName());
    }
    if (change.transferAccount() != null) {
      builder.setTransferAccount(change.transferAccount());
    }
    if (change.methodLabel() != null) {
      builder.setMethodLabel(change.methodLabel());
    }
    if (change.crStatus() != null) {
      builder.setCrStatus(change.crStatus());
    }
    if (change.referenceNumber() != null) {
      builder.setReferenceNumber(change.referenceNumber());
    }
    if (change.pictureUri() != null) {
      builder.setPictureUri(change.pictureUri());
    }
    if (change.splitParts() != null) {
      builder.setSplitParts(change.splitParts());
    }
    return builder.setTimeStamp(System.currentTimeMillis()).build();
  }

  private Uri buildChangesUri(long current_sync, String accountId) {
    return TransactionProvider.CHANGES_URI.buildUpon()
        .appendQueryParameter(DatabaseConstants.KEY_ACCOUNTID, accountId)
        .appendQueryParameter(KEY_SYNC_SEQUENCE_LOCAL, String.valueOf(current_sync))
        .build();
  }
  private Uri buildInitializationUri(String accountId) {
    return TransactionProvider.CHANGES_URI.buildUpon()
        .appendQueryParameter(DatabaseConstants.KEY_ACCOUNTID, accountId)
        .appendQueryParameter(TransactionProvider.QUERY_PARAMETER_INIT, "1")
        .build();
  }

  private List<TransactionChange> ensureList(HashMap<String, List<TransactionChange>> map, String uuid) {
    List<TransactionChange> changesForUuid = map.get(uuid);
    if (changesForUuid == null) {
      changesForUuid = new ArrayList<>();
      map.put(uuid, changesForUuid);
    }
    return changesForUuid;
  }

  private SyncBackend getBackendForAccount(String accountId) {
    File baseFolder = new File("/sdcard/Debug");
    if (!baseFolder.isDirectory()) {
      return null;
    }
    File accountFolder = new File(baseFolder, "_" + accountId);
    accountFolder.mkdir();
    if (!accountFolder.isDirectory()) {
      return null;
    }
    return new LocalFileBackend(DocumentFile.fromFile(accountFolder), getContext());
  }

  private boolean hasLocalChanges(ContentProviderClient provider, Uri changesUri) throws RemoteException {
    boolean result = false;
    Cursor c = provider.query(changesUri,
        new String[]{"count(*)"}, null, null, null);

    if (c != null) {
      if (c.moveToFirst()) {
        result = c.getLong(0) > 0;
      }
      c.close();
    }
    return result;
  }

  @VisibleForTesting
  public org.totschnig.myexpenses.model.Account getAccount() {
    return dbAccount.get();
  }
}

/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.om.response.key;

import org.apache.hadoop.ozone.om.OMMetadataManager;
import org.apache.hadoop.ozone.om.helpers.BucketLayout;
import org.apache.hadoop.ozone.om.helpers.OmKeyInfo;
import org.apache.hadoop.ozone.om.request.OMClientRequestUtils;
import org.apache.hadoop.ozone.om.response.CleanupTableInfo;
import org.apache.hadoop.ozone.protocol.proto.OzoneManagerProtocolProtos
    .OMResponse;
import org.apache.hadoop.hdds.utils.db.BatchOperation;

import java.io.IOException;
import javax.annotation.Nonnull;

import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.KEY_TABLE;
import static org.apache.hadoop.ozone.om.OmMetadataManagerImpl.SNAPSHOT_RENAMED_KEY_TABLE;

/**
 * Response for RenameKey request.
 */
@CleanupTableInfo(cleanupTables = {KEY_TABLE, SNAPSHOT_RENAMED_KEY_TABLE})
public class OMKeyRenameResponse extends OmKeyResponse {

  private String fromKeyName;
  private String toKeyName;
  private OmKeyInfo renameKeyInfo;

  public OMKeyRenameResponse(@Nonnull OMResponse omResponse,
      String fromKeyName, String toKeyName, @Nonnull OmKeyInfo renameKeyInfo) {
    super(omResponse);
    this.fromKeyName = fromKeyName;
    this.toKeyName = toKeyName;
    this.renameKeyInfo = renameKeyInfo;
  }

  public OMKeyRenameResponse(@Nonnull OMResponse omResponse, String fromKeyName,
      String toKeyName, @Nonnull OmKeyInfo renameKeyInfo,
      BucketLayout bucketLayout) {
    super(omResponse, bucketLayout);
    this.fromKeyName = fromKeyName;
    this.toKeyName = toKeyName;
    this.renameKeyInfo = renameKeyInfo;
  }

  /**
   * For when the request is not successful.
   * For a successful request, the other constructor should be used.
   */
  public OMKeyRenameResponse(@Nonnull OMResponse omResponse,
                             @Nonnull BucketLayout bucketLayout) {
    super(omResponse, bucketLayout);
    checkStatusNotOK();
  }

  @Override
  public void addToDBBatch(OMMetadataManager omMetadataManager,
      BatchOperation batchOperation) throws IOException {
    String volumeName = renameKeyInfo.getVolumeName();
    String bucketName = renameKeyInfo.getBucketName();
    String fromDbKey = omMetadataManager
        .getOzoneKey(volumeName, bucketName, fromKeyName);
    omMetadataManager.getKeyTable(getBucketLayout())
        .deleteWithBatch(batchOperation, fromDbKey);
    omMetadataManager.getKeyTable(getBucketLayout())
        .putWithBatch(batchOperation,
            omMetadataManager.getOzoneKey(volumeName, bucketName, toKeyName),
            renameKeyInfo);

    // Check if the bucket is in snapshot scope, if yes
    // add the key to snapshotRenamedKeyTable.
    boolean isSnapshotBucket = OMClientRequestUtils.
        isSnapshotBucket(omMetadataManager, renameKeyInfo);
    String renameDbKey = omMetadataManager.getRenameKey(
        renameKeyInfo.getVolumeName(), renameKeyInfo.getBucketName(),
        renameKeyInfo.getObjectID());
    String renamedKey = omMetadataManager.getSnapshotRenamedKeyTable()
        .get(renameDbKey);
    if (isSnapshotBucket && renamedKey == null) {
      omMetadataManager.getSnapshotRenamedKeyTable().putWithBatch(
          batchOperation, renameDbKey, fromDbKey);
    }
  }

  public OmKeyInfo getRenameKeyInfo() {
    return renameKeyInfo;
  }

  public String getFromKeyName() {
    return fromKeyName;
  }

  public String getToKeyName() {
    return toKeyName;
  }
}

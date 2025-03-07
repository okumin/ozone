/*
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

package org.apache.hadoop.ozone.recon.api;

import org.apache.hadoop.hdds.client.RatisReplicationConfig;
import org.apache.hadoop.hdds.conf.OzoneConfiguration;
import org.apache.hadoop.hdds.protocol.DatanodeDetails;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.ExtendedDatanodeDetailsProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.LifeCycleState;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.PipelineID;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReplicaProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReport;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.StorageTypeProto;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos.DatanodeDetailsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.LayoutVersionProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.StorageReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.NodeReportProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.ContainerReportsProto;
import org.apache.hadoop.hdds.protocol.proto.StorageContainerDatanodeProtocolProtos.PipelineReportsProto;
import org.apache.hadoop.hdds.scm.container.ContainerInfo;
import org.apache.hadoop.hdds.scm.container.common.helpers.ContainerWithPipeline;
import org.apache.hadoop.hdds.scm.pipeline.Pipeline;
import org.apache.hadoop.hdds.scm.protocol.StorageContainerLocationProtocol;
import org.apache.hadoop.hdds.scm.server.OzoneStorageContainerManager;
import org.apache.hadoop.hdfs.web.URLConnectionFactory;
import org.apache.hadoop.ozone.recon.ReconTestInjector;
import org.apache.hadoop.ozone.recon.ReconUtils;
import org.apache.hadoop.ozone.recon.api.types.ClusterStateResponse;
import org.apache.hadoop.ozone.recon.api.types.DatanodesResponse;
import org.apache.hadoop.ozone.recon.persistence.AbstractReconSqlDBTest;
import org.apache.hadoop.ozone.recon.persistence.ContainerHealthSchemaManager;
import org.apache.hadoop.ozone.recon.recovery.ReconOMMetadataManager;
import org.apache.hadoop.ozone.recon.scm.ReconStorageContainerManagerFacade;
import org.apache.hadoop.ozone.recon.spi.StorageContainerServiceProvider;
import org.apache.hadoop.ozone.recon.spi.impl.OzoneManagerServiceProviderImpl;
import org.apache.hadoop.ozone.recon.spi.impl.StorageContainerServiceProviderImpl;
import org.apache.ozone.test.GenericTestUtils;
import org.hadoop.ozone.recon.schema.tables.daos.GlobalStatsDao;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import java.net.HttpURLConnection;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

import static org.apache.hadoop.hdds.protocol.MockDatanodeDetails.randomDatanodeDetails;
import static org.apache.hadoop.ozone.container.upgrade.UpgradeUtils.defaultLayoutVersionProto;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.initializeNewOmMetadataManager;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getRandomPipeline;
import static org.apache.hadoop.ozone.recon.OMMetadataManagerTestUtils.getTestReconOmMetadataManager;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test for clusterStateEndpoint for checking deletedContainers count.
 */
@Disabled("HDDS-8374")
public class TestContainerStateCounts extends AbstractReconSqlDBTest {
  private NodeEndpoint nodeEndpoint;
  private ClusterStateEndpoint clusterStateEndpoint;
  private ReconOMMetadataManager reconOMMetadataManager;
  private ReconStorageContainerManagerFacade reconScm;
  private boolean isSetupDone = false;
  private String pipelineId, pipelineId2;
  private DatanodeDetails datanodeDetails;
  private DatanodeDetails datanodeDetails2;
  private ContainerReportsProto containerReportsProto;
  private HddsProtos.ExtendedDatanodeDetailsProto extendedDatanodeDetailsProto;
  private Pipeline pipeline, pipeline2;
  private static final String HOST1 = "host1.datanode";
  private static final String HOST2 = "host2.datanode";
  private static final String IP1 = "1.1.1.1";
  private static final String IP2 = "2.2.2.2";
  private ReconUtils reconUtilsMock;
  private ContainerHealthSchemaManager containerHealthSchemaManager;
  private List<Long> containerIDs;
  private List<ContainerWithPipeline> cpw;
  private StorageContainerServiceProvider mockScmServiceProvider;
  private ContainerReportsProto.Builder builder;

  private void initializeInjector() throws Exception {
    reconOMMetadataManager = getTestReconOmMetadataManager(
        initializeNewOmMetadataManager(temporaryFolder.newFolder()),
        temporaryFolder.newFolder());
    datanodeDetails = randomDatanodeDetails();
    datanodeDetails2 = randomDatanodeDetails();
    datanodeDetails.setHostName(HOST1);
    datanodeDetails.setIpAddress(IP1);
    datanodeDetails2.setHostName(HOST2);
    datanodeDetails2.setIpAddress(IP2);
    pipeline = getRandomPipeline(datanodeDetails);
    pipelineId = pipeline.getId().getId().toString();
    pipeline2 = getRandomPipeline(datanodeDetails2);
    pipelineId2 = pipeline2.getId().getId().toString();

    StorageContainerLocationProtocol mockScmClient = mock(
        StorageContainerLocationProtocol.class);
    mockScmServiceProvider = mock(
        StorageContainerServiceProviderImpl.class);

    when(mockScmServiceProvider.getPipeline(
        pipeline.getId().getProtobuf())).thenReturn(pipeline);
    when(mockScmServiceProvider.getPipeline(
        pipeline2.getId().getProtobuf())).thenReturn(pipeline2);

    // Initialize 5 DELETED containers associated with pipeline 1
    // which is associated with DataNode1
    containerIDs = new LinkedList<>();
    cpw = new LinkedList<>();
    for (long i = 1L; i <= 5L; ++i) {
      ContainerInfo containerInfo = new ContainerInfo.Builder()
          .setContainerID(i)
          .setReplicationConfig(RatisReplicationConfig.getInstance(
              HddsProtos.ReplicationFactor.ONE))
          .setState(LifeCycleState.DELETED)
          .setOwner("test")
          .setPipelineID(pipeline.getId())
          .build();

      ContainerWithPipeline containerWithPipeline =
          new ContainerWithPipeline(containerInfo, pipeline);
      when(mockScmServiceProvider.getContainerWithPipeline(i))
          .thenReturn(containerWithPipeline);
      containerIDs.add(i);
      cpw.add(containerWithPipeline);
    }


    // Initialize 2 CLOSED and 3 OPEN containers associated with pipeline 2
    // which is associated with DataNode2
    for (long i = 6L; i <= 10L; ++i) {
      LifeCycleState lifeCycleState = (i == 6L || i == 7L) ?
          LifeCycleState.CLOSED : LifeCycleState.OPEN;
      ContainerInfo containerInfo = new ContainerInfo.Builder()
          .setContainerID(i)
          .setReplicationConfig(
              RatisReplicationConfig.getInstance(
                  HddsProtos.ReplicationFactor.ONE))
          .setState(lifeCycleState)
          .setOwner("test")
          .setPipelineID(pipeline2.getId())
          .build();
      ContainerWithPipeline containerWithPipeline =
          new ContainerWithPipeline(containerInfo, pipeline2);
      when(mockScmServiceProvider.getContainerWithPipeline(i))
          .thenReturn(containerWithPipeline);
      containerIDs.add(i);
      cpw.add(containerWithPipeline);
    }


    when(mockScmServiceProvider
        .getExistContainerWithPipelinesInBatch(containerIDs))
        .thenReturn(cpw);

    reconUtilsMock = mock(ReconUtils.class);
    HttpURLConnection urlConnectionMock = mock(HttpURLConnection.class);
    when(urlConnectionMock.getResponseCode())
        .thenReturn(HttpServletResponse.SC_OK);
    when(reconUtilsMock.makeHttpCall(any(URLConnectionFactory.class),
        anyString(), anyBoolean())).thenReturn(urlConnectionMock);
    when(reconUtilsMock.getReconDbDir(any(OzoneConfiguration.class),
        anyString())).thenReturn(GenericTestUtils.getRandomizedTestDir());

    ReconTestInjector reconTestInjector =
        new ReconTestInjector.Builder(temporaryFolder)
            .withReconSqlDb()
            .withReconOm(reconOMMetadataManager)
            .withOmServiceProvider(mock(OzoneManagerServiceProviderImpl.class))
            .addBinding(StorageContainerServiceProvider.class,
                mockScmServiceProvider)
            .addBinding(OzoneStorageContainerManager.class,
                ReconStorageContainerManagerFacade.class)
            .withContainerDB()
            .addBinding(ClusterStateEndpoint.class)
            .addBinding(NodeEndpoint.class)
            .addBinding(ContainerHealthSchemaManager.class)
            .addBinding(ReconUtils.class, reconUtilsMock)
            .addBinding(StorageContainerLocationProtocol.class, mockScmClient)
            .build();

    nodeEndpoint = reconTestInjector.getInstance(NodeEndpoint.class);
    GlobalStatsDao globalStatsDao = getDao(GlobalStatsDao.class);
    reconScm = (ReconStorageContainerManagerFacade)
        reconTestInjector.getInstance(OzoneStorageContainerManager.class);
    containerHealthSchemaManager =
        reconTestInjector.getInstance(ContainerHealthSchemaManager.class);
    clusterStateEndpoint =
        new ClusterStateEndpoint(reconScm, globalStatsDao,
            containerHealthSchemaManager);
  }

  @BeforeEach
  public void setUp() throws Exception {
    // Check if the setup has already been done
    if (!isSetupDone) {
      // Initialize the injector if setup has not been done
      initializeInjector();
      // Mark the setup as done
      isSetupDone = true;
    }
    // Get UUIDs for datanodes
    String datanodeId = datanodeDetails.getUuid().toString();
    String datanodeId2 = datanodeDetails2.getUuid().toString();

    // initialize container report
    builder = ContainerReportsProto.newBuilder();
    builder = ContainerReportsProto.newBuilder();

    // Generate container reports with different states
    for (long i = 1L; i < 11L; i++) {
      ContainerReplicaProto.State state;
      if (i < 5L) {
        state = ContainerReplicaProto.State.DELETED;
      } else if (i < 8L) {
        state = ContainerReplicaProto.State.CLOSED;
      } else {
        state = ContainerReplicaProto.State.OPEN;
      }

      builder.addReports(
          ContainerReplicaProto.newBuilder()
              .setContainerID(i)
              .setState(state)
              .setOriginNodeId(i < 5L ? datanodeId : datanodeId2)
              .build()
      );
    }
    // Build container reports
    containerReportsProto = builder.build();

    // Build UUID object for pipeline
    UUID pipelineUuid = UUID.fromString(pipelineId);
    HddsProtos.UUID uuid128 = HddsProtos.UUID.newBuilder()
        .setMostSigBits(pipelineUuid.getMostSignificantBits())
        .setLeastSigBits(pipelineUuid.getLeastSignificantBits())
        .build();

    // Build pipeline report for pipeline 1
    PipelineReport pipelineReport = PipelineReport.newBuilder()
        .setPipelineID(
            PipelineID.newBuilder().setId(pipelineId).setUuid128(uuid128)
                .build())
        .setIsLeader(true)
        .build();
    DatanodeDetailsProto datanodeDetailsProto =
        DatanodeDetailsProto.newBuilder()
            .setHostName(HOST1)
            .setUuid(datanodeId)
            .setIpAddress(IP1)
            .build();
    extendedDatanodeDetailsProto =
        ExtendedDatanodeDetailsProto.newBuilder()
            .setDatanodeDetails(datanodeDetailsProto)
            .setVersion("0.6.0")
            .setSetupTime(1596347628802L)
            .setBuildDate("2020-08-01T08:50Z")
            .setRevision("3346f493fa1690358add7bb9f3e5b52545993f36")
            .build();
    StorageReportProto storageReportProto1 =
        StorageReportProto.newBuilder().setStorageType(StorageTypeProto.DISK)
            .setStorageLocation("/disk1").setScmUsed(10000).setRemaining(5400)
            .setCapacity(25000)
            .setStorageUuid(UUID.randomUUID().toString())
            .setFailed(false).build();
    StorageReportProto storageReportProto2 =
        StorageReportProto.newBuilder().setStorageType(StorageTypeProto.DISK)
            .setStorageLocation("/disk2").setScmUsed(25000).setRemaining(10000)
            .setCapacity(50000)
            .setStorageUuid(UUID.randomUUID().toString())
            .setFailed(false).build();
    NodeReportProto nodeReportProto =
        NodeReportProto.newBuilder()
            .addStorageReport(storageReportProto1)
            .addStorageReport(storageReportProto2).build();

    UUID pipelineUuid2 = UUID.fromString(pipelineId2);
    uuid128 = HddsProtos.UUID.newBuilder()
        .setMostSigBits(pipelineUuid2.getMostSignificantBits())
        .setLeastSigBits(pipelineUuid2.getLeastSignificantBits())
        .build();

    // Build pipeline report for pipeline 2
    PipelineReport pipelineReport2 = PipelineReport.newBuilder()
        .setPipelineID(
            PipelineID.newBuilder().setId(pipelineId2).setUuid128(uuid128)
                .build()).setIsLeader(false).build();
    PipelineReportsProto pipelineReportsProto =
        PipelineReportsProto.newBuilder()
            .addPipelineReport(pipelineReport)
            .addPipelineReport(pipelineReport2)
            .build();
    DatanodeDetailsProto datanodeDetailsProto2 =
        DatanodeDetailsProto.newBuilder()
            .setHostName(HOST2)
            .setUuid(datanodeId2)
            .setIpAddress(IP2)
            .build();
    HddsProtos.ExtendedDatanodeDetailsProto extendedDatanodeDetailsProto2 =
        ExtendedDatanodeDetailsProto.newBuilder()
            .setDatanodeDetails(datanodeDetailsProto2)
            .setVersion("0.6.0")
            .setSetupTime(1596347636802L)
            .setBuildDate("2020-08-01T08:50Z")
            .setRevision("3346f493fa1690358add7bb9f3e5b52545993f36")
            .build();
    StorageReportProto storageReportProto3 =
        StorageReportProto.newBuilder().setStorageType(StorageTypeProto.DISK)
            .setStorageLocation("/disk1").setScmUsed(20000).setRemaining(7800)
            .setCapacity(50000)
            .setStorageUuid(UUID.randomUUID().toString())
            .setFailed(false).build();
    StorageReportProto storageReportProto4 =
        StorageReportProto.newBuilder().setStorageType(StorageTypeProto.DISK)
            .setStorageLocation("/disk2").setScmUsed(60000).setRemaining(10000)
            .setCapacity(80000)
            .setStorageUuid(UUID.randomUUID().toString())
            .setFailed(false).build();
    NodeReportProto nodeReportProto2 =
        NodeReportProto.newBuilder()
            .addStorageReport(storageReportProto3)
            .addStorageReport(storageReportProto4).build();
    LayoutVersionProto layoutInfo = defaultLayoutVersionProto();

    try {
      reconScm.getDatanodeProtocolServer()
          .register(extendedDatanodeDetailsProto, nodeReportProto,
              containerReportsProto, pipelineReportsProto, layoutInfo);
      reconScm.getDatanodeProtocolServer()
          .register(extendedDatanodeDetailsProto2,
              nodeReportProto2, containerReportsProto, pipelineReportsProto,
              layoutInfo);
      // Process all events in the event queue
      reconScm.getEventQueue().processAll(1000);
    } catch (Exception ex) {
      Assertions.fail(ex.getMessage());
    }
  }

  @Test
  public void testDeletedContainerCount() throws Exception {

    // Total Available Containers = Total - Deleted Containers = 10 - 5 = 5
    // Total Deleted Containers = 5
    // Total Open Containers = 3
    // Total Closed Containers = 2

    Response response = nodeEndpoint.getDatanodes();
    DatanodesResponse datanodesResponse =
        (DatanodesResponse) response.getEntity();
    Assertions.assertEquals(2, datanodesResponse.getTotalCount());

    Response response1 = clusterStateEndpoint.getClusterState();
    ClusterStateResponse clusterStateResponse1 =
        (ClusterStateResponse) response1.getEntity();

    // Test for total pipelines
    Assertions.assertEquals(2, clusterStateResponse1.getPipelines());
    // Test for total containers
    Assertions.assertEquals(5, clusterStateResponse1.getContainers());
    // Test for total deleted containers
    Assertions.assertEquals(5, clusterStateResponse1.getDeletedContainers());
    // Test for OPEN containers
    Assertions.assertEquals(3, clusterStateResponse1.getOpenContainers());
  }

}

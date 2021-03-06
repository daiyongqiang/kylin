/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  
 *     http://www.apache.org/licenses/LICENSE-2.0
 *  
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.kylin.tool;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

import org.apache.commons.cli.Option;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.AbstractApplication;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.HadoopUtil;
import org.apache.kylin.common.util.OptionsHelper;
import org.apache.kylin.cube.CubeInstance;
import org.apache.kylin.cube.CubeManager;
import org.apache.kylin.cube.CubeSegment;
import org.apache.kylin.engine.mr.JobBuilderSupport;
import org.apache.kylin.job.engine.JobEngineConfig;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.metadata.realization.IRealizationConstants;
import org.apache.kylin.source.hive.HiveClientFactory;
import org.apache.kylin.common.util.HiveCmdBuilder;
import org.apache.kylin.source.hive.IHiveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

public class StorageCleanupJob extends AbstractApplication {

    @SuppressWarnings("static-access")
    protected static final Option OPTION_DELETE = OptionBuilder.withArgName("delete").hasArg().isRequired(false).withDescription("Delete the unused storage").create("delete");
    protected static final Option OPTION_FORCE = OptionBuilder.withArgName("force").hasArg().isRequired(false).withDescription("Warning: will delete all kylin intermediate hive tables").create("force");

    protected static final Logger logger = LoggerFactory.getLogger(StorageCleanupJob.class);
    public static final int deleteTimeout = 10; // Unit minute

    protected boolean delete = false;
    protected boolean force = false;
    protected static ExecutableManager executableManager = ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv());

    private void cleanUnusedHBaseTables(Configuration conf) throws IOException {
        CubeManager cubeMgr = CubeManager.getInstance(KylinConfig.getInstanceFromEnv());
        // get all kylin hbase tables
        try (HBaseAdmin hbaseAdmin = new HBaseAdmin(conf)) {
            String tableNamePrefix = IRealizationConstants.SharedHbaseStorageLocationPrefix;
            HTableDescriptor[] tableDescriptors = hbaseAdmin.listTables(tableNamePrefix + ".*");
            List<String> allTablesNeedToBeDropped = new ArrayList<String>();
            for (HTableDescriptor desc : tableDescriptors) {
                String host = desc.getValue(IRealizationConstants.HTableTag);
                if (KylinConfig.getInstanceFromEnv().getMetadataUrlPrefix().equalsIgnoreCase(host)) {
                    //only take care htables that belongs to self, and created more than 2 days
                    allTablesNeedToBeDropped.add(desc.getTableName().getNameAsString());
                }
            }

            // remove every segment htable from drop list
            for (CubeInstance cube : cubeMgr.listAllCubes()) {
                for (CubeSegment seg : cube.getSegments()) {
                    String tablename = seg.getStorageLocationIdentifier();
                    if (allTablesNeedToBeDropped.contains(tablename)) {
                        allTablesNeedToBeDropped.remove(tablename);
                        logger.info("Exclude table " + tablename + " from drop list, as the table belongs to cube " + cube.getName() + " with status " + cube.getStatus());
                    }
                }
            }

            if (delete == true) {
                // drop tables
                ExecutorService executorService = Executors.newSingleThreadExecutor();
                for (String htableName : allTablesNeedToBeDropped) {
                    FutureTask futureTask = new FutureTask(new DeleteHTableRunnable(hbaseAdmin, htableName));
                    executorService.execute(futureTask);
                    try {
                        futureTask.get(deleteTimeout, TimeUnit.MINUTES);
                    } catch (TimeoutException e) {
                        logger.warn("It fails to delete htable " + htableName + ", for it cost more than " + deleteTimeout + " minutes!");
                        futureTask.cancel(true);
                    } catch (Exception e) {
                        e.printStackTrace();
                        futureTask.cancel(true);
                    }
                }
                executorService.shutdown();
            } else {
                System.out.println("--------------- Tables To Be Dropped ---------------");
                for (String htableName : allTablesNeedToBeDropped) {
                    System.out.println(htableName);
                }
                System.out.println("----------------------------------------------------");
            }
        }
    }

    @Override
    protected Options getOptions() {
        Options options = new Options();
        options.addOption(OPTION_DELETE);
        options.addOption(OPTION_FORCE);
        return options;
    }

    @Override
    protected void execute(OptionsHelper optionsHelper) throws Exception {
        logger.info("options: '" + optionsHelper.getOptionsAsString() + "'");
        logger.info("delete option value: '" + optionsHelper.getOptionValue(OPTION_DELETE) + "'");
        logger.info("force option value: '" + optionsHelper.getOptionValue(OPTION_FORCE) + "'");
        delete = Boolean.parseBoolean(optionsHelper.getOptionValue(OPTION_DELETE));
        force = Boolean.parseBoolean(optionsHelper.getOptionValue(OPTION_FORCE));

        Configuration conf = HBaseConfiguration.create();

        cleanUnusedIntermediateHiveTable(conf);
        cleanUnusedHdfsFiles(conf);
        cleanUnusedHBaseTables(conf);

    }

    class DeleteHTableRunnable implements Callable {
        HBaseAdmin hbaseAdmin;
        String htableName;

        DeleteHTableRunnable(HBaseAdmin hbaseAdmin, String htableName) {
            this.hbaseAdmin = hbaseAdmin;
            this.htableName = htableName;
        }

        public Object call() throws Exception {
            logger.info("Deleting HBase table " + htableName);
            if (hbaseAdmin.tableExists(htableName)) {
                if (hbaseAdmin.isTableEnabled(htableName)) {
                    hbaseAdmin.disableTable(htableName);
                }

                hbaseAdmin.deleteTable(htableName);
                logger.info("Deleted HBase table " + htableName);
            } else {
                logger.info("HBase table" + htableName + " does not exist");
            }
            return null;
        }
    }

    private void cleanUnusedHdfsFiles(Configuration conf) throws IOException {
        JobEngineConfig engineConfig = new JobEngineConfig(KylinConfig.getInstanceFromEnv());
        CubeManager cubeMgr = CubeManager.getInstance(KylinConfig.getInstanceFromEnv());

        FileSystem fs = HadoopUtil.getWorkingFileSystem(conf);
        List<String> allHdfsPathsNeedToBeDeleted = new ArrayList<String>();
        // GlobFilter filter = new
        // GlobFilter(KylinConfig.getInstanceFromEnv().getHdfsWorkingDirectory()
        // + "/kylin-.*");
        // TODO: when first use, /kylin/kylin_default_instance does not exist.
        FileStatus[] fStatus = fs.listStatus(new Path(KylinConfig.getInstanceFromEnv().getHdfsWorkingDirectory()));
        for (FileStatus status : fStatus) {
            String path = status.getPath().getName();
            // System.out.println(path);
            if (path.startsWith("kylin-")) {
                String kylinJobPath = engineConfig.getHdfsWorkingDirectory() + path;
                allHdfsPathsNeedToBeDeleted.add(kylinJobPath);
            }
        }

        List<String> allJobs = executableManager.getAllJobIds();
        for (String jobId : allJobs) {
            // only remove FINISHED and DISCARDED job intermediate files
            final ExecutableState state = executableManager.getOutput(jobId).getState();
            if (!state.isFinalState()) {
                String path = JobBuilderSupport.getJobWorkingDir(engineConfig.getHdfsWorkingDirectory(), jobId);
                allHdfsPathsNeedToBeDeleted.remove(path);
                logger.info("Skip " + path + " from deletion list, as the path belongs to job " + jobId + " with status " + state);
            }
        }

        // remove every segment working dir from deletion list
        for (CubeInstance cube : cubeMgr.listAllCubes()) {
            for (CubeSegment seg : cube.getSegments()) {
                String jobUuid = seg.getLastBuildJobID();
                if (jobUuid != null && jobUuid.equals("") == false) {
                    String path = JobBuilderSupport.getJobWorkingDir(engineConfig.getHdfsWorkingDirectory(), jobUuid);
                    allHdfsPathsNeedToBeDeleted.remove(path);
                    logger.info("Skip " + path + " from deletion list, as the path belongs to segment " + seg + " of cube " + cube.getName());
                }
            }
        }

        if (delete == true) {
            // remove files
            for (String hdfsPath : allHdfsPathsNeedToBeDeleted) {
                logger.info("Deleting hdfs path " + hdfsPath);
                Path p = new Path(hdfsPath);
                if (fs.exists(p) == true) {
                    fs.delete(p, true);
                    logger.info("Deleted hdfs path " + hdfsPath);
                } else {
                    logger.info("Hdfs path " + hdfsPath + "does not exist");
                }
            }
        } else {
            System.out.println("--------------- HDFS Path To Be Deleted ---------------");
            for (String hdfsPath : allHdfsPathsNeedToBeDeleted) {
                System.out.println(hdfsPath);
            }
            System.out.println("-------------------------------------------------------");
        }
    }

    private void cleanUnusedIntermediateHiveTable(Configuration conf) throws Exception {
        final KylinConfig config = KylinConfig.getInstanceFromEnv();
        JobEngineConfig engineConfig = new JobEngineConfig(KylinConfig.getInstanceFromEnv());
        final CliCommandExecutor cmdExec = config.getCliCommandExecutor();
        final int uuidLength = 36;
        final String preFix = "kylin_intermediate_";
        final String uuidPattern = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

        IHiveClient hiveClient = HiveClientFactory.getHiveClient();
        List<String> hiveTableNames = hiveClient.getHiveTableNames(config.getHiveDatabaseForIntermediateTable());
        Iterable<String> kylinIntermediates = Iterables.filter(hiveTableNames, new Predicate<String>() {
            @Override
            public boolean apply(@Nullable String input) {
                return input != null && input.startsWith("kylin_intermediate_");
            }
        });

        List<String> allJobs = executableManager.getAllJobIds();
        List<String> allHiveTablesNeedToBeDeleted = new ArrayList<String>();
        List<String> workingJobList = new ArrayList<String>();
        Map<String, String> segmentId2JobId = Maps.newHashMap();

        StringBuilder sb = new StringBuilder();
        for (String jobId : allJobs) {
            // only remove FINISHED and DISCARDED job intermediate table
            final ExecutableState state = executableManager.getOutput(jobId).getState();
            if (!state.isFinalState()) {
                workingJobList.add(jobId);
                sb.append(jobId).append("(").append(state).append("), ");
            }

            String segmentId = getSegmentIdFromJobId(jobId);
            if (segmentId != null) {//some jobs are not cubing jobs 
                segmentId2JobId.put(segmentId, jobId);
            }
        }
        logger.info("Working jobIDs: " + workingJobList);

        for (String line : kylinIntermediates) {
            logger.info("Checking table " + line);

            if (!line.startsWith(preFix))
                continue;

            if (force == true) {
                logger.warn("Warning: will delete all intermediate hive tables!!!!!!!!!!!!!!!!!!!!!!");
                allHiveTablesNeedToBeDeleted.add(line);
                continue;
            }

            boolean isNeedDel = true;

            if (line.length() > preFix.length() + uuidLength) {
                String uuid = line.substring(line.length() - uuidLength, line.length());
                uuid = uuid.replace("_", "-");
                final Pattern UUId_PATTERN = Pattern.compile(uuidPattern);
                if (UUId_PATTERN.matcher(uuid).matches()) {
                    //Check whether it's a hive table in use
                    if (isTableInUse(uuid, workingJobList)) {
                        logger.info("Skip deleting because the table is in use");
                        isNeedDel = false;
                    }
                } else {
                    logger.info("Skip deleting because not match pattern");
                    isNeedDel = false;
                }
            } else {
                logger.info("Skip deleting because length not qualified");
                isNeedDel = false;
            }

            if (isNeedDel) {
                allHiveTablesNeedToBeDeleted.add(line);
            }
        }

        if (delete == true) {

            try {
                final String useDatabaseHql = "USE " + config.getHiveDatabaseForIntermediateTable() + ";";
                final HiveCmdBuilder hiveCmdBuilder = new HiveCmdBuilder();
                hiveCmdBuilder.addStatement(useDatabaseHql);
                for (String delHive : allHiveTablesNeedToBeDeleted) {
                    hiveCmdBuilder.addStatement("drop table if exists " + delHive + "; ");
                    logger.info("Remove " + delHive + " from hive tables.");
                }
                cmdExec.execute(hiveCmdBuilder.build());

                //if kylin.source.hive.keep-flat-table, some intermediate table might be kept 
                //delete external path
                for (String tableToDelete : allHiveTablesNeedToBeDeleted) {
                    String uuid = tableToDelete.substring(tableToDelete.length() - uuidLength, tableToDelete.length());
                    String segmentId = uuid.replace("_", "-");

                    if (segmentId2JobId.containsKey(segmentId)) {
                        String path = JobBuilderSupport.getJobWorkingDir(engineConfig.getHdfsWorkingDirectory(), segmentId2JobId.get(segmentId)) + "/" + tableToDelete;
                        Path externalDataPath = new Path(path);
                        FileSystem fs = HadoopUtil.getWorkingFileSystem();
                        if (fs.exists(externalDataPath)) {
                            fs.delete(externalDataPath, true);
                            logger.info("Hive table {}'s external path {} deleted", tableToDelete, path);
                        } else {
                            logger.info("Hive table {}'s external path {} not exist. It's normal if kylin.source.hive.keep-flat-table set false (By default)", tableToDelete, path);
                        }
                    } else {
                        logger.warn("Hive table {}'s job ID not found, segmentId2JobId: {}", tableToDelete, segmentId2JobId.toString());
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

        } else {
            System.out.println("------ Intermediate Hive Tables To Be Dropped ------");
            for (String hiveTable : allHiveTablesNeedToBeDeleted) {
                System.out.println(hiveTable);
            }
            System.out.println("----------------------------------------------------");
        }
    }

    private String getSegmentIdFromJobId(String jobId) {
        AbstractExecutable abstractExecutable = executableManager.getJob(jobId);
        String segmentId = abstractExecutable.getParam("segmentId");
        return segmentId;
    }

    private boolean isTableInUse(String segUuid, List<String> workingJobList) {
        for (String jobId : workingJobList) {
            String segmentId = getSegmentIdFromJobId(jobId);

            if (null == segmentId)
                continue;

            return segUuid.equals(segmentId);
        }
        return false;
    }

    public static void main(String[] args) throws Exception {
        StorageCleanupJob cli = new StorageCleanupJob();
        cli.execute(args);
    }
}
